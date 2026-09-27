package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.tools.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.tools")


// ===== Tools =====

public class ToolsProperties {
    private boolean builtinsEnabled = true;
    /** Comma/semicolon-separated allowed roots for file_read / file_write. Empty = unrestricted (dev only). */
    private String fileAllowedRoots = "";
    /** Tool-set gate: all | whitelist | blacklist. */
    private String policyMode = "all";
    /** Comma/semicolon-separated tool names allowed in whitelist mode. */
    private String policyAllowed = "";
    /** Comma/semicolon-separated tool names blocked in blacklist mode. */
    private String policyBlocked = "";
    /** Dev escape hatch: permit http_client/web_fetch to reach private/loopback addresses. */
    private boolean allowPrivateNetwork = false;
    /** Comma/semicolon-separated tools trusted to run WITHOUT approval (auto-approve). */
    private String autoApprove = "";
    /** code_executor isolation: "local" (host shell) or "docker" (locked-down container). */
    private String codeExecutorSandbox = "local";
    private String dockerImage = "alpine:3.20";
    private String dockerMemory = "512m";
    private double dockerCpus = 1.0;
    /** Optional forward/egress proxy for http_client/web_fetch, format "host:port" (empty = direct). */
    private String egressProxy = "";
    /** Enable budget-threshold auto-approval: first N low-risk calls per session skip the prompt. */
    private boolean autoApproveBudgetEnabled = false;
    /** Auto-approve budget per tool per session, unless overridden in autoApproveBudgets. */
    private int autoApproveBudgetDefault = 3;
    /** Highest risk level eligible for budget auto-approval: SAFE|READ|NETWORK|WRITE. DESTRUCTIVE never eligible. */
    private String autoApproveBudgetRiskCeiling = "WRITE";
    /** Per-tool budget overrides, e.g. {file_write: 5}. */
    private java.util.Map<String, Integer> autoApproveBudgets = new java.util.LinkedHashMap<>();
    public boolean isBuiltinsEnabled() { return builtinsEnabled; }
    public void setBuiltinsEnabled(boolean v) { this.builtinsEnabled = v; }
    public String getFileAllowedRoots() { return fileAllowedRoots; }
    public void setFileAllowedRoots(String v) { this.fileAllowedRoots = v; }
    public String getPolicyMode() { return policyMode; }
    public void setPolicyMode(String v) { this.policyMode = v; }
    public String getPolicyAllowed() { return policyAllowed; }
    public void setPolicyAllowed(String v) { this.policyAllowed = v; }
    public String getPolicyBlocked() { return policyBlocked; }
    public void setPolicyBlocked(String v) { this.policyBlocked = v; }
    public boolean isAllowPrivateNetwork() { return allowPrivateNetwork; }
    public void setAllowPrivateNetwork(boolean v) { this.allowPrivateNetwork = v; }
    public String getAutoApprove() { return autoApprove; }
    public void setAutoApprove(String v) { this.autoApprove = v; }
    public String getCodeExecutorSandbox() { return codeExecutorSandbox; }
    public void setCodeExecutorSandbox(String v) { this.codeExecutorSandbox = v; }
    public String getDockerImage() { return dockerImage; }
    public void setDockerImage(String v) { this.dockerImage = v; }
    public String getDockerMemory() { return dockerMemory; }
    public void setDockerMemory(String v) { this.dockerMemory = v; }
    public double getDockerCpus() { return dockerCpus; }
    public void setDockerCpus(double v) { this.dockerCpus = v; }
    public String getEgressProxy() { return egressProxy; }
    public void setEgressProxy(String v) { this.egressProxy = v; }
    public boolean isAutoApproveBudgetEnabled() { return autoApproveBudgetEnabled; }
    public void setAutoApproveBudgetEnabled(boolean v) { this.autoApproveBudgetEnabled = v; }
    public int getAutoApproveBudgetDefault() { return autoApproveBudgetDefault; }
    public void setAutoApproveBudgetDefault(int v) { this.autoApproveBudgetDefault = v; }
    public String getAutoApproveBudgetRiskCeiling() { return autoApproveBudgetRiskCeiling; }
    public void setAutoApproveBudgetRiskCeiling(String v) { this.autoApproveBudgetRiskCeiling = v; }
    public java.util.Map<String, Integer> getAutoApproveBudgets() { return autoApproveBudgets; }
    public void setAutoApproveBudgets(java.util.Map<String, Integer> v) { this.autoApproveBudgets = v; }

    /** Session-scoped cache for successful results of idempotent read-only tools. */
    private boolean resultCacheEnabled = true;
    /** TTL of cached tool results, in seconds. */
    private long resultCacheTtlSeconds = 600;
    /** Max cached entries globally (per JVM); oldest are evicted past this. */
    private int resultCacheMaxEntries = 1000;
    /** Comma-separated idempotent read-only tools eligible for result caching. */
    private String resultCacheTools = "calculator,web_fetch";
    public boolean isResultCacheEnabled() { return resultCacheEnabled; }
    public void setResultCacheEnabled(boolean v) { this.resultCacheEnabled = v; }
    public long getResultCacheTtlSeconds() { return resultCacheTtlSeconds; }
    public void setResultCacheTtlSeconds(long v) { this.resultCacheTtlSeconds = v; }
    public int getResultCacheMaxEntries() { return resultCacheMaxEntries; }
    public void setResultCacheMaxEntries(int v) { this.resultCacheMaxEntries = v; }
    public String getResultCacheTools() { return resultCacheTools; }
    public void setResultCacheTools(String v) { this.resultCacheTools = v; }

    // ===== Per-tenant workspace jails (P2-3) =====

    /**
     * Enable per-user filesystem workspaces ({@code <workspace-root>/<userId>/}).
     * When on, file tools and code_executor are jailed to the caller's own root;
     * off by default to preserve the single-tenant {@code file-allowed-roots} behaviour.
     */
    private boolean workspacesEnabled = false;
    /** Base directory for tenant roots. Blank resolves to {@code <working-dir>/workspaces}. */
    private String workspacesRoot = "";
    /**
     * When tenant workspaces are on, also expose the host-wide configured file roots
     * to every tenant (shared codebases). Default false = strict tenant-only isolation.
     */
    private boolean workspacesCombineGlobalRoots = false;
    public boolean isWorkspacesEnabled() { return workspacesEnabled; }
    public void setWorkspacesEnabled(boolean v) { this.workspacesEnabled = v; }
    public String getWorkspacesRoot() { return workspacesRoot; }
    public void setWorkspacesRoot(String v) { this.workspacesRoot = v; }
    public boolean isWorkspacesCombineGlobalRoots() { return workspacesCombineGlobalRoots; }
    public void setWorkspacesCombineGlobalRoots(boolean v) { this.workspacesCombineGlobalRoots = v; }
}
