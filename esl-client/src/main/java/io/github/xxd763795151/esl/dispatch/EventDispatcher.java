package io.github.xxd763795151.esl.dispatch;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventListener;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.event.LogLine;
import io.github.xxd763795151.esl.event.LogListener;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moves events from the I/O thread to listeners running on worker threads, honouring the configured
 * {@link DispatchMode} and applying backpressure when listeners fall behind.
 * <p>
 * {@link #dispatch(EslEvent)} never blocks, so it is safe to call from a Netty event loop.
 */
public final class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final DispatchOptions options;
    private final Executor executor;
    private final EslMetrics metrics;
    private final SerialExecutor[] lanes;
    private final SerialExecutor logLane;
    private final List<ListenerEntry> eventListeners = new CopyOnWriteArrayList<>();
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger backlog = new AtomicInteger();
    private final AtomicBoolean readPaused = new AtomicBoolean();
    private final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>();
    private volatile Runnable readGate = () -> { };
    private volatile boolean shutdown;

    public EventDispatcher(DispatchOptions options, Executor executor, EslMetrics metrics) {
        this.options = Objects.requireNonNull(options, "options");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        int laneCount = switch (options.mode()) {
            case SEQUENTIAL -> 1;
            case PER_CHANNEL_ORDERED -> options.lanes();
            case UNORDERED -> 0;
        };
        this.lanes = new SerialExecutor[laneCount];
        for (int i = 0; i < laneCount; i++) {
            lanes[i] = new SerialExecutor(executor);
        }
        this.logLane = new SerialExecutor(executor);
    }

    public Registration addEventListener(EventSelector selector, EventListener listener) {
        ListenerEntry entry = new ListenerEntry(Objects.requireNonNull(selector, "selector"),
                Objects.requireNonNull(listener, "listener"));
        eventListeners.add(entry);
        return () -> eventListeners.remove(entry);
    }

    public Registration addLogListener(LogListener listener) {
        Objects.requireNonNull(listener, "listener");
        LogListener entry = listener::onLog;
        logListeners.add(entry);
        return () -> logListeners.remove(entry);
    }

    public boolean hasEventListeners() {
        return !eventListeners.isEmpty();
    }

    /**
     * Callback run whenever the read pause state flips. The owner re-applies {@link #isReadPaused()} to its
     * channel on the channel's event loop, so a late pause can never override a newer resume.
     */
    public void setReadGate(Runnable readGate) {
        this.readGate = Objects.requireNonNull(readGate, "readGate");
    }

    public boolean isReadPaused() {
        return readPaused.get();
    }

    /** Queues the event for every matching listener. Non-blocking. */
    public void dispatch(EslEvent event) {
        if (shutdown || eventListeners.isEmpty()) {
            return;
        }
        if (admit(event.name())) {
            submit(laneFor(keyOf(event)), () -> deliver(event));
        }
    }

    public void dispatchLog(LogLine line) {
        if (shutdown || logListeners.isEmpty()) {
            return;
        }
        if (admit("log")) {
            submit(logLane, () -> {
                for (LogListener listener : logListeners) {
                    try {
                        listener.onLog(line);
                    } catch (Throwable t) {
                        metrics.listenerFailed();
                        log.warn("Log listener failed", t);
                    }
                }
            });
        }
    }

    /** Runs a task on the lane of {@code key}, ordered with the events of that key. */
    public void execute(Object key, Runnable task) {
        if (shutdown) {
            return;
        }
        backlog.incrementAndGet();
        submit(laneFor(key), task);
    }

    /** Current number of queued or running dispatch tasks. */
    public int backlog() {
        return backlog.get();
    }

    /** Completes once every queued task has run (immediately when idle). */
    public CompletableFuture<Void> awaitIdle() {
        synchronized (idleWaiters) {
            if (backlog.get() == 0) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            idleWaiters.add(future);
            return future;
        }
    }

    /** Stops accepting new events; queued ones still run. */
    public void shutdown() {
        shutdown = true;
    }

    private boolean admit(String eventName) {
        int pending = backlog.incrementAndGet();
        if (pending >= options.highWaterMark()) {
            if (options.backpressureStrategy() == BackpressureStrategy.DROP) {
                completed();
                metrics.eventDropped(eventName);
                return false;
            }
            if (readPaused.compareAndSet(false, true)) {
                log.warn("Event backlog reached {}, pausing socket reads until it drops to {}",
                        pending, options.lowWaterMark());
                readGate.run();
            }
        }
        return true;
    }

    private void submit(Executor target, Runnable task) {
        Runnable tracked = () -> {
            try {
                task.run();
            } finally {
                completed();
            }
        };
        try {
            target.execute(tracked);
        } catch (RejectedExecutionException e) {
            completed();
            log.debug("Dispatch rejected, executor is shut down");
        }
    }

    private void completed() {
        int pending = backlog.decrementAndGet();
        if (pending <= options.lowWaterMark() && readPaused.compareAndSet(true, false)) {
            log.info("Event backlog drained to {}, resuming socket reads", pending);
            readGate.run();
        }
        if (pending == 0) {
            List<CompletableFuture<Void>> waiters;
            synchronized (idleWaiters) {
                if (idleWaiters.isEmpty() || backlog.get() != 0) {
                    return;
                }
                waiters = new ArrayList<>(idleWaiters);
                idleWaiters.clear();
            }
            waiters.forEach(waiter -> waiter.complete(null));
        }
    }

    private void deliver(EslEvent event) {
        for (ListenerEntry entry : eventListeners) {
            try {
                if (entry.selector.matches(event)) {
                    entry.listener.onEvent(event);
                }
            } catch (Throwable t) {
                metrics.listenerFailed();
                log.warn("Event listener failed on {}", event, t);
            }
        }
    }

    private Executor laneFor(Object key) {
        if (lanes.length == 0 || key == null) {
            return executor;
        }
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return lanes[Math.floorMod(hash, lanes.length)];
    }

    private static Object keyOf(EslEvent event) {
        String uuid = event.uniqueId();
        if (uuid != null) {
            return uuid;
        }
        String job = event.jobUuid();
        return job != null ? job : event.name();
    }

    private record ListenerEntry(EventSelector selector, EventListener listener) {
    }
}
