package io.github.xxd763795151.esl.reconnect;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Restores socket state after a reconnect. A failed recovery counts as a failed connection attempt and is
 * retried according to the {@link ReconnectPolicy}.
 *
 * <pre>{@code
 * RecoveryStrategy.replayState()                                   // default
 * RecoveryStrategy.replayState().andThen(ctx -> ctx.send(...).thenApply(r -> null))
 * RecoveryStrategy.none()
 * }</pre>
 */
@FunctionalInterface
public interface RecoveryStrategy {

    CompletableFuture<Void> recover(RecoveryContext context);

    /** Re-sends every subscription, filter and log level that was active before the connection was lost. */
    static RecoveryStrategy replayState() {
        return RecoveryContext::replayRecordedState;
    }

    /** Starts from a blank socket. */
    static RecoveryStrategy none() {
        return context -> {
            context.discardRecordedState();
            return CompletableFuture.completedFuture(null);
        };
    }

    default RecoveryStrategy andThen(RecoveryStrategy next) {
        Objects.requireNonNull(next, "next");
        return context -> recover(context).thenCompose(ignored -> next.recover(context));
    }
}
