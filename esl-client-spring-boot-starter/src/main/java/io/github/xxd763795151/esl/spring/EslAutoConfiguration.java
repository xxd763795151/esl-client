package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.metrics.EslMetrics;
import io.github.xxd763795151.esl.metrics.MicrometerEslMetrics;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import io.github.xxd763795151.esl.outbound.OutboundSessionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration of the FreeSWITCH ESL client.
 * <ul>
 *   <li>{@link InboundClient} when {@code esl.inbound.enabled=true}, plus one per {@code esl.inbounds} entry</li>
 *   <li>{@link OutboundServer} when an {@link OutboundSessionHandler} bean exists, plus one per
 *       {@code esl.outbounds} entry</li>
 *   <li>shared {@link EslResources}, Micrometer metrics, lifecycle management, {@link EslClients} and
 *       {@link EslEventListener}</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(EslProperties.class)
public class EslAutoConfiguration {

    /** One set of I/O and worker threads for every ESL component; closed after their lifecycles stopped. */
    @Bean
    @ConditionalOnMissingBean
    public EslResources eslResources(EslProperties properties) {
        EslProperties.Threads threads = properties.threads();
        return EslResources.builder()
                .ioThreads(threads.ioThreads())
                .virtualThreads(threads.virtualThreads())
                .transport(threads.transport())
                .threadNamePrefix("esl")
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public EslMetricsFactory eslMetricsFactory() {
        return name -> EslMetrics.NOOP;
    }

    /** One {@link EslClients} registry over every client and server, the named instances included. */
    @Bean
    @ConditionalOnMissingBean
    public EslClients eslClients(ListableBeanFactory beanFactory, Environment environment) {
        return new EslClients(beanFactory, environment);
    }

    /** Registers the {@code esl.inbounds} and {@code esl.outbounds} entries. */
    @Bean
    public static EslInstancesRegistrar eslInstancesRegistrar() {
        return new EslInstancesRegistrar();
    }

    // ------------------------------------------------------------------ inbound

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty("esl.inbound.enabled")
    public InboundClient eslInboundClient(EslProperties properties, EslResources resources,
                                          EslMetricsFactory metricsFactory,
                                          ObjectProvider<InboundClientCustomizer> customizers) {
        EslProperties.Inbound inbound = properties.inbound();
        return EslBuilders.inbound(inbound.nameOr(EslBuilders.DEFAULT_INBOUND_NAME), inbound, resources,
                metricsFactory, customizers);
    }

    @Bean
    @ConditionalOnBooleanProperty("esl.inbound.enabled")
    public InboundClientLifecycle eslInboundClientLifecycle(InboundClient client, EslProperties properties) {
        EslProperties.Inbound inbound = properties.inbound();
        return new InboundClientLifecycle(client, inbound.startupWait(), inbound.failFast(), inbound.shutdownTimeout());
    }

    @Bean
    public static EslEventListenerProcessor eslEventListenerProcessor(ObjectProvider<EslClients> clients) {
        return new EslEventListenerProcessor(clients);
    }

    // ------------------------------------------------------------------ outbound

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    @ConditionalOnBean(OutboundSessionHandler.class)
    @ConditionalOnBooleanProperty(name = "esl.outbound.enabled", matchIfMissing = true)
    @Conditional(EslInstances.OnDefaultOutbound.class)
    public OutboundServer eslOutboundServer(EslProperties properties, EslResources resources,
                                            EslMetricsFactory metricsFactory, BeanFactory beanFactory,
                                            ObjectProvider<OutboundServerCustomizer> customizers) {
        EslProperties.Outbound outbound = properties.outbound();
        OutboundSessionHandler handler = EslBuilders.handler(beanFactory, outbound.handler(), "esl.outbound.handler");
        return EslBuilders.outbound(outbound.nameOr(EslBuilders.DEFAULT_OUTBOUND_NAME), outbound, handler,
                resources, metricsFactory, customizers);
    }

    @Bean
    @ConditionalOnBean(OutboundServer.class)
    public OutboundServerLifecycle eslOutboundServerLifecycle(OutboundServer server, EslProperties properties) {
        return new OutboundServerLifecycle(server, properties.outbound().shutdownTimeout());
    }

    // ------------------------------------------------------------------ metrics

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        EslMetricsFactory micrometerEslMetricsFactory(ObjectProvider<MeterRegistry> registry) {
            return name -> {
                MeterRegistry meterRegistry = registry.getIfAvailable();
                return meterRegistry == null ? EslMetrics.NOOP : new MicrometerEslMetrics(meterRegistry, name);
            };
        }
    }
}
