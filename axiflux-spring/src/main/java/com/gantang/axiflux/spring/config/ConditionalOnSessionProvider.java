package com.gantang.axiflux.spring.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Conditional that matches {@code axiflux.session.provider} against a set of
 * accepted values (case-insensitive). Used instead of
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * because the provider setting accepts multiple values per backend
 * ({@code auto} means "jpa if available, else redis, else in-memory").
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnSessionProvider.SessionProviderCondition.class)
public @interface ConditionalOnSessionProvider {

    /** Accepted provider values, e.g. {"jpa", "auto"}. */
    String[] value();

    /** Match when the property is not set at all (defaults to "auto" semantics). */
    boolean matchIfMissing() default false;

    class SessionProviderCondition implements Condition {

        static final String PROPERTY = "axiflux.session.provider";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var attrs = metadata.getAnnotationAttributes(ConditionalOnSessionProvider.class.getName());
            if (attrs == null) {
                return false;
            }
            String[] accepted = (String[]) attrs.get("value");
            boolean matchIfMissing = (boolean) attrs.get("matchIfMissing");
            Set<String> acceptedSet = Arrays.stream(accepted)
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

            String raw = context.getEnvironment().getProperty(PROPERTY);
            if (raw == null || raw.isBlank()) {
                return matchIfMissing;
            }
            return acceptedSet.contains(raw.trim().toLowerCase(Locale.ROOT));
        }
    }
}
