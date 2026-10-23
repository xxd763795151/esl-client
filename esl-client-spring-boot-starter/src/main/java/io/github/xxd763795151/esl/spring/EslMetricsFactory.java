package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.metrics.EslMetrics;

/**
 * Creates the metrics binding of a client or server. Backed by Micrometer when a {@code MeterRegistry} exists.
 */
@FunctionalInterface
public interface EslMetricsFactory {

    EslMetrics create(String name);
}
