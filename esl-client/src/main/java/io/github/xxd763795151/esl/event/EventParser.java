package io.github.xxd763795151.esl.event;

import io.github.xxd763795151.esl.protocol.EslMessage;

/**
 * Turns an event frame into an {@link EslEvent}. One implementation exists per {@link EventFormat}.
 */
@FunctionalInterface
public interface EventParser {

    /**
     * @throws IllegalArgumentException when the frame body is not a valid event of this format
     */
    EslEvent parse(EslMessage message);
}
