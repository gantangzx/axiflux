package com.gantang.reaxon.impl.tool;

import com.gantang.reaxon.api.tool.ToolResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounds tool output before it enters session history.
 *
 * <p>Every byte of a tool result lands in the transcript and the next model
 * call, so oversized output is sliced at this persistence boundary. Slicing is
 * tool-aware:
 * <ul>
 *   <li>cuts happen on line boundaries (never mid-line),</li>
 *   <li>head/tail ratio and cap follow the tool kind — search/diff results are
 *       head-heavy (the top is most relevant), code reads keep more tail,
 *       stack traces keep a balanced tail for the root cause,</li>
 *   <li>the omission marker tells the model how to retrieve the middle
 *       (offset re-read, narrower glob, …) instead of blindly dropping it.</li>
 * </ul>
 * Error results always pass through untouched.
 */
public final class ToolResultPruner {

    /** Default cap in characters (~1/4 to 1 token per char depending on script). */
    public static final int DEFAULT_MAX_CHARS = 12_000;

    private static final double DEFAULT_HEAD_RATIO = 0.75;

    /** Per-tool tuning: cap (chars) and share of the budget given to the head. */
    private record Profile(int cap, double headRatio, String hint) {
        static Profile of(int cap, double headRatio, String hint) {
            return new Profile(cap, headRatio, hint);
        }
    }

    private static final Map<String, Profile> PROFILES = Map.of(
        // Code reads: generous cap; head and tail both matter (imports vs. closing
        // context). Middle is retrievable via offset.
        "file_read", Profile.of(16_000, 0.60,
            "use file_read with offset/maxBytes to read the middle"),
        // Search results: the summary header + first files' matches are the most
        // relevant; later entries are deeper/less relevant.
        "grep_search", Profile.of(14_000, 0.85,
            "narrow the path/glob or raise maxResults for more matches"),
        // Diff/status/log: the beginning (file list, first hunks) carries the gist.
        "git", Profile.of(14_000, 0.70,
            "use git diff with a path to see specific changes"),
        // Web content: keep the top of the document.
        "web_fetch", Profile.of(14_000, 0.70,
            "fetch a more specific URL for the rest"),
        "http_client", Profile.of(14_000, 0.70,
            "refine the request for the rest")
    );

    private volatile int maxChars;
    private volatile boolean capConfigured;

    public ToolResultPruner() {
        this(DEFAULT_MAX_CHARS);
    }

    public ToolResultPruner(int maxChars) {
        this.maxChars = maxChars > 0 ? maxChars : Integer.MAX_VALUE;
        this.capConfigured = maxChars > 0;
    }

    /**
     * Update the cap at runtime (config API); non-positive values are ignored.
     * An explicitly configured value tightens tool-specific caps; the built-in
     * default does not.
     */
    public void setMaxChars(int maxChars) {
        if (maxChars > 0) {
            this.maxChars = maxChars;
            this.capConfigured = true;
        }
    }

    /** Backward-compatible entry point: tool-agnostic default profile. */
    public ToolResult prune(ToolResult result) {
        return prune(result, null, null);
    }

    /** Backward-compatible entry point: no side-storage handle. */
    public ToolResult prune(ToolResult result, String toolName) {
        return prune(result, toolName, null);
    }

    /**
     * Whether {@code content} from {@code toolName} would be pruned (i.e. it is
     * large enough to warrant parking in the side store).
     */
    public boolean wouldTruncate(String content, String toolName) {
        if (content == null) return false;
        return content.length() > effectiveCap(toolName);
    }

    private int effectiveCap(String toolName) {
        Profile defaultProfile = Profile.of(maxChars, DEFAULT_HEAD_RATIO,
            "refine the request for the rest");
        Profile profile = toolName == null ? null : PROFILES.get(toolName);
        if (profile == null) {
            profile = defaultProfile;
        }
        return capConfigured && maxChars < DEFAULT_MAX_CHARS
            ? Math.min(maxChars, profile.cap())
            : profile.cap();
    }

