package io.github.xxd763795151.esl.command;

/**
 * The kind of frame FreeSWITCH answers a command with. Every command gets exactly one reply.
 */
public enum ReplyType {
    /** {@code Content-Type: command/reply} carrying a {@code Reply-Text} header. */
    COMMAND_REPLY,
    /** {@code Content-Type: api/response} carrying the command output as body. */
    API_RESPONSE
}
