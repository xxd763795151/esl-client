package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.exception.CommandFailedException;

/**
 * Output of an {@code api} command (the body of the {@code api/response} frame).
 *
 * @param command the command as sent
 * @param body    command output; FreeSWITCH reports failures in-band as {@code -ERR ...} or {@code -USAGE ...}
 */
public record ApiResponse(String command, String body) {

    public ApiResponse {
        body = body == null ? "" : body;
    }

    public boolean isOk() {
        return !body.startsWith("-ERR") && !body.startsWith("-USAGE");
    }

    /** Output with surrounding whitespace removed, e.g. {@code +OK 6f3c...} for {@code originate}. */
    public String trimmed() {
        return body.trim();
    }

    /** Returns this response, or throws {@link CommandFailedException} when it reports an error. */
    public ApiResponse orThrow() {
        if (!isOk()) {
            throw new CommandFailedException(command, body.trim());
        }
        return this;
    }
}
