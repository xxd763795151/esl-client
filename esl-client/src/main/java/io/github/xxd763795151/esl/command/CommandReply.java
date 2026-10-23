package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.protocol.EslMessage;

import java.util.Objects;

/**
 * A {@code command/reply} frame. Success is signalled by a {@code Reply-Text} starting with {@code +OK}.
 *
 * @param message the raw reply frame
 */
public record CommandReply(EslMessage message) {

    public CommandReply {
        Objects.requireNonNull(message, "message");
    }

    /** {@code Reply-Text}; empty when FreeSWITCH sent none. */
    public String replyText() {
        String text = message.replyText();
        return text == null ? "" : text;
    }

    public boolean isOk() {
        return replyText().startsWith("+OK");
    }

    /** Reply text without the leading {@code +OK} / {@code -ERR} marker. */
    public String text() {
        String text = replyText();
        if (text.startsWith("+OK")) {
            return text.substring(3).trim();
        }
        if (text.startsWith("-ERR")) {
            return text.substring(4).trim();
        }
        return text;
    }

    public String header(String name) {
        return message.header(name);
    }
}
