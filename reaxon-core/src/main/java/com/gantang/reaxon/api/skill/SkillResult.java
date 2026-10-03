package com.gantang.reaxon.api.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a skill execution.
 *
 * <p>Uses Builder pattern for flexible construction with optional fields
 * such as intermediate steps and metadata.
 */
public record SkillResult(
    String skillName,
    boolean success,
    String output,
    String error,
    int stepsExecuted,
    List<StepResult> steps,
    Instant startedAt,
    Instant completedAt,
    Map<String, Object> metadata
) {
    /** Per-step execution record */
    public record StepResult(
        int index,
        String stepName,
        String type,          // "tool" | "llm" | "sub_skill" | "condition"
        boolean success,
        String output,
        String error,
        long durationMs
    ) {}

    public long durationMs() {
        if (startedAt == null || completedAt == null) return 0L;
        return java.time.Duration.between(startedAt, completedAt).toMillis();
    }

    public static Builder builder(String skillName) {
        return new Builder(skillName);
    }

    /** Convenience for a trivial success result */
    public static SkillResult ok(String skillName, String output) {
        return builder(skillName).success(true).output(output).build();
    }

    /** Convenience for a trivial failure result */
    public static SkillResult fail(String skillName, String error) {
        return builder(skillName).success(false).error(error).build();
    }

    public static final class Builder {
        private final String skillName;
        private boolean success = true;
        private String output = "";
        private String error;
        private int stepsExecuted;
        private final List<StepResult> steps = new ArrayList<>();
        private Instant startedAt = Instant.now();
        private Instant completedAt;
        private final java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();

        private Builder(String skillName) {
            this.skillName = skillName;
        }

        public Builder success(boolean v)          { this.success = v; return this; }
        public Builder output(String v)            { this.output = v; return this; }
        public Builder error(String v)             { this.error = v; if (v != null) this.success = false; return this; }
        public Builder stepsExecuted(int v)        { this.stepsExecuted = v; return this; }
        public Builder startedAt(Instant v)        { this.startedAt = v; return this; }
        public Builder completedAt(Instant v)      { this.completedAt = v; return this; }
        public Builder addStep(StepResult step) {
            this.steps.add(step);
            this.stepsExecuted = this.steps.size();
            return this;
        }
        public Builder metadata(String k, Object v) { this.metadata.put(k, v); return this; }

        public SkillResult build() {
            Instant end = completedAt != null ? completedAt : Instant.now();
            return new SkillResult(
                skillName, success, output, error, stepsExecuted,
                Collections.unmodifiableList(new ArrayList<>(steps)),
                startedAt, end,
                Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata))
            );
        }
    }
}
