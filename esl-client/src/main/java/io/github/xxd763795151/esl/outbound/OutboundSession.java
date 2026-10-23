package io.github.xxd763795151.esl.outbound;

import io.github.xxd763795151.esl.app.CallChannel;
import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.dispatch.DispatchMode;
import io.github.xxd763795151.esl.dispatch.EventDispatcher;
import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.event.EventParsers;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.event.LogLine;
import io.github.xxd763795151.esl.exception.CommandFailedException;
import io.github.xxd763795151.esl.exception.ConnectionClosedException;
import io.github.xxd763795151.esl.internal.AbstractConnection;
import io.github.xxd763795151.esl.internal.EslFuture;
import io.github.xxd763795151.esl.internal.Futures;
import io.github.xxd763795151.esl.internal.netty.ChannelConnection;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.github.xxd763795151.esl.protocol.HeaderNames;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * One call controlled through the outbound socket. FreeSWITCH connected to us; the session sends
 * {@code connect}, optionally {@code myevents}/{@code linger}, then hands itself to the
 * {@link OutboundSessionHandler}.
 */
public final class OutboundSession extends AbstractConnection {

    private static final Logger log = LoggerFactory.getLogger(OutboundSession.class);

    private final OutboundServer server;
    private final ChannelConnection connection;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean handlerStarted = new AtomicBoolean();
    private final EslFuture<Void> terminated = new EslFuture<>();
    private volatile EslEvent channelData;
    private volatile String uuid;

    OutboundSession(OutboundServer server, Channel channel) {
        super(new EventDispatcher(server.dispatchOptions().withMode(DispatchMode.SEQUENTIAL), server.worker(),
                server.metrics()), server.worker(), server.metrics());
        this.server = server;
        this.connection = new ChannelConnection(channel, server.connectionSettings(), worker, metrics, new Callbacks());
        dispatcher.setReadGate(() -> channel.eventLoop().execute(
                () -> channel.config().setAutoRead(!dispatcher.isReadPaused())));
        OutboundSessionHandler handler = server.handler();
        dispatcher.addEventListener(EventSelector.all(), event -> handler.onEvent(this, event));
    }

