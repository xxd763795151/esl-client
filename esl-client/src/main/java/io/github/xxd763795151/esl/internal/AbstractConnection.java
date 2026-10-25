package io.github.xxd763795151.esl.internal;

import io.github.xxd763795151.esl.command.ApiResponse;
import io.github.xxd763795151.esl.command.BackgroundJobResult;
import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.command.Execute;
import io.github.xxd763795151.esl.command.ExecuteResult;
import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.command.SendEvent;
import io.github.xxd763795151.esl.command.SendMsg;
import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.connection.ConnectionStateListener;
import io.github.xxd763795151.esl.connection.EslConnection;
import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.dispatch.EventDispatcher;
import io.github.xxd763795151.esl.dispatch.Registration;
import io.github.xxd763795151.esl.dispatch.SerialExecutor;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.event.EventListener;
import io.github.xxd763795151.esl.event.EventNames;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.event.LogListener;
import io.github.xxd763795151.esl.exception.CommandFailedException;
import io.github.xxd763795151.esl.internal.netty.ChannelConnection;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.github.xxd763795151.esl.protocol.ContentTypes;
import io.github.xxd763795151.esl.protocol.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Implements the {@link EslConnection} operations once, for inbound clients and outbound sessions alike.
 * Subclasses decide which socket a command goes to ({@link #withConnection}) and manage the lifecycle.
 */
public abstract class AbstractConnection implements EslConnection {

    private static final Logger log = LoggerFactory.getLogger(AbstractConnection.class);

    protected final SessionState sessionState = new SessionState();
    protected final EventDispatcher dispatcher;
    protected final Executor worker;
    protected final EslMetrics metrics;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.NEW);
    private final List<ConnectionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final SerialExecutor stateNotifier;

    protected AbstractConnection(EventDispatcher dispatcher, Executor worker, EslMetrics metrics) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.stateNotifier = new SerialExecutor(worker);
    }

    /**
     * Runs {@code action} against the socket commands should go to, or fails/defers it when there is none.
     */
    protected abstract <T> CompletableFuture<T> withConnection(
            Function<ChannelConnection, CompletableFuture<T>> action);

    /** Format used by {@link #subscribe(String...)}. */
    protected abstract EventFormat eventFormat();

    protected abstract Duration defaultCloseTimeout();

    /** Makes sure the socket receives the execute/hangup events of {@code uuid}. */
    protected CompletableFuture<Void> ensureExecuteEvents(String uuid) {
        return ensureSubscribed(EventNames.CHANNEL_EXECUTE_COMPLETE, EventNames.CHANNEL_HANGUP_COMPLETE);
    }

    // ------------------------------------------------------------------ state

    @Override
    public ConnectionState state() {
        return state.get();
    }

    /** Atomically moves from {@code expected} to {@code next}; returns whether it did. */
    protected boolean transition(ConnectionState expected, ConnectionState next, Throwable cause) {
        if (expected == next || !state.compareAndSet(expected, next)) {
            return false;
        }
        notifyState(expected, next, cause);
        return true;
    }

    /** Moves to {@code next} unless already there or closed; returns the previous state. */
    protected ConnectionState moveTo(ConnectionState next, Throwable cause) {
        while (true) {
            ConnectionState current = state.get();
            if (current == next || current == ConnectionState.CLOSED) {
                return current;
            }
            if (state.compareAndSet(current, next)) {
                notifyState(current, next, cause);
                return current;
            }
        }
    }

    private void notifyState(ConnectionState from, ConnectionState to, Throwable cause) {
        log.debug("{}: {} -> {}{}", this, from, to, cause == null ? "" : " (" + cause + ")");
        metrics.stateChanged(from, to);
        if (stateListeners.isEmpty()) {
            return;
        }
        Runnable notification = () -> stateListeners.forEach(listener -> {
            try {
                listener.onStateChanged(from, to, cause);
            } catch (Throwable t) {
                log.warn("State listener failed", t);
            }
        });
        try {
            stateNotifier.execute(notification);
        } catch (RejectedExecutionException e) {
            // worker pool already released (final CLOSED transition): notify on this thread
            notification.run();
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public CompletableFuture<EslMessage> send(EslCommand command) {
        Objects.requireNonNull(command, "command");
        return withConnection(connection -> connection.send(command, reply -> {
            if (reply.isContentType(ContentTypes.COMMAND_REPLY) && new CommandReply(reply).isOk()) {
                sessionState.record(command);
            }
        }, Function.identity()));
    }

    @Override
    public CompletableFuture<ApiResponse> api(String command, String arguments) {
        EslCommand.Api api = new EslCommand.Api(command, arguments);
        return withConnection(connection -> connection.send(api,
                reply -> new ApiResponse(api.describe(), reply.body())));
    }

    @Override
    public CompletableFuture<BackgroundJobResult> bgapi(String command, String arguments) {
        EslCommand.BgApi bgapi = EslCommand.bgapi(command, arguments);
        return ensureSubscribed(EventNames.BACKGROUND_JOB)
                .thenCompose(ignored -> withConnection(connection -> connection.bgapi(bgapi)));
    }

    @Override
    public CompletableFuture<CommandReply> subscribe(String... events) {
        return command(new EslCommand.Event(eventFormat(), Arrays.asList(events)));
    }

    @Override
    public CompletableFuture<CommandReply> unsubscribe(String... events) {
        return command(new EslCommand.NixEvent(Arrays.asList(events)));
    }

    @Override
    public CompletableFuture<CommandReply> unsubscribeAll() {
        return command(new EslCommand.NoEvents());
    }

    @Override
    public CompletableFuture<CommandReply> addFilter(String header, String value) {
        return command(new EslCommand.Filter(header, value));
    }

    @Override
    public CompletableFuture<CommandReply> removeFilter(String header, String value) {
        return command(new EslCommand.FilterDelete(header, value));
    }

    @Override
    public CompletableFuture<CommandReply> removeAllFilters() {
        return command(EslCommand.FilterDelete.all());
    }

    @Override
    public CompletableFuture<CommandReply> setLogLevel(LogLevel level) {
        return command(new EslCommand.Log(level));
    }

    @Override
    public CompletableFuture<CommandReply> disableLog() {
        return command(new EslCommand.NoLog());
    }

    @Override
    public CompletableFuture<CommandReply> sendMsg(SendMsg message) {
        return command(message);
    }

    @Override
    public CompletableFuture<CommandReply> sendEvent(SendEvent event) {
        return command(event);
    }

    @Override
    public CompletableFuture<ExecuteResult> execute(String uuid, Execute execute) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(execute, "execute");
        if (!execute.awaitCompletion()) {
            return withConnection(connection -> connection.execute(uuid, execute));
        }
        return ensureExecuteEvents(uuid)
                .thenCompose(ignored -> withConnection(connection -> connection.execute(uuid, execute)));
    }

    /**
     * Sends a {@code command/reply} command, failing on {@code -ERR} and recording state on success. State is
     * recorded on the event loop in reply order, so back-to-back commands cannot be applied out of order.
     */
    protected CompletableFuture<CommandReply> command(EslCommand command) {
        return withConnection(connection -> connection.send(command, reply -> {
            if (new CommandReply(reply).isOk()) {
                sessionState.record(command);
            }
        }, reply -> {
            CommandReply commandReply = new CommandReply(reply);
            if (!commandReply.isOk()) {
                throw new CommandFailedException(command.describe(), commandReply.replyText());
            }
            return commandReply;
        }));
    }

    /** Subscribes to whichever of {@code eventNames} the socket does not receive yet. */
    protected CompletableFuture<Void> ensureSubscribed(String... eventNames) {
        String[] missing = Arrays.stream(eventNames)
                .filter(name -> !sessionState.isSubscribed(name))
                .toArray(String[]::new);
        if (missing.length == 0) {
            return EslFuture.completed(null);
        }
        return subscribe(missing).thenApply(reply -> null);
    }

    // ------------------------------------------------------------------ listeners

    @Override
    public Registration addEventListener(EventSelector selector, EventListener listener) {
        return dispatcher.addEventListener(selector, listener);
    }

    @Override
    public Registration addLogListener(LogListener listener) {
        return dispatcher.addLogListener(listener);
    }

    @Override
    public Registration addStateListener(ConnectionStateListener listener) {
        Objects.requireNonNull(listener, "listener");
        ConnectionStateListener entry = listener::onStateChanged;
        stateListeners.add(entry);
        return () -> stateListeners.remove(entry);
    }

    // ------------------------------------------------------------------ closing

    @Override
    public void close() {
        Duration timeout = defaultCloseTimeout();
        CompletableFuture<Void> closed = closeGracefully(timeout);
        if (EslFuture.isIoThread() || EslResources.isWorkerThread()) {
            // waiting here could wait for this very thread; the close proceeds asynchronously
            return;
        }
        try {
            closed.get(timeout.toMillis() + 2_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            log.warn("{} did not close within {}", this, timeout);
        } catch (Exception e) {
            log.warn("{} closed with error", this, Futures.unwrap(e));
        }
    }
}
