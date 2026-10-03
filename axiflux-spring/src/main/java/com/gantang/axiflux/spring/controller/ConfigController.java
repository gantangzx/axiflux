package com.gantang.axiflux.spring.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.RuntimeTunableAgent;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.llm.MutableModelRouter;
import com.gantang.reaxon.api.tool.ToolRegistry;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.gantang.axiflux.spring.config.props.AgentProperties;
import com.gantang.axiflux.spring.config.props.AuthProperties;
import com.gantang.axiflux.spring.config.props.EmailProperties;
import com.gantang.axiflux.spring.config.props.LlmProperties;
import com.gantang.axiflux.spring.config.props.MemoryProperties;
import com.gantang.axiflux.spring.config.props.SchedulerProperties;
import com.gantang.axiflux.spring.config.props.SessionProperties;
import com.gantang.axiflux.spring.config.props.SkillsProperties;
import com.gantang.axiflux.spring.config.props.ToolsProperties;
import com.gantang.axiflux.spring.config.props.TtsProperties;
import com.gantang.axiflux.spring.config.props.VectorProperties;
import com.gantang.axiflux.spring.config.props.VisionProperties;
import com.gantang.axiflux.spring.config.props.WebProperties;
import com.gantang.axiflux.spring.config.props.WebsocketProperties;
import com.gantang.axiflux.spring.service.LiveSettingsCatalog;
import com.gantang.axiflux.spring.service.LiveSettingsCatalog.SettingMeta;
import com.gantang.axiflux.spring.service.RuntimeConfigService;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime configuration API.
 *
 * <pre>
 * GET    /api/v1/config                 effective config (secrets masked) + runtime status
 * GET    /api/v1/config/settings        hot-tunable keys, current values + live/restart metadata
 * POST   /api/v1/config/routing         { provider }                  — hot-switch model provider
 * POST   /api/v1/config/agent           { maxIterations }             — hot-set the tool-loop cap
 * PUT    /api/v1/config/live            { key, value }                — set any hot-tunable key
 * DELETE /api/v1/config/override/{key}  reset one key to application.yml default
 * </pre>
 *
 * <p>Only settings that take effect without a restart are mutable.
 * Infrastructure (datasource/vector/auth/sandbox) requires a restart and is
 * exposed read-only, always with secret fields masked.
 *
 * <p><b>Authorization:</b> every endpoint here is deployment-wide — a single
 * write changes tool policy, the filesystem jail, SSRF allowance or auto-approval
 * for <em>all</em> users and all instances, and is persisted. All endpoints
 * therefore require the {@code config:admin} scope (or the dev wildcard);
 * ownership checks would be meaningless because there is no per-user owner.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    /** 403 for a caller without {@code config:admin}; throws so reactive callers offload uniformly. */
    private static void requireConfigAdmin(String scopes) {
        if (!CallerAuthorization.isConfigAdmin(scopes)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "caller lacks scope '" + CallerAuthorization.ADMIN_SCOPE_CONFIG
                    + "' required to read or change runtime configuration");
        }
    }

    /** Property keys whose values must never leave the process. */
    private static final List<String> SECRET_KEY_HINTS = List.of(
        "apikey", "password", "secret", "token", "credential");
    private static final String MASK = "***";

    /** Console-facing catalogue of hot-tunable keys (single source: LiveSettingsCatalog). */
    private static final List<SettingMeta> HOT_SETTINGS = LiveSettingsCatalog.SETTINGS;

    private final LlmProperties llm;
    private final SchedulerProperties scheduler;
    private final VectorProperties vector;
    private final AgentProperties agentProps;
    private final ToolsProperties tools;
    private final SkillsProperties skills;
    private final WebProperties web;
    private final SessionProperties session;
    private final VisionProperties vision;
    private final TtsProperties tts;
    private final EmailProperties email;
    private final AuthProperties auth;
    private final MemoryProperties memory;
    private final WebsocketProperties websocket;
    private final ObjectMapper om;
    private final ObjectProvider<ModelRouter> routerProvider;
    private final ObjectProvider<Agent> agentProvider;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final ObjectProvider<RuntimeConfigService> configServiceProvider;
    private final ObjectProvider<LiveSettings> liveProvider;

    public ConfigController(LlmProperties llm, SchedulerProperties scheduler,
                            VectorProperties vector, AgentProperties agentProps,
                            ToolsProperties tools, SkillsProperties skills,
                            WebProperties web, SessionProperties session,
                            VisionProperties vision, TtsProperties tts,
                            EmailProperties email, AuthProperties auth,
                            MemoryProperties memory, WebsocketProperties websocket,
                            ObjectMapper om,
                            ObjectProvider<ModelRouter> routerProvider,
                            ObjectProvider<Agent> agentProvider,
                            ObjectProvider<ToolRegistry> toolRegistryProvider,
                            ObjectProvider<RuntimeConfigService> configServiceProvider,
                            ObjectProvider<LiveSettings> liveProvider) {
        this.llm = llm;
        this.scheduler = scheduler;
        this.vector = vector;
        this.agentProps = agentProps;
        this.tools = tools;
        this.skills = skills;
        this.web = web;
        this.session = session;
        this.vision = vision;
        this.tts = tts;
        this.email = email;
        this.auth = auth;
        this.memory = memory;
        this.websocket = websocket;
        this.om = om;
        this.routerProvider = routerProvider;
        this.agentProvider = agentProvider;
        this.toolRegistryProvider = toolRegistryProvider;
        this.configServiceProvider = configServiceProvider;
        this.liveProvider = liveProvider;
    }

    /** Effective configuration with secrets masked, plus live runtime status. */
    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> get(
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requireConfigAdmin(authScopes);
            Map<String, Object> out = new LinkedHashMap<>();
            ObjectNode cfg = om.valueToTree(effectiveConfig());
            maskSecrets(cfg);
            out.put("config", om.convertValue(cfg, Object.class));
            RuntimeConfigService svc = configServiceProvider.getIfAvailable();
            // Blocking JPA reads must not run on the Netty event loop.
            out.put("persisted", svc != null ? svc.snapshot() : Map.of());
            out.put("runtime", runtimeStatus());
            return ApiResponse.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Hot-tunable settings catalogue with live values (for the console form). */
    @GetMapping("/settings")
    public Mono<ApiResponse<Map<String, Object>>> settings(
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requireConfigAdmin(authScopes);
            RuntimeConfigService svc = configServiceProvider.getIfAvailable();
            Map<String, String> persisted = svc != null ? svc.snapshot() : Map.of();
            LiveSettings live = liveProvider.getIfAvailable();

            List<Map<String, Object>> items = new ArrayList<>();
            for (SettingMeta meta : HOT_SETTINGS) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", meta.key());
                row.put("type", meta.type());
                row.put("description", meta.description());
                row.put("hotReload", meta.hotReload());
                row.put("options", meta.options());
                row.put("value", liveValue(live, meta.key(), persisted.get(meta.key())));
                row.put("overridden", persisted.containsKey(meta.key()));
                items.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("settings", items);
            out.put("note", "以上为可热更项，修改立即生效并持久化；数据源/Redis/向量库/认证/沙箱等基础设施项需重启。");
            return ApiResponse.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record LiveValueRequest(String key, String value) {}

    /** Set any hot-tunable key; persisted and applied live immediately. */
    @PutMapping("/live")
    public Mono<ApiResponse<Map<String, Object>>> setLive(@RequestBody LiveValueRequest req,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requireConfigAdmin(authScopes);
            if (req == null || req.key() == null || req.key().isBlank()) {
                throw badRequest("key is required");
            }
            String key = req.key().trim();
            if (!LiveSettingsCatalog.isKnown(key)) {
                throw badRequest("Unknown or non-hot-reloadable key: " + key
                    + ". See GET /api/v1/config/settings.");
            }
            String validation = validate(key, req.value());
            if (validation != null) throw badRequest(validation);

            RuntimeConfigService svc = configServiceProvider.getIfAvailable();
            if (svc == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Config persistence is not available");
            }
            boolean applied = svc.put(key, req.value() == null ? "" : req.value(), "api");
            log.info("Live config set {} = {} (applied={})", key, req.value(), applied);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("key", key);
            body.put("applied", applied);
            return ApiResponse.ok(body);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record RoutingRequest(String provider) {}

    /** Hot-switch the default model provider used by the router. */
    @PostMapping("/routing")
    public Mono<ApiResponse<Map<String, Object>>> routing(@RequestBody RoutingRequest req,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requireConfigAdmin(authScopes);
            if (req == null || req.provider() == null || req.provider().isBlank()) {
                throw badRequest("provider is required");
            }
            ModelRouter router = routerProvider.getIfAvailable();
            if (!(router instanceof MutableModelRouter mr)) {
                throw badRequest("Router does not support runtime provider switching");
            }
            if (mr.get(req.provider()).isEmpty()) {
                throw badRequest("Unknown provider: " + req.provider()
                    + ". Available: " + mr.listProviders());
            }
            mr.setDefaultProvider(req.provider());
            persist(RuntimeConfigService.KEY_DEFAULT_PROVIDER, req.provider());
            log.info("Runtime config: default provider switched to '{}'", req.provider());
            return ApiResponse.ok(Map.<String, Object>of(
                "success", true, "defaultProvider", req.provider()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record AgentRequest(Integer maxIterations) {}
    @PostMapping("/agent")
    public Mono<ApiResponse<Map<String, Object>>> agent(@RequestBody AgentRequest req,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requireConfigAdmin(authScopes);
            if (req == null || req.maxIterations() == null) {
                throw badRequest("maxIterations is required");
            }
            int n = req.maxIterations();
            if (n < 1 || n > 100) {
                throw badRequest("maxIterations must be between 1 and 100");
            }
            Agent agent = agentProvider.getIfAvailable();
            if (!(agent instanceof RuntimeTunableAgent ta)) {
                throw badRequest("Agent does not support runtime tuning");
            }
            ta.withMaxIterations(n);
            persist(RuntimeConfigService.KEY_MAX_ITERATIONS, String.valueOf(n));
            log.info("Runtime config: agent maxIterations set to {}", n);
            return ApiResponse.ok(Map.<String, Object>of(
                "success", true, "maxIterations", ta.getMaxIterations()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Reset a key to its application.yml default (removes the persisted override). */
    @DeleteMapping("/override/{key}")
    public Mono<ApiResponse<Map<String, Object>>> resetOverride(@PathVariable String key,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requireConfigAdmin(authScopes);
            RuntimeConfigService svc = configServiceProvider.getIfAvailable();
            if (svc == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Config persistence is not available");
            }
            // Accept legacy short aliases, then validate against the catalogue.
            String fullKey = LiveSettingsCatalog.canonicalKey(key);
            if (!LiveSettingsCatalog.isKnown(fullKey)) {
                throw badRequest("Unknown or non-hot-reloadable key: " + key);
            }
            boolean existed = svc.reset(fullKey);
            return ApiResponse.ok(Map.<String, Object>of(
                "success", true, "removed", existed, "key", fullKey));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ===== helpers =====

    private String validate(String key, String value) {
        String v = value == null ? "" : value.trim();
        try {
            switch (key) {
                case "agent.max-iterations" -> {
                    int n = Integer.parseInt(v);
                    if (n < 1 || n > 100) return "max-iterations must be between 1 and 100";
                }
                case "agent.tool-timeout-seconds" -> {
                    long n = Long.parseLong(v);
                    if (n < 1 || n > 3600) return "tool-timeout-seconds must be between 1 and 3600";
                }
                case "tools.policy-mode" -> {
                    if (!List.of("all", "whitelist", "blacklist").contains(v.toLowerCase()))
                        return "policy-mode must be all|whitelist|blacklist";
                }
                case "tools.allow-private-network", "tools.auto-approve-budget-enabled" -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false"))
                        return key + " must be true|false";
                }
                case "tools.auto-approve-budget-default" -> {
                    if (Integer.parseInt(v) < 0) return "budget default must be >= 0";
                }
                case "tools.auto-approve-budget-risk-ceiling" -> {
                    if (!List.of("SAFE", "READ", "NETWORK", "WRITE").contains(v.toUpperCase()))
                        return "risk-ceiling must be SAFE|READ|NETWORK|WRITE";
                }
                case "tools.auto-approve-budgets" -> {
                    if (!v.isEmpty()) om.readValue(v, Map.class); // throws on malformed JSON
                }
                case "llm.default-provider" -> {
                    // Only validate when the router actually exposes a registry
                    // (default interface methods return empty lists) — a custom
                    // router with no registry keeps accepting any value.
                    ModelRouter mr = routerProvider.getIfAvailable();
                    if (mr != null && !mr.listProviders().isEmpty() && mr.get(v).isEmpty()) {
                        return "Unknown provider: " + v + ". Available: " + mr.listProviders();
                    }
                }
                case "tools.file-allowed-roots" -> {
                    // The filesystem jail must never be emptied or widened to the whole
                    // volume at runtime: an empty allowlist is refused by PathGuard, and a
                    // root like "/" or "C:\" would be a jail in name only.
                    if (v.isEmpty()) return "file-allowed-roots must not be empty; "
                        + "at least one absolute directory is required";
                    for (String part : v.split(",")) {
                        String root = part.trim();
                        if (root.isEmpty()) continue;
                        Path p;
                        try {
                            p = Paths.get(root).toAbsolutePath().normalize();
                        } catch (InvalidPathException e) {
                            return "file-allowed-roots entry is not a valid path: " + root;
                        }
                        if (!Paths.get(root).isAbsolute()) {
                            return "file-allowed-roots entry must be an absolute path: " + root;
                        }
                        if (p.getParent() == null) {
                            return "file-allowed-roots entry must not be a filesystem root: " + root;
                        }
                    }
                }
                default -> { /* csv/string: no validation */ }
            }
        } catch (NumberFormatException e) {
            return key + " must be a number";
        } catch (Exception e) {
            // Never echo the parser/validator detail (Jackson messages can quote
            // back the raw input; infra P2-7): the caller gets a generic reason
            // plus the key, the specifics stay in the server log.
            log.debug("Live config validation failed for {}: {}", key, e.toString());
            return key + " value is invalid";
        }
        return null;
    }

    private Object liveValue(LiveSettings live, String key, String persisted) {
        if (live == null) return persisted;
        LiveSettings.Snapshot s = live.snapshot();
        return switch (key) {
            case "agent.max-iterations" -> s.maxIterations();
            case "agent.tool-timeout-seconds" -> s.toolTimeoutSeconds();
            case "tools.policy-mode" -> s.toolPolicyMode();
            case "tools.policy-allowed" -> String.join(",", s.allowedTools());
            case "tools.policy-blocked" -> String.join(",", s.blockedTools());
            case "tools.auto-approve" -> String.join(",", s.autoApproveTools());
            case "tools.allow-private-network" -> s.allowPrivateNetwork();
            case "tools.file-allowed-roots" -> s.fileAllowedRoots().stream()
                .map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            case "tools.auto-approve-budget-enabled" -> s.budgetEnabled();
            case "tools.auto-approve-budget-default" -> s.budgetDefault();
            case "tools.auto-approve-budget-risk-ceiling" -> s.budgetRiskCeiling();
            case "tools.auto-approve-budgets" -> s.toolBudgets();
            case "llm.default-provider" -> routerProvider.getIfAvailable() instanceof MutableModelRouter mmr
                ? mmr.getDefaultProvider() : persisted;
            default -> persisted;
        };
    }

    private Map<String, Object> runtimeStatus() {
        Map<String, Object> rt = new LinkedHashMap<>();

        List<Map<String, String>> providers = new ArrayList<>();
        ModelRouter router = routerProvider.getIfAvailable();
        String defaultProvider = null;
        if (router != null) {
            if (router instanceof MutableModelRouter mmr) {
                defaultProvider = mmr.getDefaultProvider();
            }
            for (String name : router.listProviders()) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("name", name);
                p.put("model", router.get(name).map(LlmClient::primaryModel).orElse(""));
                p.put("provider", router.get(name).map(LlmClient::provider).orElse(""));
                providers.add(p);
            }
        }
        rt.put("defaultProvider", defaultProvider);
        rt.put("providers", providers);

        ToolRegistry reg = toolRegistryProvider.getIfAvailable();
        rt.put("toolCount", reg != null ? reg.getAll().size() : 0);

        Agent agent = agentProvider.getIfAvailable();
        rt.put("maxIterations", agent instanceof RuntimeTunableAgent ta ? ta.getMaxIterations() : null);
        rt.put("vectorProvider", vector != null ? vector.getProvider() : null);
        rt.put("sessionProvider", session != null ? session.getProvider() : null);
        rt.put("skillsRootDir", skills != null ? skills.getRootDir() : null);
        return rt;
    }

    /**
     * The effective {@code axiflux.*} configuration as a domain-keyed map — the
     * post-P5 replacement for serializing the monolithic AxifluxProperties bean.
     * Keys match the YAML domain names so the console payload shape is unchanged.
     */
    private Map<String, Object> effectiveConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("llm", llm);
        cfg.put("scheduler", scheduler);
        cfg.put("vector", vector);
        cfg.put("agent", agentProps);
        cfg.put("tools", tools);
        cfg.put("skills", skills);
        cfg.put("web", web);
        cfg.put("session", session);
        cfg.put("vision", vision);
        cfg.put("tts", tts);
        cfg.put("email", email);
        cfg.put("auth", auth);
        cfg.put("memory", memory);
        cfg.put("websocket", websocket);
        return cfg;
    }

    private void maskSecrets(JsonNode node) {
        if (node == null) return;
        if (node.isArray()) {
            // Recurse element-wise (authz P2-4): a list of objects/strings may
            // carry secrets even though the property key itself is innocuous
            // (e.g. MCP server env lists, custom origin/credential arrays).
            for (JsonNode element : node) {
                maskSecrets(element);
            }
            return;
        }
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        Iterator<String> fields = obj.fieldNames();
        List<String> names = new ArrayList<>();
        fields.forEachRemaining(names::add);
        for (String name : names) {
            JsonNode child = obj.get(name);
            if (isSecretKey(name) && child != null) {
                if (child.isValueNode()) {
                    obj.put(name, child.isNull() ? null : MASK);
                } else if (child.isArray()) {
                    // A secret-keyed list (e.g. tokens: [...]) is masked wholesale:
                    // the elements are credential material, not structure to recurse.
                    obj.put(name, MASK);
                } else {
                    maskSecrets(child);
                }
            } else if (child != null && child.isContainerNode()) {
                maskSecrets(child);
            } else if (child != null && child.isTextual() && looksLikeSecret(child.asText())) {
                // Key name didn't match, but the value itself is shaped like a
                // credential (long hex/base64/token) — mask defensively.
                obj.put(name, MASK);
            }
        }
    }

    private boolean isSecretKey(String name) {
        String lower = name.toLowerCase();
        for (String hint : SECRET_KEY_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /** Common API-key prefixes (OpenAI, GitHub, AWS, Google, Slack, Stripe, JWT, …). */
    private static final java.util.regex.Pattern SECRET_VALUE_PREFIX = java.util.regex.Pattern.compile(
        "(?i)^(sk-[A-Za-z0-9_-]{16,}|sk-ant-[A-Za-z0-9_-]{10,}|gh[pousr]_[A-Za-z0-9]{20,}"
        + "|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}"
        + "|xox[baprs]-[0-9A-Za-z-]{10,}|[er]k_live_[0-9A-Za-z]{16,}"
        + "|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,})");

    /** Long unbroken hex/base64url runs (>= 32 chars) are near-certainly key material. */
    private static final java.util.regex.Pattern SECRET_VALUE_ENTROPY = java.util.regex.Pattern.compile(
        "^(?=.{32,}$)[A-Za-z0-9+/_=-]+$");

    /**
     * Heuristic for "value shaped like a credential" (authz P2-4). Conservative:
     * only an unbroken token-shaped string qualifies — ordinary prose, paths, URLs
     * and hosts never match. Whitespace anywhere disqualifies the value.
     */
    static boolean looksLikeSecret(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < 20 || v.length() > 4096 || v.indexOf(' ') >= 0 || v.indexOf('\t') >= 0
            || v.indexOf('\n') >= 0 || v.contains("://") || v.startsWith("/") || v.contains("\\")) {
            return false;
        }
        if (SECRET_VALUE_PREFIX.matcher(v).find()) return true;
        // Pure long token: require both letters and digits so plain English words,
        // hashes-in-docs and UUID-like identifiers without entropy don't trip it.
        if (SECRET_VALUE_ENTROPY.matcher(v).matches()
            && v.chars().anyMatch(Character::isDigit)
            && v.chars().anyMatch(Character::isLetter)) {
            return true;
        }
        return false;
    }

    /** 400 exception carried through the global exception handler (uniform {success:false,error} body). */
    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** Persist an override when a backing store is available; no-op (in-memory only) otherwise. */
    private void persist(String key, String value) {
        RuntimeConfigService svc = configServiceProvider.getIfAvailable();
        if (svc != null) {
            try {
                svc.put(key, value, "api");
            } catch (Exception e) {
                log.warn("Persisting override {} failed (live change still applied): {}", key, e.toString());
            }
        }
    }
}
