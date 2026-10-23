package io.github.xxd763795151.esl.inbound;

import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.dispatch.DispatchOptions;
import io.github.xxd763795151.esl.dispatch.EventDispatcher;
import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.event.LogLine;
import io.github.xxd763795151.esl.exception.AuthenticationException;
import io.github.xxd763795151.esl.exception.CommandFailedException;
import io.github.xxd763795151.esl.exception.CommandTimeoutException;
import io.github.xxd763795151.esl.exception.ConnectionClosedException;
import io.github.xxd763795151.esl.exception.RudeRejectionException;
import io.github.xxd763795151.esl.internal.AbstractConnection;
import io.github.xxd763795151.esl.internal.ConnectionSettings;
import io.github.xxd763795151.esl.internal.EslFuture;
import io.github.xxd763795151.esl.internal.Futures;
import io.github.xxd763795151.esl.internal.netty.ChannelConnection;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.github.xxd763795151.esl.reconnect.DisconnectedCommandPolicy;
import io.github.xxd763795151.esl.reconnect.ReconnectPolicy;
import io.github.xxd763795151.esl.reconnect.RecoveryContext;
import io.github.xxd763795151.esl.reconnect.RecoveryStrategy;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Inbound event socket client: connects to FreeSWITCH ({@code mod_event_socket}, port 8021 by default),
 * authenticates, and keeps the connection alive according to its {@link ReconnectPolicy}.
 *
 * <pre>{@code
 * InboundClient client = InboundClient.builder()
 *         .address("10.0.0.10", 8021)
 *         .password("ClueCon")
 *         .events("CHANNEL_ANSWER", "CHANNEL_HANGUP_COMPLETE")
 *         .build();
 * client.addEventListener(EventSelector.named("CHANNEL_ANSWER"), event -> log.info("answered {}", event.uniqueId()));
 * client.connect().join();
 * String status = client.api("status").join().body();
 * client.close();
 * }</pre>
 */
public final class InboundClient extends AbstractConnection {

    private static final Logger log = LoggerFactory.getLogger(InboundClient.class);

    private final String name;
    private final List<InetSocketAddress> addresses;
    private final EslCommand authCommand;
    private final Duration connectTimeout;
    private final Duration closeTimeout;
    private final EventFormat eventFormat;
    private final ReconnectPolicy reconnectPolicy;
    private final RecoveryStrategy recoveryStrategy;
    private final DisconnectedCommandPolicy disconnectedPolicy;
    private final int maxQueuedCommands;
    private final boolean retryInitialConnect;
    private final boolean shutdownHookEnabled;
    private final ConnectionSettings settings;
    private final EslResources resources;
    private final boolean ownsResources;
    private final Bootstrap bootstrap;

    private final AtomicReference<ChannelConnection> current = new AtomicReference<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger endpointIndex = new AtomicInteger();
    private final Deque<QueuedCommand> queued = new ArrayDeque<>();
    private final EslFuture<Void> initialConnect = new EslFuture<>();
    private final EslFuture<Void> terminated = new EslFuture<>();
    private volatile ChannelConnection establishing;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean everConnected;
    private volatile Thread shutdownHook;

