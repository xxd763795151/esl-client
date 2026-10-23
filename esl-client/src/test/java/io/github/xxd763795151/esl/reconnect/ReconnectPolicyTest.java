package io.github.xxd763795151.esl.reconnect;

import io.github.xxd763795151.esl.exception.AuthenticationException;
import io.github.xxd763795151.esl.exception.ConnectionClosedException;
import io.github.xxd763795151.esl.exception.RudeRejectionException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectPolicyTest {

    private static final Throwable CAUSE = new IOException("reset");

    @Test
    void noneNeverRetries() {
        assertThat(ReconnectPolicy.none().nextDelay(1, CAUSE)).isEmpty();
    }

    @Test
    void fixedDelayHonoursMaxAttempts() {
        ReconnectPolicy policy = ReconnectPolicy.fixed(Duration.ofSeconds(2), 3);

        assertThat(policy.nextDelay(1, CAUSE)).contains(Duration.ofSeconds(2));
        assertThat(policy.nextDelay(3, CAUSE)).contains(Duration.ofSeconds(2));
        assertThat(policy.nextDelay(4, CAUSE)).isEmpty();
        assertThat(ReconnectPolicy.fixed(Duration.ofSeconds(1)).nextDelay(10_000, CAUSE)).isPresent();
    }

    @Test
    void exponentialGrowsAndIsCapped() {
        ExponentialBackoff policy = ReconnectPolicy.exponential()
                .initialDelay(Duration.ofMillis(100)).maxDelay(Duration.ofSeconds(1))
                .multiplier(2).jitter(0).build();

        assertThat(policy.nextDelay(1, CAUSE)).contains(Duration.ofMillis(100));
        assertThat(policy.nextDelay(2, CAUSE)).contains(Duration.ofMillis(200));
        assertThat(policy.nextDelay(4, CAUSE)).contains(Duration.ofMillis(800));
        assertThat(policy.nextDelay(5, CAUSE)).contains(Duration.ofSeconds(1));
        assertThat(policy.nextDelay(50, CAUSE)).contains(Duration.ofSeconds(1));
    }

    @Test
    void jitterStaysWithinBounds() {
        ExponentialBackoff policy = ReconnectPolicy.exponential()
                .initialDelay(Duration.ofMillis(1000)).maxDelay(Duration.ofSeconds(10)).jitter(0.2).build();

        for (int i = 0; i < 1000; i++) {
            long millis = policy.nextDelay(1, CAUSE).orElseThrow().toMillis();
            assertThat(millis).isBetween(800L, 1200L);
        }
    }

    @Test
    void authAndAclFailuresAreFinalUnlessOverridden() {
        ReconnectPolicy policy = ReconnectPolicy.fixed(Duration.ofSeconds(1));

        assertThat(policy.isRetryable(new ConnectionClosedException("lost"))).isTrue();
        assertThat(policy.isRetryable(new AuthenticationException("bad"))).isFalse();
        assertThat(policy.isRetryable(new RudeRejectionException("acl"))).isFalse();
        assertThat(policy.retryingAllFailures().isRetryable(new AuthenticationException("bad"))).isTrue();
    }

    @Test
    void customPoliciesAreLambdas() {
        ReconnectPolicy policy = (attempt, cause) -> attempt <= 2 ? Optional.of(Duration.ofMillis(attempt)) : Optional.empty();

        assertThat(policy.withMaxAttempts(1).nextDelay(2, CAUSE)).isEmpty();
        assertThat(policy.nextDelay(2, CAUSE)).contains(Duration.ofMillis(2));
    }
}
