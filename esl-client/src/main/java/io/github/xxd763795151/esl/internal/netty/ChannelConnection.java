package io.github.xxd763795151.esl.internal.netty;

import io.github.xxd763795151.esl.command.BackgroundJobResult;
import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.command.Execute;
import io.github.xxd763795151.esl.command.ExecuteResult;
import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventNames;
import io.github.xxd763795151.esl.event.LogLine;
import io.github.xxd763795151.esl.exception.ChannelHangupException;
import io.github.xxd763795151.esl.exception.CommandFailedException;
import io.github.xxd763795151.esl.exception.CommandTimeoutException;
import io.github.xxd763795151.esl.exception.ConnectionClosedException;
import io.github.xxd763795151.esl.exception.RudeRejectionException;
import io.github.xxd763795151.esl.internal.ConnectionSettings;
import io.github.xxd763795151.esl.internal.EslFuture;
import io.github.xxd763795151.esl.internal.Futures;
import io.github.xxd763795151.esl.internal.codec.EslCommandEncoder;
import io.github.xxd763795151.esl.internal.codec.EslFrameDecoder;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.github.xxd763795151.esl.protocol.HeaderNames;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * One established ESL socket: sends commands, awaits {@code bgapi} jobs and application executions, probes
 * liveness, and closes the socket in stages. Owners (inbound client, outbound session) receive everything
 * else through {@link Callbacks}.
 */
public final class ChannelConnection implements ProtocolListener {

    private static final Logger log = LoggerFactory.getLogger(ChannelConnection.class);

    /** Owner hooks; all invoked on the channel's event loop and must not block. */
    public interface Callbacks {

        default void onAuthRequest(ChannelConnection connection) {
        }

        void onEvent(ChannelConnection connection, EslEvent event);

        default void onLog(ChannelConnection connection, LogLine line) {
        }

        default void onDisconnectNotice(ChannelConnection connection, EslMessage message) {
        }

        default void onRudeRejection(ChannelConnection connection, EslMessage message) {
        }

        void onClosed(ChannelConnection connection, Throwable cause);
    }

    private final Channel channel;
    private final ConnectionSettings settings;
    private final Executor worker;
    private final EslMetrics metrics;
    private final Callbacks callbacks;
    private final EslProtocolHandler handler;
    private final Map<String, PendingJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, PendingExecute> executes = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closedNotified = new AtomicBoolean();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private volatile Throwable closeCause;

    /**
     * Installs the ESL pipeline on {@code channel}. Call from a {@code ChannelInitializer}.
     */
    public ChannelConnection(Channel channel, ConnectionSettings settings, Executor worker, EslMetrics metrics,
                             Callbacks callbacks) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.handler = new EslProtocolHandler(this, metrics);

