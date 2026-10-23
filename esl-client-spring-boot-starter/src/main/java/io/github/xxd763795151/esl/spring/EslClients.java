package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Every {@link InboundClient} and {@link OutboundServer} of the application by instance name: the
 * {@code esl.inbound}/{@code esl.outbound} ones, the {@code esl.inbounds}/{@code esl.outbounds} entries and
 * clients or servers defined as beans by the application.
 *
 * <pre>{@code
 * clients.inbound("fs2").api("status");
 * clients.inbounds().forEach((name, client) -> ...);
 * }</pre>
 */
public class EslClients implements SmartInitializingSingleton {

    private final ListableBeanFactory beanFactory;
    private final Environment environment;
    private volatile Map<String, InboundClient> inbounds;
    private volatile Map<String, OutboundServer> outbounds;

    public EslClients(ListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    /** @throws NoSuchElementException when no inbound client has this name */
    public InboundClient inbound(String name) {
        return findInbound(name).orElseThrow(() -> new NoSuchElementException(
                "No inbound client named '" + name + "', known: " + inbounds().keySet()));
    }

    public Optional<InboundClient> findInbound(String name) {
        return Optional.ofNullable(inbounds().get(name));
    }

    /** Inbound clients by {@link InboundClient#name()}, in registration order. */
    public Map<String, InboundClient> inbounds() {
        Map<String, InboundClient> resolved = inbounds;
        return resolved != null ? resolved : resolveInbounds();
    }

    /** @throws NoSuchElementException when no outbound server has this name */
    public OutboundServer outbound(String name) {
        return findOutbound(name).orElseThrow(() -> new NoSuchElementException(
                "No outbound server named '" + name + "', known: " + outbounds().keySet()));
    }

    public Optional<OutboundServer> findOutbound(String name) {
        return Optional.ofNullable(outbounds().get(name));
    }

    /** Outbound servers by instance name (bean name for servers the application defined), in registration order. */
    public Map<String, OutboundServer> outbounds() {
        Map<String, OutboundServer> resolved = outbounds;
        return resolved != null ? resolved : resolveOutbounds();
    }

    /** The set of instances is final once all singletons exist; resolve lazily before that. */
    @Override
    public void afterSingletonsInstantiated() {
        inbounds = resolveInbounds();
        outbounds = resolveOutbounds();
    }

    private Map<String, InboundClient> resolveInbounds() {
        Map<String, InboundClient> byName = new LinkedHashMap<>();
        beanFactory.getBeansOfType(InboundClient.class).forEach((bean, client) -> put(byName, client.name(), client, bean));
        return Collections.unmodifiableMap(byName);
    }

    private Map<String, OutboundServer> resolveOutbounds() {
        Map<String, String> names = EslInstances.outboundNamesByBean(environment);
        Map<String, OutboundServer> byName = new LinkedHashMap<>();
        beanFactory.getBeansOfType(OutboundServer.class)
                .forEach((bean, server) -> put(byName, names.getOrDefault(bean, bean), server, bean));
        return Collections.unmodifiableMap(byName);
    }

    private static <T> void put(Map<String, T> byName, String name, T instance, String bean) {
        if (byName.putIfAbsent(name, instance) != null) {
            throw new IllegalStateException("Two ESL instances are named '" + name + "' (bean '" + bean
                    + "'); give them distinct names");
        }
    }
}
