package com.gantang.axiflux.spring.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 documentation auto-configuration.
 *
 * <p>Activated when:
 * <ul>
 *   <li>springdoc-openapi is on the classpath, and</li>
 *   <li>{@code axiflux.api-docs.enabled=true} (default: true)</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code /v3/api-docs} — OpenAPI JSON spec</li>
 *   <li>{@code /swagger-ui.html} — Swagger UI</li>
 * </ul>
 */
@Configuration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnProperty(name = "axiflux.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    @Bean
    public OpenAPI AxifluxOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Axiflux Java API")
                .description("AI Agent framework — REST API for chat, sessions, tools, memory, skills, scheduling, MCP, and approvals.")
                .version("0.1.0")
                .contact(new Contact()
                    .name("Axiflux")
                    .url("https://github.com/gantang/axiflux/Axiflux"))
                .license(new License()
                    .name("MIT")
                    .url("https://opensource.org/licenses/MIT")));
    }
}
