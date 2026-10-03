package com.gantang.axiflux.spring.config;

import com.gantang.reaxon.api.tool.Workspaces;
import com.gantang.axiflux.spring.config.props.DeploymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantWorkspaceGuardTest {

    private final Workspaces workspaces = mock(Workspaces.class);

    private DeploymentProperties mode(DeploymentProperties.Mode m) {
        DeploymentProperties p = new DeploymentProperties();
        p.setMode(m);
        return p;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<Workspaces> provider(Workspaces value) {
        ObjectProvider<Workspaces> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void saasWithoutWorkspacesFailsFast() {
        TenantWorkspaceGuard guard = new TenantWorkspaceGuard(
            mode(DeploymentProperties.Mode.SAAS), provider(null));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            guard::afterSingletonsInstantiated);
        assert ex.getMessage().contains("saas requires per-user workspace isolation");
    }

    @Test
    void saasWithWorkspacesStarts() {
        TenantWorkspaceGuard guard = new TenantWorkspaceGuard(
            mode(DeploymentProperties.Mode.SAAS), provider(workspaces));

        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    @Test
    void standaloneDoesNotRequireWorkspaces() {
        TenantWorkspaceGuard guard = new TenantWorkspaceGuard(
            mode(DeploymentProperties.Mode.STANDALONE), provider(null));

        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }
}
