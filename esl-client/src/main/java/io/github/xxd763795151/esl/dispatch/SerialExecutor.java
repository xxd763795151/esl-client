package io.github.xxd763795151.esl.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs submitted tasks one at a time, in submission order, on a shared delegate executor. Holds no thread of
 * its own: an idle lane costs nothing. After a batch it yields the thread so busy lanes cannot starve others.
 */
public final class SerialExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(SerialExecutor.class);
    private static final int BATCH = 128;

    private final Executor delegate;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Runnable drainer = this::drain;

    public SerialExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void execute(Runnable task) {
        tasks.add(Objects.requireNonNull(task, "task"));
        schedule();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                delegate.execute(drainer);
            } catch (RejectedExecutionException e) {
                scheduled.set(false);
                tasks.clear();
                throw e;
            }
        }
    }

    private void drain() {
        try {
            for (int i = 0; i < BATCH; i++) {
                Runnable task = tasks.poll();
                if (task == null) {
                    break;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("Task failed on serial lane", t);
                }
            }
        } finally {
            scheduled.set(false);
            if (!tasks.isEmpty()) {
                try {
                    schedule();
                } catch (RejectedExecutionException e) {
                    log.debug("Executor shut down, queued task(s) discarded");
                }
            }
        }
    }
}
