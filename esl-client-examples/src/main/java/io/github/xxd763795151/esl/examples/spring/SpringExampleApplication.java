package io.github.xxd763795151.esl.examples.spring;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.outbound.OutboundSessionHandler;
import io.github.xxd763795151.esl.spring.EslEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Spring Boot usage: the inbound client and the outbound server are configured in {@code application.yml}
 * and managed by the application lifecycle (graceful shutdown included).
 */
@SpringBootApplication
public class SpringExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringExampleApplication.class, args);
    }

    /** Presence of this bean starts the outbound server. */
    @Bean
    OutboundSessionHandler ivr() {
        return session -> {
            session.channel().answer().join();
            session.channel().playback("ivr/ivr-welcome.wav").join();
            session.channel().hangup().join();
        };
    }

    @Component
    static class CallMonitor {

        private static final Logger log = LoggerFactory.getLogger(CallMonitor.class);

        private final InboundClient client;

        CallMonitor(InboundClient client) {
            this.client = client;
        }

        @EslEventListener("CHANNEL_ANSWER")
        void onAnswer(EslEvent event) {
            log.info("Answered {} ({})", event.uniqueId(), event.header("Caller-Caller-ID-Number"));
        }

        @EslEventListener("CHANNEL_HANGUP_COMPLETE")
        void onHangup(EslEvent event) {
            log.info("Hangup {} cause {}", event.uniqueId(), event.header("Hangup-Cause"));
            client.api("show", "calls count")
                    .thenAccept(response -> log.info("Active calls: {}", response.trimmed()));
        }
    }
}
