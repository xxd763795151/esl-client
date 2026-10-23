package io.github.xxd763795151.esl.examples;

import io.github.xxd763795151.esl.app.CallChannel;
import io.github.xxd763795151.esl.app.PlayAndGetDigits;
import io.github.xxd763795151.esl.event.EventNames;
import io.github.xxd763795151.esl.exception.ChannelHangupException;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import io.github.xxd763795151.esl.outbound.OutboundSession;
import io.github.xxd763795151.esl.outbound.OutboundSessionHandler;
import io.github.xxd763795151.esl.event.EslEvent;

import java.time.Duration;
import java.util.concurrent.CompletionException;

/**
 * A small IVR on the outbound socket. Route calls to it from the dialplan:
 * <pre>{@code
 * <extension name="esl-ivr">
 *   <condition field="destination_number" expression="^9999$">
 *     <action application="socket" data="127.0.0.1:8084 async full"/>
 *   </condition>
 * </extension>
 * }</pre>
 */
public final class OutboundIvrExample {

    public static void main(String[] args) throws Exception {
        OutboundServer server = OutboundServer.builder()
                .port(args.length > 0 ? Integer.parseInt(args[0]) : 8084)
                .linger(Duration.ofSeconds(5))
                .handler(new Ivr())
                .build();
        server.start().join();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdownGracefully(Duration.ofSeconds(30)).join()));
        System.out.println("Outbound IVR listening on " + server.localAddress());
        Thread.currentThread().join();
    }

    /** A sequential call script; blocking calls are fine because it runs on its own worker thread. */
    static final class Ivr implements OutboundSessionHandler {

        @Override
        public void onConnect(OutboundSession session) {
            CallChannel call = session.channel();
            System.out.println("Call from " + session.channelData().header("Caller-Caller-ID-Number"));
            try {
                call.answer().join();
                String choice = call.playAndGetDigits(PlayAndGetDigits.builder("ivr/ivr-welcome_to_freeswitch.wav")
                        .digits(1, 1)
                        .tries(3)
                        .timeout(Duration.ofSeconds(5))
                        .regex("[1-2]")
                        .invalidPrompt("ivr/ivr-that_was_an_invalid_entry.wav")
                        .build()).join();
                if ("1".equals(choice)) {
                    call.playback("ivr/ivr-thank_you.wav").join();
                } else if ("2".equals(choice)) {
                    call.bridge("user/1000").join();
                }
                call.hangup().join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof ChannelHangupException) {
                    System.out.println("Caller hung up");
                } else {
                    throw e;
                }
            }
        }

        @Override
        public void onEvent(OutboundSession session, EslEvent event) {
            if (event.isNamed(EventNames.DTMF)) {
                System.out.println("DTMF " + event.header("DTMF-Digit"));
            }
        }

        @Override
        public void onDisconnect(OutboundSession session, Throwable cause) {
            System.out.println("Session " + session.uuid() + " ended");
        }

        @Override
        public void onServerShutdown(OutboundSession session) {
            session.channel().hangup("SYSTEM_SHUTDOWN");
        }
    }

    private OutboundIvrExample() {
    }
}
