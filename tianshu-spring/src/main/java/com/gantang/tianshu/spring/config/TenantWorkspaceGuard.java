package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.tool.Workspaces;
import com.gantang.tianshu.spring.config.props.DeploymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Startup guard for online multi-tenant (SaaS) deployments (M2-4).
 *
 * <p>When {@code tianshu.deployment.mode=saas}, per-user workspace isolation is
 * mandatory. The {@link Workspaces} bean is only created when
 * {@code tianshu.tools.workspaces-enabled=true}; if it is missing, file/git/exec
 * tools silently fall back to a single shared global root, which is a cross-tenant
 * data-leak hazard. This guard fails fast <b>before the server starts serving
 * traffic</b> rather than allowing that silent fallback.
 *
 * <p>{@code standalone} (the default) is the single-tenant on-prem shape and is
 * intentionally left untouched so existing private deployments need no changes.
 */
public class TenantWorkspaceGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TenantWorkspaceGuard.class);

    private final DeploymentProperties deployment;
    private final ObjectProvider<Workspaces> workspaces;

    public TenantWorkspaceGuard(DeploymentProperties deployment,
                                ObjectProvider<Workspaces> workspaces) {
        this.deployment = deployment;
        this.workspaces = workspaces;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!deployment.isSaas()) {
            log.debug("Deployment mode={}; tenant workspace isolation not required",
                deployment.getMode());
            return;
        }

        Workspaces ws = workspaces.getIfAvailable();
        if (ws == null) {
            throw new IllegalStateException(
                "tianshu.deployment.mode=saas requires per-user workspace isolation, but "
                + "TenantWorkspaces is not enabled. Set tianshu.tools.workspaces-enabled=true "
                + "(and configure tianshu.tools.workspaces-root). Refusing to start in SaaS mode "
                + "with a shared global tool root. For a single-tenant on-prem install set "
                + "tianshu.deployment.mode=standalone.");
        }
        log.info("SaaS mode: tenant workspace isolation active at {}", ws.baseRoot());
    }
}
