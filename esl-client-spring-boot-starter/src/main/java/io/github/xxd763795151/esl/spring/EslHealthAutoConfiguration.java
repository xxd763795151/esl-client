package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.connection.ConnectionState;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health indicators when Spring Boot's health support is present: {@code eslInbound} and {@code eslOutbound} for
 * the {@code esl.inbound}/{@code esl.outbound} instances, {@code eslInbounds} and {@code eslOutbounds} with one
 * entry per {@code esl.inbounds}/{@code esl.outbounds} instance.
 */
@AutoConfiguration(after = EslAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
public class EslHealthAutoConfiguration {

    @Bean
    @ConditionalOnBean(InboundClient.class)
    @ConditionalOnMissingBean(name = "eslInboundHealthIndicator")
    public HealthIndicator eslInboundHealthIndicator(InboundClient client) {
        return inboundHealth(client);
    }

    @Bean
    @ConditionalOnBean(OutboundServer.class)
    @ConditionalOnMissingBean(name = "eslOutboundHealthIndicator")
    public HealthIndicator eslOutboundHealthIndicator(OutboundServer server) {
        return outboundHealth(server);
    }

    @Bean
    @Conditional(EslInstances.OnNamedInbounds.class)
    @ConditionalOnMissingBean(name = "eslInboundsHealthContributor")
    public CompositeHealthContributor eslInboundsHealthContributor(BeanFactory beanFactory, Environment environment) {
        Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
        EslInstances.inbounds(environment).forEach((key, inbound) -> indicators.put(inbound.nameOr(key),
                inboundHealth(beanFactory.getBean(EslInstances.inboundBean(key), InboundClient.class))));
        return CompositeHealthContributor.fromMap(indicators);
    }

    @Bean
    @Conditional(EslInstances.OnNamedOutbounds.class)
    @ConditionalOnMissingBean(name = "eslOutboundsHealthContributor")
    public CompositeHealthContributor eslOutboundsHealthContributor(BeanFactory beanFactory, Environment environment) {
        Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
        EslInstances.outbounds(environment).forEach((key, outbound) -> indicators.put(outbound.nameOr(key),
                outboundHealth(beanFactory.getBean(EslInstances.outboundBean(key), OutboundServer.class))));
        return CompositeHealthContributor.fromMap(indicators);
    }

    private static HealthIndicator inboundHealth(InboundClient client) {
        return () -> {
            ConnectionState state = client.state();
            Health.Builder health = state == ConnectionState.READY ? Health.up() : Health.down();
            health.withDetail("state", state.name());
            if (client.remoteAddress() != null) {
                health.withDetail("remote", client.remoteAddress().toString());
            }
            return health.build();
        };
    }

    private static HealthIndicator outboundHealth(OutboundServer server) {
        return () -> {
            Health.Builder health = server.isRunning() ? Health.up() : Health.down();
            if (server.localAddress() != null) {
                health.withDetail("listening", server.localAddress().toString());
            }
            return health.withDetail("activeSessions", server.sessions().size()).build();
        };
    }
}
