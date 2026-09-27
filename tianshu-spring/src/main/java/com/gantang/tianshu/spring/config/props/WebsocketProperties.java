package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.websocket.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.websocket")


// ===== WebSocket =====

public class WebsocketProperties {
    /** Expose the agent WebSocket endpoint (/ws/agent). Default enabled. */
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
