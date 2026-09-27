package com.gantang.tianshu.api.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide, thread-safe holder for the subset of configuration that is safe
 * to change at <b>runtime</b> (hot-reload from the operations console) without
 * restarting the service.
 *
 * <p>Design:
 * <ul>
 *   <li>Immutable {@link Snapshot} swapped atomically via {@link AtomicReference},
 *       so readers (policy chain, agent loop, built-in tools) always observe a
 *       consistent set of values and never a half-written state.</li>
 *   <li>Zero Spring dependency: lives in {@code tianshu-core}; the Spring layer
 *       seeds it from the {@code tianshu.*} configuration properties at boot
 *       and mutates it when an operator applies a change through
 *       {@code /api/v1/config}.</li>
 *   <li>Infrastructure knobs (datasource, Redis, vector store, auth, code
 *       sandbox, provider credentials) are intentionally NOT here — those bind
 *       to connection pools / security filter chains / conditional beans at
 *       startup and require a restart.</li>
 * </ul>
 */
public final class LiveSettings {

    /** Immutable point-in-time view of all hot-reloadable settings. */
    public record Snapshot(
            int maxIterations,
            long toolTimeoutSeconds,
            String toolPolicyMode,                 // all | whitelist | blacklist
            Set<String> allowedTools,
            Set<String> blockedTools,
            Set<String> autoApproveTools,
            boolean allowPrivateNetwork,
            List<Path> fileAllowedRoots,
            boolean budgetEnabled,
            String budgetRiskCeiling,             // SAFE | READ | NETWORK | WRITE
            int budgetDefault,
            Map<String, Integer> toolBudgets
    ) {
        public Snapshot {
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            blockedTools = blockedTools == null ? Set.of() : Set.copyOf(blockedTools);
            autoApproveTools = autoApproveTools == null ? Set.of() : Set.copyOf(autoApproveTools);
            fileAllowedRoots = fileAllowedRoots == null ? List.of() : List.copyOf(fileAllowedRoots);
            toolBudgets = toolBudgets == null ? Map.of() : Map.copyOf(toolBudgets);
            if (toolPolicyMode == null) toolPolicyMode = "all";
            if (budgetRiskCeiling == null) budgetRiskCeiling = "WRITE";
        }
    }

    private final AtomicReference<Snapshot> ref;

    public LiveSettings(Snapshot initial) {
        this.ref = new AtomicReference<>(initial == null ? builder().build() : initial);
    }

    public Snapshot snapshot() {
        return ref.get();
    }

    /** Replace the whole snapshot atomically. */
    public void replace(Snapshot next) {
        ref.set(next == null ? builder().build() : next);
    }

    /** Atomically derive a new snapshot from the current one. */
    public Snapshot update(java.util.function.UnaryOperator<Snapshot> fn) {
        return ref.updateAndGet(cur -> fn.apply(cur == null ? builder().build() : cur));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for seeding (from application.yml) and for partial updates. */
    public static final class Builder {
        private int maxIterations = 25;
        private long toolTimeoutSeconds = 30;
        private String toolPolicyMode = "all";
        private Set<String> allowedTools = Set.of();
        private Set<String> blockedTools = Set.of();
        private Set<String> autoApproveTools = Set.of();
        private boolean allowPrivateNetwork = false;
        private List<Path> fileAllowedRoots = List.of();
        private boolean budgetEnabled = false;
        private String budgetRiskCeiling = "WRITE";
        private int budgetDefault = 3;
        private Map<String, Integer> toolBudgets = Map.of();

        public Builder maxIterations(int v) { this.maxIterations = Math.clamp(v, 1, 100); return this; }
        public Builder toolTimeoutSeconds(long v) { this.toolTimeoutSeconds = Math.max(1, v); return this; }
        public Builder toolPolicyMode(String v) { this.toolPolicyMode = v; return this; }
        public Builder allowedTools(Set<String> v) { this.allowedTools = v; return this; }
        public Builder blockedTools(Set<String> v) { this.blockedTools = v; return this; }
        public Builder autoApproveTools(Set<String> v) { this.autoApproveTools = v; return this; }
        public Builder allowPrivateNetwork(boolean v) { this.allowPrivateNetwork = v; return this; }
        public Builder fileAllowedRoots(List<Path> v) { this.fileAllowedRoots = v; return this; }
        public Builder budgetEnabled(boolean v) { this.budgetEnabled = v; return this; }
        public Builder budgetRiskCeiling(String v) { this.budgetRiskCeiling = v; return this; }
        public Builder budgetDefault(int v) { this.budgetDefault = Math.max(0, v); return this; }
        public Builder toolBudgets(Map<String, Integer> v) { this.toolBudgets = v; return this; }

        public Snapshot build() {
            return new Snapshot(maxIterations, toolTimeoutSeconds, toolPolicyMode,
                allowedTools, blockedTools, autoApproveTools, allowPrivateNetwork,
                fileAllowedRoots, budgetEnabled, budgetRiskCeiling, budgetDefault, toolBudgets);
        }
    }
}
