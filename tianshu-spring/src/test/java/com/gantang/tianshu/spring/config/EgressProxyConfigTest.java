package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.spring.config.props.ToolsProperties;
import org.junit.jupiter.api.Test;

import java.net.ProxySelector;

import static org.junit.jupiter.api.Assertions.*;

class EgressProxyConfigTest {

    private ToolsProperties propsWithProxy(String raw) {
        ToolsProperties props = new ToolsProperties();
        props.setEgressProxy(raw);
        return props;
    }

    @Test
    void unconfiguredMeansDirectConnection() {
        assertNull(BuiltInToolsConfiguration.egressProxy(new ToolsProperties()));
        assertNull(BuiltInToolsConfiguration.egressProxy(propsWithProxy("")));
        assertNull(BuiltInToolsConfiguration.egressProxy(propsWithProxy("   ")));
    }

    @Test
    void parsesHostAndPort() {
        ProxySelector selector = BuiltInToolsConfiguration.egressProxy(propsWithProxy("proxy.internal:3128"));
        assertNotNull(selector);
    }

    @Test
    void malformedValuesFailFast() {
        assertThrows(IllegalArgumentException.class,
            () -> BuiltInToolsConfiguration.egressProxy(propsWithProxy("no-port")));
        assertThrows(IllegalArgumentException.class,
            () -> BuiltInToolsConfiguration.egressProxy(propsWithProxy("host:notaport")));
        assertThrows(IllegalArgumentException.class,
            () -> BuiltInToolsConfiguration.egressProxy(propsWithProxy("host:99999")));
    }
}
