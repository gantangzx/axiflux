package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.tool.Workspaces;
import com.gantang.tianshu.spring.config.props.DeploymentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Readiness signal for tenant workspace isolation (M2-4).
 *
 * <p>In {@code saas} mode the component is DOWN if the {@link Workspaces}
 * bean is absent or its base root is missing/not writable — a runtime backstop
 * behind the startup {@link TenantWorkspaceGuard}. In standalone mode it reports
 * UP (isolation is not a requirement).
 */
public class TenantWorkspacesHealthIndicator implements HealthIndicator {

    private final DeploymentProperties deployment;
    private final ObjectProvider<Workspaces> workspaces;

    public TenantWorkspacesHealthIndicator(DeploymentProperties deployment,
                                           ObjectProvider<Workspaces> workspaces) {
        this.deployment = deployment;
        this.workspaces = workspaces;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        builder.withDetail("mode", deployment.getMode().name());
        if (!deployment.isSaas()) {
            return builder.build();
        }

        Workspaces ws = workspaces.getIfAvailable();
        if (ws == null) {
            return Health.down()
                .withDetail("mode", deployment.getMode().name())
                .withDetail("isolated", false)
                .withDetail("reason", "tenant workspaces disabled in SaaS mode")
                .build();
        }

        Path root = ws.baseRoot();
        boolean writable = Files.isDirectory(root) && Files.isWritable(root);
        builder.withDetail("isolated", true)
            .withDetail("root", root.toString())
            .withDetail("writable", writable);
        return writable ? builder.build()
            : Health.down()
                .withDetail("mode", deployment.getMode().name())
                .withDetail("isolated", true)
                .withDetail("root", root.toString())
                .withDetail("reason", "workspace root not writable")
                .build();
    }
}
