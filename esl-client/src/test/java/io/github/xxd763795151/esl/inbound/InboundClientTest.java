package io.github.xxd763795151.esl.inbound;

import io.github.xxd763795151.esl.app.PlayAndGetDigits;
import io.github.xxd763795151.esl.command.ApiResponse;
import io.github.xxd763795151.esl.command.BackgroundJobResult;
import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.ExecuteResult;
import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.event.LogLine;
import io.github.xxd763795151.esl.exception.AuthenticationException;
import io.github.xxd763795151.esl.exception.ChannelHangupException;
import io.github.xxd763795151.esl.exception.CommandFailedException;
import io.github.xxd763795151.esl.exception.CommandTimeoutException;
import io.github.xxd763795151.esl.exception.ConnectionClosedException;
import io.github.xxd763795151.esl.exception.RudeRejectionException;
import io.github.xxd763795151.esl.reconnect.DisconnectedCommandPolicy;
import io.github.xxd763795151.esl.reconnect.ReconnectPolicy;
import io.github.xxd763795151.esl.test.FakeFreeSwitch;
import io.github.xxd763795151.esl.test.FakePeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class InboundClientTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private FakeFreeSwitch fs;
    private InboundClient client;

    @BeforeEach
    void startFake() {
        fs = FakeFreeSwitch.start();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        fs.close();
    }

    private InboundClient.Builder builder() {
        return InboundClient.builder()
                .name("test")
                .address("127.0.0.1", fs.port())
                .connectTimeout(Duration.ofSeconds(2))
                .commandTimeout(Duration.ofSeconds(2))
                .closeTimeout(Duration.ofSeconds(2))
                .reconnectPolicy(ReconnectPolicy.fixed(Duration.ofMillis(100)));
    }

    private InboundClient connect(InboundClient.Builder builder) throws Exception {
        client = builder.build();
        client.connect().get(5, TimeUnit.SECONDS);
        return client;
    }

    @Test
    void authenticatesAndAppliesInitialState() throws Exception {
        connect(builder().events("CHANNEL_ANSWER", "CHANNEL_HANGUP").filter("Event-Name", "CHANNEL_ANSWER"));
        FakePeer peer = fs.awaitPeer(WAIT);

        assertThat(client.state()).isEqualTo(ConnectionState.READY);
        assertThat(peer.receivedLines()).containsExactly(
                "auth ClueCon",
                "event plain CHANNEL_ANSWER CHANNEL_HANGUP",
                "filter Event-Name CHANNEL_ANSWER");
    }

    @Test
    void wrongPasswordFailsWithoutRetrying() {
        fs.password("other");
        client = builder().build();

        assertThatThrownBy(() -> client.connect().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AuthenticationException.class);
        await().atMost(WAIT).until(() -> client.state() == ConnectionState.CLOSED);
        assertThat(fs.peers()).hasSize(1);
    }

    @Test
    void rudeRejectionIsReported() {
        fs.rudeRejection("Access Denied, go away.\n");
        client = builder().build();

        assertThatThrownBy(() -> client.connect().get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RudeRejectionException.class)
                .hasMessageContaining("Access Denied");
    }

    @Test
    void connectFailsWhenNothingListens() {
        int port = fs.port();
        fs.close();
        client = InboundClient.builder().address("127.0.0.1", port).connectTimeout(Duration.ofSeconds(1)).build();

        assertThatThrownBy(() -> client.connect().get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ConnectionClosedException.class);
        await().atMost(WAIT).until(() -> client.state() == ConnectionState.CLOSED);
        fs = FakeFreeSwitch.start();
    }

    @Test
    void concurrentApiCallsGetTheirOwnReplies() throws Exception {
        connect(builder());
        List<CompletableFuture<ApiResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(client.api("echo", "reply-" + i));
        }

        for (int i = 0; i < 100; i++) {
            assertThat(futures.get(i).get(5, TimeUnit.SECONDS).body()).isEqualTo("reply-" + i);
        }
    }

    @Test
    void lateReplyOfTimedOutCommandDoesNotShiftLaterReplies() throws Exception {
        // FreeSWITCH processes a socket's commands one by one: "next" is answered only after the late "slow"
        CompletableFuture<Void> slowAnswered = new CompletableFuture<>();
        fs.handler((peer, command) -> {
            if (command.line().equals("api echo slow")) {
                peer.channel().eventLoop().schedule(() -> {
                    peer.apiResponse("slow");
                    slowAnswered.complete(null);
                }, 500, TimeUnit.MILLISECONDS);
                return true;
            }
            if (command.line().equals("api echo next")) {
                slowAnswered.thenRun(() -> peer.apiResponse("next"));
                return true;
            }
            return false;
        });
        connect(builder().commandTimeout(Duration.ofMillis(300)));

        CompletableFuture<ApiResponse> slow = client.api("echo", "slow");
        assertThatThrownBy(() -> slow.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CommandTimeoutException.class);

        ApiResponse next = client.api("echo", "next").get(5, TimeUnit.SECONDS);
        assertThat(next.body()).isEqualTo("next");
    }

    @Test
    void bgapiCompletesEvenWhenTheJobEventOvertakesTheReply() throws Exception {
        fs.handler((peer, command) -> {
            if (command.is("bgapi")) {
                String job = command.header("Job-UUID");
                peer.event(Map.of("Event-Name", "BACKGROUND_JOB", "Job-UUID", job), "+OK done\n");
                peer.reply("+OK Job-UUID: " + job);
                return true;
            }
            return false;
        });
        connect(builder());
        List<EslEvent> jobEvents = new CopyOnWriteArrayList<>();
        client.addEventListener(EventSelector.named("BACKGROUND_JOB"), jobEvents::add);

        BackgroundJobResult result = client.bgapi("originate", "user/1000 &park").get(5, TimeUnit.SECONDS);

        assertThat(result.body()).isEqualTo("+OK done\n");
        assertThat(result.isOk()).isTrue();
        await().atMost(WAIT).until(() -> jobEvents.size() == 1);
        FakePeer peer = fs.peers().get(0);
        assertThat(peer.receivedLines()).containsSubsequence("event plain BACKGROUND_JOB", "bgapi originate user/1000 &park");
    }

    @Test
    void failedCommandRepliesFailHighLevelCalls() throws Exception {
        fs.handler((peer, command) -> {
            if (command.is("filter")) {
                peer.reply("-ERR invalid syntax");
                return true;
            }
            return false;
        });
        connect(builder());

        assertThatThrownBy(() -> client.addFilter("Unique-ID", "x").get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CommandFailedException.class)
                .hasMessageContaining("-ERR invalid syntax");
    }

    @Test
    void executeCompletesWhenTheApplicationFinishes() throws Exception {
        connect(builder());
        FakePeer peer = fs.awaitPeer(WAIT);
        peer.executeVariable(PlayAndGetDigits.DEFAULT_VARIABLE, "1234");

        String digits = client.channel("uuid-1")
                .playAndGetDigits(PlayAndGetDigits.builder("ivr/enter.wav").digits(1, 4).build())
                .get(5, TimeUnit.SECONDS);

        assertThat(digits).isEqualTo("1234");
        assertThat(peer.receivedLines()).contains("event plain CHANNEL_EXECUTE_COMPLETE CHANNEL_HANGUP_COMPLETE");
        assertThat(peer.received()).filteredOn(command -> command.is("sendmsg")).singleElement()
                .satisfies(command -> {
                    assertThat(command.argument()).isEqualTo("uuid-1");
                    assertThat(command.header("execute-app-name")).isEqualTo("play_and_get_digits");
                    assertThat(command.header("Event-UUID")).isNotBlank();
                });
    }

    @Test
    void executeFailsWhenTheCallerHangsUp() throws Exception {
        connect(builder());
        FakePeer peer = fs.awaitPeer(WAIT).autoCompleteExecute(false);

        CompletableFuture<ExecuteResult> playback = client.channel("uuid-2").playback("music.wav");
        peer.awaitCommand(command -> command.is("sendmsg"), WAIT);
        peer.event("CHANNEL_HANGUP_COMPLETE", "uuid-2", Map.of("Hangup-Cause", "NORMAL_CLEARING"));

        assertThatThrownBy(() -> playback.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ChannelHangupException.class)
                .hasMessageContaining("NORMAL_CLEARING");
    }

    @Test
    void eventsAndLogsReachListenersDecoded() throws Exception {
        connect(builder().events("CHANNEL_ANSWER"));
        FakePeer peer = fs.awaitPeer(WAIT);
        List<EslEvent> events = new CopyOnWriteArrayList<>();
        List<LogLine> logs = new CopyOnWriteArrayList<>();
        client.addEventListener(EventSelector.named("CHANNEL_ANSWER"), events::add);
        client.addLogListener(logs::add);

        peer.event("CHANNEL_ANSWER", "u1", Map.of("Caller-Caller-ID-Name", "张三 +86"));
        peer.send("Content-Type: log/data\nContent-Length: 5\nLog-Level: 6\nText-Channel: 3\n\nhello");

        await().atMost(WAIT).until(() -> events.size() == 1 && logs.size() == 1);
        assertThat(events.get(0).header("Caller-Caller-ID-Name")).isEqualTo("张三 +86");
        assertThat(logs.get(0).text()).isEqualTo("hello");
        assertThat(logs.get(0).level().name()).isEqualTo("INFO");
    }

    @Test
    void blockingOnAnIoThreadIsRejectedInsteadOfDeadlocking() throws Exception {
        try (EslResources resources = EslResources.create()) {
            connect(builder().resources(resources));

            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            resources.ioGroup().execute(() -> {
                try {
                    client.api("echo", "x").join();
                    failure.complete(null);
                } catch (Throwable t) {
                    failure.complete(t);
                }
            });

            assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
            client.close();
            client = null;
        }
    }

    @Test
    void reconnectsAndReplaysRecordedState() throws Exception {
        List<ConnectionState> states = Collections.synchronizedList(new ArrayList<>());
        connect(builder().events("CHANNEL_ANSWER"));
        client.addStateListener((from, to, cause) -> states.add(to));
        FakePeer first = fs.awaitPeer(WAIT);
        client.subscribe("CUSTOM", "sofia::register").get(5, TimeUnit.SECONDS);
        client.addFilter("Unique-ID", "u1").get(5, TimeUnit.SECONDS);
        first.handler((peer, command) -> command.line().equals("api echo never"));
        CompletableFuture<ApiResponse> pending = client.api("echo", "never");
        first.awaitCommand(command -> command.line().equals("api echo never"), WAIT);

        first.close();

        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ConnectionClosedException.class);
        FakePeer second = fs.awaitPeer(WAIT);
        await().atMost(WAIT).until(() -> client.state() == ConnectionState.READY && second.received().size() >= 3);
        assertThat(second.receivedLines()).containsExactly(
                "auth ClueCon",
                "event plain CHANNEL_ANSWER CUSTOM sofia::register",
                "filter Unique-ID u1");
        assertThat(client.api("echo", "back").get(5, TimeUnit.SECONDS).body()).isEqualTo("back");
        await().atMost(WAIT).until(() -> states.containsAll(List.of(ConnectionState.RECONNECTING, ConnectionState.READY)));
    }

    @Test
    void commandsFailFastWhileReconnectingByDefault() throws Exception {
        connect(builder().reconnectPolicy(ReconnectPolicy.fixed(Duration.ofSeconds(2))));
        fs.awaitPeer(WAIT).close();
        await().atMost(WAIT).until(() -> client.state() == ConnectionState.RECONNECTING);

        assertThatThrownBy(() -> client.api("status").get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ConnectionClosedException.class);
    }

    @Test
    void commandsCanWaitForTheReconnect() throws Exception {
        connect(builder()
                .reconnectPolicy(ReconnectPolicy.fixed(Duration.ofMillis(500)))
                .disconnectedCommandPolicy(DisconnectedCommandPolicy.QUEUE_UNTIL_READY));
        fs.awaitPeer(WAIT).close();
        await().atMost(WAIT).until(() -> client.state() == ConnectionState.RECONNECTING);

        CompletableFuture<ApiResponse> queued = client.api("echo", "queued");

        assertThat(queued.get(5, TimeUnit.SECONDS).body()).isEqualTo("queued");
    }

    @Test
    void reconnectGivesUpWhenThePolicySaysSo() throws Exception {
        connect(builder().reconnectPolicy(ReconnectPolicy.fixed(Duration.ofMillis(50), 2)));
        fs.stopListening();
        fs.dropAllConnections();

        await().atMost(WAIT).until(() -> client.state() == ConnectionState.CLOSED);
    }

    @Test
    void deadPeerIsDetectedByTheLivenessProbe() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        fs.handler((peer, command) -> {
            if (command.is("auth")) {
                connections.incrementAndGet();
            }
            // the first connection swallows the probe like a hung peer
            return connections.get() == 1 && command.line().equals("api version");
        });
        connect(builder().idleTimeout(Duration.ofMillis(300)).commandTimeout(Duration.ofMillis(300)));
        FakePeer first = fs.awaitPeer(WAIT);

        first.closeFuture().get(5, TimeUnit.SECONDS);
        await().atMost(WAIT).until(() -> connections.get() == 2 && client.state() == ConnectionState.READY);
    }

    @Test
    void peerThatStopsAnsweringIsResetAfterACommandTimesOut() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        fs.handler((peer, command) -> {
            if (command.is("auth")) {
                connections.incrementAndGet();
            }
            // the first connection hangs on this command: the socket stays open but nothing comes back
            return connections.get() == 1 && command.line().equals("api status");
        });
        connect(builder().idleTimeout(Duration.ofMillis(300)).commandTimeout(Duration.ofMillis(300)));
        FakePeer first = fs.awaitPeer(WAIT);

        assertThatThrownBy(() -> client.api("status").get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CommandTimeoutException.class);

        first.closeFuture().get(5, TimeUnit.SECONDS);
        await().atMost(WAIT).until(() -> connections.get() == 2 && client.state() == ConnectionState.READY);
        assertThat(client.api("echo", "back").get(5, TimeUnit.SECONDS).body()).isEqualTo("back");
    }

    @Test
    void stateIsRecordedInReplyOrderWhateverOrderWorkersRunIn() throws Exception {
        HeldExecutor worker = new HeldExecutor();
        try (EslResources resources = EslResources.builder().workerExecutor(worker).build()) {
            connect(builder().resources(resources));
            FakePeer first = fs.awaitPeer(WAIT);
            worker.holding = true;
            CompletableFuture<CommandReply> subscribed = client.subscribe("CHANNEL_ANSWER");
            CompletableFuture<CommandReply> unsubscribed = client.unsubscribe("CHANNEL_ANSWER");
            await().atMost(WAIT).until(() -> worker.held.size() == 2);

            // the unsubscribe completion runs before the subscribe completion
            worker.holding = false;
            worker.held.get(1).run();
            worker.held.get(0).run();
            subscribed.get(5, TimeUnit.SECONDS);
            unsubscribed.get(5, TimeUnit.SECONDS);
            first.close();

            FakePeer second = fs.awaitPeer(WAIT);
            await().atMost(WAIT).until(() -> client.state() == ConnectionState.READY);
            assertThat(second.receivedLines()).containsExactly("auth ClueCon");
            client.close();
            client = null;
        }
    }

    @Test
    void gracefulCloseSendsExitAndStopsAllThreads() throws Exception {
        connect(builder().name("closing"));
        FakePeer peer = fs.awaitPeer(WAIT);

        client.close();

        assertThat(client.state()).isEqualTo(ConnectionState.CLOSED);
        assertThat(peer.receivedLines()).endsWith("exit");
        peer.closeFuture().get(5, TimeUnit.SECONDS);
        await().atMost(WAIT).until(() -> liveThreads("esl-closing").isEmpty());
        client = null;
    }

    @Test
    void closeResetsTheConnectionWhenThePeerIgnoresExit() throws Exception {
        connect(builder());
        FakePeer peer = fs.awaitPeer(WAIT).ignoreExit(true);
        long start = System.nanoTime();

        client.closeGracefully(Duration.ofSeconds(1)).get(5, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        peer.closeFuture().get(5, TimeUnit.SECONDS);
        assertThat(peer.isOpen()).isFalse();
        client = null;
    }

    @Test
    void closeIsIdempotentAndRejectsLaterCommands() throws Exception {
        connect(builder());
        CompletableFuture<Void> first = client.closeGracefully(Duration.ofSeconds(2));
        CompletableFuture<Void> second = client.closeGracefully(Duration.ofSeconds(2));
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> client.api("status").get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ConnectionClosedException.class);
        client = null;
    }

    @Test
    void stateListenersSeeTheWholeLifecycle() throws Exception {
        AtomicReference<List<ConnectionState>> seen = new AtomicReference<>(new CopyOnWriteArrayList<>());
        client = builder().build();
        client.addStateListener((from, to, cause) -> seen.get().add(to));
        client.connect().get(5, TimeUnit.SECONDS);
        client.close();

        await().atMost(WAIT).untilAsserted(() -> assertThat(seen.get()).containsExactly(
                ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.READY,
                ConnectionState.CLOSING, ConnectionState.CLOSED));
        client = null;
    }

    private static List<String> liveThreads(String prefix) {
        List<String> names = new ArrayList<>();
        Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(prefix))
                .forEach(names::add);
        return names;
    }

    /** Runs tasks inline, or holds them back so a test can run them in any order. */
    private static final class HeldExecutor implements Executor {
        final List<Runnable> held = new CopyOnWriteArrayList<>();
        volatile boolean holding;

        @Override
        public void execute(Runnable task) {
            if (holding) {
                held.add(task);
            } else {
                task.run();
            }
        }
    }
}
