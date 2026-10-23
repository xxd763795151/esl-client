package io.github.xxd763795151.esl.dispatch;

/**
 * Ordering guarantee for listener invocations.
 */
public enum DispatchMode {

    /** All events are delivered one at a time, in arrival order. Simplest, lowest throughput. */
    SEQUENTIAL,

    /**
     * Events of the same channel ({@code Unique-ID}) are delivered in order, different channels in parallel.
     * Non-channel events are ordered per {@code Job-UUID} / event name. The default.
     */
    PER_CHANNEL_ORDERED,

    /** No ordering at all; every event is an independent task. */
    UNORDERED
}
