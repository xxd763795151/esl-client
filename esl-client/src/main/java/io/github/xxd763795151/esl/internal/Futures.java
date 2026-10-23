package io.github.xxd763795151.esl.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * Future plumbing helpers.
 */
public final class Futures {

    private Futures() {
    }

    /**
     * Returns a future completed with {@code mapper(source result)} on {@code executor}, so user callbacks
     * never run on the thread that completed {@code source} (typically a Netty I/O thread).
     */
    public static <S, T> EslFuture<T> relay(CompletableFuture<S> source, Function<? super S, ? extends T> mapper,
                                            Executor executor) {
        EslFuture<T> target = new EslFuture<>();
        source.whenComplete((value, error) -> {
            Runnable completion = () -> {
                if (error != null) {
                    target.completeExceptionally(unwrap(error));
                    return;
                }
                try {
                    target.complete(mapper.apply(value));
                } catch (Throwable t) {
                    target.completeExceptionally(unwrap(t));
                }
            };
            try {
                executor.execute(completion);
            } catch (RejectedExecutionException e) {
                completion.run();
            }
        });
        return target;
    }

    /** Strips the {@link CompletionException} / {@link ExecutionException} wrappers. */
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
