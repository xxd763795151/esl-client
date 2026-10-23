package io.github.xxd763795151.esl.metrics;

/**
 * How a command ended, used as a metric tag.
 */
public enum CommandOutcome {
    SUCCESS,
    /** FreeSWITCH replied with {@code -ERR}. */
    ERROR_REPLY,
    TIMEOUT,
    /** Connection lost or write failed before a reply arrived. */
    FAILED
}
