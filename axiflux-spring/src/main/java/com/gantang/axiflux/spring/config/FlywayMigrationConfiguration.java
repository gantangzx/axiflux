package com.gantang.axiflux.spring.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * Explicit Flyway migration wiring for Spring Boot 4.
 *
 * <p>Spring Boot 4 removed the Flyway auto-configuration from
 * {@code spring-boot-autoconfigure}. Without a replacement, the
 * {@code db/migration/*.sql} scripts are never executed and Hibernate
 * {@code ddl-auto: validate} fails on missing columns.
 *
 * <p>Two pieces work together:
 * <ol>
 *   <li>{@link FlywayMigrator} — runs {@code Flyway.migrate()} on construction.</li>
 *   <li>a {@link BeanFactoryPostProcessor} — forces every
 *       {@code entityManagerFactory} bean to depend on the migrator, so the
 *       migration always finishes before Hibernate schema validation.</li>
 * </ol>
 *
 * <p>Ordering: after {@code DataSourceAutoConfiguration} (so a DataSource exists),
 * before {@code HibernateJpaAutoConfiguration} (so validation sees migrated schema).
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureBefore(name = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(Flyway.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "axiflux.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayMigrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationConfiguration.class);

    @Bean
    public FlywayMigrator AxifluxFlywayMigrator(DataSource dataSource,
                                                ResourcePatternResolver resources) {
        String[] locations = resolveLocations(resources);
        log.info("Flyway: running database migrations from {}", Arrays.toString(locations));
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(locations)
            .baselineOnMigrate(true)
            .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("Flyway: migrations complete ({} applied)", applied);
        return new FlywayMigrator();
    }

    /**
     * Open-source migrations are always present; the commercial location is
     * appended only when that (optional) jar actually ships resources, so a pure
     * open-source deployment keeps a single, unchanged location.
     */
    private static String[] resolveLocations(ResourcePatternResolver resources) {
        String commercialLocation = "classpath:db/migration/commercial";
        try {
            if (resources.getResources(commercialLocation + "/*.sql").length > 0) {
                return new String[]{"classpath:db/migration", commercialLocation};
            }
        } catch (Exception e) {
            log.debug("Flyway: commercial migration location not resolvable, ignoring ({})", e.toString());
        }
        return new String[]{"classpath:db/migration"};
    }

    /**
     * Adds a "depends on AxifluxFlywayMigrator" relationship to every JPA entity
     * manager factory, guaranteeing migrations run before Hibernate validation.
     * Declared {@code static} so it is detected before the enclosing config is
     * instantiated.
     */
    @Bean
    public static BeanFactoryPostProcessor jpaDependsOnFlywayPostProcessor() {
        return beanFactory -> {
            for (String name : beanFactory.getBeanDefinitionNames()) {
                if (name.toLowerCase().contains("entitymanagerfactory")) {
                    BeanDefinition bd = beanFactory.getBeanDefinition(name);
                    String[] existing = bd.getDependsOn();
                    boolean already = existing != null
                        && Arrays.asList(existing).contains("AxifluxFlywayMigrator");
                    if (!already) {
                        String[] updated;
                        if (existing == null) {
                            updated = new String[]{"AxifluxFlywayMigrator"};
                        } else {
                            updated = Arrays.copyOf(existing, existing.length + 1);
                            updated[existing.length] = "AxifluxFlywayMigrator";
                        }
                        bd.setDependsOn(updated);
                        log.info("Flyway: added migration dependency to JPA bean '{}'", name);
                    }
                }
            }
        };
    }

    /** Marker bean proving Flyway has run. */
    public static class FlywayMigrator {
    }
}
