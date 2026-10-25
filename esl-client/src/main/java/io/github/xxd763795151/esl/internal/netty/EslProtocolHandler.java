package io.github.xxd763795151.esl.internal.netty;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventParsers;
import io.github.xxd763795151.esl.exception.ConnectionClosedException;
import io.github.xxd763795151.esl.metrics.CommandOutcome;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.github.xxd763795151.esl.protocol.ContentTypes;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Correlates replies with commands and routes unsolicited frames.
 * <p>
 * FreeSWITCH answers commands strictly in order, so a FIFO queue is enough. The queue is only touched on the
 * event loop: a command is enqueued in {@link #write} right before it goes to the wire, which makes "queue
 * order == wire order" hold without any lock, regardless of which threads send.
 */
final class EslProtocolHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(EslProtocolHandler.class);

    private final ProtocolListener listener;
    private final EslMetrics metrics;
    private final ArrayDeque<PendingCommand> pending = new ArrayDeque<>();
    private final List<CompletableFuture<Void>> drainWaiters = new ArrayList<>();
    private volatile int pendingCount;
    private volatile Throwable failure;
    private boolean closed;

    EslProtocolHandler(ProtocolListener listener, EslMetrics metrics) {
        this.listener = listener;
        this.metrics = metrics;
    }

    Throwable failure() {
        return failure;
    }

    int pendingCount() {
        return pendingCount;
    }

    /** Completes once no command is waiting for a reply. Must be called on the event loop. */
    CompletableFuture<Void> awaitDrained() {
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        drainWaiters.add(waiter);
        return waiter;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof PendingCommand command)) {
            ctx.write(msg, promise);
            return;
        }
        if (closed) {
            command.fail(new ConnectionClosedException("Connection closed", failure));
            promise.setFailure(new ConnectionClosedException("Connection closed"));
            return;
        }
        pending.addLast(command);
        pendingCount = pending.size();
        command.start(ctx.executor(), () -> metrics.commandCompleted(command.command().name(),
                command.elapsedNanos(), CommandOutcome.TIMEOUT));
        promise.addListener(future -> {
            if (!future.isSuccess() && pending.remove(command)) {
                pendingCount = pending.size();
                command.fail(new ConnectionClosedException("Failed to write " + command.command().describe(),
                        future.cause()));
                metrics.commandCompleted(command.command().name(), command.elapsedNanos(), CommandOutcome.FAILED);
                notifyDrainedIfEmpty();
            }
        });
        if (log.isDebugEnabled()) {
            log.debug("{} >> {}", ctx.channel().remoteAddress(), command.command().describe());
        }
        ctx.write(command.command(), promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof EslMessage message)) {
            ctx.fireChannelRead(msg);
            return;
        }
        String contentType = message.contentType().toLowerCase(Locale.ROOT);
        switch (contentType) {
            case ContentTypes.COMMAND_REPLY, ContentTypes.API_RESPONSE -> completeNext(ctx, message);
            case ContentTypes.EVENT_PLAIN, ContentTypes.EVENT_JSON, ContentTypes.EVENT_XML -> {
                EslEvent event;
                try {
                    event = EventParsers.parse(message);
                } catch (IllegalArgumentException e) {
                    log.warn("Dropping malformed {} frame: {}", contentType, e.getMessage());
                    return;
                }
                listener.onEvent(event);
            }
            case ContentTypes.AUTH_REQUEST -> listener.onAuthRequest();
            case ContentTypes.LOG_DATA -> listener.onLog(message);
            case ContentTypes.DISCONNECT_NOTICE -> listener.onDisconnectNotice(message);
            case ContentTypes.RUDE_REJECTION -> listener.onRudeRejection(message);
            default -> log.debug("Ignoring frame with unknown content type '{}'", contentType);
        }
    }

    private void completeNext(ChannelHandlerContext ctx, EslMessage reply) {
        PendingCommand command = pending.pollFirst();
        pendingCount = pending.size();
        if (command == null) {
            log.warn("{} sent a reply nobody waits for: {}", ctx.channel().remoteAddress(), reply.replyText());
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("{} << {} ({})", ctx.channel().remoteAddress(), command.command().describe(),
                    reply.replyText() != null ? reply.replyText() : reply.contentType());
        }
        if (command.complete(reply)) {
            metrics.commandCompleted(command.command().name(), command.elapsedNanos(), outcome(reply));
        } else {
            log.debug("Discarding late reply to timed out command {}", command.command().describe());
        }
        notifyDrainedIfEmpty();
    }

    private static CommandOutcome outcome(EslMessage reply) {
        String text = reply.isContentType(ContentTypes.API_RESPONSE) ? reply.body() : reply.replyText();
        return text != null && text.startsWith("-ERR") ? CommandOutcome.ERROR_REPLY : CommandOutcome.SUCCESS;
    }

    private void notifyDrainedIfEmpty() {
        if (pending.isEmpty() && !drainWaiters.isEmpty()) {
            List<CompletableFuture<Void>> waiters = new ArrayList<>(drainWaiters);
            drainWaiters.clear();
            waiters.forEach(waiter -> waiter.complete(null));
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            PendingCommand oldest = pending.peekFirst();
            listener.onReadIdle(pending.size(), oldest != null && oldest.isTimedOut());
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        if (cause instanceof IOException) {
            log.debug("I/O error on {}: {}", ctx.channel().remoteAddress(), cause.toString());
        } else {
            log.warn("Closing {} after unexpected error", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closed = true;
        ConnectionClosedException closedException = new ConnectionClosedException(
                "Connection to " + ctx.channel().remoteAddress() + " closed", failure);
        PendingCommand command;
        while ((command = pending.pollFirst()) != null) {
            if (command.fail(closedException)) {
                metrics.commandCompleted(command.command().name(), command.elapsedNanos(), CommandOutcome.FAILED);
            }
        }
        pendingCount = 0;
        notifyDrainedIfEmpty();
        listener.onChannelClosed(failure);
        ctx.fireChannelInactive();
    }
}
