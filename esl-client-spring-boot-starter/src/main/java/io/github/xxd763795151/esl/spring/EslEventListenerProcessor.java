package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.inbound.InboundClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finds {@link EslEventListener} methods and registers them on their inbound clients once all singletons exist.
 */
public class EslEventListenerProcessor implements BeanPostProcessor, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EslEventListenerProcessor.class);

    private final ObjectProvider<EslClients> clients;
    private final List<Target> targets = new CopyOnWriteArrayList<>();

    public EslEventListenerProcessor(ObjectProvider<EslClients> clients) {
        this.clients = clients;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        ReflectionUtils.doWithMethods(type, method -> {
            EslEventListener annotation = AnnotatedElementUtils.findMergedAnnotation(method, EslEventListener.class);
            if (annotation == null) {
                return;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length > 1 || (parameters.length == 1 && !parameters[0].isAssignableFrom(EslEvent.class))) {
                throw new IllegalStateException("@EslEventListener method " + method
                        + " must take no argument or a single EslEvent");
            }
            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
            ReflectionUtils.makeAccessible(invocable);
            targets.add(new Target(bean, invocable, selector(annotation), annotation.client()));
        });
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (targets.isEmpty()) {
            return;
        }
        EslClients registry = clients.getIfAvailable();
        Map<String, InboundClient> inbounds = registry == null ? Map.of() : registry.inbounds();
        if (inbounds.isEmpty()) {
            log.warn("{} @EslEventListener method(s) found but no InboundClient is configured "
                    + "(esl.inbound.enabled or esl.inbounds)", targets.size());
            return;
        }
        targets.forEach(target -> clientsOf(target, inbounds)
                .forEach(inbound -> inbound.addEventListener(target.selector, event -> target.invoke(event))));
        log.debug("Registered {} @EslEventListener method(s)", targets.size());
    }

    private static Collection<InboundClient> clientsOf(Target target, Map<String, InboundClient> inbounds) {
        if (target.clients.length == 0) {
            return inbounds.values();
        }
        return Arrays.stream(target.clients).map(name -> {
            InboundClient inbound = inbounds.get(name);
            if (inbound == null) {
                throw new IllegalStateException("@EslEventListener method " + target.method + " names unknown client '"
                        + name + "', known: " + inbounds.keySet());
            }
            return inbound;
        }).toList();
    }

    private static EventSelector selector(EslEventListener annotation) {
        EventSelector byName = annotation.value().length == 0 ? null : EventSelector.named(annotation.value());
        EventSelector bySubclass = annotation.subclass().length == 0 ? null : EventSelector.custom(annotation.subclass());
        if (byName == null && bySubclass == null) {
            return EventSelector.all();
        }
        if (byName == null) {
            return bySubclass;
        }
        return bySubclass == null ? byName : byName.or(bySubclass);
    }

    private record Target(Object bean, Method method, EventSelector selector, String[] clients) {

        void invoke(EslEvent event) throws Exception {
            try {
                if (method.getParameterCount() == 0) {
                    method.invoke(bean);
                } else {
                    method.invoke(bean, event);
                }
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(cause);
            }
        }
    }
}
