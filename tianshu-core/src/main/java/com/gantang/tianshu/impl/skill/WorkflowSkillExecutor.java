package com.gantang.tianshu.impl.skill;

import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.*;
import com.gantang.tianshu.impl.skill.strategy.AbstractWorkflowStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composite {@link SkillExecutor} that wires:
 *
 * <ul>
 *   <li>{@link SkillRegistry} — Registry pattern</li>
 *   <li>{@link SkillLoaderFactory} — Factory pattern</li>
 *   <li>{@link WorkflowStrategy}s — Strategy pattern (one per execution mode)</li>
 *   <li>{@link SkillInterceptor}s — Chain of Responsibility around each execute</li>
 *   <li>{@link SkillEventListener}s — Observer pattern</li>
 * </ul>
 *
 * <p>Trigger matching is delegated to the registry; execution is delegated to
 * the strategy chosen from the skill's {@code executionMode}.
 */
public final class WorkflowSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSkillExecutor.class);

    private final SkillRegistry registry;
    private final SkillLoaderFactory loaderFactory;
    private final Map<String, WorkflowStrategy> strategies = new LinkedHashMap<>();
    private final List<SkillInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private final List<SkillEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Agent agent;  // optional; injected by Spring for LlmGuidedStrategy

    /** Root directory last loaded from; reused by {@link #reloadSkills()}. */
    private volatile String skillsRoot;
    /** Content signatures (SHA-256 of SKILL.md) of directory skills, for change detection. */
    private final Map<String, String> signatures = new ConcurrentHashMap<>();

    /**
     * Optional gate supplying the set of disabled skill names. Evaluated once per
     * {@link #reloadSkills()} (single snapshot). Disabled skills are loaded from
     * disk for change tracking but never registered (and unregistered immediately
     * if a previous load had registered them). Backed by the install ledger.
     * {@code null} means all skills enabled.
     */
    private volatile java.util.function.Supplier<Set<String>> disabledNamesSupplier;

    /** Set the disabled-names gate (null disables the gate). */
    @Override
    public void setDisabledNamesSupplier(java.util.function.Supplier<Set<String>> supplier) {
        this.disabledNamesSupplier = supplier;
    }

    private boolean isDisabled(String name, Set<String> disabledSnapshot) {
        return disabledSnapshot != null && disabledSnapshot.contains(name);
    }

    public WorkflowSkillExecutor(SkillRegistry registry,
                                 SkillLoaderFactory loaderFactory,
                                 List<WorkflowStrategy> strategies) {
        this.registry = Objects.requireNonNull(registry);
        this.loaderFactory = Objects.requireNonNull(loaderFactory);
        Objects.requireNonNull(strategies);
        for (WorkflowStrategy s : strategies) this.strategies.put(s.mode(), s);
    }

    @Override
    public Mono<SkillResult> execute(Skill skill, String input, AgentContext context) {
        publish(SkillEvent.of(SkillEvent.Type.SKILL_STARTED, skill.name(),
            context.sessionId(), input));
        SkillInterceptor.Chain chain = buildChain(0);
        return chain.proceed(skill, input, context)
            .doOnNext(r -> publish(SkillEvent.of(
                r.success() ? SkillEvent.Type.SKILL_COMPLETED : SkillEvent.Type.SKILL_FAILED,
                skill.name(), context.sessionId(), r)))
            .doOnError(e -> publish(SkillEvent.of(SkillEvent.Type.SKILL_FAILED,
                skill.name(), context.sessionId(), e.getMessage())));
    }

    @Override
    public Mono<SkillResult> executeMatching(String query, AgentContext context) {
        List<Skill> matched = registry.findByTrigger(query);
        if (matched.isEmpty()) {
            return Mono.just(SkillResult.fail("(none)", "no skill matched query: " + query));
        }
        Skill chosen = matched.get(0);
        publish(SkillEvent.of(SkillEvent.Type.SKILL_MATCHED, chosen.name(),
            context.sessionId(), query));
        return execute(chosen, query, context);
    }

    @Override
    public boolean matchesTrigger(Skill skill, String query) {
        if (skill == null || query == null) return false;
        String q = query.toLowerCase(Locale.ROOT);
        for (String t : skill.triggers()) {
            if (t != null && !t.isBlank() && q.contains(t.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @Override
    public synchronized void loadFromDirectory(String rootDir) {
        if (rootDir == null || rootDir.isBlank()) return;
        this.skillsRoot = rootDir;
        SkillReloadResult r = reloadSkills();
        log.info("Loaded {} skill(s) from {} (+{} ~{} -{})",
            r.total(), rootDir, r.added().size(), r.updated().size(), r.removed().size());
    }

    /**
     * Re-scan {@link #skillsRoot} and reconcile the registry: register new/changed
     * skills, unregister ones that vanished from disk. Safe to call concurrently
     * with registry reads; the registry is a concurrent map and register/unregister
     * are atomic per skill.
     */
    @Override
    public synchronized SkillReloadResult reloadSkills() {
        String rootDir = this.skillsRoot;
        if (rootDir == null || rootDir.isBlank()) {
            return SkillReloadResult.empty(registry.size());
        }
        Path root = Path.of(rootDir);
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        try {
            List<Skill> loaded = loaderFactory.loadAll(root);
            Set<String> onDisk = new LinkedHashSet<>();
            java.util.function.Supplier<Set<String>> gate = disabledNamesSupplier;
            Set<String> disabledSnapshot = gate != null ? gate.get() : Set.of();
            for (Skill s : loaded) {
                onDisk.add(s.name());
                String sig = sha256(s.readContent());
                String prev = signatures.get(s.name());
                boolean disabled = isDisabled(s.name(), disabledSnapshot);
                if (prev == null) {
                    added.add(s.name());
                } else if (!prev.equals(sig)) {
                    updated.add(s.name());
                }
                // Disabled skills: keep signature (enable picks up changes) but never register.
                // Re-enabling must re-register even when content is unchanged (the previous
                // reload had unregistered it), so treat "not currently in registry" as a change.
                if (disabled) {
                    if (registry.get(s.name()).isPresent()) registry.unregister(s.name());
                } else if (prev == null || !prev.equals(sig) || registry.get(s.name()).isEmpty()) {
                    registry.register(s);  // put() — overwrites previous version atomically
                }
                signatures.put(s.name(), sig);
            }
            for (String old : new ArrayList<>(signatures.keySet())) {
                if (!onDisk.contains(old)) {
                    registry.unregister(old);
                    signatures.remove(old);
                    removed.add(old);
                }
            }
            log.info("Skills reloaded from {}: +{} ~{} -{} (total {})",
                rootDir, added.size(), updated.size(), removed.size(), registry.size());
            return new SkillReloadResult(List.copyOf(added), List.copyOf(updated),
                List.copyOf(removed), registry.size());
        } catch (IOException e) {
            log.warn("Failed to reload skills from {}: {}", rootDir, e.getMessage());
            throw new java.io.UncheckedIOException(
                new IOException("reload skills from " + rootDir + " failed: " + e.getMessage(), e));
        }
    }

    private static String sha256(String s) {
        if (s == null) return "";
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @Override
    public void addListener(SkillEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void addInterceptor(SkillInterceptor interceptor) {
        if (interceptor == null) return;
        interceptors.add(interceptor);
        interceptors.sort(Comparator.comparingInt(SkillInterceptor::order));
    }

    /** Register or replace a workflow strategy by its {@link WorkflowStrategy#mode()}. */
    public void registerStrategy(WorkflowStrategy strategy) {
        if (strategy == null) return;
        strategies.put(strategy.mode(), strategy);
    }

    // === Chain of Responsibility construction ===

    private SkillInterceptor.Chain buildChain(int index) {
        return (skill, input, context) -> {
            if (index >= interceptors.size()) {
                return dispatchToStrategy(skill, input, context);
            }
            SkillInterceptor next = interceptors.get(index);
            return next.intercept(skill, input, context, buildChain(index + 1));
        };
    }

    private Mono<SkillResult> dispatchToStrategy(Skill skill, String input, AgentContext context) {
        String mode = skill.metadata().executionMode();
        WorkflowStrategy strategy = strategies.getOrDefault(mode,
            strategies.get(SkillMetadata.MODE_SEQUENTIAL));
        if (strategy == null) {
            return Mono.just(SkillResult.fail(skill.name(),
                "no workflow strategy registered for mode: " + mode));
        }
        // Inject Agent reference into the chosen strategy if it extends AbstractWorkflowStrategy
        // so LlmGuidedWorkflowStrategy can delegate to the tool loop.
        if (strategy instanceof AbstractWorkflowStrategy aws && agent != null) {
            aws.setAgent(agent);
        }
        return strategy.execute(skill, input, context);
    }

    private void publish(SkillEvent event) {
        for (SkillEventListener l : listeners) {
            try { l.onEvent(event); }
            catch (Exception e) { log.warn("Listener error for event {}: {}", event.type(), e.getMessage()); }
        }
    }

    public SkillRegistry registry() { return registry; }
    public Map<String, WorkflowStrategy> strategies() { return Collections.unmodifiableMap(strategies); }
    public List<SkillInterceptor> interceptors() { return Collections.unmodifiableList(interceptors); }

    /**
     * Optional injection of the {@link Agent} used by {@link AbstractWorkflowStrategy}
     * subclasses (notably LlmGuidedWorkflowStrategy) to re-enter the tool call loop
     * from within a skill.
     */
    public void setAgent(Agent agent) { this.agent = agent; }
}
