package io.github.xxd763795151.esl.test;

import io.github.xxd763795151.esl.protocol.EslHeaders;

/**
 * A command as received by {@link FakeFreeSwitch}.
 *
 * @param line    first line, e.g. {@code api status}
 * @param headers following headers
 * @param body    body (when a Content-Length was sent), else empty
 */
public record ReceivedCommand(String line, EslHeaders headers, String body) {

    /** First word of the line, lower-cased. */
    public String name() {
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
    }

    /** Everything after the first word. */
    public String argument() {
        int space = line.indexOf(' ');
        return space < 0 ? "" : line.substring(space + 1);
    }

    public String header(String name) {
        return headers.get(name);
    }

    public boolean is(String commandName) {
        return name().equalsIgnoreCase(commandName);
    }
}
