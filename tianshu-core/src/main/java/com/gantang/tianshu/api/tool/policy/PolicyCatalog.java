package com.gantang.tianshu.api.tool.policy;

import java.util.List;
import java.util.Map;

/**
 * Authoritative, Spring-free catalogue describing every built-in
 * {@link ToolPolicy}. The console renders the live chain from this metadata so
 * the picture can never silently diverge from the beans actually assembled.
 *
 * <p>Design rule encoded here: <b>capability entitlement</b> is a commercial
 * variable (it tracks the plan), while <b>security baselines</b> (SSRF, risk
 * approval, throttle, egress) are invariants enforced identically across all
 * plans and never relaxed in exchange for payment.
 */
public final class PolicyCatalog {

    private PolicyCatalog() {}

    private record Spec(String key, String label, String detail, String kind, boolean planAware) {}

    // Keyed by the concrete policy class simple name.
    private static final Map<String, Spec> SPECS = Map.ofEntries(
        Map.entry("ToolListPolicy", new Spec("ToolList", "部署开关",
            "只有已注册且按部署策略启用的工具才可能进入策略链。", "deployment", false)),
        Map.entry("OrgToolWhitelistPolicy", new Spec("OrgToolWhitelist", "组织白名单",
            "按组织治理配置把成员可调用工具收窄到组织允许的集合；未配置则不限制。", "governance", false)),
        Map.entry("AgentScopePolicy", new Spec("AgentScope", "人设收窄",
            "智能体可调用的工具集合按其人格定义收窄。", "governance", false)),
        Map.entry("ScopePolicy", new Spec("Scope", "OBO 授权",
            "凭 JWT scope 校验，缺少对应 scope 的调用被拒；pro/team 自动含基础 scope（网络/数据库/写文件）。",
            "commercial", true)),
        Map.entry("GitActionPolicy", new Spec("GitAction", "Git 操作管控",
            "对 Git 推送、强制推送等高风险动作进行额外约束。", "security", false)),
        Map.entry("RiskLevelPolicy", new Spec("RiskLevel", "风险门槛",
            "按 SAFE/READ/NETWORK/WRITE/DESTRUCTIVE 分级，高风险需审批；DESTRUCTIVE 始终人工审批。",
            "security", false)),
        Map.entry("InjectionEscalationPolicy", new Spec("InjectionEscalation", "注入升级",
            "检测疑似提示注入的参数并将调用升级为审批或拒绝。", "security", false)),
        Map.entry("ToolCallThrottlePolicy", new Spec("Throttle", "限流 / 循环防护",
            "限流与循环调用保护，防止工具被滥用；限额可按套餐放宽，防护不撤销。", "security", false)),
        Map.entry("NetworkEgressPolicy", new Spec("NetworkEgress", "SSRF 防护",
            "出站请求逐跳校验，防 SSRF 与 DNS rebinding；对所有套餐一致。", "security", false)),
        Map.entry("PlanTierToolPolicy", new Spec("PlanTier", "套餐门禁",
            "按 free/pro/team 矩阵控制高阶能力，升级即时生效。", "commercial", true))
    );

    /**
     * Describe one wired policy at its execution index. Unknown (custom /
     * third-party) policies get a safe generic descriptor rather than failing.
     */
    public static PolicyMetadata describe(ToolPolicy policy, int order) {
        String className = policy.getClass().getSimpleName();
        Spec spec = SPECS.get(className);
        if (spec != null) {
            return new PolicyMetadata(order, className, spec.key(), spec.label(),
                    spec.detail(), spec.kind(), spec.planAware());
        }
        return new PolicyMetadata(order, className, className, className,
                "自定义策略（未在内置目录登记）。", "security", false);
    }

    /** Describe every policy in a live chain, in execution order. */
    public static List<PolicyMetadata> describeChain(List<ToolPolicy> policies) {
        if (policies == null) return List.of();
        return java.util.stream.IntStream.range(0, policies.size())
                .mapToObj(i -> describe(policies.get(i), i))
                .toList();
    }
}
