package com.gantang.tianshu.impl.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory tool registry.
 * Production deployments replace this with a Redis-backed or database-backed registry.
 */
public class DefaultToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolRegistry.class);

    // name → Tool
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    // group → Set<name>
    private final Map<String, Set<String>> groupIndex = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            log.warn("Tool '{}' already registered, replacing", tool.name());
        }
        groupIndex
            .computeIfAbsent(tool.group(), k -> ConcurrentHashMap.newKeySet())
            .add(tool.name());
        log.info("Registered tool: {} (group={})", tool.name(), tool.group());
    }

    @Override
    public void register(String group, Tool... tools) {
        for (Tool t : tools) {
            // Temporarily override group via wrapper
            Tool wrapped = new GroupedTool(t, group);
            register(wrapped);
        }
    }

    @Override
    public void unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            groupIndex.getOrDefault(removed.group(), Set.of()).remove(name);
            log.info("Unregistered tool: {}", name);
        }
    }

    @Override
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<Tool> getByGroup(String group) {
        return groupIndex.getOrDefault(group, Set.of()).stream()
            .map(tools::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Override
    public List<Tool> getAll() {
        return tools.values().stream()
            .filter(t -> !t.hidden())
            .collect(Collectors.toList());
    }

    @Override
    public List<String> getGroups() {
        return new ArrayList<>(groupIndex.keySet());
    }

    @Override
    public void clear() {
        tools.clear();
        groupIndex.clear();
    }

    /** Wrapper that forces a group regardless of tool.group() */
    private record GroupedTool(Tool delegate, String forcedGroup) implements Tool {
        @Override public String name()       { return delegate.name(); }
        @Override public String description(){ return delegate.description(); }
        @Override public JsonNode parameters(){ return delegate.parameters(); }
        @Override public boolean requiresApproval(){ return delegate.requiresApproval(); }
        @Override public boolean hidden()     { return delegate.hidden(); }
        @Override public String group()      { return forcedGroup; }
        @Override public com.gantang.tianshu.api.tool.policy.RiskLevel riskLevel() { return delegate.riskLevel(); }
        @Override public java.time.Duration timeout() { return delegate.timeout(); }
        @Override
        public ToolResult execute(String callId, Map<String, Object> params,
                                  AgentContext context) {
            return delegate.execute(callId, params, context);
        }
        }
}
