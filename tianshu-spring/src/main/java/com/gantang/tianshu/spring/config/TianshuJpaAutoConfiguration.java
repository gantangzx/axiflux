package com.gantang.tianshu.spring.config;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires JPA for the tianshu-storage module when the starter is consumed by a
 * downstream application.
 *
 * <p>The storage packages ({@code com.gantang.tianshu.storage.entity} and
 * {@code com.gantang.tianshu.storage.repository}) live outside the consumer's base
 * package, so component scanning alone never finds them. Previously every
 * application had to copy a {@code @EntityScan/@EnableJpaRepositories} config
 * class (as the examples module did); without it the JPA-backed SessionManager
 * and repositories silently did not exist and the app fell back to in-memory
 * sessions. This auto-configuration makes JPA work out of the box.
 *
 * <p>Activates only when JPA is on the classpath AND a JDBC URL is configured,
 * preserving zero-dependency startup otherwise. Ordered before
 * {@link TianshuAutoConfiguration} so its {@code @ConditionalOnBean} checks for
 * the storage repositories evaluate positively.
 */
@AutoConfiguration(before = TianshuAutoConfiguration.class)
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(name = "spring.datasource.url")
@EntityScan("com.gantang.tianshu.storage.entity")
@EnableJpaRepositories(basePackages = "com.gantang.tianshu.storage.repository")
@EnableTransactionManagement
public class TianshuJpaAutoConfiguration {
}
