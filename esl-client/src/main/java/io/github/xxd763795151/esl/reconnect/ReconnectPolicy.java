package io.github.xxd763795151.esl.reconnect;

import io.github.xxd763795151.esl.exception.AuthenticationException;
import io.github.xxd763795151.esl.exception.RudeRejectionException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether and when an inbound client reconnects after losing its connection (or failing to connect).
 * Implementations must be stateless or thread-safe; the attempt counter is kept by the client.
 *
 * <pre>{@code
 * ReconnectPolicy.none();
 * ReconnectPolicy.fixed(Duration.ofSeconds(5));
 * ReconnectPolicy.exponential().initialDelay(Duration.ofMillis(500)).maxDelay(Duration.ofSeconds(30)).build();
 * (attempt, cause) -> attempt <= 3 ? Optional.of(Duration.ofSeconds(attempt)) : Optional.empty();
 * }</pre>
 */
@FunctionalInterface
public interface ReconnectPolicy {

    /**
     * @param attempt 1-based number of the attempt about to be scheduled; resets after a successful connect
     * @param cause   why the previous connection or attempt ended
     * @return delay before the attempt, or empty to give up (the client then closes)
     */
    Optional<Duration> nextDelay(int attempt, Throwable cause);

    /**
     * Whether a failure may be retried at all. By default a rejected password or ACL rejection is final,
     * since reconnecting cannot fix it.
     */
    default boolean isRetryable(Throwable cause) {
        return !(cause instanceof AuthenticationException) && !(cause instanceof RudeRejectionException);
    }

    /** Never reconnect. */
    static ReconnectPolicy none() {
        return (attempt, cause) -> Optional.empty();
    }

    /** Reconnect forever with a fixed delay. */
    static ReconnectPolicy fixed(Duration delay) {
        return new FixedDelay(delay, 0);
    }

    /** Reconnect with a fixed delay, at most {@code maxAttempts} times in a row (0 = unlimited). */
    static ReconnectPolicy fixed(Duration delay, int maxAttempts) {
        return new FixedDelay(delay, maxAttempts);
    }

    static ExponentialBackoff.Builder exponential() {
        return ExponentialBackoff.builder();
    }

    /** Limits this policy to {@code maxAttempts} consecutive attempts. */
    default ReconnectPolicy withMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        ReconnectPolicy delegate = this;
        return new ReconnectPolicy() {
            @Override
            public Optional<Duration> nextDelay(int attempt, Throwable cause) {
                return attempt > maxAttempts ? Optional.empty() : delegate.nextDelay(attempt, cause);
            }

            @Override
            public boolean isRetryable(Throwable cause) {
                return delegate.isRetryable(cause);
            }
        };
    }

    /** Also retries authentication failures and ACL rejections (e.g. while FreeSWITCH is being reconfigured). */
    default ReconnectPolicy retryingAllFailures() {
        ReconnectPolicy delegate = this;
        return new ReconnectPolicy() {
            @Override
            public Optional<Duration> nextDelay(int attempt, Throwable cause) {
                return delegate.nextDelay(attempt, cause);
            }

            @Override
            public boolean isRetryable(Throwable cause) {
                return true;
            }
        };
    }

    /**
     * Fixed delay between attempts.
     *
     * @param delay       delay before every attempt
     * @param maxAttempts consecutive attempts before giving up, 0 = unlimited
     */
    record FixedDelay(Duration delay, int maxAttempts) implements ReconnectPolicy {
        public FixedDelay {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative() || maxAttempts < 0) {
                throw new IllegalArgumentException("delay and maxAttempts must not be negative");
            }
        }

        @Override
        public Optional<Duration> nextDelay(int attempt, Throwable cause) {
            return maxAttempts > 0 && attempt > maxAttempts ? Optional.empty() : Optional.of(delay);
        }
    }
}
