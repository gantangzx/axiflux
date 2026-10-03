package com.gantang.axiflux.registry.config;

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
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * Explicit Flyway wiring (Spring Boot 4 removed Flyway auto-configuration).
 * Registered as an auto-configuration (AutoConfiguration.imports) so the
 * DataSource condition is evaluated after DataSourceAutoConfiguration.
 * Runs migrations before Hibernate schema validation via a depends-on post-processor.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureBefore(name = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(Flyway.class)
@ConditionalOnBean(DataSource.class)
public class RegistryFlywayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RegistryFlywayConfiguration.class);

    @Bean
    public FlywayMigrator registryFlywayMigrator(DataSource dataSource) {
        log.info("Flyway: running registry migrations from classpath:db/migration");
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("Flyway: registry migrations complete ({} applied)", applied);
        return new FlywayMigrator();
    }

    @Bean
    public static BeanFactoryPostProcessor jpaDependsOnFlywayPostProcessor() {
        return beanFactory -> {
            for (String name : beanFactory.getBeanDefinitionNames()) {
                if (name.toLowerCase().contains("entitymanagerfactory")) {
                    BeanDefinition bd = beanFactory.getBeanDefinition(name);
                    String[] existing = bd.getDependsOn();
                    boolean already = existing != null
                        && Arrays.asList(existing).contains("registryFlywayMigrator");
                    if (!already) {
                        String[] updated;
                        if (existing == null) {
                            updated = new String[]{"registryFlywayMigrator"};
                        } else {
                            updated = Arrays.copyOf(existing, existing.length + 1);
                            updated[existing.length] = "registryFlywayMigrator";
                        }
                        bd.setDependsOn(updated);
                    }
                }
            }
        };
    }

    public static class FlywayMigrator {
    }
}
