package com.gantang.tianshu.spring.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes PostgreSQL and Redis genuinely <strong>optional</strong> for the
 * open-source edition.
 *
 * <p>The baseline configuration binds {@code spring.datasource.url} to
 * {@code ${PG_URL:}} and {@code spring.data.redis.host} to {@code ${REDIS_HOST:}}.
 * A bare empty default is not enough on its own: Spring resolves those placeholders
 * to an empty string, and condition evaluation treats an empty-string property as
 * <em>present</em> (e.g. {@code @ConditionalOnProperty("spring.datasource.url")}
 * still matches, and Hikari/Lettuce build a bean regardless of an empty url/host).
 * That chain would force a database on every first-time user.
 *
 * <p>This post-processor therefore detects whether the user actually supplied a
 * JDBC URL / Redis host. When they did not, it appends the corresponding
 * auto-configurations to {@code spring.autoconfigure.exclude}, so no
 * DataSource/JPA/Flyway or Redis connection factory is created and the
 * application runs with in-memory sessions and process-local state.
 *
 * <p>Explicit configuration (PG_URL / SPRING_DATASOURCE_URL / REDIS_HOST /
 * SPRING_DATA_REDIS_HOST), as well as the {@code dev}/{@code test}/{@code prod}
 * profiles which always set non-empty values, keeps the original behaviour.
 */
public class OptionalInfrastructureEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";

    private static final String[] DATASOURCE_URL_KEYS = {
        "spring.datasource.url",
        "SPRING_DATASOURCE_URL",
        "PG_URL"
    };

    private static final String[] REDIS_HOST_KEYS = {
        "spring.data.redis.host",
        "SPRING_DATA_REDIS_HOST",
        "REDIS_HOST"
    };

    private static final String[] DATASOURCE_EXCLUDES = {
        // Spring Boot built-in JDBC/JPA infrastructure
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.metrics.HibernateMetricsAutoConfiguration",
        // Tianshu's own JPA + Flyway wiring (registered in AutoConfiguration.imports).
        // These carry @EnableJpaRepositories / run migrations, so they must back
        // off together with the DataSource to avoid repositories without an EMF.
        "com.gantang.tianshu.spring.config.TianshuJpaAutoConfiguration",
        "com.gantang.tianshu.spring.config.FlywayMigrationConfiguration"
    };

    private static final String[] REDIS_EXCLUDES = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.observation.LettuceObservationAutoConfiguration"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Set<String> excludes = new LinkedHashSet<>(readExistingExcludes(environment));
        boolean changed = false;

        if (!hasNonBlankValue(environment, DATASOURCE_URL_KEYS)) {
            for (String e : DATASOURCE_EXCLUDES) changed |= excludes.add(e);
        }
        if (!hasNonBlankValue(environment, REDIS_HOST_KEYS)) {
            for (String e : REDIS_EXCLUDES) changed |= excludes.add(e);
        }

        if (changed) {
            environment.getPropertySources()
                .addFirst(new ExcludePropertySource(EXCLUDE_PROPERTY, String.join(",", excludes)));
        }
    }

    /**
     * @return true only when at least one candidate key resolves to non-blank text
     *         <em>and</em> that value was actually provided (not the placeholder
     *         default collapsing to empty).
     */
    private static boolean hasNonBlankValue(ConfigurableEnvironment environment, String[] keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readExistingExcludes(ConfigurableEnvironment environment) {
        List<String> result = new ArrayList<>();
        try {
            String raw = environment.getProperty(EXCLUDE_PROPERTY);
            if (raw != null) {
                for (String part : raw.split(",")) {
                    if (StringUtils.hasText(part)) result.add(part.trim());
                }
            }
        } catch (Exception ignored) {
            // Some users configure excludes as a List; fall back to typed lookup.
            List<String> list = environment.getProperty(EXCLUDE_PROPERTY, List.class);
            if (list != null) {
                for (Object o : list) {
                    if (o != null && StringUtils.hasText(o.toString())) result.add(o.toString().trim());
                }
            }
        }
        return result;
    }

    /** Minimal property source that only exposes the merged exclude list. */
    private static final class ExcludePropertySource extends PropertySource<String> {

        private final String value;

        ExcludePropertySource(String name, String value) {
            super(name);
            this.value = value;
        }

        @Override
        public Object getProperty(String name) {
            return EXCLUDE_PROPERTY.equals(name) ? value : null;
        }
    }
}
