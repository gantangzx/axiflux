package com.gantang.axiflux.storage.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * JPA entity for the {@code runtime_config} table — a flat key/value store of
 * hot-tunable configuration overrides.
 *
 * <p>Rows written by the runtime config API are read back at startup and applied
 * over {@code application.yml}, so live-tuned settings (default model provider,
 * agent iteration cap, …) survive restarts.
 */
@Entity
@Table(name = "runtime_config")
public class RuntimeConfigEntity {

    @Id
    @Column(name = "config_key", length = 128)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "text")
    private String configValue;

    @Column(name = "value_type", length = 16, nullable = false)
    private String valueType = "string";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    public RuntimeConfigEntity() {}

    public RuntimeConfigEntity(String configKey, String configValue,
                               String valueType, String updatedBy) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.valueType = valueType != null ? valueType : "string";
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
