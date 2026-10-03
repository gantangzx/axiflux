package com.gantang.reaxon.api.tool;

import java.util.List;
import java.util.Optional;

/**
 * Central registry for all available tools.
 * Thread-safe.
 */
public interface ToolRegistry {

    /** Register a single tool */
    void register(Tool tool);

    /** Register multiple tools with an optional group */
    void register(String group, Tool... tools);

    /** Unregister a tool by name */
    void unregister(String name);

    /** Get a tool by name */
    Optional<Tool> get(String name);

    /** Get all tools in a group */
    List<Tool> getByGroup(String group);

    /** Get all registered tools */
    List<Tool> getAll();

    /** Get all tool groups */
    List<String> getGroups();

    /** Clear all tools */
    void clear();
}
