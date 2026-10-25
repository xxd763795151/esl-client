package io.github.xxd763795151.esl.outbound;

import io.github.xxd763795151.esl.app.CallChannel;
import io.github.xxd763795151.esl.app.PlayAndGetDigits;
import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.test.FakeFreeSwitch;
import io.github.xxd763795151.esl.test.FakePeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboundServerTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final FakeFreeSwitch fs = FakeFreeSwitch.dialer();
    private final CountDownLatch releaseListener = new CountDownLatch(1);
    private OutboundServer server;

    @AfterEach
    void stop() {
        releaseListener.countDown();
        if (server != null) {
            server.close();
        }
        fs.close();
    }

    private OutboundServer start(OutboundServer.Builder builder) throws Exception {
        server = builder.port(0).host("127.0.0.1").shutdownTimeout(Duration.ofSeconds(2)).build();
        server.start().get(5, TimeUnit.SECONDS);
        return server;
    }

    private FakePeer dial(String uuid) {
        return fs.dialOutbound(server.localAddress().getPort(), Map.of(
                "Unique-ID", uuid,
                "Caller-Caller-ID-Number", "+8613800000000",
                "Caller-Caller-ID-Name", "张三",
                "variable_lang", "zh"));
    }

    @Test
    void runsASequentialCallScriptWithBlockingCalls() throws Exception {
        CompletableFuture<String> digits = new CompletableFuture<>();
        CompletableFuture<EslEvent> channelData = new CompletableFuture<>();
        start(OutboundServer.builder().handler(session -> {
            channelData.complete(session.channelData());
            CallChannel call = session.channel();
            call.answer().join();
            digits.complete(call.playAndGetDigits(PlayAndGetDigits.builder("ivr/menu.wav").build()).join());
            call.hangup().join();
        }));

        FakePeer peer = dial("call-1").executeVariable(PlayAndGetDigits.DEFAULT_VARIABLE, "7");

        assertThat(digits.get(5, TimeUnit.SECONDS)).isEqualTo("7");
        EslEvent data = channelData.get(5, TimeUnit.SECONDS);
        assertThat(data.uniqueId()).isEqualTo("call-1");
        assertThat(data.header("Caller-Caller-ID-Number")).isEqualTo("+8613800000000");
        assertThat(data.header("Caller-Caller-ID-Name")).isEqualTo("张三");
        await().atMost(WAIT).untilAsserted(() -> assertThat(peer.receivedLines()).containsExactly(
                "connect", "myevents plain", "sendmsg call-1", "sendmsg call-1", "sendmsg call-1"));
        assertThat(peer.received().get(4).header("call-command")).isEqualTo("hangup");
    }

    @Test
    void deliversEventsInOrderThenDisconnect() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        start(OutboundServer.builder().handler(new OutboundSessionHandler() {
            @Override
            public void onConnect(OutboundSession session) {
                seen.add("connect");
            }

            @Override
            public void onEvent(OutboundSession session, EslEvent event) {
                seen.add(event.name());
            }

            @Override
            public void onDisconnect(OutboundSession session, Throwable cause) {
                seen.add("disconnect");
            }
        }));
        FakePeer peer = dial("call-2");
        peer.awaitCommand(command -> command.is("myevents"), WAIT);
        await().atMost(WAIT).until(() -> seen.contains("connect"));

        peer.event("CHANNEL_ANSWER", "call-2", Map.of());
        peer.event("DTMF", "call-2", Map.of("DTMF-Digit", "5"));
        peer.event("CHANNEL_HANGUP_COMPLETE", "call-2", Map.of());
        peer.disconnectNotice();
        peer.close();

        await().atMost(WAIT).until(() -> seen.contains("disconnect"));
        assertThat(seen).containsExactly("connect", "CHANNEL_ANSWER", "DTMF", "CHANNEL_HANGUP_COMPLETE", "disconnect");
        await().atMost(WAIT).until(() -> server.sessions().isEmpty());
    }

    @Test
    void sessionCommandsAndConfiguredHandshake() throws Exception {
        CompletableFuture<String> language = new CompletableFuture<>();
        start(OutboundServer.builder().linger(Duration.ofSeconds(5)).resumeOnDisconnect(true)
                .handler(session -> language.complete(session.getVar("lang").join())));

        FakePeer peer = dial("call-3");

        assertThat(language.get(5, TimeUnit.SECONDS)).isEqualTo("zh");
        assertThat(peer.receivedLines()).containsExactly("connect", "myevents plain", "linger 5", "resume",
                "getvar lang");
    }

    @Test
    void failingHandlerClosesTheSession() throws Exception {
        start(OutboundServer.builder().handler(session -> {
            throw new IllegalStateException("script bug");
        }));

        FakePeer peer = dial("call-4");

        peer.closeFuture().get(5, TimeUnit.SECONDS);
        assertThat(peer.receivedLines()).contains("exit");
    }

    @Test
    void gracefulShutdownLetsCallsFinish() throws Exception {
        start(OutboundServer.builder().handler(new OutboundSessionHandler() {
            @Override
            public void onConnect(OutboundSession session) {
            }

            @Override
            public void onServerShutdown(OutboundSession session) {
                session.channel().hangup("SYSTEM_SHUTDOWN");
            }
        }));
        FakePeer peer = dial("call-5").handler((p, command) -> {
            if ("hangup".equals(command.header("call-command"))) {
                p.reply("+OK");
                p.close();
                return true;
            }
            return false;
        });
        peer.awaitCommand(command -> command.is("myevents"), WAIT);
        await().atMost(WAIT).until(() -> server.sessions().size() == 1);
        long start = System.nanoTime();

        server.shutdownGracefully(Duration.ofSeconds(10)).get(15, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(peer.received()).anySatisfy(command ->
                assertThat(command.header("hangup-cause")).isEqualTo("SYSTEM_SHUTDOWN"));
        assertThat(server.isRunning()).isFalse();
        server = null;
    }

    @Test
    void shutdownForcesSessionsThatDoNotEnd() throws Exception {
        start(OutboundServer.builder().handler(session -> { }));
        FakePeer peer = dial("call-6").ignoreExit(true);
        peer.awaitCommand(command -> command.is("myevents"), WAIT);
        long start = System.nanoTime();

        server.shutdownGracefully(Duration.ofSeconds(1)).get(10, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        peer.closeFuture().get(5, TimeUnit.SECONDS);
        server = null;
    }

    @Test
    void rejectsConnectionsBeyondTheSessionLimit() throws Exception {
        start(OutboundServer.builder().maxSessions(1).handler(session -> { }));
        FakePeer first = dial("call-7");
        first.awaitCommand(command -> command.is("connect"), WAIT);

        FakePeer second = dial("call-8");

        second.closeFuture().get(5, TimeUnit.SECONDS);
        assertThat(second.received()).isEmpty();
        assertThat(first.isOpen()).isTrue();
    }

    @Test
    void closeIsBoundedByItsTimeoutWhileAListenerBlocks() throws Exception {
        OutboundSession session = blockedListenerSession(OutboundServer.builder(), "call-9").session();
        long start = System.nanoTime();

        session.closeGracefully(Duration.ofMillis(300)).get(5, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(session.state()).isEqualTo(ConnectionState.CLOSED);
        assertThat(server.sessions()).isEmpty();
    }

    @Test
    void sessionClosedByThePeerIsReleasedWhileAListenerBlocks() throws Exception {
        BlockedCall call = blockedListenerSession(
                OutboundServer.builder().sessionCloseTimeout(Duration.ofMillis(300)), "call-10");

        call.peer().close();

        call.session().closeFuture().get(5, TimeUnit.SECONDS);
        assertThat(server.sessions()).isEmpty();
    }

    private record BlockedCall(OutboundSession session, FakePeer peer) {
    }

    /** A call whose event listener is stuck until {@link #releaseListener} opens. */
    private BlockedCall blockedListenerSession(OutboundServer.Builder builder, String uuid) throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CompletableFuture<OutboundSession> connected = new CompletableFuture<>();
        start(builder.handler(new OutboundSessionHandler() {
            @Override
            public void onConnect(OutboundSession session) {
                connected.complete(session);
            }

            @Override
            public void onEvent(OutboundSession session, EslEvent event) throws InterruptedException {
                blocked.countDown();
                releaseListener.await(10, TimeUnit.SECONDS);
            }
        }));
        FakePeer peer = dial(uuid);
        OutboundSession session = connected.get(5, TimeUnit.SECONDS);
        peer.event("CHANNEL_ANSWER", uuid, Map.of());
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        return new BlockedCall(session, peer);
    }
}
