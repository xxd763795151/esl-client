package io.github.xxd763795151.esl.examples;

import io.github.xxd763795151.esl.command.ApiResponse;
import io.github.xxd763795151.esl.command.BackgroundJobResult;
import io.github.xxd763795151.esl.event.EventNames;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.reconnect.ReconnectPolicy;

import java.time.Duration;

/**
 * Connects to FreeSWITCH, prints call events, runs an api and a bgapi, then closes gracefully.
 * <p>
 * Usage: {@code InboundExample [host] [port] [password]}
 */
public final class InboundExample {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8021;
        String password = args.length > 2 ? args[2] : "ClueCon";

        try (InboundClient client = InboundClient.builder()
                .name("example")
                .address(host, port)
                .password(password)
                .events(EventNames.CHANNEL_CREATE, EventNames.CHANNEL_ANSWER, EventNames.CHANNEL_HANGUP_COMPLETE)
                .reconnectPolicy(ReconnectPolicy.exponential().maxDelay(Duration.ofSeconds(10)).build())
                .build()) {

            client.addStateListener((from, to, cause) -> System.out.println("state " + from + " -> " + to));
            client.addEventListener(EventSelector.named(EventNames.CHANNEL_ANSWER, EventNames.CHANNEL_HANGUP_COMPLETE),
                    event -> System.out.printf("%s %s %s%n", event.name(), event.uniqueId(),
                            event.header("Caller-Caller-ID-Number")));

            client.connect().join();

            ApiResponse status = client.api("status").join();
            System.out.println(status.body());

            BackgroundJobResult channels = client.bgapi("show", "channels count").join();
            System.out.println("channels: " + channels.body().trim());

            System.out.println("Listening for events for 60 seconds...");
            Thread.sleep(60_000);
        }
    }

    private InboundExample() {
    }
}
