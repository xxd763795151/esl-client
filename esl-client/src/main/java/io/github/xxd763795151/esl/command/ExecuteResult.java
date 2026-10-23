package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.event.EslEvent;

/**
 * Completion of a dialplan application started with {@code sendmsg execute}.
 *
 * @param uuid            channel the application ran on
 * @param application     application name
 * @param applicationUuid correlation id ({@code Event-UUID} / {@code Application-UUID})
 * @param event           the {@code CHANNEL_EXECUTE_COMPLETE} event, or {@code null} when the execution was
 *                        fire-and-forget or ended by the channel hanging up during {@code hangup}
 */
public record ExecuteResult(String uuid, String application, String applicationUuid, EslEvent event) {

    /** {@code Application-Response} reported by FreeSWITCH, or {@code null}. */
    public String response() {
        return event == null ? null : event.applicationResponse();
    }

    /** Channel variable as of application completion (e.g. digits collected by {@code play_and_get_digits}). */
    public String variable(String name) {
        return event == null ? null : event.variable(name);
    }

    public boolean completed() {
        return event != null;
    }
}
