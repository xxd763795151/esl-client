package io.github.xxd763795151.esl.metrics;

import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.test.FakeFreeSwitch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MicrometerEslMetricsTest {

    @Test
    void recordsCommandsEventsAndGaugesAndCleansUp() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FakeFreeSwitch fs = FakeFreeSwitch.start()) {
            InboundClient client = InboundClient.builder()
                    .name("metrics")
                    .address("127.0.0.1", fs.port())
                    .metrics(new MicrometerEslMetrics(registry, "metrics"))
                    .build();
            client.connect().get(5, TimeUnit.SECONDS);
            client.api("status").get(5, TimeUnit.SECONDS);
            fs.awaitPeer(Duration.ofSeconds(5)).event("HEARTBEAT", null, Map.of());

            await().atMost(Duration.ofSeconds(5)).until(() ->
                    registry.find("esl.events.received").tag("event", "HEARTBEAT").counter() != null);
            assertThat(registry.get("esl.commands").tags("client", "metrics", "command", "api", "outcome", "success")
                    .timer().count()).isEqualTo(1);
            assertThat(registry.get("esl.connected").gauge().value()).isEqualTo(1.0);

            client.close();

            assertThat(registry.find("esl.commands").meters()).isEmpty();
            assertThat(registry.find("esl.connected").meters()).isEmpty();
        }
    }
}
