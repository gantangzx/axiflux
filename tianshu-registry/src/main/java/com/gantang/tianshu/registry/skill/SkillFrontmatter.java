package com.gantang.tianshu.registry.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML-frontmatter reader for SKILL.md files.
 *
 * <p>Supports the subset skills actually use:
 * <ul>
 *   <li>{@code key: value} scalars</li>
 *   <li>block sequences: {@code key:} followed by {@code  - item} lines</li>
 *   <li>inline sequences: {@code key: [a, b, c]}</li>
 * </ul>
 * Snake/kebab/camel key variants are normalized by callers.
 */
public final class SkillFrontmatter {

    private SkillFrontmatter() {}

    /** Parse the frontmatter block; empty map when the document has none. */
    public static Map<String, Object> parse(String markdown) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (markdown == null) return out;
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int start = 0;
        // Allow an optional leading BOM.
        if (lines.length > 0 && lines[0].replace("﻿", "").strip().equals("---")) {
            start = 1;
        } else {
            return out;
        }
        String currentListKey = null;
        List<String> currentList = null;
        for (int i = start; i < lines.length; i++) {
            String raw = lines[i];
            String stripped = raw.strip();
            if (stripped.equals("---")) break;
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;

            String listItem = blockListItem(stripped);
            if (listItem != null && currentListKey != null) {
                currentList.add(unquote(listItem));
                continue;
            }
            // Flush previous list.
            currentListKey = null;
            currentList = null;

            int colon = indexOfColon(stripped);
            if (colon < 0) continue;
            String key = stripped.substring(0, colon).strip();
            String value = stripped.substring(colon + 1).strip();
            if (key.isEmpty()) continue;

            if (value.isEmpty()) {
                // Block sequence follows.
                currentListKey = key;
                currentList = new ArrayList<>();
                out.put(key, currentList);
            } else if (value.startsWith("[") && value.endsWith("]")) {
                String inner = value.substring(1, value.length() - 1).trim();
                List<String> items = new ArrayList<>();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String v = part.strip();
                        if (!v.isEmpty()) items.add(unquote(v));
                    }
                }
                out.put(key, items);
            } else {
                out.put(key, unquote(value));
            }
        }
        return out;
    }

    /** Return the trimmed item text for a {@code - item} block line, else null. */
    private static String blockListItem(String stripped) {
        if (stripped.startsWith("- ")) return stripped.substring(2).strip();
        if (stripped.equals("-")) return "";
        return null;
    }

    /** First ':' that isn't inside quotes (frontmatter values rarely contain colons). */
    private static int indexOfColon(String s) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static String unquote(String v) {
        if (v.length() >= 2) {
            char a = v.charAt(0), b = v.charAt(v.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    // -- typed accessors with key-alias tolerance ----------------------------------

    public static String str(Map<String, Object> fm, String... keys) {
        for (String k : keys) {
            Object v = fm.get(k);
            if (v instanceof String s && !s.isBlank()) return s.strip();
        }
        return null;
    }

    public static List<String> list(Map<String, Object> fm, String... keys) {
        for (String k : keys) {
            Object v = fm.get(k);
            if (v instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object o : l) {
                    if (o != null) {
                        String s = o.toString().strip();
                        if (!s.isEmpty()) out.add(s);
                    }
                }
                return out;
            }
            if (v instanceof String s && !s.isBlank()) return List.of(s.strip());
        }
        return List.of();
    }
}
