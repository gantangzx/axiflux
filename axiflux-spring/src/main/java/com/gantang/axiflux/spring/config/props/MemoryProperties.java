package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code axiflux.memory.*}. Split out of the monolithic {@code AxifluxProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "axiflux.memory")


// ===== Scheduler =====

public class MemoryProperties {
    /** Automatically extract and persist durable memories after successful turns. */
    private boolean autoCapture = true;
    /** Minimum importance (1-10) for an extracted memory to be persisted. */
    private int minImportance = 4;
    /** Max characters of turn text fed to the extraction LLM. */
    private int maxInputChars = 6000;
    /** Cosine similarity (0-1) at/above which an extracted memory is treated as a duplicate. */
    private double dedupSimilarity = 0.62;
    /** Nightly maintenance: summarize high-importance memories (ShedLock-guarded, one instance). */
    private boolean maintenanceEnabled = true;
    /** Cron for memory maintenance (Spring 6-field); set to "-" to disable. */
    private String maintenanceCron = "0 30 3 * * *";
    public boolean isAutoCapture() { return autoCapture; }
    public void setAutoCapture(boolean autoCapture) { this.autoCapture = autoCapture; }
    public int getMinImportance() { return minImportance; }
    public void setMinImportance(int minImportance) { this.minImportance = minImportance; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    public double getDedupSimilarity() { return dedupSimilarity; }
    public void setDedupSimilarity(double dedupSimilarity) { this.dedupSimilarity = dedupSimilarity; }
    public boolean isMaintenanceEnabled() { return maintenanceEnabled; }
    public void setMaintenanceEnabled(boolean maintenanceEnabled) { this.maintenanceEnabled = maintenanceEnabled; }
    public String getMaintenanceCron() { return maintenanceCron; }
    public void setMaintenanceCron(String maintenanceCron) { this.maintenanceCron = maintenanceCron; }
}
