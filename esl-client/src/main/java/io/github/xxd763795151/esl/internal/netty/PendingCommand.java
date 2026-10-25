package io.github.xxd763795151.esl.internal.netty;

import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.exception.CommandTimeoutException;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A command waiting for its reply. Lives in the handler's FIFO queue from the moment it is written until a
 * reply arrives, even after it timed out: the late reply still has to be consumed to keep the queue aligned.
 */
final class PendingCommand {

    private final EslCommand command;
    private final Duration timeout;
    private final CompletableFuture<EslMessage> promise = new CompletableFuture<>();
    private long startNanos;
    private ScheduledFuture<?> timeoutTask;
    private boolean timedOut;

    PendingCommand(EslCommand command, Duration timeout) {
        this.command = command;
        this.timeout = timeout;
    }

    EslCommand command() {
        return command;
    }

    CompletableFuture<EslMessage> promise() {
        return promise;
    }

    long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    /** Whether the timeout fired before a reply arrived. Event loop only. */
    boolean isTimedOut() {
        return timedOut;
    }

    /** Called on the event loop when the command is queued for writing. */
    void start(EventExecutor executor, Runnable onTimeout) {
        startNanos = System.nanoTime();
        if (!timeout.isZero()) {
            timeoutTask = executor.schedule(() -> {
                if (promise.completeExceptionally(new CommandTimeoutException(command.describe(), timeout))) {
                    timedOut = true;
                    onTimeout.run();
                }
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    boolean complete(EslMessage reply) {
        cancelTimeout();
        return promise.complete(reply);
    }

    boolean fail(Throwable cause) {
        cancelTimeout();
        return promise.completeExceptionally(cause);
    }

    private void cancelTimeout() {
        ScheduledFuture<?> task = timeoutTask;
        if (task != null) {
            task.cancel(false);
        }
    }
}
