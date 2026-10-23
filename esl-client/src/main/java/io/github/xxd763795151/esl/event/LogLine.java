package io.github.xxd763795151.esl.event;

import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.github.xxd763795151.esl.protocol.HeaderNames;

/**
 * One FreeSWITCH log line received after the {@code log} command ({@code log/data} frame).
 *
 * @param level       log level
 * @param textChannel log channel id ({@code Text-Channel})
 * @param file        source file
 * @param function    source function
 * @param line        source line
 * @param userData    session uuid or other user data, may be {@code null}
 * @param text        the log text
 */
public record LogLine(LogLevel level, String textChannel, String file, String function, int line,
                      String userData, String text) {

    public static LogLine from(EslMessage message) {
        return new LogLine(
                LogLevel.fromLevel(message.headers().getInt(HeaderNames.LOG_LEVEL, LogLevel.DEBUG.level())),
                message.header(HeaderNames.TEXT_CHANNEL),
                message.header(HeaderNames.LOG_FILE),
                message.header(HeaderNames.LOG_FUNC),
                message.headers().getInt(HeaderNames.LOG_LINE, 0),
                message.header(HeaderNames.USER_DATA),
                message.body());
    }
}
