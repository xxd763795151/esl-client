package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import io.github.xxd763795151.esl.outbound.OutboundSessionHandler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds clients and servers from their properties; shared by the {@code esl.inbound}/{@code esl.outbound} beans
 * and the named {@code esl.inbounds}/{@code esl.outbounds} instances.
 */
final class EslBuilders {

    static final String DEFAULT_INBOUND_NAME = "esl";
    static final String DEFAULT_OUTBOUND_NAME = "esl-outbound";

    private EslBuilders() {
    }

    static InboundClient inbound(String name, EslProperties.Inbound inbound, EslResources resources,
                                 EslMetricsFactory metricsFactory, ObjectProvider<InboundClientCustomizer> customizers) {
        InboundClient.Builder builder = InboundClient.builder()
                .name(name)
                .password(inbound.password())
                .user(inbound.user() == null || inbound.user().isBlank() ? null : inbound.user())
                .connectTimeout(inbound.connectTimeout())
                .commandTimeout(inbound.commandTimeout())
                .jobTimeout(inbound.jobTimeout())
                .executeTimeout(inbound.executeTimeout())
                .idleTimeout(inbound.idleTimeout())
                .closeTimeout(inbound.shutdownTimeout())
                .eventFormat(inbound.eventFormat())
                .events(inbound.events().toArray(String[]::new))
                .logLevel(inbound.logLevel())
                .disconnectedCommandPolicy(inbound.disconnectedCommandPolicy())
                .reconnectPolicy(inbound.reconnect().toPolicy())
                .dispatch(inbound.dispatch().toOptions())
                .retryInitialConnect(!inbound.failFast())
                .resources(resources)
                .metrics(metricsFactory.create(name));
        inbound.addresses().forEach(address -> {
            int colon = address.lastIndexOf(':');
            if (colon < 0) {
                builder.address(address.trim(), 8021);
            } else {
                builder.address(address.substring(0, colon).trim(), Integer.parseInt(address.substring(colon + 1).trim()));
            }
        });
        inbound.filters().forEach(builder::filter);
        customizers.orderedStream().forEach(customizer -> customizer.customize(name, builder));
        return builder.build();
    }

    static OutboundServer outbound(String name, EslProperties.Outbound outbound, OutboundSessionHandler handler,
                                   EslResources resources, EslMetricsFactory metricsFactory,
                                   ObjectProvider<OutboundServerCustomizer> customizers) {
        OutboundServer.Builder builder = OutboundServer.builder()
                .name(name)
                .host(outbound.host())
                .port(outbound.port())
                .handler(handler)
                .autoMyEvents(outbound.autoMyEvents())
                .eventFormat(outbound.eventFormat())
                .linger(outbound.linger())
                .divertEvents(outbound.divertEvents())
                .resumeOnDisconnect(outbound.resumeOnDisconnect())
                .maxSessions(outbound.maxSessions())
                .commandTimeout(outbound.commandTimeout())
                .jobTimeout(outbound.jobTimeout())
                .executeTimeout(outbound.executeTimeout())
                .shutdownTimeout(outbound.shutdownTimeout())
                .resources(resources)
                .metrics(metricsFactory.create(name));
        customizers.orderedStream().forEach(customizer -> customizer.customize(name, builder));
        return builder.build();
    }

    /** The handler named by {@code property}, or the only {@link OutboundSessionHandler} bean when it is empty. */
    static OutboundSessionHandler handler(BeanFactory beanFactory, String beanName, String property) {
        if (beanName != null && !beanName.isBlank()) {
            return beanFactory.getBean(beanName, OutboundSessionHandler.class);
        }
        OutboundSessionHandler handler = beanFactory.getBeanProvider(OutboundSessionHandler.class).getIfUnique();
        if (handler == null) {
            throw new IllegalStateException("No unique OutboundSessionHandler bean; set " + property
                    + " to the bean name of the handler to use");
        }
        return handler;
    }
}
