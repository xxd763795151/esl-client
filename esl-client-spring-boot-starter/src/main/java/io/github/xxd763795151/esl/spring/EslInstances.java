package io.github.xxd763795151.esl.spring;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The enabled {@code esl.inbounds}/{@code esl.outbounds} entries and the bean names they are registered under.
 * Read straight from the {@link Environment} because they are needed before {@link EslProperties} is bound.
 */
final class EslInstances {

    static final String DEFAULT_INBOUND_BEAN = "eslInboundClient";
    static final String DEFAULT_OUTBOUND_BEAN = "eslOutboundServer";

    private EslInstances() {
    }

    static Map<String, EslProperties.Inbound> inbounds(Environment environment) {
        Map<String, EslProperties.Inbound> inbounds = new LinkedHashMap<>();
        Binder.get(environment).bind("esl.inbounds", Bindable.mapOf(String.class, EslProperties.Inbound.class))
                .ifBound(bound -> bound.forEach((key, inbound) -> {
                    if (inbound.enabledOr(true)) {
                        inbounds.put(key, inbound);
                    }
                }));
        return inbounds;
    }

    static Map<String, EslProperties.Outbound> outbounds(Environment environment) {
        Map<String, EslProperties.Outbound> outbounds = new LinkedHashMap<>();
        Binder.get(environment).bind("esl.outbounds", Bindable.mapOf(String.class, EslProperties.Outbound.class))
                .ifBound(bound -> bound.forEach((key, outbound) -> {
                    if (outbound.enabled()) {
                        outbounds.put(key, outbound);
                    }
                }));
        return outbounds;
    }

    static String inboundBean(String key) {
        return key + "InboundClient";
    }

    static String inboundLifecycleBean(String key) {
        return key + "InboundClientLifecycle";
    }

    static String outboundBean(String key) {
        return key + "OutboundServer";
    }

    static String outboundLifecycleBean(String key) {
        return key + "OutboundServerLifecycle";
    }

    /** Instance name of every auto-configured outbound server by bean name ({@code OutboundServer} has none). */
    static Map<String, String> outboundNamesByBean(Environment environment) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(DEFAULT_OUTBOUND_BEAN, defaultOutboundName(environment));
        outbounds(environment).forEach((key, outbound) -> names.put(outboundBean(key), outbound.nameOr(key)));
        return names;
    }

    static String defaultInboundName(Environment environment) {
        String name = environment.getProperty("esl.inbound.name");
        return StringUtils.hasText(name) ? name : EslBuilders.DEFAULT_INBOUND_NAME;
    }

    static String defaultOutboundName(Environment environment) {
        String name = environment.getProperty("esl.outbound.name");
        return StringUtils.hasText(name) ? name : EslBuilders.DEFAULT_OUTBOUND_NAME;
    }

    /** Matches when at least one {@code esl.inbounds} entry is enabled. */
    static class OnNamedInbounds extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return inbounds(context.getEnvironment()).isEmpty()
                    ? ConditionOutcome.noMatch("no esl.inbounds entry")
                    : ConditionOutcome.match("esl.inbounds entries exist");
        }
    }

    /** Matches when at least one {@code esl.outbounds} entry is enabled. */
    static class OnNamedOutbounds extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return outbounds(context.getEnvironment()).isEmpty()
                    ? ConditionOutcome.noMatch("no esl.outbounds entry")
                    : ConditionOutcome.match("esl.outbounds entries exist");
        }
    }

    /**
     * Matches unless {@code esl.outbounds} is used without {@code esl.outbound.handler}: the handler beans then
     * belong to the named servers and must not also start the default one.
     */
    static class OnDefaultOutbound extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            if (StringUtils.hasText(environment.getProperty("esl.outbound.handler"))) {
                return ConditionOutcome.match("esl.outbound.handler is set");
            }
            return outbounds(environment).isEmpty()
                    ? ConditionOutcome.match("no esl.outbounds entry")
                    : ConditionOutcome.noMatch("esl.outbounds entries exist and esl.outbound.handler is not set");
        }
    }
}
