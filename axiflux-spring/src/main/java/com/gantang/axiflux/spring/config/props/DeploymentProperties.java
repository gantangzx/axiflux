package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code axiflux.deployment.*}.
 *
 * <p>Distinguishes the two shapes a {@code prod} deployment can take, because the
 * Spring profile alone cannot: both the online multi-tenant SaaS and a single-tenant
 * on-prem install run with {@code SPRING_PROFILES_ACTIVE=prod}.
 *
 * <ul>
 *   <li>{@code mode=standalone} (default) — single-tenant on-prem / private deploy;
 *       no per-user workspace isolation required (existing behaviour, zero impact).</li>
 *   <li>{@code mode=saas} — online multi-tenant; the startup guard requires
 *       {@code TenantWorkspaces} to be enabled so file/git/exec tools cannot fall
 *       back to one shared global root.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "axiflux.deployment")
public class DeploymentProperties {

    public enum Mode {
        /** Single-tenant on-prem / private deployment; isolation not enforced. */
        STANDALONE,
        /** Online multi-tenant SaaS; per-user workspaces are mandatory. */
        SAAS
    }

    /** Deployment shape. Defaults to standalone so existing private installs are unaffected. */
    private Mode mode = Mode.STANDALONE;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isSaas() {
        return mode == Mode.SAAS;
    }
}
