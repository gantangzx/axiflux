package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code axiflux.scheduler.*}. Split out of the monolithic {@code AxifluxProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "axiflux.scheduler")


// ===== Scheduler =====

public class SchedulerProperties {
    private boolean enabled = true;
    private int poolSize = 10;
    private String lockProvider = "redis";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    public String getLockProvider() { return lockProvider; }
    public void setLockProvider(String lockProvider) { this.lockProvider = lockProvider; }
}
