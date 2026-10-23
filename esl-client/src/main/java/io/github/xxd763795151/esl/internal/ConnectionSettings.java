package io.github.xxd763795151.esl.internal;

import io.github.xxd763795151.esl.command.EslCommand;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-connection timing settings. A zero duration disables the corresponding timeout.
 *
 * @param commandTimeout  time to wait for the reply of a command
 * @param jobTimeout      time to wait for the {@code BACKGROUND_JOB} of a {@code bgapi}
 * @param executeTimeout  time to wait for {@code CHANNEL_EXECUTE_COMPLETE}; zero waits until hangup/close
 * @param idleTimeout     read idle time after which liveness is probed; zero disables probing
 * @param livenessProbe   command used as probe
 * @param maxHeaderBytes  frame header limit
 * @param maxBodyBytes    frame body limit
 */
public record ConnectionSettings(Duration commandTimeout, Duration jobTimeout, Duration executeTimeout,
                                 Duration idleTimeout, EslCommand livenessProbe,
                                 int maxHeaderBytes, int maxBodyBytes) {

    public ConnectionSettings {
        requireNonNegative(commandTimeout, "commandTimeout");
        requireNonNegative(jobTimeout, "jobTimeout");
        requireNonNegative(executeTimeout, "executeTimeout");
        requireNonNegative(idleTimeout, "idleTimeout");
        Objects.requireNonNull(livenessProbe, "livenessProbe");
        if (maxHeaderBytes <= 0 || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("frame limits must be positive");
        }
    }

    private static void requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