    /**
     * Return a result whose {@code content} fits the cap for the given tool;
     * error messages pass through.
     *
     * @param toolName tool that produced the result (may be {@code null})
     * @param handle   {@code ref://tool-result/<id>} of the full content parked in
     *                 the side store, or {@code null}; when present the omission
     *                 marker teaches the model how to page via {@code result_read}
     */
    public ToolResult prune(ToolResult result, String toolName, String handle) {
        if (result == null) {
            return null;
        }
        String content = result.content();
        if (content == null) {
            return result;
        }
        Profile defaultProfile = Profile.of(maxChars, DEFAULT_HEAD_RATIO,
            "refine the request for the rest");
        Profile profile = toolName == null ? null : PROFILES.get(toolName);
        if (profile == null) {
            profile = defaultProfile;
        }
        // An explicitly configured context budget tightens every cap; the built-in
        // default leaves tool-specific (larger) caps intact.
        int cap = effectiveCap(toolName);
        if (content.length() <= cap) {
            return result;
        }

        String sliced = sliceOnLines(content, cap, profile.headRatio(), profile.hint(), handle);

        Map<String, Object> metadata = new HashMap<>(
            result.metadata() != null ? result.metadata() : Map.of());
        metadata.put("truncated", true);
        metadata.put("originalLength", content.length());
        if (toolName != null) {
            metadata.put("prunedTool", toolName);
        }

        return new ToolResult(result.callId(), result.success(), sliced,
            result.errorMessage(), Map.copyOf(metadata));
    }

    /**
     * Head/tail slice that never breaks a line: consumes whole lines from the
     * top up to {@code cap*headRatio}, then whole lines from the bottom with
     * the remaining budget, and inserts an omission marker between them.
     */
    static String sliceOnLines(String content, int cap, double headRatio, String hint,
                               String handle) {
        String[] lines = content.split("\n", -1);
        int headBudget = (int) (cap * headRatio);
        int tailBudget = cap - headBudget;

        int headEnd = 0;
        int headChars = 0;
        while (headEnd < lines.length) {
            int len = lines[headEnd].length() + 1; // +1 for the '\n'
            if (headChars + len > headBudget && headEnd > 0) {
                break;
            }
            headChars += len;
            headEnd++;
        }

        int tailStart = lines.length;
        int tailChars = 0;
        while (tailStart > headEnd) {
            int len = lines[tailStart - 1].length() + 1;
            if (tailChars + len > tailBudget && tailStart < lines.length) {
                break;
            }
            tailChars += len;
            tailStart--;
        }

        // Degenerate case (e.g. one giant line with no newlines): fall back to a
        // hard character slice so the cap always holds.
        if (tailStart <= headEnd) {
            int head = headBudget;
            int tail = cap - head;
            int omitted = content.length() - cap;
            return content.substring(0, head)
                + marker(omitted, content.length() - cap, hint, handle, headEnd + 1)
                + content.substring(content.length() - tail);
        }

        StringBuilder sb = new StringBuilder(cap + 300);
        for (int i = 0; i < headEnd; i++) {
            sb.append(lines[i]).append('\n');
        }
        int omittedLines = tailStart - headEnd;
        int omittedChars = 0;
        for (int i = headEnd; i < tailStart; i++) {
            omittedChars += lines[i].length() + 1;
        }
        sb.append(marker(omittedChars, omittedLines, hint, handle, headEnd + 1));
        for (int i = tailStart; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String marker(int omittedChars, int omittedLines, String hint,
                                 String handle, int nextLine) {
        if (handle != null && !handle.isBlank()) {
            return "\n...[output truncated: " + omittedChars + " chars / " + omittedLines
                + " lines omitted; full output parked at " + handle
                + " — call result_read(ref=\"" + handle + "\", offset=" + nextLine
                + ", limit=200) to page, or add keyword=\"...\" to search]...\n";
        }
        return "\n...[output truncated: " + omittedChars + " chars / " + omittedLines
            + " lines omitted; " + hint + "]...\n";
    }
}
