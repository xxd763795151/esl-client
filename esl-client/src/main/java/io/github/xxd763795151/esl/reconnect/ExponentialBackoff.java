package io.github.xxd763795151.esl.reconnect;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter: {@code min(maxDelay, initialDelay * multiplier^(attempt-1))}, randomised by
 * {@code ±jitter} so that many clients restarting together do not reconnect in lockstep.
 *
 * @param initialDelay delay before the first attempt
 * @param maxDelay     upper bound of the delay
 * @param multiplier   growth factor per attempt (&gt;= 1)
 * @param jitter       relative randomisation in [0, 1]
 * @param maxAttempts  consecutive attempts before giving up, 0 = unlimited
 */
public record ExponentialBackoff(Duration initialDelay, Duration maxDelay, double multiplier, double jitter,
                                 int maxAttempts) implements ReconnectPolicy {

    public ExponentialBackoff {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("require 0 <= initialDelay <= maxDelay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be within [0, 1]");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must not be negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<Duration> nextDelay(int attempt, Throwable cause) {
        if (maxAttempts > 0 && attempt > maxAttempts) {
            return Optional.empty();
        }
        double base = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
        double capped = Math.min(base, maxDelay.toMillis());
        double factor = jitter == 0 ? 1.0 : 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        long millis = Math.max(0, Math.min(maxDelay.toMillis(), Math.round(capped * factor)));
        return Optional.of(Duration.ofMillis(millis));
    }

    public static final class Builder {
        private Duration initialDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofSeconds(30);
        private double multiplier = 2.0;
        private double jitter = 0.2;
        private int maxAttempts;

        private Builder() {
        }

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        public Builder jitter(double jitter) {
            this.jitter = jitter;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public ExponentialBackoff build() {
            return new ExponentialBackoff(initialDelay, maxDelay, multiplier, jitter, maxAttempts);
        }
    }
}