        ChannelPipeline pipeline = channel.pipeline();
        if (!settings.idleTimeout().isZero()) {
            pipeline.addLast("idle", new IdleStateHandler(settings.idleTimeout().toMillis(), 0, 0,
                    TimeUnit.MILLISECONDS));
        }
        pipeline.addLast("decoder", new EslFrameDecoder(settings.maxHeaderBytes(), settings.maxBodyBytes()));
        pipeline.addLast("encoder", EslCommandEncoder.INSTANCE);
        pipeline.addLast("esl", handler);
        // covers channels that close without ever becoming active (channelInactive is not fired for those)
        channel.closeFuture().addListener(future -> onChannelClosed(handler.failure()));
    }

    public Channel channel() {
        return channel;
    }

    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    public boolean isActive() {
        return channel.isActive() && !closing.get();
    }

    public boolean isClosing() {
        return closing.get();
    }

    /** Completes when the socket is closed, for whatever reason. Never fails. */
    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    public int pendingCommands() {
        return handler.pendingCount();
    }

    public int pendingJobs() {
        return jobs.size();
    }

    public int pendingExecutes() {
        return executes.size();
    }

    // ------------------------------------------------------------------ sending

    /**
     * Writes a command; the returned future completes on the event loop with the raw reply. For internal
     * protocol steps only: callers must not block in its callbacks.
     */
    public CompletableFuture<EslMessage> sendRaw(EslCommand command) {
        return sendRaw(command, settings.commandTimeout());
    }

    public CompletableFuture<EslMessage> sendRaw(EslCommand command, Duration timeout) {
        PendingCommand pending = new PendingCommand(command, timeout);
        if (!channel.isActive()) {
            pending.fail(new ConnectionClosedException("Connection is not active", closeCause));
            return pending.promise();
        }
        // the handler fails the command on write errors while it is queued; this covers writes that never
        // reach the handler because the pipeline is already torn down
        channel.writeAndFlush(pending).addListener(future -> {
            if (!future.isSuccess()) {
                pending.fail(new ConnectionClosedException("Failed to write " + command.describe(), future.cause()));
            }
        });
        return pending.promise();
    }

    /** Sends a user command and maps the reply on a worker thread. */
    public <T> CompletableFuture<T> send(EslCommand command, Function<EslMessage, T> mapper) {
        if (closing.get()) {
            return EslFuture.failed(new ConnectionClosedException("Connection is closing", closeCause));
        }
        return Futures.relay(sendRaw(command), mapper, worker);
    }

    /**
     * Runs a {@code bgapi} and completes with its {@code BACKGROUND_JOB}. The job is registered under the
     * client chosen Job-UUID <em>before</em> the command is written, so the event can never arrive first.
     */
    public CompletableFuture<BackgroundJobResult> bgapi(EslCommand.BgApi command) {
        PendingJob job = new PendingJob(command);
        jobs.put(command.jobUuid(), job);
        if (!settings.jobTimeout().isZero()) {
            job.timeoutTask = channel.eventLoop().schedule(() -> {
                if (jobs.remove(command.jobUuid(), job)) {
                    job.future.completeExceptionally(new CommandTimeoutException(
                            "Background job " + command.jobUuid() + " (" + command.describe() + ")",
                            settings.jobTimeout()));
                }
            }, settings.jobTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
        sendRaw(command).whenComplete((reply, error) -> {
            Throwable failure = error;
            if (failure == null) {
                CommandReply commandReply = new CommandReply(reply);
                if (!commandReply.isOk()) {
                    failure = new CommandFailedException(command.describe(), commandReply.replyText());
                }
            }
            if (failure != null && jobs.remove(command.jobUuid(), job)) {
                job.fail(failure);
            }
        });
        return Futures.relay(job.future, Function.identity(), worker);
    }

    /**
     * Executes a dialplan application on {@code uuid}. Unless fire-and-forget, completes on the matching
     * {@code CHANNEL_EXECUTE_COMPLETE}; the connection must receive that channel's execute and hangup events.
     */
    public CompletableFuture<ExecuteResult> execute(String uuid, Execute execute) {
        Objects.requireNonNull(uuid, "uuid");
        String eventUuid = UUID.randomUUID().toString();
        EslCommand message = execute.toSendMsg(uuid, eventUuid);
        if (!execute.awaitCompletion()) {
            return send(message, reply -> {
                CommandReply commandReply = new CommandReply(reply);
                if (!commandReply.isOk()) {
                    throw new CommandFailedException(message.describe(), commandReply.replyText());
                }
                return new ExecuteResult(uuid, execute.application(), eventUuid, null);
            });
        }
        PendingExecute pending = new PendingExecute(uuid, execute.application(), eventUuid);
        executes.put(eventUuid, pending);
        if (!settings.executeTimeout().isZero()) {
            pending.timeoutTask = channel.eventLoop().schedule(() -> {
                if (executes.remove(eventUuid, pending)) {
                    pending.future.completeExceptionally(new CommandTimeoutException(
                            "Execution of " + execute.application() + " on " + uuid, settings.executeTimeout()));
                }
            }, settings.executeTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
        sendRaw(message).whenComplete((reply, error) -> {
            Throwable failure = error;
            if (failure == null) {
                CommandReply commandReply = new CommandReply(reply);
                if (!commandReply.isOk()) {
                    failure = new CommandFailedException(message.describe(), commandReply.replyText());
                }
            }
            if (failure != null && executes.remove(eventUuid, pending)) {
                pending.fail(failure);
            }
        });
        return Futures.relay(pending.future, Function.identity(), worker);
    }

    // ------------------------------------------------------------------ closing

    /**
     * Graceful close: wait for in-flight replies, send {@code exit}, half-close, then reset the connection if
     * FreeSWITCH has not closed it when the timeout expires. The returned future always completes, and does so
     * no later than {@code timeout} (plus scheduling slack).
     */
    public CompletableFuture<Void> closeGracefully(Duration timeout) {
        if (!closing.compareAndSet(false, true)) {
            return closeFuture;
        }
        if (!channel.isActive()) {
            channel.close();
            return closeFuture;
        }
        long budget = Math.max(timeout.toNanos(), TimeUnit.MILLISECONDS.toNanos(1));
        ScheduledFuture<?> deadline = channel.eventLoop().schedule(
                () -> abort(new ConnectionClosedException("Graceful close timed out")), budget, TimeUnit.NANOSECONDS);
        closeFuture.whenComplete((ignored, error) -> deadline.cancel(false));

        CompletableFuture<Void> drained = new CompletableFuture<>();
        channel.eventLoop().execute(() -> handler.awaitDrained().whenComplete((v, e) -> drained.complete(null)));
        drained.completeOnTimeout(null, budget / 2, TimeUnit.NANOSECONDS)
                .thenCompose(ignored -> sendRaw(new EslCommand.Exit(), Duration.ofNanos(budget / 4))
                        .handle((reply, error) -> null))
                .thenCompose(ignored -> {
                    if (channel.isActive() && channel instanceof DuplexChannel duplex && !duplex.isOutputShutdown()) {
                        duplex.shutdownOutput();
                    }
                    return closeFuture.copy();
                })
                .whenComplete((ignored, error) -> {
                    if (error != null && channel.isActive()) {
                        abort(error);
                    }
                });
        return closeFuture;
    }

    /**
     * Closes immediately with a TCP reset ({@code SO_LINGER=0}), discarding unsent data. Used when the peer
     * does not cooperate, so no socket is left lingering in {@code FIN_WAIT}/{@code CLOSE_WAIT}.
     */
    public void abort(Throwable reason) {
        closing.set(true);
        if (closeCause == null) {
            closeCause = reason;
        }
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.config().setOption(ChannelOption.SO_LINGER, 0);
        } catch (RuntimeException e) {
            log.debug("Could not set SO_LINGER=0 on {}", channel, e);
        }
        log.debug("Resetting connection to {}: {}", channel.remoteAddress(), reason == null ? "" : reason.toString());
        channel.close();
    }

    // ------------------------------------------------------------------ protocol callbacks (event loop)

    @Override
    public void onAuthRequest() {
        callbacks.onAuthRequest(this);
    }

    @Override
    public void onEvent(EslEvent event) {
        metrics.eventReceived(event.name());
        switch (event.name()) {
            case EventNames.BACKGROUND_JOB -> {
                String jobUuid = event.jobUuid();
                PendingJob job = jobUuid == null ? null : jobs.remove(jobUuid);
                if (job != null) {
                    job.complete(new BackgroundJobResult(jobUuid, job.command.describe(), event));
                }
            }
            case EventNames.CHANNEL_EXECUTE_COMPLETE -> {
                String applicationUuid = event.applicationUuid();
                PendingExecute pending = applicationUuid == null ? null : executes.remove(applicationUuid);
                if (pending != null) {
                    pending.complete(new ExecuteResult(pending.uuid, pending.application, applicationUuid, event));
                }
            }
            case EventNames.CHANNEL_HANGUP_COMPLETE -> failExecutionsOf(event);
            default -> {
                // nothing awaited
            }
        }
        callbacks.onEvent(this, event);
    }

    private void failExecutionsOf(EslEvent hangup) {
        String uuid = hangup.uniqueId();
        if (uuid == null || executes.isEmpty()) {
            return;
        }
        String cause = hangup.header(HeaderNames.HANGUP_CAUSE);
        executes.values().removeIf(pending -> {
            if (!uuid.equals(pending.uuid)) {
                return false;
            }
            if ("hangup".equalsIgnoreCase(pending.application)) {
                pending.complete(new ExecuteResult(uuid, pending.application, pending.eventUuid, hangup));
            } else {
                pending.fail(new ChannelHangupException(uuid, cause));
            }
            return true;
        });
    }

    @Override
    public void onLog(EslMessage message) {
        callbacks.onLog(this, LogLine.from(message));
    }

    @Override
    public void onDisconnectNotice(EslMessage message) {
        log.debug("Disconnect notice from {}: {}", channel.remoteAddress(),
                message.header(HeaderNames.CONTENT_DISPOSITION));
        callbacks.onDisconnectNotice(this, message);
    }

    @Override
    public void onRudeRejection(EslMessage message) {
        String reason = message.body().isBlank() ? "Access denied" : message.body().trim();
        closeCause = new RudeRejectionException(reason);
        log.warn("{} rejected the connection: {}", channel.remoteAddress(), reason);
        callbacks.onRudeRejection(this, message);
    }

    @Override
    public void onReadIdle(int pendingCommands) {
        if (pendingCommands > 0 || closing.get()) {
            // replies are outstanding (FreeSWITCH processes commands sequentially, a slow api blocks the
            // socket); their own timeouts detect a dead peer
            return;
        }
        log.debug("{} idle for {}, probing", channel.remoteAddress(), settings.idleTimeout());
        sendRaw(settings.livenessProbe()).whenComplete((reply, error) -> {
            if (error != null && channel.isActive() && !closing.get()) {
                log.warn("Liveness probe to {} failed, resetting connection: {}", channel.remoteAddress(),
                        error.toString());
                abort(new ConnectionClosedException("Liveness probe failed", error));
            }
        });
    }

    @Override
    public void onChannelClosed(Throwable cause) {
        if (!closedNotified.compareAndSet(false, true)) {
            return;
        }
        closing.set(true);
        Throwable reason = closeCause != null ? closeCause : cause;
        ConnectionClosedException closed = new ConnectionClosedException(
                "Connection to " + channel.remoteAddress() + " closed", reason);
        jobs.values().removeIf(job -> {
            job.fail(closed);
            return true;
        });
        executes.values().removeIf(pending -> {
            pending.fail(closed);
            return true;
        });
        closeFuture.complete(null);
        callbacks.onClosed(this, reason);
    }

    // ------------------------------------------------------------------ pending registries

    private static final class PendingJob {
        final EslCommand.BgApi command;
        final CompletableFuture<BackgroundJobResult> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeoutTask;

        PendingJob(EslCommand.BgApi command) {
            this.command = command;
        }

        void complete(BackgroundJobResult result) {
            cancel();
            future.complete(result);
        }

        void fail(Throwable cause) {
            cancel();
            future.completeExceptionally(cause);
        }

        private void cancel() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static final class PendingExecute {
        final String uuid;
        final String application;
        final String eventUuid;
        final CompletableFuture<ExecuteResult> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeoutTask;

        PendingExecute(String uuid, String application, String eventUuid) {
            this.uuid = uuid;
            this.application = application;
            this.eventUuid = eventUuid;
        }

        void complete(ExecuteResult result) {
            cancel();
            future.complete(result);
        }

        void fail(Throwable cause) {
            cancel();
            future.completeExceptionally(cause);
        }

        private void cancel() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}
