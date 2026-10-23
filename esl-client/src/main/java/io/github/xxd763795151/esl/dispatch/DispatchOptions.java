package io.github.xxd763795151.esl.dispatch;

import java.util.Objects;

/**
 * Event dispatch tuning.
 *
 * @param mode                 ordering guarantee
 * @param lanes                number of ordered lanes for {@link DispatchMode#PER_CHANNEL_ORDERED}
 * @param highWaterMark        backlog at which {@link BackpressureStrategy} kicks in
 * @param lowWaterMark         backlog at which reading resumes after a pause
 * @param backpressureStrategy what to do above the high water mark
 */
public record DispatchOptions(DispatchMode mode, int lanes, int highWaterMark, int lowWaterMark,
                              BackpressureStrategy backpressureStrategy) {

    public static final DispatchOptions DEFAULT = new DispatchOptions(
            DispatchMode.PER_CHANNEL_ORDERED, 64, 10_000, 5_000, BackpressureStrategy.PAUSE_READ);

    public DispatchOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(backpressureStrategy, "backpressureStrategy");
        if (lanes < 1) {
            throw new IllegalArgumentException("lanes must be >= 1");
        }
        if (lowWaterMark < 0 || highWaterMark <= lowWaterMark) {
            throw new IllegalArgumentException("require 0 <= lowWaterMark < highWaterMark");
        }
    }

    public DispatchOptions withMode(DispatchMode mode) {
        return new DispatchOptions(mode, lanes, highWaterMark, lowWaterMark, backpressureStrategy);
    }
}
