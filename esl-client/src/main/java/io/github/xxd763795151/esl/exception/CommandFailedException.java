package io.github.xxd763795151.esl.exception;

/**
 * FreeSWITCH answered a command with an error ({@code -ERR ...}).
 */
public class CommandFailedException extends EslException {

    private final String command;
    private final String reply;

    public CommandFailedException(String command, String reply) {
        super("'" + command + "' failed: " + reply);
        this.command = command;
        this.reply = reply;
    }

    /** The command as sent (secrets masked). */
    public String command() {
        return command;
    }

    /** The error text returned by FreeSWITCH. */
    public String reply() {
        return reply;
    }
}
