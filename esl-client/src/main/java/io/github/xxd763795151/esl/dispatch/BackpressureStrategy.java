package io.github.xxd763795151.esl.dispatch;

/**
 * What to do when listeners cannot keep up and the dispatch backlog reaches its high water mark.
 */
public enum BackpressureStrategy {

    /**
     * Stop reading from the socket until the backlog falls to the low water mark. Nothing is lost; FreeSWITCH
     * buffers on its side (and eventually drops events for that listener if it stays blocked for long).
     */
    PAUSE_READ,

    /** Keep reading and drop events beyond the high water mark (counted in metrics). */
    DROP
}
