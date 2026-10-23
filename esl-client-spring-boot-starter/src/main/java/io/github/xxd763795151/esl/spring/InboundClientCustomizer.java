package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.inbound.InboundClient;

/**
 * Adjusts the auto-configured {@link InboundClient} beyond what properties offer (custom recovery strategy,
 * reconnect policy, liveness probe ...). Applied after the properties.
 */
@FunctionalInterface
public interface InboundClientCustomizer {

    void customize(InboundClient.Builder builder);

    /**
     * Called for every auto-configured instance with its name ({@code esl.inbound.name} or the {@code esl.inbounds}
     * key); delegates to {@link #customize(InboundClient.Builder)}. Override to customize only some instances.
     */
    default void customize(String name, InboundClient.Builder builder) {
        customize(builder);
    }
}
