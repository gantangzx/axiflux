package com.gantang.axiflux.spring.service;

import com.gantang.axiflux.spring.config.props.RegistryEndpoint;
import com.gantang.axiflux.spring.config.props.SkillsProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The federated set of configured {@link SkillSource}s.
 *
 * <p>Sources come from three places:
 * <ul>
 *   <li>{@code axiflux.skills.registries[]} — axiflux-registry-protocol endpoints
 *       ({@link RegistrySource}); install spec {@code registry:[<name>/]slug[@v]}.</li>
 *   <li>{@code axiflux.skills.clawhub} (enabled by default) — the public ClawHub
 *       marketplace via {@link ClawHubSource}; install spec {@code clawhub:slug[@v]}.</li>
 *   <li>Legacy fallback: a single {@code axiflux.skills.registry-url} synthesized as a
 *       source named {@value #DEFAULT}.</li>
 * </ul>
 *
 * <p>Backward compatibility: install spec {@code registry:slug} (no source prefix)
 * always routes to the default registry (the one explicitly named {@value #DEFAULT},
 * or the first configured registry).
 */
public class SkillSources {

    public static final String DEFAULT = "default";

    private final List<SkillSource> sources;
    private final RegistrySource defaultRegistry;

    private SkillSources(List<SkillSource> sources, RegistrySource defaultRegistry) {
        this.sources = List.copyOf(sources);
        this.defaultRegistry = defaultRegistry;
    }

    public static SkillSources from(SkillsProperties skills) {
        return from(skills, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5) applied to
     *              every constructed source's HTTP client; null = direct.
     */
    public static SkillSources from(SkillsProperties skills, java.net.ProxySelector proxy) {
        List<SkillSource> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (skills != null && skills.getRegistries() != null) {
            for (RegistryEndpoint ep : skills.getRegistries()) {
                if (ep == null || ep.getName() == null || ep.getName().isBlank()
                    || ep.getUrl() == null || ep.getUrl().isBlank()) {
                    continue;
                }
                RegistrySource s = new RegistrySource(ep.getName(), ep.getUrl(), ep.getToken(), ep.getPublicKey(), proxy);
                if (!seen.add(s.name())) {
                    throw new IllegalStateException("重复的技能注册表名称: " + s.name());
                }
                list.add(s);
            }
        }

        // Built-in ClawHub marketplace (its own protocol via adapter).
        if (skills != null && skills.getClawhub() != null && skills.getClawhub().isEnabled()
            && skills.getClawhub().getUrl() != null && !skills.getClawhub().getUrl().isBlank()) {
            ClawHubSource ch = new ClawHubSource(ClawHubSource.NAME,
                skills.getClawhub().getUrl(), skills.getClawhub().getToken(), proxy);
            if (seen.add(ch.name())) {
                list.add(ch);
            }
        }

        List<RegistrySource> registries = list.stream()
            .filter(s -> s instanceof RegistrySource)
            .map(s -> (RegistrySource) s)
            .toList();

        // Legacy single-registry fallback when no registry-protocol endpoint is configured.
        if (registries.isEmpty() && skills != null
            && skills.getRegistryUrl() != null && !skills.getRegistryUrl().isBlank()) {
            RegistrySource def = new RegistrySource(DEFAULT, skills.getRegistryUrl(),
                skills.getRegistryToken(), skills.getRegistryPublicKey(), proxy);
            if (seen.add(def.name())) {
                list.add(def);
            }
            registries = List.of(def);
        }

        RegistrySource defRegistry = registries.stream()
            .filter(s -> DEFAULT.equals(s.name()))
            .findFirst()
            .orElse(registries.isEmpty() ? null : registries.get(0));
        return new SkillSources(list, defRegistry);
    }

    public List<SkillSource> all() { return sources; }

    public boolean isEmpty() { return sources.isEmpty(); }

    /** The registry for unprefixed {@code registry:slug} installs; null when none configured. */
    public RegistrySource defaultRegistry() { return defaultRegistry; }

    public SkillSource byName(String name) {
        if (name == null) return null;
        return sources.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    /** Resolve a registry-protocol source by name (null = default registry); throws on miss. */
    public RegistrySource requireRegistry(String name) {
        RegistrySource s = name == null ? defaultRegistry
            : (byName(name) instanceof RegistrySource r ? r : null);
        if (s == null) {
            throw new IllegalArgumentException("未知技能来源: " + name + "（已配置: " + names() + "）");
        }
        return s;
    }

    /** The ClawHub adapter source, or null when disabled. */
    public ClawHubSource clawhub() {
        return byName(ClawHubSource.NAME) instanceof ClawHubSource ch ? ch : null;
    }

    public String names() {
        return sources.isEmpty() ? "无"
            : sources.stream().map(SkillSource::name).reduce((a, b) -> a + ", " + b).orElse("无");
    }
}
