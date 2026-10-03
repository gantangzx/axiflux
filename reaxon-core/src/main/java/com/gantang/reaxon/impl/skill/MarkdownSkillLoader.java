package com.gantang.reaxon.impl.skill;

import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillLoader;
import com.gantang.reaxon.api.skill.SkillMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Parses SKILL.md files with optional YAML front-matter. Compatible with the
 * Node/Python Axiflux SKILL.md format.
 *
 * <p>Format:
 * <pre>{@code
 * ---
 * name: reminder
 * description: Set reminders for the user
 * triggers: ["remind me", "reminder"]
 * execution_mode: sequential
 * steps:
 *   - name: parse_time
 *     type: llm
 *     target: "Extract the time from the user's request"
 *     output_var: due_time
 * ---
 *
 * # Reminder Skill
 * ...markdown body...
 * }</pre>
 */
public final class MarkdownSkillLoader implements SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSkillLoader.class);
    private static final String FRONT_MATTER_DELIM = "---";
    private static final String SKILL_FILE = "SKILL.md";

    @Override
    public boolean supports(Path skillDir) {
        if (skillDir == null || !Files.isDirectory(skillDir)) return false;
        return Files.exists(skillDir.resolve(SKILL_FILE));
    }

    @Override
    public Skill load(Path skillDir) throws IOException {
        Path mdPath = skillDir.resolve(SKILL_FILE);
        if (!Files.exists(mdPath)) {
            throw new IOException("SKILL.md not found in " + skillDir);
        }
        String content = Files.readString(mdPath, StandardCharsets.UTF_8);
        Parsed parsed = parseFrontMatter(content);
        String defaultName = skillDir.getFileName() != null
            ? skillDir.getFileName().toString()
            : "skill-" + System.nanoTime();
        SkillMetadata meta = buildMetadata(parsed.frontMatter(), parsed.body(), defaultName);
        return new DefaultSkill(meta, skillDir, mdPath);
    }

    @Override
    public List<Skill> loadAll(Path rootDir) throws IOException {
        if (rootDir == null || !Files.isDirectory(rootDir)) {
            return List.of();
        }
        List<Skill> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootDir, 4)) {
            walk.filter(Files::isDirectory)
                .filter(this::supports)
                .forEach(dir -> {
                    try {
                        out.add(load(dir));
                    } catch (IOException e) {
                        log.warn("Skipping skill dir {} due to error: {}", dir, e.getMessage());
                    }
                });
        }
        return out;
    }

    /**
     * Extract YAML front-matter and Markdown body from a file's text content.
     * If no front-matter is present, returns empty front-matter and full body.
     */
    static Parsed parseFrontMatter(String content) {
        if (content == null) return new Parsed(Map.of(), "");
        String normalized = content.replace("\r\n", "\n");
        // Tolerate a UTF-8 BOM (Windows editors / PowerShell-created skill zips).
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        if (!normalized.startsWith(FRONT_MATTER_DELIM + "\n") &&
            !normalized.startsWith(FRONT_MATTER_DELIM + "\r")) {
            return new Parsed(Map.of(), normalized);
        }
        int start = FRONT_MATTER_DELIM.length() + 1;
        int end = normalized.indexOf("\n" + FRONT_MATTER_DELIM, start);
        if (end < 0) return new Parsed(Map.of(), normalized);
        String yaml = normalized.substring(start, end);
        int bodyStart = end + ("\n" + FRONT_MATTER_DELIM).length();
        // skip trailing newline after closing delim
        if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') bodyStart++;
        String body = normalized.substring(bodyStart);
        Map<String, Object> map;
        try {
            Object loaded = new Yaml().load(yaml);
            map = (loaded instanceof Map) ? castStringKeyed((Map<?, ?>) loaded) : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse SKILL.md front-matter: {}", e.getMessage());
            map = Map.of();
        }
        return new Parsed(map, body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyed(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }

    private SkillMetadata buildMetadata(Map<String, Object> fm, String body, String defaultName) {
        String name = str(fm, "name", defaultName);
        String description = str(fm, "description", firstNonBlankLine(body));
        SkillMetadata.Builder b = SkillMetadata.builder(name)
            .description(description)
            .version(str(fm, "version", "0.0.1"))
            .author(str(fm, "author", ""))
            .triggers(strList(fm.get("triggers")))
            .requiredTools(strList(coalesce(fm, "required_tools", "requiredTools")))
            .requiredSkills(strList(coalesce(fm, "required_skills", "requiredSkills")))
            .sandboxed(bool(fm.get("sandboxed"), false))
            .executionMode(str(fm,
                fm.containsKey("execution_mode") ? "execution_mode" : "executionMode",
                SkillMetadata.MODE_SEQUENTIAL))
            .env(strMap(fm.get("env")))
            .steps(parseSteps(fm.get("steps")));
        return b.build();
    }

    private static Object coalesce(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.containsKey(k)) return m.get(k);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<SkillMetadata.StepDefinition> parseSteps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<SkillMetadata.StepDefinition> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> step = castStringKeyed(m);
            String name = str(step, "name", "step-" + out.size());
            String type = str(step, "type", "tool");
            String target = str(step, "target", "");
            Map<String, Object> params = step.get("params") instanceof Map<?, ?> p
                ? castStringKeyed(p) : Map.of();
            String condition = str(step, "condition", null);
            String outputVar = str(step, "output_var", null);
            int timeout = intVal(step.get("timeout_seconds"), 30);
            out.add(new SkillMetadata.StepDefinition(name, type, target, params, condition, outputVar, timeout));
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key, String defv) {
        Object v = m.get(key);
        return v == null ? defv : v.toString();
    }

    private static List<String> strList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object x : l) if (x != null) out.add(x.toString());
            return out;
        }
        return List.of(v.toString());
    }

    private static Map<String, String> strMap(Object v) {
        if (!(v instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().toString(), e.getValue().toString());
            }
        }
        return out;
    }

    private static boolean bool(Object v, boolean defv) {
        if (v == null) return defv;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static int intVal(Object v, int defv) {
        if (v == null) return defv;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException ignored) { return defv; }
    }

    private static String firstNonBlankLine(String body) {
        if (body == null) return "";
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) return t;
        }
        // fallback: first non-blank line even if header
        for (String line : body.split("\n", -1)) {
            String t = line.replaceFirst("^#+\\s*", "").trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** Internal record for front-matter split. */
    record Parsed(Map<String, Object> frontMatter, String body) {}
}