    /** Runs the handshake; called on the channel's event loop once the channel is registered. */
    void start() {
        moveTo(ConnectionState.CONNECTING, null);
        connection.sendRaw(new EslCommand.Connect())
                .thenCompose(reply -> {
                    channelData = EventParsers.fromEncodedHeaders(reply.headers(), reply.body());
                    uuid = channelData.uniqueId();
                    if (uuid == null) {
                        uuid = channelData.header(HeaderNames.CHANNEL_CALL_UUID);
                    }
                    CompletableFuture<Void> setup = CompletableFuture.completedFuture(null);
                    if (server.autoMyEvents()) {
                        setup = setup.thenCompose(ignored -> handshake(new EslCommand.MyEvents(null, server.eventFormat())));
                    }
                    if (server.linger() != null) {
                        setup = setup.thenCompose(ignored -> handshake(new EslCommand.Linger(server.linger())));
                    }
                    if (server.divertEvents()) {
                        setup = setup.thenCompose(ignored -> handshake(new EslCommand.DivertEvents(true)));
                    }
                    if (server.resumeOnDisconnect()) {
                        setup = setup.thenCompose(ignored -> handshake(new EslCommand.Resume()));
                    }
                    return setup;
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.warn("{}: handshake failed: {}", this, Futures.unwrap(error).toString());
                        connection.abort(Futures.unwrap(error));
                        return;
                    }
                    if (moveTo(ConnectionState.READY, null) != ConnectionState.CONNECTING) {
                        return;
                    }
                    log.debug("{}: connected", this);
                    runHandler();
                });
    }

    private CompletableFuture<Void> handshake(EslCommand command) {
        return connection.sendRaw(command).thenAccept(reply -> {
            CommandReply commandReply = new CommandReply(reply);
            if (!commandReply.isOk()) {
                throw new CommandFailedException(command.describe(), commandReply.replyText());
            }
            sessionState.record(command);
        });
    }

    private void runHandler() {
        handlerStarted.set(true);
        try {
            worker.execute(() -> {
                try {
                    server.handler().onConnect(this);
                } catch (Throwable t) {
                    log.warn("{}: handler failed, closing session", this, t);
                    closeGracefully(server.sessionCloseTimeout());
                }
            });
        } catch (RuntimeException e) {
            log.warn("{}: cannot run handler, closing session", this, e);
            connection.abort(e);
        }
    }

    void notifyServerShutdown() {
        if (!handlerStarted.get()) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    server.handler().onServerShutdown(this);
                } catch (Throwable t) {
                    log.warn("{}: onServerShutdown failed", this, t);
                }
            });
        } catch (RuntimeException e) {
            log.debug("{}: worker unavailable for shutdown notification", this);
        }
    }

    // ------------------------------------------------------------------ accessors

    /** Unique-ID of the controlled channel. */
    public String uuid() {
        return uuid;
    }

    /** Channel data sent by FreeSWITCH in reply to {@code connect} (all channel variables and caller info). */
    public EslEvent channelData() {
        return channelData;
    }

    /** Call control for the session's own channel. */
    public CallChannel channel() {
        return channel(uuid);
    }

    /** Per-session storage for application state. */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public SocketAddress remoteAddress() {
        return connection.remoteAddress();
    }

    /** Completes when the session is fully closed and its events were delivered. */
    public CompletableFuture<Void> closeFuture() {
        return Futures.relay(terminated, Function.identity(), worker);
    }

    // ------------------------------------------------------------------ session commands

    /** {@code getvar}: reads a channel variable of this session; {@code null} when unset. */
    public CompletableFuture<String> getVar(String variable) {
        EslCommand.GetVar command = new EslCommand.GetVar(variable);
        return withConnection(conn -> conn.send(command, reply -> {
            String value = reply.replyText();
            return value == null || value.isEmpty() || "_undef_".equals(value) ? null : value;
        }));
    }

    /** {@code linger}: keep receiving events for {@code duration} after hangup ({@code null}: server default). */
    public CompletableFuture<CommandReply> linger(Duration duration) {
        return command(new EslCommand.Linger(duration));
    }

    public CompletableFuture<CommandReply> noLinger() {
        return command(new EslCommand.NoLinger());
    }

    /** {@code resume}: continue the dialplan when this socket disconnects. */
    public CompletableFuture<CommandReply> resume() {
        return command(new EslCommand.Resume());
    }

    public CompletableFuture<CommandReply> divertEvents(boolean enabled) {
        return command(new EslCommand.DivertEvents(enabled));
    }

    /** {@code myevents}: receive all events of this channel (enabled automatically unless configured off). */
    public CompletableFuture<CommandReply> myEvents() {
        return command(new EslCommand.MyEvents(null, server.eventFormat()));
    }

    // ------------------------------------------------------------------ AbstractConnection

    @Override
    protected <T> CompletableFuture<T> withConnection(Function<ChannelConnection, CompletableFuture<T>> action) {
        ConnectionState state = state();
        if (state == ConnectionState.READY && connection.isActive()) {
            return action.apply(connection);
        }
        return EslFuture.failed(new ConnectionClosedException(this + " is not connected (" + state + ")"));
    }

    @Override
    protected CompletableFuture<Void> ensureExecuteEvents(String targetUuid) {
        if (sessionState.myEventsEnabled() && targetUuid.equals(uuid)) {
            return EslFuture.completed(null);
        }
        return super.ensureExecuteEvents(targetUuid);
    }

    @Override
    protected EventFormat eventFormat() {
        return server.eventFormat();
    }

    @Override
    protected Duration defaultCloseTimeout() {
        return server.sessionCloseTimeout();
    }

    @Override
    public CompletableFuture<Void> closeGracefully(Duration timeout) {
        moveTo(ConnectionState.CLOSING, null);
        connection.closeGracefully(timeout);
        return Futures.relay(terminated, Function.identity(), worker);
    }

    @Override
    public String toString() {
        return "OutboundSession[" + (uuid != null ? uuid : connection.remoteAddress()) + ']';
    }

    private final class Callbacks implements ChannelConnection.Callbacks {

        @Override
        public void onEvent(ChannelConnection conn, EslEvent event) {
            dispatcher.dispatch(event);
        }

        @Override
        public void onLog(ChannelConnection conn, LogLine line) {
            dispatcher.dispatchLog(line);
        }

        @Override
        public void onDisconnectNotice(ChannelConnection conn, EslMessage message) {
            log.debug("{}: disconnect notice ({})", OutboundSession.this,
                    message.header(HeaderNames.CONTENT_DISPOSITION));
        }

        @Override
        public void onClosed(ChannelConnection conn, Throwable cause) {
            moveTo(ConnectionState.CLOSING, cause);
            if (handlerStarted.get()) {
                // ordered after every event already queued for this session
                dispatcher.execute(OutboundSession.this, () -> {
                    try {
                        server.handler().onDisconnect(OutboundSession.this, cause);
                    } catch (Throwable t) {
                        log.warn("{}: onDisconnect failed", OutboundSession.this, t);
                    }
                });
            }
            dispatcher.shutdown();
            dispatcher.awaitIdle().whenComplete((ignored, error) -> {
                moveTo(ConnectionState.CLOSED, cause);
                server.sessionClosed(OutboundSession.this);
                terminated.complete(null);
            });
        }
    }
}
