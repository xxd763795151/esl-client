package io.github.xxd763795151.esl.dispatch;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.github.xxd763795151.esl.protocol.EslHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventDispatcherTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void keepsPerChannelOrderWhileChannelsRunInParallel() throws Exception {
        EventDispatcher dispatcher = new EventDispatcher(DispatchOptions.DEFAULT, executor, EslMetrics.NOOP);
        Map<String, List<Long>> seen = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(4 * 200);
        dispatcher.addEventListener(EventSelector.all(), event -> {
            seen.computeIfAbsent(event.uniqueId(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(event.sequence());
            done.countDown();
        });

        for (long i = 0; i < 200; i++) {
            for (String uuid : List.of("a", "b", "c", "d")) {
                dispatcher.dispatch(event("CHANNEL_STATE", uuid, i));
            }
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        seen.values().forEach(sequence -> assertThat(sequence).isSorted().hasSize(200));
    }

    @Test
    void isolatesFailingListenersAndHonoursSelectors() {
        AtomicInteger failures = new AtomicInteger();
        EventDispatcher dispatcher = new EventDispatcher(DispatchOptions.DEFAULT, executor, new EslMetrics() {
            @Override
            public void listenerFailed() {
                failures.incrementAndGet();
            }
        });
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        dispatcher.addEventListener(EventSelector.all(), event -> {
            throw new IllegalStateException("boom");
        });
        Registration registration = dispatcher.addEventListener(EventSelector.named("CHANNEL_ANSWER"),
                event -> received.add(event.name()));

        dispatcher.dispatch(event("CHANNEL_ANSWER", "x", 1));
        dispatcher.dispatch(event("CHANNEL_HANGUP", "x", 2));
        await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.backlog() == 0);
        registration.remove();
        dispatcher.dispatch(event("CHANNEL_ANSWER", "x", 3));
        await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.backlog() == 0);

        assertThat(received).containsExactly("CHANNEL_ANSWER");
        assertThat(failures).hasValue(3);
    }

    @Test
    void pausesReadsAboveHighWaterMarkAndResumesBelowLow() throws Exception {
        DispatchOptions options = new DispatchOptions(DispatchMode.SEQUENTIAL, 1, 10, 2,
                BackpressureStrategy.PAUSE_READ);
        EventDispatcher dispatcher = new EventDispatcher(options, executor, EslMetrics.NOOP);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> gateStates = Collections.synchronizedList(new ArrayList<>());
        dispatcher.setReadGate(() -> gateStates.add(dispatcher.isReadPaused()));
        dispatcher.addEventListener(EventSelector.all(), event -> release.await());

        for (int i = 0; i < 20; i++) {
            dispatcher.dispatch(event("CHANNEL_STATE", "x", i));
        }
        assertThat(dispatcher.isReadPaused()).isTrue();
        assertThat(dispatcher.backlog()).isEqualTo(20);

        release.countDown();
        dispatcher.awaitIdle().get(5, TimeUnit.SECONDS);

        assertThat(dispatcher.isReadPaused()).isFalse();
        assertThat(gateStates).containsExactly(true, false);
    }

    @Test
    void dropsAboveHighWaterMarkWhenConfigured() throws Exception {
        AtomicInteger dropped = new AtomicInteger();
        DispatchOptions options = new DispatchOptions(DispatchMode.SEQUENTIAL, 1, 5, 1, BackpressureStrategy.DROP);
        EventDispatcher dispatcher = new EventDispatcher(options, executor, new EslMetrics() {
            @Override
            public void eventDropped(String eventName) {
                dropped.incrementAndGet();
            }
        });
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        dispatcher.addEventListener(EventSelector.all(), event -> {
            release.await();
            delivered.incrementAndGet();
        });

        for (int i = 0; i < 20; i++) {
            dispatcher.dispatch(event("CHANNEL_STATE", "x", i));
        }
        release.countDown();
        dispatcher.awaitIdle().get(5, TimeUnit.SECONDS);

        assertThat(delivered.get() + dropped.get()).isEqualTo(20);
        assertThat(dropped.get()).isGreaterThan(0);
        assertThat(dispatcher.isReadPaused()).isFalse();
    }

    static EslEvent event(String name, String uuid, long sequence) {
        return new EslEvent(EslHeaders.builder()
                .add("Event-Name", name)
                .add("Unique-ID", uuid)
                .add("Event-Sequence", Long.toString(sequence))
                .build(), "", EventFormat.PLAIN);
    }
}
