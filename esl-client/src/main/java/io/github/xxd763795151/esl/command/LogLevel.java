package io.github.xxd763795151.esl.command;

import java.util.Locale;

/**
 * FreeSWITCH log levels accepted by the {@code log} command, from least to most verbose.
 */
public enum LogLevel {

    CONSOLE(0),
    ALERT(1),
    CRIT(2),
    ERR(3),
    WARNING(4),
    NOTICE(5),
    INFO(6),
    DEBUG(7);

    private final int level;

    LogLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Maps the numeric {@code Log-Level} header of a {@code log/data} frame, defaulting to {@link #DEBUG}. */
    public static LogLevel fromLevel(int level) {
        for (LogLevel value : values()) {
            if (value.level == level) {
                return value;
            }
        }
        return DEBUG;
    }
}
