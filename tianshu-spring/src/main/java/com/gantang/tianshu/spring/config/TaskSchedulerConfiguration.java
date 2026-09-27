package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.scheduler.CronExpressionParser;
import com.gantang.tianshu.api.scheduler.TaskLockProvider;
import com.gantang.tianshu.api.scheduler.TaskScheduler;
import com.gantang.tianshu.impl.scheduler.DefaultTaskScheduler;
import com.gantang.tianshu.impl.scheduler.LockableTaskScheduler;
import com.gantang.tianshu.impl.scheduler.SimpleCron;
import com.gantang.tianshu.spring.scheduler.ShedLockTaskLockProvider;
import com.gantang.tianshu.spring.event.ScheduledTaskEventListener;
import com.gantang.tianshu.spring.service.ScheduledTaskService;
import com.gantang.tianshu.storage.repository.ScheduledTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the Tianshu {@link TaskScheduler}.
 *
 * <p>Creates an in-process {@link DefaultTaskScheduler}, then optionally
 * decorates it with {@link LockableTaskScheduler} when a ShedLock
 * {@link LockProvider} is available (e.g. Redis is configured).
 *
 * <p>Design pattern: <b>Decorator</b> — distributed locking is layered on top
 * of the base scheduler without modifying its code.
 */
@Configuration
@ConditionalOnProperty(name = "tianshu.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TaskSchedulerConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(TaskScheduler.class)
    public TaskScheduler tianshuTaskScheduler(ObjectProvider<LockProvider> lockProvider) {
        DefaultTaskScheduler base = new DefaultTaskScheduler();

        // Wrap with distributed lock if ShedLock is available (e.g. Redis)
        LockProvider shedLock = lockProvider.getIfAvailable();
        if (shedLock != null) {
            TaskLockProvider taskLock = new ShedLockTaskLockProvider(shedLock);
            return new LockableTaskScheduler(base, taskLock);
        }

        return base;
    }

    @Bean
    @ConditionalOnBean(ScheduledTaskRepository.class)
    @ConditionalOnMissingBean
    public ScheduledTaskService scheduledTaskService(
            ScheduledTaskRepository repo,
            ApplicationEventPublisher events,
            ObjectMapper om,
            ObjectProvider<TaskScheduler> schedulerProvider,
            CronExpressionParser cronParser,
            org.springframework.transaction.support.TransactionTemplate transactionTemplate,
            ObjectProvider<com.gantang.tianshu.api.session.SessionManager> sessionProvider) {
        return new ScheduledTaskService(repo, events, om, schedulerProvider, cronParser,
            transactionTemplate, sessionProvider);
    }

    /**
     * P1-4: the cron parsing backend. Spring wires {@code SimpleCron} here so the
     * service layer depends on the {@link CronExpressionParser} contract only.
     * Tests / deployments with their own dialect can override this bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public CronExpressionParser tianshuCronExpressionParser() {
        return SimpleCron::parse;
    }

    @Bean
    @ConditionalOnBean({TaskScheduler.class, ScheduledTaskService.class})
    @ConditionalOnMissingBean
    public TaskSchedulerLifecycle taskSchedulerLifecycle(
            TaskScheduler scheduler,
            ScheduledTaskService taskService,
            ApplicationEventPublisher events) {
        return new TaskSchedulerLifecycle(scheduler, taskService, events);
    }

    /**
     * The single consumer of {@link com.gantang.tianshu.spring.event.ScheduledTaskFiredEvent}.
     * Must be an explicit bean: this module is a Spring Boot starter and is not
     * covered by the application's component scan, so {@code @Component} would
     * leave scheduled tasks firing into the void (runCount/nextRun updated but
     * agentTurn/systemEvent never dispatched).
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduledTaskEventListener scheduledTaskEventListener(
            ObjectProvider<com.gantang.tianshu.api.agent.Agent> agentProvider,
            ObjectProvider<com.gantang.tianshu.api.session.SessionManager> sessionProvider,
            ObjectProvider<ObjectMapper> omProvider) {
        return new ScheduledTaskEventListener(agentProvider, sessionProvider, omProvider.getIfAvailable());
    }
}
