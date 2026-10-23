package io.github.xxd763795151.esl.outbound;

import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.dispatch.DispatchOptions;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.internal.ConnectionSettings;
import io.github.xxd763795151.esl.internal.EslFuture;
import io.github.xxd763795151.esl.internal.Futures;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Outbound event socket server: FreeSWITCH connects to it for every call that reaches a
 * {@code socket} dialplan application, and each connection becomes an {@link OutboundSession}.
 *
 * <pre>{@code
 * OutboundServer server = OutboundServer.builder()
 *         .port(8084)
 *         .handler(session -> {
 *             CallChannel call = session.channel();
 *             call.answer().join();
 *             call.playback("ivr/ivr-welcome.wav").join();
 *             call.hangup().join();
 *         })
 *         .build();
 * server.start().join();
 * ...
 * server.shutdownGracefully(Duration.ofSeconds(30)).join();
 * }</pre>
 */
public final class OutboundServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboundServer.class);

    private enum State { NEW, STARTING, RUNNING, STOPPING, STOPPED }

    private final String name;
    private final String host;
    private final int port;
    private final OutboundSessionHandler handler;
    private final boolean autoMyEvents;
    private final EventFormat eventFormat;
    private final Duration linger;
    private final boolean divertEvents;
    private final boolean resumeOnDisconnect;
    private final int maxSessions;
    private final Duration shutdownTimeout;
    private final Duration sessionCloseTimeout;
    private final ConnectionSettings settings;
    private final DispatchOptions dispatchOptions;
    private final EslResources resources;
    private final boolean ownsResources;
    private final EslMetrics metrics;
    private final ServerBootstrap bootstrap;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final Set<OutboundSession> sessions = ConcurrentHashMap.newKeySet();
    private final EslFuture<Void> terminated = new EslFuture<>();
    private volatile Channel serverChannel;

    private OutboundServer(Builder builder, EslResources resources, boolean ownsResources) {
        this.name = builder.name;
        this.host = builder.host;
        this.port = builder.port;
        this.handler = builder.handler;
        this.autoMyEvents = builder.autoMyEvents;
        this.eventFormat = builder.eventFormat;
        this.linger = builder.linger;
        this.divertEvents = builder.divertEvents;
        this.resumeOnDisconnect = builder.resumeOnDisconnect;
        this.maxSessions = builder.maxSessions;
        this.shutdownTimeout = builder.shutdownTimeout;
        this.sessionCloseTimeout = builder.sessionCloseTimeout;
        this.settings = new ConnectionSettings(builder.commandTimeout, builder.jobTimeout, builder.executeTimeout,
                builder.idleTimeout, builder.livenessProbe, builder.maxHeaderBytes, builder.maxBodyBytes);
        this.dispatchOptions = builder.dispatchOptions;
        this.resources = resources;
        this.ownsResources = ownsResources;
        this.metrics = builder.metrics;
        this.bootstrap = new ServerBootstrap()
                .group(resources.ioGroup())
                .channel(resources.serverSocketChannelClass())
                .option(ChannelOption.SO_BACKLOG, builder.acceptBacklog)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, false)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        accept(channel);
                    }
                });
        metrics.gauge(EslMetrics.GAUGE_ACTIVE_SESSIONS, sessions::size);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Binds the listening socket. Completes once the server accepts connections.
     */
    public CompletableFuture<Void> start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            return EslFuture.failed(new IllegalStateException(this + " already started"));
        }
        CompletableFuture<Void> started = new CompletableFuture<>();
        bootstrap.bind(host, port).addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                log.error("{}: cannot listen on {}:{}", this, host, port, future.cause());
                state.set(State.STOPPED);
                if (ownsResources) {
                    resources.shutdownGracefully(Duration.ZERO);
                }
                terminated.complete(null);
                started.completeExceptionally(future.cause());
                return;
            }
            serverChannel = future.channel();
            if (!state.compareAndSet(State.STARTING, State.RUNNING)) {
                serverChannel.close();
            }
            log.info("{}: listening on {} ({} transport)", this, serverChannel.localAddress(),
                    resources.transportName());
            started.complete(null);
        });
        return Futures.relay(started, Function.identity(), resources.worker());
    }

    private void accept(Channel channel) {
        if (state.get() != State.RUNNING) {
            reject(channel, "server is shutting down");
            return;
        }
        if (maxSessions > 0 && sessions.size() >= maxSessions) {
            reject(channel, "maximum of " + maxSessions + " sessions reached");
            return;
        }
        OutboundSession session = new OutboundSession(this, channel);
        sessions.add(session);
        metrics.sessionOpened();
        channel.eventLoop().execute(session::start);
    }

    private void reject(Channel channel, String reason) {
        log.warn("{}: rejecting connection from {}: {}", this, channel.remoteAddress(), reason);
        channel.config().setOption(ChannelOption.SO_LINGER, 0);
        channel.close();
    }

    void sessionClosed(OutboundSession session) {
        if (sessions.remove(session)) {
            metrics.sessionClosed();
        }
    }

    /** Actual listening address (useful with port 0). */
    public InetSocketAddress localAddress() {
        Channel channel = serverChannel;
        return channel == null ? null : (InetSocketAddress) channel.localAddress();
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    public Collection<OutboundSession> sessions() {
        return Collections.unmodifiableSet(sessions);
    }

    /**
     * Stops accepting calls, tells active sessions ({@link OutboundSessionHandler#onServerShutdown}), waits up
     * to two thirds of the timeout for them to finish on their own, then closes the rest (exit, then reset) and
     * releases owned threads.
     */
    public CompletableFuture<Void> shutdownGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        State previous = state.getAndUpdate(s -> s == State.STOPPED ? s : State.STOPPING);
        if (previous == State.STOPPING || previous == State.STOPPED) {
            return Futures.relay(terminated, Function.identity(), resources.worker());
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        log.info("{}: shutting down, {} active session(s)", this, sessions.size());
        Channel channel = serverChannel;
        CompletableFuture<Void> unbound = new CompletableFuture<>();
        if (channel == null) {
            unbound.complete(null);
        } else {
            channel.close().addListener(future -> unbound.complete(null));
        }
        unbound
                .thenCompose(ignored -> {
                    sessions.forEach(OutboundSession::notifyServerShutdown);
                    return allClosed().completeOnTimeout(null, timeout.toNanos() * 2 / 3, TimeUnit.NANOSECONDS);
                })
                .thenCompose(ignored -> {
                    if (sessions.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    log.info("{}: closing {} remaining session(s)", this, sessions.size());
                    long remaining = remaining(deadline);
                    sessions.forEach(session -> session.closeGracefully(Duration.ofNanos(remaining * 3 / 4)));
                    return allClosed().completeOnTimeout(null, remaining, TimeUnit.NANOSECONDS);
                })
                .thenCompose(ignored -> ownsResources
                        ? resources.shutdownGracefully(Duration.ofNanos(remaining(deadline)))
                        : CompletableFuture.completedFuture(null))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.warn("{}: error during shutdown", this, error);
                    }
                    state.set(State.STOPPED);
                    metrics.close();
                    log.info("{}: stopped", this);
                    terminated.complete(null);
                });
        return Futures.relay(terminated, Function.identity(), resources.worker());
    }

    private CompletableFuture<Void> allClosed() {
        return CompletableFuture.allOf(sessions.stream()
                .map(OutboundSession::closeFuture)
                .toArray(CompletableFuture[]::new));
    }

    private static long remaining(long deadline) {
        return Math.max(TimeUnit.MILLISECONDS.toNanos(1), deadline - System.nanoTime());
    }

    /** {@link #shutdownGracefully(Duration)} with the configured shutdown timeout, waiting for completion. */
    @Override
    public void close() {
        CompletableFuture<Void> stopped = shutdownGracefully(shutdownTimeout);
        if (EslFuture.isIoThread() || EslResources.isWorkerThread()) {
            return;
        }
        try {
            stopped.get(shutdownTimeout.toMillis() + 2_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("{}: did not stop cleanly: {}", this, e.toString());
        }
    }

    @Override
    public String toString() {
        return "OutboundServer[" + name + ']';
    }

    // ------------------------------------------------------------------ session configuration

    OutboundSessionHandler handler() {
        return handler;
    }

    boolean autoMyEvents() {
        return autoMyEvents;
    }

    EventFormat eventFormat() {
        return eventFormat;
    }

    Duration linger() {
        return linger;
    }

    boolean divertEvents() {
        return divertEvents;
    }

    boolean resumeOnDisconnect() {
        return resumeOnDisconnect;
    }

    Duration sessionCloseTimeout() {
        return sessionCloseTimeout;
    }

    ConnectionSettings connectionSettings() {
        return settings;
    }

    DispatchOptions dispatchOptions() {
        return dispatchOptions;
    }

    Executor worker() {
        return resources.worker();
    }

    EslMetrics metrics() {
        return metrics;
    }

    /**
     * Configuration of an {@link OutboundServer}.
     */
    public static final class Builder {
        private String name = "esl-outbound";
        private String host = "0.0.0.0";
        private int port = 8084;
        private OutboundSessionHandler handler;
        private boolean autoMyEvents = true;
        private EventFormat eventFormat = EventFormat.PLAIN;
        private Duration linger;
        private boolean divertEvents;
        private boolean resumeOnDisconnect;
        private int maxSessions;
        private int acceptBacklog = 1024;
        private Duration commandTimeout = Duration.ofSeconds(30);
        private Duration jobTimeout = Duration.ofMinutes(5);
        private Duration executeTimeout = Duration.ZERO;
        private Duration idleTimeout = Duration.ZERO;
        private EslCommand livenessProbe = EslCommand.api("version");
        private Duration shutdownTimeout = Duration.ofSeconds(30);
        private Duration sessionCloseTimeout = Duration.ofSeconds(5);
        private DispatchOptions dispatchOptions = DispatchOptions.DEFAULT;
        private EslResources resources;
        private EslMetrics metrics = EslMetrics.NOOP;
        private int maxHeaderBytes = 1 << 20;
        private int maxBodyBytes = 64 << 20;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /** Listening port; 0 picks a free one (see {@link OutboundServer#localAddress()}). */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder handler(OutboundSessionHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Send {@code myevents} after connecting (default true); needed for awaited application executions. */
        public Builder autoMyEvents(boolean autoMyEvents) {
            this.autoMyEvents = autoMyEvents;
            return this;
        }

        public Builder eventFormat(EventFormat eventFormat) {
            this.eventFormat = Objects.requireNonNull(eventFormat, "eventFormat");
            return this;
        }

        /** Send {@code linger} so events after hangup (e.g. CHANNEL_HANGUP_COMPLETE) still arrive. */
        public Builder linger(Duration linger) {
            this.linger = linger;
            return this;
        }

        public Builder divertEvents(boolean divertEvents) {
            this.divertEvents = divertEvents;
            return this;
        }

        /** Send {@code resume} so the dialplan continues if the socket goes away. */
        public Builder resumeOnDisconnect(boolean resumeOnDisconnect) {
            this.resumeOnDisconnect = resumeOnDisconnect;
            return this;
        }

        /** Limit of concurrent sessions; excess connections are reset immediately. 0 = unlimited. */
        public Builder maxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
            return this;
        }

        public Builder acceptBacklog(int acceptBacklog) {
            this.acceptBacklog = acceptBacklog;
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

        public Builder executeTimeout(Duration executeTimeout) {
            this.executeTimeout = Objects.requireNonNull(executeTimeout, "executeTimeout");
            return this;
        }

        /** Read idle time after which a session is probed; zero (default) disables probing. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
            return this;
        }

        public Builder livenessProbe(EslCommand livenessProbe) {
            this.livenessProbe = Objects.requireNonNull(livenessProbe, "livenessProbe");
            return this;
        }

        /** Default timeout of {@link OutboundServer#close()}. */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
            return this;
        }

        public Builder sessionCloseTimeout(Duration sessionCloseTimeout) {
            this.sessionCloseTimeout = Objects.requireNonNull(sessionCloseTimeout, "sessionCloseTimeout");
            return this;
        }

        /** Backpressure settings; sessions always deliver their events sequentially. */
        public Builder dispatch(DispatchOptions dispatchOptions) {
            this.dispatchOptions = Objects.requireNonNull(dispatchOptions, "dispatchOptions");
            return this;
        }

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

        public OutboundServer build() {
            if (handler == null) {
                throw new IllegalStateException("A session handler is required");
            }
            boolean owns = resources == null;
            EslResources effective = owns ? EslResources.builder().threadNamePrefix(name).build() : resources;
            return new OutboundServer(this, effective, owns);
        }
    }
}
