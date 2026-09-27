package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.web.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.web")


// ===== Web =====

public class WebProperties {
    private boolean enabled = true;
    /** Interval between SSE keep-alive comment frames for streaming endpoints;
     *  <=0 disables heartbeats. Kept under typical reverse-proxy idle timeouts. */
    private int sseHeartbeatSeconds = 20;
    private HeadersProperties headers = new HeadersProperties();
    private CorsProperties cors = new CorsProperties();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public int getSseHeartbeatSeconds() { return sseHeartbeatSeconds; }
    public void setSseHeartbeatSeconds(int v) { this.sseHeartbeatSeconds = v; }
    public HeadersProperties getHeaders() { return headers; }
    public void setHeaders(HeadersProperties v) { this.headers = v == null ? new HeadersProperties() : v; }
    public CorsProperties getCors() { return cors; }
    public void setCors(CorsProperties v) { this.cors = v == null ? new CorsProperties() : v; }
}
