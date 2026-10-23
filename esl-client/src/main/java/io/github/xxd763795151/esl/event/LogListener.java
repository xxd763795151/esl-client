package io.github.xxd763795151.esl.event;

/**
 * Receives FreeSWITCH log lines, in order, on a worker thread.
 */
@FunctionalInterface
public interface LogListener {

    void onLog(LogLine line) throws Exception;
}
