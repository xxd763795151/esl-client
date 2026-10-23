package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.outbound.OutboundServer;

/**
 * Adjusts the auto-configured {@link OutboundServer} beyond what properties offer. Applied after the properties.
 */
@FunctionalInterface
public interface OutboundServerCustomizer {

    void customize(OutboundServer.Builder builder);

    /**
     * Called for every auto-configured instance with its name ({@code esl.outbound.name} or the {@code esl.outbounds}
     * key); delegates to {@link #customize(OutboundServer.Builder)}. Override to customize only some instances.
     */
    default void customize(String name, OutboundServer.Builder builder) {
        customize(builder);
    }
}
