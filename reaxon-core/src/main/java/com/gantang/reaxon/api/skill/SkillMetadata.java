package com.gantang.reaxon.api.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata parsed from SKILL.md YAML front-matter.
 *
 * <p>Uses Builder pattern to allow flexible optional field configuration.
 */
public record SkillMetadata(
    String name,
    String description,
    String version,
    String author,
    List<String> triggers,
    List<String> requiredTools,
    List<String> requiredSkills,
    boolean sandboxed,
    String executionMode,
    List<StepDefinition> steps,
    Map<String, String> env
) {
    public static final String MODE_SEQUENTIAL = "sequential";
    public static final String MODE_PARALLEL   = "parallel";
    public static final String MODE_LLM_GUIDED = "llm_guided";

    /**
     * A step definition for structured (sequential/parallel) execution.
     * Absent in llm_guided mode where the LLM plans the steps at runtime.
     */
    public record StepDefinition(
        String name,
        String type,               // "tool" | "llm" | "sub_skill" | "condition"
        String target,             // tool name / sub-skill name / condition expr
        Map<String, Object> params,
        String condition,          // AviatorScript boolean expr, optional
        String outputVar,          // context variable to capture output
        int timeoutSeconds
    ) {}

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private String description = "";
        private String version = "0.0.1";
        private String author = "";
        private List<String> triggers = new ArrayList<>();
        private List<String> requiredTools = new ArrayList<>();
        private List<String> requiredSkills = new ArrayList<>();
        private boolean sandboxed = false;
        private String executionMode = MODE_SEQUENTIAL;
        private List<StepDefinition> steps = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();

        private Builder(String name) { this.name = name; }

        public Builder description(String v)   { this.description = v; return this; }
        public Builder version(String v)       { this.version = v; return this; }
        public Builder author(String v)        { this.author = v; return this; }
        public Builder triggers(List<String> v){ this.triggers = v != null ? v : new ArrayList<>(); return this; }
        public Builder requiredTools(List<String> v)  { this.requiredTools  = v != null ? v : new ArrayList<>(); return this; }
        public Builder requiredSkills(List<String> v) { this.requiredSkills = v != null ? v : new ArrayList<>(); return this; }
        public Builder sandboxed(boolean v)    { this.sandboxed = v; return this; }
        public Builder executionMode(String v) { this.executionMode = v != null ? v : MODE_SEQUENTIAL; return this; }
        public Builder steps(List<StepDefinition> v) { this.steps = v != null ? v : new ArrayList<>(); return this; }
        public Builder env(Map<String, String> v)    { this.env = v != null ? v : new LinkedHashMap<>(); return this; }

        public SkillMetadata build() {
            return new SkillMetadata(
                name, description, version, author,
                Collections.unmodifiableList(new ArrayList<>(triggers)),
                Collections.unmodifiableList(new ArrayList<>(requiredTools)),
                Collections.unmodifiableList(new ArrayList<>(requiredSkills)),
                sandboxed, executionMode,
                Collections.unmodifiableList(new ArrayList<>(steps)),
                Collections.unmodifiableMap(new LinkedHashMap<>(env))
            );
        }
    }
}
