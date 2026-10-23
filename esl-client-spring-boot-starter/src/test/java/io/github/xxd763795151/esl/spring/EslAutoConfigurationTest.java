package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import io.github.xxd763795151.esl.outbound.OutboundSession;
import io.github.xxd763795151.esl.outbound.OutboundSessionHandler;
import io.github.xxd763795151.esl.test.FakeFreeSwitch;
import io.github.xxd763795151.esl.test.FakePeer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EslAutoConfigurationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EslAutoConfiguration.class, EslHealthAutoConfiguration.class));

    private FakeFreeSwitch fs;

    @BeforeEach
    void startFake() {
        fs = FakeFreeSwitch.start();
    }

    @AfterEach
    void stopFake() {
        fs.close();
    }

    @Test
    void createsNothingButSharedResourcesByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EslResources.class);
            assertThat(context).doesNotHaveBean(InboundClient.class);
            assertThat(context).doesNotHaveBean(OutboundServer.class);
        });
    }

    @Test
    void connectsTheInboundClientAndRoutesAnnotatedListeners() {
        runner.withUserConfiguration(ListenerConfiguration.class, MetricsConfiguration.class)
                .withPropertyValues(
                        "esl.inbound.enabled=true",
                        "esl.inbound.name=fs1",
                        "esl.inbound.addresses=127.0.0.1:" + fs.port(),
                        "esl.inbound.events=CHANNEL_ANSWER,CHANNEL_HANGUP_COMPLETE",
                        "esl.inbound.filters.Event-Name=CHANNEL_ANSWER",
                        "esl.inbound.reconnect.strategy=fixed",
                        "esl.inbound.reconnect.delay=200ms")
                .run(context -> {
                    InboundClient client = context.getBean(InboundClient.class);
                    assertThat(client.state()).isEqualTo(ConnectionState.READY);
                    FakePeer peer = fs.awaitPeer(WAIT);
                    assertThat(peer.receivedLines()).containsExactly("auth ClueCon",
                            "event plain CHANNEL_ANSWER CHANNEL_HANGUP_COMPLETE", "filter Event-Name CHANNEL_ANSWER");

                    peer.event("CHANNEL_ANSWER", "u1", Map.of());
                    peer.event("CHANNEL_HANGUP_COMPLETE", "u1", Map.of());
                    Listener listener = context.getBean(Listener.class);
                    await().atMost(WAIT).until(() -> listener.answered.size() == 1 && listener.all.size() == 2);

                    HealthIndicator health = context.getBean("eslInboundHealthIndicator", HealthIndicator.class);
                    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(context.getBean(MeterRegistry.class).find("esl.connected").tag("client", "fs1").gauge())
                            .isNotNull();
                });
        // context closed: the lifecycle closed the client gracefully
        FakePeer peer = fs.peers().get(0);
        assertThat(peer.receivedLines()).endsWith("exit");
    }

    @Test
    void startsTheOutboundServerWhenAHandlerExists() {
        runner.withUserConfiguration(HandlerConfiguration.class)
                .withPropertyValues("esl.outbound.port=0", "esl.outbound.host=127.0.0.1",
                        "esl.outbound.shutdown-timeout=2s")
                .run(context -> {
                    OutboundServer server = context.getBean(OutboundServer.class);
                    assertThat(server.isRunning()).isTrue();
                    assertThat(server.localAddress().getPort()).isPositive();
                    HealthIndicator health = context.getBean("eslOutboundHealthIndicator", HealthIndicator.class);
                    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
                });
    }

    @Test
    void outboundServerCanBeDisabled() {
        runner.withUserConfiguration(HandlerConfiguration.class)
                .withPropertyValues("esl.outbound.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboundServer.class));
    }

    @Test
    void connectsNamedInboundClientsAndRoutesListenersByClient() {
        try (FakeFreeSwitch fs2 = FakeFreeSwitch.start()) {
            runner.withUserConfiguration(NamedListenerConfiguration.class, MetricsConfiguration.class)
                    .withPropertyValues(
                            "esl.inbounds.fs1.addresses=127.0.0.1:" + fs.port(),
                            "esl.inbounds.fs1.events=CHANNEL_ANSWER",
                            "esl.inbounds.fs2.addresses=127.0.0.1:" + fs2.port(),
                            "esl.inbounds.fs2.events=CHANNEL_ANSWER",
                            "esl.inbounds.fs3.enabled=false")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean("eslInboundClient");
                        EslClients clients = context.getBean(EslClients.class);
                        assertThat(clients.inbounds()).containsOnlyKeys("fs1", "fs2");
                        assertThat(clients.inbounds().values()).allMatch(c -> c.state() == ConnectionState.READY);

                        Consumer consumer = context.getBean(Consumer.class);
                        assertThat(consumer.fs2()).isSameAs(clients.inbound("fs2"));
                        assertThat(consumer.fs2().name()).isEqualTo("fs2");
                        assertThat(context.getBean(NameRecordingCustomizer.class).names)
                                .containsExactlyInAnyOrder("fs1", "fs2");

                        FakePeer peer1 = fs.awaitPeer(WAIT);
                        FakePeer peer2 = fs2.awaitPeer(WAIT);
                        peer1.event("CHANNEL_ANSWER", "u1", Map.of());
                        peer2.event("CHANNEL_ANSWER", "u2", Map.of());
                        NamedListener listener = context.getBean(NamedListener.class);
                        await().atMost(WAIT).until(() -> listener.fs1.size() == 1 && listener.all.size() == 2);
                        assertThat(listener.fs1).containsExactly("u1");
                        assertThat(listener.all).containsExactlyInAnyOrder("u1", "u2");

                        CompositeHealthContributor health = context.getBean("eslInboundsHealthContributor",
                                CompositeHealthContributor.class);
                        assertThat(((HealthIndicator) health.getContributor("fs1")).health().getStatus())
                                .isEqualTo(Status.UP);
                        assertThat(((HealthIndicator) health.getContributor("fs2")).health().getStatus())
                                .isEqualTo(Status.UP);
                        MeterRegistry registry = context.getBean(MeterRegistry.class);
                        assertThat(registry.find("esl.connected").tag("client", "fs1").gauge()).isNotNull();
                        assertThat(registry.find("esl.connected").tag("client", "fs2").gauge()).isNotNull();
                    });
            // context closed: every lifecycle closed its client gracefully
            assertThat(fs.peers().get(0).receivedLines()).endsWith("exit");
            assertThat(fs2.peers().get(0).receivedLines()).endsWith("exit");
        }
    }

    @Test
    void plainInjectionGetsTheDefaultClientNextToNamedOnes() {
        try (FakeFreeSwitch fs2 = FakeFreeSwitch.start()) {
            runner.withUserConfiguration(DefaultConsumerConfiguration.class)
                    .withPropertyValues(
                            "esl.inbound.enabled=true",
                            "esl.inbound.addresses=127.0.0.1:" + fs.port(),
                            "esl.inbounds.fs2.addresses=127.0.0.1:" + fs2.port())
                    .run(context -> {
                        DefaultConsumer consumer = context.getBean(DefaultConsumer.class);
                        assertThat(consumer.client().name()).isEqualTo("esl");
                        assertThat(consumer.fs2().name()).isEqualTo("fs2");
                        assertThat(context.getBean(EslClients.class).inbounds()).containsOnlyKeys("esl", "fs2");
                        HealthIndicator health = context.getBean("eslInboundHealthIndicator", HealthIndicator.class);
                        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
                    });
        }
    }

    @Test
    void rejectsDuplicateInstanceNames() {
        runner.withPropertyValues(
                        "esl.inbound.enabled=true",
                        "esl.inbound.name=fs1",
                        "esl.inbounds.fs1.addresses=127.0.0.1:" + fs.port())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("'fs1' of esl.inbounds.fs1 is already used by esl.inbound");
                });
    }

    @Test
    void rejectsListenersOfUnknownClients() {
        runner.withUserConfiguration(UnknownClientListenerConfiguration.class)
                .withPropertyValues("esl.inbounds.fs1.addresses=127.0.0.1:" + fs.port())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("unknown client 'nope', known: [fs1]");
                });
    }

    @Test
    void startsNamedOutboundServersWithTheirOwnHandlers() {
        runner.withUserConfiguration(TwoHandlersConfiguration.class)
                .withPropertyValues(
                        "esl.outbounds.ivr.port=0", "esl.outbounds.ivr.host=127.0.0.1",
                        "esl.outbounds.ivr.handler=ivrHandler", "esl.outbounds.ivr.shutdown-timeout=2s",
                        "esl.outbounds.queue.port=0", "esl.outbounds.queue.host=127.0.0.1",
                        "esl.outbounds.queue.handler=queueHandler", "esl.outbounds.queue.shutdown-timeout=2s")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("eslOutboundServer");
                    EslClients clients = context.getBean(EslClients.class);
                    assertThat(clients.outbounds()).containsOnlyKeys("ivr", "queue");
                    assertThat(clients.outbounds().values()).allMatch(OutboundServer::isRunning);

                    fs.dialOutbound(clients.outbound("ivr").localAddress().getPort(), Map.of("Unique-ID", "call-1"));
                    fs.dialOutbound(clients.outbound("queue").localAddress().getPort(), Map.of("Unique-ID", "call-2"));
                    RecordingHandler ivr = context.getBean("ivrHandler", RecordingHandler.class);
                    RecordingHandler queue = context.getBean("queueHandler", RecordingHandler.class);
                    await().atMost(WAIT).until(() -> ivr.calls.size() == 1 && queue.calls.size() == 1);
                    assertThat(ivr.calls).containsExactly("call-1");
                    assertThat(queue.calls).containsExactly("call-2");

                    CompositeHealthContributor health = context.getBean("eslOutboundsHealthContributor",
                            CompositeHealthContributor.class);
                    assertThat(((HealthIndicator) health.getContributor("queue")).health().getStatus())
                            .isEqualTo(Status.UP);
                });
    }

    @Test
    void namedOutboundServerNeedsAHandlerNameWhenSeveralExist() {
        runner.withUserConfiguration(TwoHandlersConfiguration.class)
                .withPropertyValues("esl.outbounds.ivr.port=0", "esl.outbounds.ivr.host=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("set esl.outbounds.ivr.handler");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean
        Listener listener() {
            return new Listener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MetricsConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfiguration {
        @Bean
        OutboundSessionHandler handler() {
            return session -> session.channel().answer().join();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NamedListenerConfiguration {
        @Bean
        NamedListener namedListener() {
            return new NamedListener();
        }

        @Bean
        Consumer consumer(@Qualifier("fs2") InboundClient fs2) {
            return new Consumer(fs2);
        }

        @Bean
        NameRecordingCustomizer nameRecordingCustomizer() {
            return new NameRecordingCustomizer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultConsumerConfiguration {
        @Bean
        DefaultConsumer defaultConsumer(InboundClient client, @Qualifier("fs2") InboundClient fs2) {
            return new DefaultConsumer(client, fs2);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnknownClientListenerConfiguration {
        @Bean
        UnknownClientListener unknownClientListener() {
            return new UnknownClientListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoHandlersConfiguration {
        @Bean
        RecordingHandler ivrHandler() {
            return new RecordingHandler();
        }

        @Bean
        RecordingHandler queueHandler() {
            return new RecordingHandler();
        }
    }

    record Consumer(InboundClient fs2) {
    }

    record DefaultConsumer(InboundClient client, InboundClient fs2) {
    }

    static class NameRecordingCustomizer implements InboundClientCustomizer {
        final List<String> names = new CopyOnWriteArrayList<>();

        @Override
        public void customize(InboundClient.Builder builder) {
        }

        @Override
        public void customize(String name, InboundClient.Builder builder) {
            names.add(name);
        }
    }

    static class RecordingHandler implements OutboundSessionHandler {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onConnect(OutboundSession session) {
            calls.add(session.channelData().uniqueId());
        }
    }

    static class UnknownClientListener {
        @EslEventListener(client = "nope")
        void onAnything() {
        }
    }

    static class NamedListener {
        final List<String> fs1 = new CopyOnWriteArrayList<>();
        final List<String> all = new CopyOnWriteArrayList<>();

        @EslEventListener(value = "CHANNEL_ANSWER", client = "fs1")
        void onFs1Answer(EslEvent event) {
            fs1.add(event.uniqueId());
        }

        @EslEventListener("CHANNEL_ANSWER")
        void onAnyAnswer(EslEvent event) {
            all.add(event.uniqueId());
        }
    }

    static class Listener {
        final List<EslEvent> answered = new CopyOnWriteArrayList<>();
        final List<String> all = new CopyOnWriteArrayList<>();

        @EslEventListener("CHANNEL_ANSWER")
        void onAnswer(EslEvent event) {
            answered.add(event);
        }

        @EslEventListener
        void onAnything(EslEvent event) {
            all.add(event.name());
        }
    }
}
