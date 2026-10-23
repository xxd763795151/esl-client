package io.github.xxd763795151.esl.metrics;

import io.github.xxd763795151.esl.connection.ConnectionState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Records ESL metrics into a Micrometer {@link MeterRegistry}. Requires {@code micrometer-core} on the class
 * path. All meters are tagged with {@code client=<name>} and removed again on {@link #close()}.
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><td>{@code esl.commands}</td><td>timer, tags {@code command}, {@code outcome}</td></tr>
 *   <tr><td>{@code esl.events.received}</td><td>counter, tag {@code event}</td></tr>
 *   <tr><td>{@code esl.events.dropped}</td><td>counter, tag {@code event}</td></tr>
 *   <tr><td>{@code esl.listener.errors}</td><td>counter</td></tr>
 *   <tr><td>{@code esl.reconnect.attempts}</td><td>counter</td></tr>
 *   <tr><td>{@code esl.state.transitions}</td><td>counter, tag {@code state}</td></tr>
 *   <tr><td>{@code esl.sessions.opened} / {@code esl.sessions.closed}</td><td>counters (outbound)</td></tr>
 *   <tr><td>{@code esl.connected}, {@code esl.pending.*}, {@code esl.dispatch.backlog},
 *       {@code esl.sessions.active}</td><td>gauges</td></tr>
 * </table>
 */
public final class MicrometerEslMetrics implements EslMetrics {

    private final MeterRegistry registry;
    private final Tags tags;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final List<Meter> meters = new CopyOnWriteArrayList<>();

    public MicrometerEslMetrics(MeterRegistry registry, String clientName) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tags = Tags.of("client", Objects.requireNonNull(clientName, "clientName"));
    }

    @Override
    public void commandCompleted(String command, long durationNanos, CommandOutcome outcome) {
        String outcomeTag = outcome.name().toLowerCase(Locale.ROOT);
        timers.computeIfAbsent(command + '|' + outcomeTag, key -> track(Timer.builder("esl.commands")
                        .description("ESL command round trip")
                        .tags(tags)
                        .tag("command", command)
                        .tag("outcome", outcomeTag)
                        .register(registry)))
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void eventReceived(String eventName) {
        counter("esl.events.received", "event", eventName).increment();
    }

    @Override
    public void eventDropped(String eventName) {
        counter("esl.events.dropped", "event", eventName).increment();
    }

    @Override
    public void listenerFailed() {
        counter("esl.listener.errors", null, null).increment();
    }

    @Override
    public void stateChanged(ConnectionState from, ConnectionState to) {
        counter("esl.state.transitions", "state", to.name().toLowerCase(Locale.ROOT)).increment();
    }

    @Override
    public void reconnectAttempt() {
        counter("esl.reconnect.attempts", null, null).increment();
    }

    @Override
    public void sessionOpened() {
        counter("esl.sessions.opened", null, null).increment();
    }

    @Override
    public void sessionClosed() {
        counter("esl.sessions.closed", null, null).increment();
    }

    @Override
    public void gauge(String name, IntSupplier source) {
        track(Gauge.builder("esl." + name, source, IntSupplier::getAsInt)
                .tags(tags)
                .strongReference(true)
                .register(registry));
    }

    @Override
    public void close() {
        meters.forEach(registry::remove);
        meters.clear();
        timers.clear();
        counters.clear();
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        String key = tagKey == null ? name : name + '|' + tagValue;
        return counters.computeIfAbsent(key, ignored -> {
            Counter.Builder builder = Counter.builder(name).tags(tags);
            if (tagKey != null) {
                builder.tag(tagKey, tagValue == null || tagValue.isEmpty() ? "unknown" : tagValue);
            }
            return track(builder.register(registry));
        });
    }

    private <M extends Meter> M track(M meter) {
        meters.add(meter);
        return meter;
    }
}
