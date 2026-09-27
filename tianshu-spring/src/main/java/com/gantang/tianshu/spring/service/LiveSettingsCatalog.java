package com.gantang.tianshu.spring.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for the hot-reloadable runtime settings catalogue.
 *
 * <p>Before this existed the key list was maintained twice: the console-facing
 * metadata (key/type/description/options) lived in {@code ConfigController}'s
 * {@code HOT_SETTINGS}, while the set of keys the persistence layer was allowed
 * to apply lived in {@code RuntimeConfigService.LIVE_KEYS}. Adding a setting
 * meant editing both — and missing one silently made a console-visible key
 * persist-but-not-apply (or vice versa). Both now derive from this catalogue.
 *
 * <p>{@link #ROUTER_KEYS} are applied through a dedicated path (model router /
 * agent tuning) rather than written into the {@code LiveSettings} hub, so they
 * are excluded from {@link #hubKeys()}; the console and persistence layers use
 * {@link #isKnown(String)} across the whole catalogue.
 */
public final class LiveSettingsCatalog {

    private LiveSettingsCatalog() {}

    /** Router default-provider key (applied via ModelRouter, not the LiveSettings hub). */
    public static final String KEY_DEFAULT_PROVIDER = "llm.default-provider";
    /** Agent iteration cap (applied to LiveSettings and mirrored onto ReactiveAgent). */
    public static final String KEY_MAX_ITERATIONS = "agent.max-iterations";

    /** Console-facing metadata for one hot-tunable key. */
    public record SettingMeta(String key, String type, String description,
                              boolean hotReload, List<String> options) {
        public SettingMeta(String key, String type, String description, List<String> options) {
            this(key, type, description, true, options);
        }
    }

    /**
     * The hot-tunable catalogue, in console display order. Every entry here is
     * shown on GET /api/v1/config/settings and accepted by PUT /api/v1/config/live.
     */
    public static final List<SettingMeta> SETTINGS = List.of(
        new SettingMeta(KEY_DEFAULT_PROVIDER, "string", "默认模型提供方（路由默认 Provider）", List.of()),
        new SettingMeta(KEY_MAX_ITERATIONS, "int", "单次对话工具循环最大次数（1-100）", List.of()),
        new SettingMeta("agent.tool-timeout-seconds", "long", "单个工具执行超时秒数", List.of()),
        new SettingMeta("tools.policy-mode", "enum", "工具部署门禁模式", List.of("all", "whitelist", "blacklist")),
        new SettingMeta("tools.policy-allowed", "csv", "白名单工具（逗号分隔，whitelist 模式生效）", List.of()),
        new SettingMeta("tools.policy-blocked", "csv", "黑名单工具（逗号分隔，blacklist 模式生效）", List.of()),
        new SettingMeta("tools.auto-approve", "csv", "完全免人工审批的受信工具（逗号分隔）", List.of()),
        new SettingMeta("tools.allow-private-network", "bool", "是否允许访问内网/回环地址（SSRF）", List.of("true", "false")),
        new SettingMeta("tools.file-allowed-roots", "csv", "文件读写允许的根目录（逗号分隔）", List.of()),
        new SettingMeta("tools.auto-approve-budget-enabled", "bool", "是否启用预算式自动审批", List.of("true", "false")),
        new SettingMeta("tools.auto-approve-budget-default", "int", "每工具每会话默认自动批准次数", List.of()),
        new SettingMeta("tools.auto-approve-budget-risk-ceiling", "enum", "可自动批准的最高风险等级",
            List.of("SAFE", "READ", "NETWORK", "WRITE")),
        new SettingMeta("tools.auto-approve-budgets", "json", "按工具覆盖预算，如 {\"file_write\":5}", List.of())
    );

    /** Keys applied through dedicated router/agent paths rather than the LiveSettings hub. */
    private static final Set<String> ROUTER_KEYS = Set.of(KEY_DEFAULT_PROVIDER);

    /** Every catalogue key. */
    public static Set<String> allKeys() {
        return SETTINGS.stream().map(SettingMeta::key)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Keys the persistence layer applies straight into the LiveSettings hub. */
    public static Set<String> hubKeys() {
        return SETTINGS.stream().map(SettingMeta::key)
            .filter(k -> !ROUTER_KEYS.contains(k))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean isKnown(String key) {
        return key != null && allKeys().contains(key);
    }

    public static Optional<SettingMeta> meta(String key) {
        return SETTINGS.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    /** Legacy short aliases accepted on the reset endpoint, mapped to full keys. */
    private static final Map<String, String> ALIASES = Map.of(
        "default-provider", KEY_DEFAULT_PROVIDER,
        "max-iterations", KEY_MAX_ITERATIONS);

    /** Resolve a legacy short alias to its full key; unknown keys pass through. */
    public static String canonicalKey(String key) {
        return ALIASES.getOrDefault(key, key);
    }
}
