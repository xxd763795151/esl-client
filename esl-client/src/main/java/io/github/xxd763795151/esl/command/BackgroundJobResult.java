package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.exception.CommandFailedException;

/**
 * Result of a {@code bgapi} command, taken from the matching {@code BACKGROUND_JOB} event.
 *
 * @param jobUuid the job id
 * @param command the command as sent
 * @param event   the {@code BACKGROUND_JOB} event; its body is the command output
 */
public record BackgroundJobResult(String jobUuid, String command, EslEvent event) {

    public String body() {
        return event.body();
    }

    public boolean isOk() {
        String body = event.body();
        return !body.startsWith("-ERR") && !body.startsWith("-USAGE");
    }

    public BackgroundJobResult orThrow() {
        if (!isOk()) {
            throw new CommandFailedException(command, event.body().trim());
        }
        return this;
    }
}