    private InboundClient(Builder builder, EslResources resources, boolean ownsResources, EventDispatcher dispatcher) {
        super(dispatcher, resources.worker(), builder.metrics);
        this.name = builder.name;
        this.addresses = List.copyOf(builder.addresses);
        this.authCommand = builder.user == null
                ? new EslCommand.Auth(builder.password)
                : new EslCommand.UserAuth(builder.user, builder.password);
        this.connectTimeout = builder.connectTimeout;
        this.closeTimeout = builder.closeTimeout;
        this.eventFormat = builder.eventFormat;
        this.reconnectPolicy = builder.reconnectPolicy;
        this.recoveryStrategy = builder.recoveryStrategy;
        this.disconnectedPolicy = builder.disconnectedPolicy;
        this.maxQueuedCommands = builder.maxQueuedCommands;
        this.retryInitialConnect = builder.retryInitialConnect;
        this.shutdownHookEnabled = builder.shutdownHook;
        this.settings = new ConnectionSettings(builder.commandTimeout, builder.jobTimeout, builder.executeTimeout,
                builder.idleTimeout, builder.livenessProbe, builder.maxHeaderBytes, builder.maxBodyBytes);
        this.resources = resources;
        this.ownsResources = ownsResources;
        this.bootstrap = new Bootstrap()
                .group(resources.ioGroup())
                .channel(resources.socketChannelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()))
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOW_HALF_CLOSURE, false);

        // the configured initial state is applied by the same code path that replays it after reconnects
        if (!builder.events.isEmpty()) {
            sessionState.record(new EslCommand.Event(eventFormat, builder.events));
        }
        builder.filters.forEach(filter -> sessionState.record(new EslCommand.Filter(filter.getKey(), filter.getValue())));
        if (builder.logLevel != null) {
            sessionState.record(new EslCommand.Log(builder.logLevel));
        }

        dispatcher.setReadGate(this::applyReadGate);
        metrics.gauge(EslMetrics.GAUGE_CONNECTED, () -> state() == ConnectionState.READY ? 1 : 0);
        metrics.gauge(EslMetrics.GAUGE_PENDING_COMMANDS, () -> withCurrent(ChannelConnection::pendingCommands));
        metrics.gauge(EslMetrics.GAUGE_PENDING_JOBS, () -> withCurrent(ChannelConnection::pendingJobs));
        metrics.gauge(EslMetrics.GAUGE_PENDING_EXECUTES, () -> withCurrent(ChannelConnection::pendingExecutes));
        metrics.gauge(EslMetrics.GAUGE_DISPATCH_BACKLOG, dispatcher::backlog);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    /** Address of the FreeSWITCH instance currently connected, or {@code null}. */
    public SocketAddress remoteAddress() {
        ChannelConnection connection = current.get();
        return connection == null ? null : connection.remoteAddress();
    }

    // ------------------------------------------------------------------ connecting

    /**
     * Starts connecting. The future completes once the client is authenticated and its initial subscriptions
     * are in place, or fails when the first attempt fails (unless {@code retryInitialConnect} is enabled, in
     * which case it fails only when the reconnect policy gives up). Calling it again returns the same outcome.
     */
    public CompletableFuture<Void> connect() {
        if (transition(ConnectionState.NEW, ConnectionState.CONNECTING, null)) {
            registerShutdownHook();
            attempt();
        }
        return Futures.relay(initialConnect, Function.identity(), worker);
    }

    private void attempt() {
        if (state().isTerminal()) {
            return;
        }
        if (!everConnected) {
            moveTo(ConnectionState.CONNECTING, null);
        }
        InetSocketAddress address = addresses.get(Math.floorMod(endpointIndex.getAndIncrement(), addresses.size()));
        establish(address).whenComplete((connection, error) -> {
            if (error == null) {
                onEstablished(connection);
            } else {
                onAttemptFailed(address, Futures.unwrap(error));
            }
        });
    }

    private CompletableFuture<ChannelConnection> establish(InetSocketAddress address) {
        CompletableFuture<ChannelConnection> result = new CompletableFuture<>();
        CompletableFuture<Void> authRequest = new CompletableFuture<>();
        AtomicReference<ChannelConnection> holder = new AtomicReference<>();
        Bootstrap attemptBootstrap = bootstrap.clone().handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                holder.set(new ChannelConnection(channel, settings, worker, metrics, new Callbacks(authRequest)));
            }
        });
        log.debug("{}: connecting to {}", this, describe(address));
        attemptBootstrap.connect(address).addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                result.completeExceptionally(new ConnectionClosedException("Cannot connect to " + describe(address),
                        future.cause()));
                return;
            }
            ChannelConnection connection = holder.get();
            establishing = connection;
            if (state().isTerminal()) {
                connection.abort(new ConnectionClosedException("Client closed"));
                result.completeExceptionally(new ConnectionClosedException("Client closed"));
                return;
            }
            authRequest.orTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .thenCompose(ignored -> {
                        transition(ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, null);
                        return connection.sendRaw(authCommand);
                    })
                    .thenCompose(reply -> {
                        CommandReply commandReply = new CommandReply(reply);
                        if (!commandReply.isOk()) {
                            throw new AuthenticationException("FreeSWITCH at " + describe(address) + " rejected "
                                    + authCommand.describe() + ": " + commandReply.replyText());
                        }
                        return recover(connection);
                    })
                    .whenComplete((ignored, error) -> {
                        establishing = null;
                        if (error == null) {
                            result.complete(connection);
                            return;
                        }
                        Throwable cause = Futures.unwrap(error);
                        if (cause instanceof TimeoutException) {
                            cause = new CommandTimeoutException("Waiting for auth/request from " + describe(address),
                                    connectTimeout);
                        }
                        connection.abort(cause);
                        result.completeExceptionally(cause);
                    });
        });
        return result;
    }

    private CompletableFuture<Void> recover(ChannelConnection connection) {
        Recovery context = new Recovery(connection, everConnected);
        return everConnected ? recoveryStrategy.recover(context) : context.replayRecordedState();
    }

    private void onEstablished(ChannelConnection connection) {
        if (state().isTerminal()) {
            connection.abort(new ConnectionClosedException("Client closed"));
            return;
        }
        current.set(connection);
        attempts.set(0);
        everConnected = true;
        applyReadGate();
        if (!connection.channel().isActive()) {
            // closed between recovery and now; the close callback may have missed it
            onConnectionLost(connection, new ConnectionClosedException("Connection closed during recovery"));
            return;
        }
        moveTo(ConnectionState.READY, null);
        log.info("{}: connected to {}", this, connection.remoteAddress());
        initialConnect.complete(null);
        flushQueued();
    }

    private void onAttemptFailed(InetSocketAddress address, Throwable cause) {
        if (state().isTerminal()) {
            return;
        }
        log.warn("{}: connection attempt to {} failed: {}", this, describe(address), cause.toString());
        if (!everConnected && !retryInitialConnect) {
            giveUp(cause);
        } else {
            scheduleReconnect(cause);
        }
    }

    private void onConnectionLost(ChannelConnection connection, Throwable cause) {
        if (!current.compareAndSet(connection, null) || state().isTerminal()) {
            return;
        }
        log.warn("{}: connection to {} lost{}", this, connection.remoteAddress(),
                cause == null ? "" : ": " + cause);
        scheduleReconnect(cause != null ? cause : new ConnectionClosedException("Connection closed by peer"));
    }

    private void scheduleReconnect(Throwable cause) {
        if (!reconnectPolicy.isRetryable(cause)) {
            giveUp(cause);
            return;
        }
        int attempt = attempts.incrementAndGet();
        Optional<Duration> delay = reconnectPolicy.nextDelay(attempt, cause);
        if (delay.isEmpty()) {
            giveUp(cause);
            return;
        }
        if (everConnected) {
            moveTo(ConnectionState.RECONNECTING, cause);
        }
        metrics.reconnectAttempt();
        log.info("{}: reconnecting in {} ms (attempt {})", this, delay.get().toMillis(), attempt);
        try {
            reconnectTask = resources.ioGroup().next().schedule(this::attempt, delay.get().toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            giveUp(new ConnectionClosedException("I/O threads shut down", e));
        }
    }

    private void giveUp(Throwable cause) {
        log.error("{}: giving up: {}", this, cause.toString());
        initialConnect.completeExceptionally(cause);
        shutdown(closeTimeout, cause);
    }

    // ------------------------------------------------------------------ command routing

    @Override
    protected <T> CompletableFuture<T> withConnection(Function<ChannelConnection, CompletableFuture<T>> action) {
        ConnectionState state = state();
        ChannelConnection connection = current.get();
        if (state == ConnectionState.READY && connection != null) {
            return action.apply(connection);
        }
        if (disconnectedPolicy == DisconnectedCommandPolicy.QUEUE_UNTIL_READY
                && state != ConnectionState.NEW && !state.isTerminal()) {
            return enqueue(action);
        }
        return EslFuture.failed(new ConnectionClosedException(this + " is not connected (" + state + ")"));
    }

    private <T> CompletableFuture<T> enqueue(Function<ChannelConnection, CompletableFuture<T>> action) {
        EslFuture<T> result = new EslFuture<>();
        QueuedCommand command = new QueuedCommand(
                connection -> action.apply(connection).whenComplete((value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(Futures.unwrap(error));
                    } else {
                        result.complete(value);
                    }
                }),
                result::completeExceptionally);
        synchronized (queued) {
            if (queued.size() >= maxQueuedCommands) {
                return EslFuture.failed(new ConnectionClosedException(
                        this + " is not connected and " + maxQueuedCommands + " commands are already queued"));
            }
            queued.addLast(command);
        }
        if (!settings.commandTimeout().isZero()) {
            try {
                command.timeoutTask = resources.ioGroup().next().schedule(() -> {
                    boolean removed;
                    synchronized (queued) {
                        removed = queued.remove(command);
                    }
                    if (removed) {
                        result.completeExceptionally(new CommandTimeoutException("Command queued while disconnected",
                                settings.commandTimeout()));
                    }
                }, settings.commandTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(new ConnectionClosedException("Client closed"));
            }
        }
        if (state() == ConnectionState.READY) {
            flushQueued();
        }
        return result;
    }

    private void flushQueued() {
        ChannelConnection connection = current.get();
        if (connection == null || state() != ConnectionState.READY) {
            return;
        }
        List<QueuedCommand> batch;
        synchronized (queued) {
            batch = new ArrayList<>(queued);
            queued.clear();
        }
        batch.forEach(command -> command.run(connection));
    }

    private void failQueued(Throwable cause) {
        List<QueuedCommand> batch;
        synchronized (queued) {
            batch = new ArrayList<>(queued);
            queued.clear();
        }
        batch.forEach(command -> command.fail(cause));
    }

    @Override
    protected EventFormat eventFormat() {
        return eventFormat;
    }

    @Override
    protected Duration defaultCloseTimeout() {
        return closeTimeout;
    }

    // ------------------------------------------------------------------ closing

    @Override
    public CompletableFuture<Void> closeGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return shutdown(timeout, null);
    }

    private CompletableFuture<Void> shutdown(Duration timeout, Throwable cause) {
        ConnectionState previous = moveTo(ConnectionState.CLOSING, cause);
        if (previous == ConnectionState.CLOSING || previous == ConnectionState.CLOSED) {
            return Futures.relay(terminated, Function.identity(), worker);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
        }
        removeShutdownHook();
        ConnectionClosedException closed = new ConnectionClosedException(this + " closed", cause);
        initialConnect.completeExceptionally(closed);
        failQueued(closed);

        ChannelConnection pending = establishing;
        if (pending != null) {
            pending.abort(closed);
        }
        ChannelConnection connection = current.getAndSet(null);
        CompletableFuture<Void> connectionClosed = connection == null
                ? CompletableFuture.completedFuture(null)
                : connection.closeGracefully(Duration.ofNanos(timeout.toNanos() * 3 / 4));

        connectionClosed
                .thenCompose(ignored -> {
                    dispatcher.shutdown();
                    return dispatcher.awaitIdle().completeOnTimeout(null, remaining(deadline), TimeUnit.NANOSECONDS);
                })
                .thenCompose(ignored -> ownsResources
                        ? resources.shutdownGracefully(Duration.ofNanos(remaining(deadline)))
                        : CompletableFuture.completedFuture(null))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.warn("{}: error while closing", this, error);
                    }
                    moveTo(ConnectionState.CLOSED, cause);
                    metrics.close();
                    log.info("{}: closed", this);
                    terminated.complete(null);
                });
        return Futures.relay(terminated, Function.identity(), worker);
    }

    private static String describe(InetSocketAddress address) {
        return address.getHostString() + ':' + address.getPort();
    }

    private static long remaining(long deadline) {
        return Math.max(TimeUnit.MILLISECONDS.toNanos(1), deadline - System.nanoTime());
    }

    private void registerShutdownHook() {
        if (!shutdownHookEnabled) {
            return;
        }
        Thread hook = new Thread(() -> {
            try {
                shutdown(closeTimeout, null).get(closeTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("{}: shutdown hook did not complete cleanly: {}", this, e.toString());
            }
        }, "esl-shutdown-hook-" + name);
        Runtime.getRuntime().addShutdownHook(hook);
        shutdownHook = hook;
    }

    private void removeShutdownHook() {
        Thread hook = shutdownHook;
        if (hook == null || hook == Thread.currentThread()) {
            return;
        }
        shutdownHook = null;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }
    }

    // ------------------------------------------------------------------ helpers

    private void applyReadGate() {
        ChannelConnection connection = current.get();
        if (connection == null) {
            return;
        }
        Channel channel = connection.channel();
        channel.eventLoop().execute(() -> channel.config().setAutoRead(!dispatcher.isReadPaused()));
    }

    private int withCurrent(java.util.function.ToIntFunction<ChannelConnection> metric) {
        ChannelConnection connection = current.get();
        return connection == null ? 0 : metric.applyAsInt(connection);
    }

    @Override
    public String toString() {
        return "InboundClient[" + name + ']';
    }

    /** Socket callbacks of one connection attempt. */
    private final class Callbacks implements ChannelConnection.Callbacks {

        private final CompletableFuture<Void> authRequest;

        Callbacks(CompletableFuture<Void> authRequest) {
            this.authRequest = authRequest;
        }

        @Override
        public void onAuthRequest(ChannelConnection connection) {
            authRequest.complete(null);
        }

        @Override
        public void onEvent(ChannelConnection connection, EslEvent event) {
            dispatcher.dispatch(event);
        }

        @Override
        public void onLog(ChannelConnection connection, LogLine line) {
            dispatcher.dispatchLog(line);
        }

        @Override
        public void onRudeRejection(ChannelConnection connection, EslMessage message) {
            authRequest.completeExceptionally(new RudeRejectionException(
                    message.body().isBlank() ? "Connection rejected" : message.body().trim()));
        }

        @Override
        public void onClosed(ChannelConnection connection, Throwable cause) {
            authRequest.completeExceptionally(new ConnectionClosedException("Connection closed during handshake",
                    cause));
            onConnectionLost(connection, cause);
        }
    }

    /** Recovery context bound to one freshly authenticated socket. */
    private final class Recovery implements RecoveryContext {

        private final ChannelConnection connection;
        private final boolean reconnect;

        Recovery(ChannelConnection connection, boolean reconnect) {
            this.connection = connection;
            this.reconnect = reconnect;
        }

        @Override
        public boolean isReconnect() {
            return reconnect;
        }

        @Override
        public SocketAddress remoteAddress() {
            return connection.remoteAddress();
        }

        @Override
        public List<EslCommand> recordedState() {
            return sessionState.replayCommands(eventFormat);
        }

        @Override
        public CompletableFuture<Void> replayRecordedState() {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (EslCommand command : recordedState()) {
                chain = chain.thenCompose(ignored -> connection.sendRaw(command).thenAccept(reply -> {
                    CommandReply commandReply = new CommandReply(reply);
                    if (!commandReply.isOk()) {
                        throw new CommandFailedException(command.describe(), commandReply.replyText());
                    }
                }));
            }
            return chain;
        }

        @Override
        public void discardRecordedState() {
            sessionState.clear();
        }

        @Override
        public CompletableFuture<EslMessage> send(EslCommand command) {
            return Futures.relay(connection.sendRaw(command), reply -> {
                if (new CommandReply(reply).isOk()) {
                    sessionState.record(command);
                }
                return reply;
            }, worker);
        }
    }

    private static final class QueuedCommand {
        private final Consumer<ChannelConnection> runner;
        private final Consumer<Throwable> failer;
        private volatile ScheduledFuture<?> timeoutTask;

        QueuedCommand(Consumer<ChannelConnection> runner, Consumer<Throwable> failer) {
            this.runner = runner;
            this.failer = failer;
        }

        void run(ChannelConnection connection) {
            cancelTimeout();
            try {
                runner.accept(connection);
            } catch (Throwable t) {
                failer.accept(t);
            }
        }

        void fail(Throwable cause) {
            cancelTimeout();
            failer.accept(cause);
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    /**
     * Configuration of an {@link InboundClient}. Defaults suit most deployments; only the address is usually
     * needed.
     */
    public static final class Builder {
        private String name = "esl";
        private final List<InetSocketAddress> addresses = new ArrayList<>();
        private String password = "ClueCon";
        private String user;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration commandTimeout = Duration.ofSeconds(30);
        private Duration jobTimeout = Duration.ofMinutes(5);
        private Duration executeTimeout = Duration.ZERO;
        private Duration idleTimeout = Duration.ofSeconds(60);
        private Duration closeTimeout = Duration.ofSeconds(10);
        private EslCommand livenessProbe = EslCommand.api("version");
        private EventFormat eventFormat = EventFormat.PLAIN;
        private final List<String> events = new ArrayList<>();
        private final List<Map.Entry<String, String>> filters = new ArrayList<>();
        private LogLevel logLevel;
        private DispatchOptions dispatchOptions = DispatchOptions.DEFAULT;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.exponential().build();
        private RecoveryStrategy recoveryStrategy = RecoveryStrategy.replayState();
        private DisconnectedCommandPolicy disconnectedPolicy = DisconnectedCommandPolicy.FAIL_FAST;
        private int maxQueuedCommands = 1_000;
        private boolean retryInitialConnect;
        private boolean shutdownHook;
        private EslResources resources;
        private EslMetrics metrics = EslMetrics.NOOP;
        private int maxHeaderBytes = 1 << 20;
        private int maxBodyBytes = 64 << 20;

        private Builder() {
        }

        /** Name used in logs, thread names and metric tags. */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Adds a FreeSWITCH address. With several addresses, connection attempts rotate through them
         * (fail-over). Host names are resolved on every attempt.
         */
        public Builder address(String host, int port) {
            addresses.add(InetSocketAddress.createUnresolved(host, port));
            return this;
        }

        public Builder address(InetSocketAddress address) {
            addresses.add(Objects.requireNonNull(address, "address"));
            return this;
        }

        public Builder password(String password) {
            this.password = Objects.requireNonNull(password, "password");
            return this;
        }

        /** Authenticate as a directory user ({@code userauth user@domain:password}) instead of {@code auth}. */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder commandTimeout(Duration commandTimeout) {
            this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
            return this;
        }

        public Builder jobTimeout(Duration jobTimeout) {
            this.jobTimeout = Objects.requireNonNull(jobTimeout, "jobTimeout");
            return this;
        }

        /** Upper bound for awaited application executions; zero (default) waits until completion or hangup. */
        public Builder executeTimeout(Duration executeTimeout) {
            this.executeTimeout = Objects.requireNonNull(executeTimeout, "executeTimeout");
            return this;
        }

        /** Read idle time after which the connection is probed; zero disables dead peer detection. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
            return this;
        }

        public Builder livenessProbe(EslCommand livenessProbe) {
            this.livenessProbe = Objects.requireNonNull(livenessProbe, "livenessProbe");
            return this;
        }

        /** Default timeout of {@link InboundClient#close()}. */
        public Builder closeTimeout(Duration closeTimeout) {
            this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
            return this;
        }

        public Builder eventFormat(EventFormat eventFormat) {
            this.eventFormat = Objects.requireNonNull(eventFormat, "eventFormat");
            return this;
        }

        /** Events subscribed right after authentication (and after every reconnect). */
        public Builder events(String... events) {
            this.events.addAll(List.of(events));
            return this;
        }

        public Builder filter(String header, String value) {
            this.filters.add(Map.entry(header, value));
            return this;
        }

        public Builder logLevel(LogLevel logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        public Builder dispatch(DispatchOptions dispatchOptions) {
            this.dispatchOptions = Objects.requireNonNull(dispatchOptions, "dispatchOptions");
            return this;
        }

        public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
            this.reconnectPolicy = Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
            return this;
        }

        public Builder recoveryStrategy(RecoveryStrategy recoveryStrategy) {
            this.recoveryStrategy = Objects.requireNonNull(recoveryStrategy, "recoveryStrategy");
            return this;
        }

        public Builder disconnectedCommandPolicy(DisconnectedCommandPolicy policy) {
            this.disconnectedPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder maxQueuedCommands(int maxQueuedCommands) {
            if (maxQueuedCommands < 1) {
                throw new IllegalArgumentException("maxQueuedCommands must be >= 1");
            }
            this.maxQueuedCommands = maxQueuedCommands;
            return this;
        }

        /** Keep retrying (per the reconnect policy) when the very first connection attempt fails. */
        public Builder retryInitialConnect(boolean retryInitialConnect) {
            this.retryInitialConnect = retryInitialConnect;
            return this;
        }

        /** Close gracefully when the JVM shuts down. Off by default; frameworks manage lifecycle themselves. */
        public Builder shutdownHook(boolean shutdownHook) {
            this.shutdownHook = shutdownHook;
            return this;
        }

        /** Shared threads; the client then does not shut them down on close. */
        public Builder resources(EslResources resources) {
            this.resources = resources;
            return this;
        }

        public Builder metrics(EslMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        public Builder maxFrameSize(int maxHeaderBytes, int maxBodyBytes) {
            this.maxHeaderBytes = maxHeaderBytes;
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        public InboundClient build() {
            if (addresses.isEmpty()) {
                throw new IllegalStateException("At least one address is required");
            }
            boolean owns = resources == null;
            EslResources effective = owns ? EslResources.builder().threadNamePrefix(name.startsWith("esl") ? name : "esl-" + name).build() : resources;
            try {
                EventDispatcher dispatcher = new EventDispatcher(dispatchOptions, effective.worker(), metrics);
                return new InboundClient(this, effective, owns, dispatcher);
            } catch (RuntimeException e) {
                if (owns) {
                    effective.shutdownGracefully(Duration.ZERO);
                }
                throw e;
            }
        }
    }
}
