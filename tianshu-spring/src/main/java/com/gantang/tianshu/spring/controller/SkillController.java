package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.api.skill.SkillReloadResult;
import com.gantang.tianshu.api.skill.SkillResult;
import com.gantang.tianshu.spring.auth.AuthWebFilter;
import com.gantang.tianshu.spring.auth.CallerAuthorization;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.service.PlanGateSpi;
import com.gantang.tianshu.spring.service.SkillInstallService;
import com.gantang.tianshu.spring.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST endpoints for browsing loaded skills and dispatching skill execution.
 *
 * <p><b>Authorization:</b> executing a skill runs a workflow that can call tools
 * and (in LLM-guided mode) re-enter the agent loop against a session, so the
 * request identity must not come from the request body. Both execute endpoints
 * resolve the caller through {@link CallerGuard}, which pins the effective user
 * to the verified header identity, refuses a session owned by someone else and
 * threads the caller's scopes into the context so the tool policy chain evaluates
 * against them rather than against an unscoped service identity.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillExecutor executor;
    private final CallerGuard guard;
    private final SkillInstallService installer;
    private final org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillLedgerService> ledgerProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillMarketService> marketProvider;
    private final org.springframework.beans.factory.ObjectProvider<PlanGateSpi> planGateProvider;

    public SkillController(SkillRegistry registry, SkillExecutor executor, CallerGuard guard,
                           SkillInstallService installer,
                           org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillLedgerService> ledgerProvider,
                           org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillMarketService> marketProvider) {
        this(registry, executor, guard, installer, ledgerProvider, marketProvider, null);
    }

    /**
     * P0-4 variant with an optional {@link PlanGate} for plan-tier gating on
     * install / batch-update. A null provider skips gating (self-hosted).
     */
    public SkillController(SkillRegistry registry, SkillExecutor executor, CallerGuard guard,
                           SkillInstallService installer,
                           org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillLedgerService> ledgerProvider,
                           org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillMarketService> marketProvider,
                           org.springframework.beans.factory.ObjectProvider<PlanGateSpi> planGateProvider) {
        this.registry = Objects.requireNonNull(registry);
        this.executor = Objects.requireNonNull(executor);
        this.guard = Objects.requireNonNull(guard);
        this.installer = Objects.requireNonNull(installer);
        this.ledgerProvider = ledgerProvider;
        this.marketProvider = marketProvider;
        this.planGateProvider = planGateProvider;
    }

    private com.gantang.tianshu.spring.service.SkillLedgerService ledger() {
        return ledgerProvider != null ? ledgerProvider.getIfAvailable() : null;
    }

    /**
     * Skill lifecycle operations (install/reload/update/delete/reconcile/toggle)
     * pull a remote source (SSRF surface) and drop executable code onto the host,
     * so they are gated behind the {@code skill:admin} scope rather than being open
     * to any authenticated caller. 403 so the missing permission is actionable.
     */
    private static void requireSkillAdmin(String scopes) {
        if (!CallerAuthorization.isSkillAdmin(scopes)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "caller lacks scope '" + CallerAuthorization.ADMIN_SCOPE_SKILL + "'");
        }
    }

    /**
     * Plan-tier gating (P0-4) on lifecycle endpoints. Resolves the caller
     * through {@link CallerGuard} (org attached) and enforces the feature's
     * minimum plan when a {@link PlanGate} bean exists; otherwise a no-op.
     * Session ownership is unaffected — no session is addressed here.
     */
    private void requirePlan(String feature, String authUser, String authScopes) {
        PlanGateSpi gate = planGateProvider != null ? planGateProvider.getIfAvailable() : null;
        if (gate == null) return;
        // Run inside the deferred pipeline: gating throws (402/403) must reach
        // the global exception handler as a reactive error signal — throwing
        // before the Mono is assembled would surface an unsandboxed 500.
        CallerGuard.Caller caller = guard.context(authUser, authScopes, null, null);
        gate.require(feature, new com.gantang.tianshu.api.auth.CallerIdentity(
            caller.userId(), caller.orgId(), caller.orgRole(),
            CallerAuthorization.scopeSet(caller.scopes())));
    }

    /** Deferred variant: the check runs when the returned Mono subscribes. */
    private Mono<Void> planCheck(String feature, String authUser, String authScopes) {
        return Mono.fromRunnable(() -> requirePlan(feature, authUser, authScopes))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @GetMapping
    public Mono<ApiResponse<List<Map<String, Object>>>> list() {
        // Merge the live registry (enabled skills) with the ledger (all installed,
        // including disabled ones that the registry intentionally hides).
        return Mono.fromCallable(() -> {
                var l = ledger();
                Map<String, com.gantang.tianshu.storage.entity.SkillEntity> rows =
                    l != null ? l.ledgerByName() : Map.of();
                Map<String, Skill> reg = new LinkedHashMap<>();
                registry.all().forEach(s -> reg.put(s.name(), s));

                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                reg.keySet().forEach(names::add);
                rows.keySet().forEach(names::add);

                List<Map<String, Object>> out = new java.util.ArrayList<>();
                for (String name : names) {
                    Skill s = reg.get(name);
                    com.gantang.tianshu.storage.entity.SkillEntity row = rows.get(name);
                    if (s != null) {
                        out.add(summary(s, row));
                    } else {
                        // Disabled skill: absent from registry; synthesize from the ledger row.
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", name);
                        m.put("description", row != null ? row.getDescription() : null);
                        m.put("triggers", row != null && row.getTriggers() != null ? row.getTriggers() : List.of());
                        m.put("executionMode", null);
                        m.put("source", row != null ? row.getSource() : "builtin");
                        m.put("enabled", false);
                        m.put("installedAt", row != null ? row.getCreatedAt() : null);
                        m.put("updatedAt", row != null ? row.getUpdatedAt() : null);
                        out.add(m);
                    }
                }
                return out;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(ApiResponse::ok);
    }

    /**
     * Skill market: proxy search against the registry service merged with local
     * install state (installed / installedVersion / updateAvailable). 503 when
     * the registry is unreachable; local skills are unaffected.
     * Literal path wins over the {@code /{name}} mapping.
     */
    @GetMapping("/market")
    public Mono<ApiResponse<Map<String, Object>>> market(@RequestParam(required = false) String q,
                                                         @RequestParam(required = false, defaultValue = "0") int page,
                                                         @RequestParam(required = false, defaultValue = "20") int size) {
        return Mono.fromCallable(() -> {
                var market = marketProvider.getIfAvailable();
                if (market == null) throw new IllegalStateException("技能市场服务不可用");
                var result = market.search(q, page, size);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("items", result.items().stream().map(it -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("source", it.source());
                    x.put("slug", it.slug());
                    x.put("name", it.name());
                    x.put("author", it.author());
                    x.put("description", it.description());
                    x.put("tags", it.tags());
                    x.put("latestVersion", it.latestVersion());
                    x.put("totalDownloads", it.totalDownloads());
                    x.put("totalInstalls", it.totalInstalls());
                    x.put("deprecated", it.deprecated());
                    x.put("installSource", it.installSource());
                    x.put("trust", it.trust());
                    x.put("installed", it.installed());
                    x.put("installedName", it.installedName());
                    x.put("installedVersion", it.installedVersion());
                    x.put("installedEnabled", it.installedEnabled());
                    x.put("updateAvailable", it.updateAvailable());
                    return x;
                }).toList());
                m.put("sources", result.sources().stream().map(s -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", s.name());
                    x.put("url", s.url());
                    x.put("available", s.available());
                    x.put("error", s.error());
                    return x;
                }).toList());
                m.put("page", result.page());
                m.put("size", result.size());
                m.put("total", result.total());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(ApiResponse::ok);
    }

    @GetMapping("/{name}")
    public Mono<ApiResponse<Map<String, Object>>> get(@PathVariable String name) {
        return Mono.justOrEmpty(registry.get(name))
            .map(s -> ApiResponse.ok(detail(s)))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("skill not found: " + name)));
    }

    @PostMapping("/reload")
    public Mono<ApiResponse<Map<String, Object>>> reload(
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        // Full directory rescan + registry reconcile — blocking file IO, off the event loop.
        return Mono.fromCallable(() -> {
                SkillReloadResult r = executor.reloadSkills();
                if (ledger() != null) ledger().reconcile();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("added", r.added());
                m.put("updated", r.updated());
                m.put("removed", r.removed());
                m.put("total", r.total());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(ApiResponse::ok);
    }

    /**
     * Install a skill from {@code git:owner/repo[@ref]}, a GitHub/Gitee URL, a .zip
     * download URL, or a local directory. Blocking IO (clone/download/copy) runs
     * on boundedElastic.
     */
    @PostMapping("/install")
    public Mono<ApiResponse<Map<String, Object>>> install(@RequestBody InstallRequest req,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        return planCheck(PlanGateSpi.FEATURE_SKILL_INSTALL, authUser, authScopes)
            .then(Mono.fromCallable(() -> {
                SkillInstallService.InstallResult r = installer.install(req.source(), Boolean.TRUE.equals(req.force()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("installed", r.installed().stream().map(s -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", s.name());
                    x.put("source", s.source());
                    return x;
                }).toList());
                m.put("total", r.reload().total());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic()))
            .map(ApiResponse::ok);
    }

    /** Delete an installed skill directory and reconcile the registry. */
    @DeleteMapping("/{name}")
    public Mono<ApiResponse<Map<String, Object>>> uninstall(@PathVariable String name,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        return Mono.fromCallable(() -> {
                SkillReloadResult r = installer.uninstall(name);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("removed", r.removed());
                m.put("total", r.total());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(ApiResponse::ok);
    }

    /** Enable/disable an installed skill (ledger state; takes effect after reload). */
    @PatchMapping("/{name}")
    public Mono<ApiResponse<Map<String, Object>>> setEnabled(@PathVariable String name,
                                                             @RequestBody EnableRequest req,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        return Mono.fromCallable(() -> {
                var l = ledger();
                if (l == null) throw new IllegalStateException("技能台账不可用（存储未启用）");
                l.setEnabled(name, Boolean.TRUE.equals(req.enabled()));
                var reload = l.reloadAndReconcile();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("enabled", Boolean.TRUE.equals(req.enabled()));
                m.put("total", reload.total());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(ApiResponse::ok);
    }

    /**
     * Batch-update all online-source skills (git/registry/zip unpinned). Builtin/local
     * and pinned-version skills are skipped; deprecated skills are reported as skipped.
     */
    @PostMapping("/update-all")
    public Mono<ApiResponse<Map<String, Object>>> updateAll(
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        return planCheck(PlanGateSpi.FEATURE_SKILL_UPDATE_ALL, authUser, authScopes)
            .then(Mono.fromCallable(() -> {
                SkillInstallService.UpdateAllResult r = installer.updateAll();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("items", r.items().stream().map(it -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", it.name());
                    x.put("source", it.source());
                    x.put("updated", it.updated());
                    x.put("detail", it.detail());
                    return x;
                }).toList());
                m.put("updated", r.updated());
                m.put("skipped", r.skipped());
                m.put("failed", r.failed());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic()))
            .map(ApiResponse::ok);
    }

    /**
     * Repair reconciliation (D5): rescan the skills directory and align the DB ledger
     * with {@code .skill-origin.json} on disk (source/version/checksum), restoring
     * rows that drifted (e.g. ledger wiped, origin file edited manually).
     */
    @PostMapping("/reconcile")
    public Mono<ApiResponse<Map<String, Object>>> reconcile(
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        return Mono.fromCallable(() -> {
                var l = ledger();
                if (l == null) throw new IllegalStateException("技能台账不可用（存储未启用）");
                var r = l.reloadAndReconcile();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("upserted", r.upserted());
                m.put("removed", r.removed());
                m.put("total", r.total());
                m.put("disabledCount", l.disabledNames().size());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(ApiResponse::ok);
    }

    /** Re-install/upgrade a skill from its recorded origin (git pull / zip re-download). */
    @PostMapping("/{name}/update")
    public Mono<ApiResponse<Map<String, Object>>> update(@PathVariable String name,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireSkillAdmin(authScopes);
        return planCheck(PlanGateSpi.FEATURE_SKILL_INSTALL, authUser, authScopes)
            .then(Mono.fromCallable(() -> {
                var l = ledger();
                if (l == null) throw new IllegalStateException("技能台账不可用（存储未启用）");
                String source = l.sourceOf(name)
                    .map(SkillInstallService::unpinFederated)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "技能 " + name + " 无安装来源（内置/本地放置的技能不支持在线更新）"));
                SkillInstallService.InstallResult r = installer.install(source, true);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("updated", r.installed().stream().map(s -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", s.name());
                    x.put("source", s.source());
                    return x;
                }).toList());
                m.put("total", r.reload().total());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic()))
            .map(ApiResponse::ok);
    }

    @PostMapping("/{name}/execute")
    public Mono<ApiResponse<SkillResult>> execute(@PathVariable String name,
                                     @RequestBody ExecuteRequest req,
                                     @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                                     @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // buildContext() resolves the caller through the blocking guard; defer it.
        return Mono.fromCallable(() -> buildContext(req, authUser, authScopes))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(ctx -> Mono.justOrEmpty(registry.get(name))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("skill not found: " + name)))
                .flatMap(s -> executor.execute(s, req.input(), ctx)))
            .map(ApiResponse::ok);
    }

    @PostMapping("/match")
    public Mono<ApiResponse<SkillResult>> executeMatching(@RequestBody ExecuteRequest req,
                                             @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                                             @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> buildContext(req, authUser, authScopes))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(ctx -> executor.executeMatching(req.input(), ctx))
            .map(ApiResponse::ok);
    }

    private AgentContext buildContext(ExecuteRequest req, String authUser, String authScopes) {
        CallerGuard.Caller caller = guard.context(
            authUser, authScopes, req.userId(), req.sessionId());
        return AgentContext.builder()
            .sessionId(caller.sessionId())
            .userId(caller.userId())
            .metadata(caller.metadata())
            .currentQuery(req.input() != null ? req.input() : "")
            .build();
    }

    private Map<String, Object> summary(Skill s, com.gantang.tianshu.storage.entity.SkillEntity row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("description", s.description());
        m.put("triggers", s.triggers());
        m.put("executionMode", s.metadata().executionMode());
        if (s.metadata() != null && s.metadata().version() != null) {
            m.put("version", s.metadata().version());
        }
        if (row != null) {
            m.put("source", row.getSource());
            m.put("enabled", Boolean.TRUE.equals(row.getEnabled()));
            if (m.get("version") == null && row.getVersion() != null) m.put("version", row.getVersion());
            m.put("installedAt", row.getCreatedAt());
            m.put("updatedAt", row.getUpdatedAt());
        } else {
            m.put("source", "builtin");
            m.put("enabled", true);
        }
        return m;
    }

    private Map<String, Object> detail(Skill s) {
        com.gantang.tianshu.storage.entity.SkillEntity row = ledger() != null ? ledger().ledgerByName().get(s.name()) : null;
        Map<String, Object> m = new LinkedHashMap<>(summary(s, row));
        m.put("version", s.metadata().version());
        m.put("requiredTools", s.metadata().requiredTools());
        m.put("stepCount", s.metadata().steps().size());
        return m;
    }

    public record ExecuteRequest(String input, String sessionId, String userId) {}
    public record InstallRequest(String source, Boolean force) {}
    public record EnableRequest(Boolean enabled) {}
}
