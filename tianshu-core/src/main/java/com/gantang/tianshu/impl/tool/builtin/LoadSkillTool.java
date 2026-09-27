package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;

import java.util.Map;
import java.util.Optional;

/**
 * {@code load_skill}: discover and read SKILL.md skills (Tianshu-style
 * progressive disclosure).
 *
 * <p>Skills are reusable instruction playbooks (plus optional resources) that
 * live in {@code tianshu.skills.root-dir} and are loaded into a
 * {@link SkillRegistry} at startup. The agent does not need their full text in
 * context: this tool lists the catalog and, on demand, returns one skill's
 * complete SKILL.md instructions for the agent to follow.
 *
 * <ul>
 *   <li>{@code action=list} (default) — name + description + triggers for every
 *       loaded skill.</li>
 *   <li>{@code action=read} — full SKILL.md body of {@code name}; the agent then
 *       follows those instructions for the rest of the task.</li>
 * </ul>
 *
 * <p>Read-only; never approval-gated. No registry wired (skills disabled / none
 * loaded) → reports that plainly instead of failing the turn.
 */
public final class LoadSkillTool implements Tool {

    private final SkillRegistry registry;

    public LoadSkillTool() { this(null); }
    public LoadSkillTool(SkillRegistry registry) { this.registry = registry; }

    @Override public String name() { return "load_skill"; }
    @Override public String group() { return "builtin"; }

    @Override public String description() {
        return "List or read installed skills (SKILL.md playbooks). When the user's task matches a "
            + "skill's purpose or trigger words, call action='read' with the skill name FIRST, then "
            + "follow that skill's instructions step by step for the whole task. Use action='list' "
            + "to see all available skills (name, what they do, trigger words). A skill can bundle "
            + "references, scripts and templates; its SKILL.md tells you how to use them. Read-only.";
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["list", "read"],
                        "description": "list=catalog of installed skills (default); read=full SKILL.md instructions for one skill." },
            "name":   { "type": "string", "description": "Skill name to load (required for action=read)." }
          },
          "required": ["action"]
        }
        """);

    @Override public JsonNode parameters() { return SCHEMA; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String action = str(params, "action", "list").toLowerCase().trim();
        if (registry == null || registry.size() == 0) {
            return ToolResult.success(callId,
                "No skills are installed. Drop SKILL.md files under the skills root directory "
                + "(tianshu.skills.root-dir, default ./skills) and restart; they will be listed here.");
        }
        return switch (action) {
            case "read" -> doRead(callId, params);
            case "list" -> doList(callId);
            default -> ToolResult.failure(callId, "Unknown action '" + action + "'. Use list | read.");
        };
    }

    private ToolResult doList(String callId) {
        StringBuilder sb = new StringBuilder();
        sb.append(registry.size()).append(" skill")
          .append(registry.size() == 1 ? "" : "s").append(" installed:\n");
        for (Skill s : registry.all()) {
            sb.append("- ").append(s.name());
            if (s.description() != null && !s.description().isBlank()) {
                sb.append(": ").append(s.description().strip());
            }
            if (s.triggers() != null && !s.triggers().isEmpty()) {
                sb.append(" [triggers: ").append(String.join(", ", s.triggers())).append("]");
            }
            sb.append('\n');
        }
        sb.append("\nCall load_skill with action=read and name=<skill> to get the full instructions "
            + "before handling a matching task.");
        return ToolResult.success(callId, sb.toString());
    }

    private ToolResult doRead(String callId, Map<String, Object> params) {
        String name = str(params, "name", null);
        if (name == null || name.isBlank()) {
            return ToolResult.failure(callId, "action=read requires a 'name' (call action=list to see skill names).");
        }
        Optional<Skill> found = registry.get(name.trim());
        if (found.isEmpty()) {
            StringBuilder names = new StringBuilder();
            registry.all().forEach(s -> names.append(s.name()).append(", "));
            return ToolResult.failure(callId, "No skill named '" + name + "'. Installed: "
                + (names.length() == 0 ? "(none)" : names.substring(0, names.length() - 2)) + ".");
        }
        Skill s = found.get();
        String content = s.readContent();
        if (content == null || content.isBlank()) {
            return ToolResult.failure(callId, "Skill '" + name + "' has no readable SKILL.md content.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(s.name()).append('\n');
        if (s.skillPath() != null) sb.append("(directory: ").append(s.skillPath()).append(")\n");
        sb.append("Follow the instructions below for this task.\n\n");
        sb.append(stripFrontMatter(content).strip());
        return ToolResult.success(callId, sb.toString());
    }

    /** Remove a leading YAML front-matter block ({@code --- ... ---}) whose fields are already surfaced. */
    static String stripFrontMatter(String md) {
        String t = md.strip();
        if (!t.startsWith("---")) return md;
        int end = t.indexOf("\n---", 3);
        if (end < 0) return md;
        int nl = t.indexOf('\n', end + 4);
        return nl < 0 ? "" : t.substring(nl + 1);
    }

    private static String str(Map<String, Object> p, String k, String def) {
        Object v = p.get(k);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }
}
