package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.connection.EslResources;
import io.github.xxd763795151.esl.inbound.InboundClient;
import io.github.xxd763795151.esl.outbound.OutboundServer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers an {@link InboundClient} per {@code esl.inbounds} entry and an {@link OutboundServer} per
 * {@code esl.outbounds} entry, each with its lifecycle. The instances are qualified by their name and are not
 * default candidates: inject them with {@code @Qualifier("name")} or look them up in {@link EslClients}, while a
 * plain {@code InboundClient}/{@code OutboundServer} injection point still gets the {@code esl.inbound}/
 * {@code esl.outbound} instance.
 */
class EslInstancesRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

    private Environment environment;
    private BeanFactory beanFactory;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Map<String, EslProperties.Inbound> inbounds = EslInstances.inbounds(environment);
        Map<String, EslProperties.Outbound> outbounds = EslInstances.outbounds(environment);
        checkUniqueNames(inbounds, outbounds);
        inbounds.forEach((key, inbound) -> registerInbound(registry, key, inbound));
        outbounds.forEach((key, outbound) -> registerOutbound(registry, key, outbound));
    }

    private void registerInbound(BeanDefinitionRegistry registry, String key, EslProperties.Inbound inbound) {
        String name = inbound.nameOr(key);
        String clientBean = EslInstances.inboundBean(key);
        registry.registerBeanDefinition(clientBean, instance(InboundClient.class, name,
                () -> EslBuilders.inbound(name, inbound, beanFactory.getBean(EslResources.class),
                        beanFactory.getBean(EslMetricsFactory.class),
                        beanFactory.getBeanProvider(InboundClientCustomizer.class))));
        registry.registerBeanDefinition(EslInstances.inboundLifecycleBean(key),
                new RootBeanDefinition(InboundClientLifecycle.class,
                        () -> new InboundClientLifecycle(beanFactory.getBean(clientBean, InboundClient.class),
                                inbound.startupWait(), inbound.failFast(), inbound.shutdownTimeout())));
    }

    private void registerOutbound(BeanDefinitionRegistry registry, String key, EslProperties.Outbound outbound) {
        String name = outbound.nameOr(key);
        String serverBean = EslInstances.outboundBean(key);
        registry.registerBeanDefinition(serverBean, instance(OutboundServer.class, name,
                () -> EslBuilders.outbound(name, outbound,
                        EslBuilders.handler(beanFactory, outbound.handler(), "esl.outbounds." + key + ".handler"),
                        beanFactory.getBean(EslResources.class), beanFactory.getBean(EslMetricsFactory.class),
                        beanFactory.getBeanProvider(OutboundServerCustomizer.class))));
        registry.registerBeanDefinition(EslInstances.outboundLifecycleBean(key),
                new RootBeanDefinition(OutboundServerLifecycle.class,
                        () -> new OutboundServerLifecycle(beanFactory.getBean(serverBean, OutboundServer.class),
                                outbound.shutdownTimeout())));
    }

    private static <T> RootBeanDefinition instance(Class<T> type, String name, Supplier<T> supplier) {
        RootBeanDefinition definition = new RootBeanDefinition(type, supplier);
        definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, name));
        definition.setDefaultCandidate(false);
        // closed by its lifecycle
        definition.setDestroyMethodName("");
        return definition;
    }

    private void checkUniqueNames(Map<String, EslProperties.Inbound> inbounds,
                                  Map<String, EslProperties.Outbound> outbounds) {
        Map<String, String> inboundNames = new HashMap<>();
        if (environment.getProperty("esl.inbound.enabled", Boolean.class, false)) {
            inboundNames.put(EslInstances.defaultInboundName(environment), "esl.inbound");
        }
        inbounds.forEach((key, inbound) -> claim(inboundNames, inbound.nameOr(key), "esl.inbounds." + key));

        Map<String, String> outboundNames = new HashMap<>();
        if (StringUtils.hasText(environment.getProperty("esl.outbound.handler"))) {
            outboundNames.put(EslInstances.defaultOutboundName(environment), "esl.outbound");
        }
        outbounds.forEach((key, outbound) -> claim(outboundNames, outbound.nameOr(key), "esl.outbounds." + key));
    }

    private static void claim(Map<String, String> names, String name, String property) {
        String previous = names.putIfAbsent(name, property);
        if (previous != null) {
            throw new IllegalStateException("ESL instance name '" + name + "' of " + property
                    + " is already used by " + previous);
        }
    }
}
