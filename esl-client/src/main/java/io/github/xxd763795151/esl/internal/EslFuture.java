package io.github.xxd763795151.esl.internal;

import io.netty.channel.EventLoop;
import io.netty.util.internal.ThreadExecutorMap;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link CompletableFuture} that refuses to block a Netty I/O thread: waiting there for a reply that must be
 * read by that same thread would deadlock forever, so it fails fast instead. Dependent stages created from it
 * carry the same guard.
 */
public final class EslFuture<T> extends CompletableFuture<T> {

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new EslFuture<>();
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        guard();
        return super.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        guard();
        return super.get(timeout, unit);
    }

    @Override
    public T join() {
        guard();
        return super.join();
    }

    private void guard() {
        if (!isDone() && isIoThread()) {
            throw new IllegalStateException("Blocking on an ESL result from a Netty I/O thread would deadlock; "
                    + "chain with thenApply/thenCompose instead");
        }
    }

    public static boolean isIoThread() {
        return ThreadExecutorMap.currentExecutor() instanceof EventLoop;
    }

    public static <T> EslFuture<T> failed(Throwable cause) {
        EslFuture<T> future = new EslFuture<>();
        future.completeExceptionally(cause);
        return future;
    }

    public static <T> EslFuture<T> completed(T value) {
        EslFuture<T> future = new EslFuture<>();
        future.complete(value);
        return future;
    }
}
