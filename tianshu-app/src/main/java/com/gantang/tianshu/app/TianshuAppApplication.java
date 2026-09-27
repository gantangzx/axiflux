package com.gantang.tianshu.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.Map;

/**
 * Minimal Tianshu application (the deployable app module).
 *
 * <p>Boots without any external service by default: in-memory sessions and a
 * no-op long-term memory. To enable real LLM chat, configure an API key via the
 * {@code local} profile (application-local.yml), an {@code OPENAI_API_KEY} /
 * {@code ANTHROPIC_API_KEY} / {@code DEEPSEEK_API_KEY} / {@code ARK_API_KEY}
 * environment variable, or {@code tianshu.llm.*.api-key} properties.
 *
 * <p>PostgreSQL (JPA sessions + Flyway migrations) and Redis (ShedLock
 * distributed locks) are enabled through {@code spring.datasource.*},
 * {@code spring.data.redis.*}, and {@code tianshu.session.provider=jpa} in
 * application.yml / application-local.yml. With none configured the app falls
 * back to in-memory sessions.
 */
@SpringBootApplication
public class TianshuAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(TianshuAppApplication.class, args);
    }

    @Bean
    public CommandLineRunner runExample(Agent agent, ModelRouter router,
                                        @Value("${tianshu.app.run-demo:false}") boolean runDemo) {
        return args -> {
            System.out.println("=== Tianshu App ===");
            var providers = router.listProviders();
            if (providers.isEmpty()) {
                System.out.println("No LLM provider is registered. The server is running but chat will fail.");
                System.out.println("Put your key in application-local.yml (tianshu.llm.openai.api-key /");
                System.out.println("anthropic.api-key), or set ARK_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY /");
                System.out.println("DEEPSEEK_API_KEY, then restart.");
                return;
            }
            System.out.println("Registered LLM providers: " + providers);

            if (!runDemo) {
                System.out.println("Tianshu is ready. Open the console at http://localhost:" + serverPort() + "/");
                System.out.println("(Set tianshu.app.run-demo=true to run the startup streaming demo.)");
                return;
            }

            AgentContext ctx = AgentContext.builder()
                .sessionId("example-session")
                .userId("example-user")
                .currentQuery("What is 1+1? Then use the http_client tool to GET https://httpbin.org/get")
                .systemPrompt("You are a helpful AI assistant.")
                .build();

            agent.processStream(ctx)
                .doOnNext(evt -> {
                    String prefix = switch (evt.type()) {
                        case THINKING_TOKEN    -> "[THINKING] ";
                        case TEXT_TOKEN        -> "[TEXT] ";
                        case TOOL_CALL         -> "[TOOL_CALL] " + evt.toolName() + " ";
                        case TOOL_RESULT       -> "[TOOL_RESULT] ";
                        case APPROVAL_REQUIRED -> "[APPROVAL] ";
                        case DONE              -> "[DONE] ";
                        case ERROR             -> "[ERROR] ";
                    };
                    System.out.println(prefix + evt.content());
                })
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast(Duration.ofMinutes(2));

            System.out.println("=== Example Complete ===");
        };
    }

    private static String serverPort() {
        String p = System.getProperty("server.port");
        if (p == null || p.isBlank()) p = System.getenv("SERVER_PORT");
        return (p == null || p.isBlank()) ? "8080" : p;
    }
}
