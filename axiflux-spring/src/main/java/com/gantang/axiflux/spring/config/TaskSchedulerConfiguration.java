package com.gantang.axiflux.spring.config;

import com.gantang.reaxon.api.scheduler.CronExpressionParser;
import com.gantang.reaxon.api.scheduler.TaskLockProvider;
import com.gantang.reaxon.api.scheduler.TaskScheduler;
import com.gantang.reaxon.impl.scheduler.DefaultTaskScheduler;
import com.gantang.reaxon.impl.scheduler.LockableTaskScheduler;
import com.gantang.reaxon.impl.scheduler.SimpleCron;
import com.gantang.axiflux.spring.scheduler.ShedLockTaskLockProvider;
import com.gantang.axiflux.spring.event.ScheduledTaskEventListener;
import com.gantang.axiflux.spring.service.ScheduledTaskService;
import com.gantang.axiflux.storage.repository.ScheduledTaskRepository;
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
 * Auto-configuration for the Axiflux {@link TaskScheduler}.
 *
 * <p>Creates an in-process {@link DefaultTaskScheduler}, then optionally
 * decorates it with {@link LockableTaskScheduler} when a ShedLock
 * {@link LockProvider} is available (e.g. Redis is configured).
 *
 * <p>Design pattern: <b>Decorator</b> — distributed locking is layered on top
 * of the base scheduler without modifying its code.
 */
@Configuration
@ConditionalOnProperty(name = "axiflux.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TaskSchedulerConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(TaskScheduler.class)
    public TaskScheduler AxifluxTaskScheduler(ObjectProvider<LockProvider> lockProvider) {
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
            ObjectProvider<com.gantang.reaxon.api.session.SessionManager> sessionProvider) {
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
    public CronExpressionParser AxifluxCronExpressionParser() {
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
     * The single consumer of {@link com.gantang.axiflux.spring.event.ScheduledTaskFiredEvent}.
     * Must be an explicit bean: this module is a Spring Boot starter and is not
     * covered by the application's component scan, so {@code @Component} would
     * leave scheduled tasks firing into the void (runCount/nextRun updated but
     * agentTurn/systemEvent never dispatched).
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduledTaskEventListener scheduledTaskEventListener(
            ObjectProvider<com.gantang.reaxon.api.agent.Agent> agentProvider,
            ObjectProvider<com.gantang.reaxon.api.session.SessionManager> sessionProvider,
            ObjectProvider<ObjectMapper> omProvider) {
        return new ScheduledTaskEventListener(agentProvider, sessionProvider, omProvider.getIfAvailable());
    }
}
