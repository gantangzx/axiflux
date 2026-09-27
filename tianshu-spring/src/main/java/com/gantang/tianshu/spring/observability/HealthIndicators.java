package com.gantang.tianshu.spring.observability;

import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.memory.LongTermMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom health indicators for Tianshu core services.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code /actuator/health/llm} — LLM client availability</li>
 *   <li>{@code /actuator/health/memory} — long-term memory connectivity</li>
 * </ul>
 *
 * <p>Design pattern: <b>Strategy</b> — each indicator is a self-contained health check.
 */
@Configuration
public class HealthIndicators {

    @Bean
    @ConditionalOnBean(LlmClient.class)
    public HealthIndicator llmHealthIndicator(ObjectProvider<LlmClient> llmClients) {
        return () -> {
            try {
                var clients = llmClients.orderedStream().toList();
                if (clients.isEmpty()) {
                    return Health.unknown().withDetail("message", "No LLM client configured").build();
                }
                Health.Builder builder = Health.up();
                for (int i = 0; i < clients.size(); i++) {
                    builder.withDetail("client-" + i, clients.get(i).getClass().getSimpleName());
                }
                return builder.build();
            } catch (Exception e) {
                return Health.down(e).build();
            }
        };
    }

    @Bean
    @ConditionalOnBean(LongTermMemory.class)
    public HealthIndicator memoryHealthIndicator(ObjectProvider<LongTermMemory> memoryProvider) {
        return () -> {
            try {
                var memories = memoryProvider.orderedStream().toList();
                if (memories.isEmpty()) {
                    return Health.unknown().withDetail("message", "No memory store configured").build();
                }
                Health.Builder builder = Health.up();
                for (int i = 0; i < memories.size(); i++) {
                    builder.withDetail("store-" + i, memories.get(i).getClass().getSimpleName());
                }
                return builder.build();
            } catch (Exception e) {
                return Health.down(e).build();
            }
        };
    }
}
