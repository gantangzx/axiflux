package com.gantang.tianshu.spring.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import com.gantang.tianshu.spring.config.props.SchedulerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ShedLock distributed scheduler configuration.
 * Uses Redis as the lock provider.
 */
@Configuration
@EnableScheduling
@EnableAsync
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerConfig {

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }

    /**
     * Single-process fallback for standalone profiles without Redis (e.g. the
     * {@code local} "out of the box" mode). Multi-instance deployments use the
     * Redis provider above; this merely keeps {@code @SchedulerLock} working and
     * prevents overlapping runs on one JVM.
     */
    @Bean
    @ConditionalOnMissingBean
    public LockProvider inMemoryLockProvider() {
        return new InMemoryLockProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolTaskScheduler taskScheduler(SchedulerProperties schedulerProps) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(schedulerProps.getPoolSize());
        scheduler.setThreadNamePrefix("tianshu-scheduler-");
        scheduler.setErrorHandler(t ->
            org.slf4j.LoggerFactory.getLogger(SchedulerConfig.class)
                .error("Scheduled task error", t));
        scheduler.initialize();
        return scheduler;
    }
}
