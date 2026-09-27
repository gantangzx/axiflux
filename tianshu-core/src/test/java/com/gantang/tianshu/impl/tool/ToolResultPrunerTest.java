package com.gantang.tianshu.impl.tool;

import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultPrunerTest {

    @Test
    void shortContent_passesThroughUnchanged() {
        ToolResult r = ToolResult.success("c1", "x".repeat(100));
        assertSame(r, new ToolResultPruner(1000).prune(r));
    }

    @Test
    void oversizedContent_isHeadTailSlicedAndFlagged() {
        ToolResult r = ToolResult.success("c1", "A".repeat(900) + "B".repeat(900));
        ToolResult out = new ToolResultPruner(1000).prune(r);

        assertNotSame(r, out);
        assertTrue(out.content().length() < r.content().length(), "content must shrink");
        assertTrue(out.content().contains("[output truncated:"), "omission marker required");
        // head and tail both preserved
        assertTrue(out.content().startsWith("AAAA"));
        assertTrue(out.content().endsWith("BBBB"));
        assertEquals(Boolean.TRUE, out.metadata().get("truncated"));
        assertEquals(1800, out.metadata().get("originalLength"));
        // call identity / success flag preserved
        assertEquals("c1", out.callId());
        assertTrue(out.success());
    }

    @Test
    void errorResultWithNullContent_passesThrough() {
        ToolResult r = ToolResult.failure("c1", "boom");
        assertSame(r, new ToolResultPruner(10).prune(r));
    }

    @Test
    void defaultCap_isApplied() {
        ToolResult r = ToolResult.success("c1", "x".repeat(ToolResultPruner.DEFAULT_MAX_CHARS + 5000));
        ToolResult out = new ToolResultPruner().prune(r);
        assertEquals(Boolean.TRUE, out.metadata().get("truncated"));
    }

    @Test
    void slicesOnLineBoundaries_neverBreakingALine() {
        // 400 lines of 40 chars each = 16k chars; cap to ~2000 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 400; i++) {
            sb.append("L").append(String.format("%03d", i)).append(" ").append("x".repeat(35)).append("\n");
        }
        ToolResult r = ToolResult.success("c1", sb.toString());
        ToolResult out = new ToolResultPruner(2000).prune(r);

        // Every retained content line (outside the marker itself) must be a full
        // original line — line numbers L001.. must appear intact.
        String body = out.content().replaceAll("\\.\\.\\.\\[output truncated:[^\\n]*\\]\\.\\.\\.", "");
        for (String line : body.split("\n")) {
            if (line.isBlank()) continue;
            assertTrue(line.matches("L\\d{3} x{35}"), "broken line: '" + line + "'");
        }
        assertTrue(out.content().contains("lines omitted"));
        // head kept from the top
        assertTrue(out.content().contains("L001"));
        // tail kept from the bottom
        assertTrue(out.content().contains("L400"));
    }

    @Test
    void toolAwareProfile_appliesLargerCapForFileRead() {
        // 13k chars of lines: above default 12k cap but below file_read's 16k cap
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 325; i++) {
            sb.append("line ").append(i).append(" ").append("y".repeat(34)).append("\n");
        }
        ToolResult r = ToolResult.success("c1", sb.toString());
        ToolResultPruner pruner = new ToolResultPruner(); // default 12k global

        ToolResult generic = pruner.prune(r, null);
        assertEquals(Boolean.TRUE, generic.metadata().get("truncated"), "default cap should truncate 13k");

        ToolResult read = pruner.prune(r, "file_read");
        assertSame(r, read, "file_read cap is 16k — 13k must pass through");
    }

    @Test
    void grepProfile_isHeadHeavyAndMarksPrunedTool() {
        StringBuilder sb = new StringBuilder("100 matches in 20 files for /foo/\n");
        for (int f = 1; f <= 20; f++) {
            sb.append("\nFile").append(f).append(".java\n");
            for (int i = 0; i < 15; i++) {
                sb.append("  ").append(i * 10 + 1).append(": foo bar ").append("z".repeat(40)).append("\n");
            }
        }
        ToolResult r = ToolResult.success("c1", sb.toString());
        ToolResult out = new ToolResultPruner().prune(r, "grep_search");

        // head-heavy: the summary header and early files survive
        assertTrue(out.content().startsWith("100 matches"), out.content().substring(0, 60));
        assertTrue(out.content().contains("File1.java"));
        assertFalse(out.content().contains("File16.java"), "middle files should be omitted in head-heavy mode");
        assertFalse(out.content().contains("File17.java"), "middle files should be omitted in head-heavy mode");
        assertEquals("grep_search", out.metadata().get("prunedTool"));
        assertTrue(out.content().contains("narrow the path/glob"));
    }

    @Test
    void globalCapTightens_toolProfile() {
        ToolResult r = ToolResult.success("c1", "q".repeat(15_000));
        ToolResultPruner tight = new ToolResultPruner(2000);
        ToolResult out = tight.prune(r, "file_read"); // file_read wants 16k
        assertEquals(Boolean.TRUE, out.metadata().get("truncated"));
        assertTrue(out.content().length() < 4000, "global cap must win when tighter");
    }

    @Test
    void withHandle_markerTeachesResultReadPaging() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 400; i++) sb.append("line").append(i).append('\n');
        ToolResult r = ToolResult.success("c1", sb.toString());
        ToolResult out = new ToolResultPruner(2000).prune(r, "file_read", "ref://tool-result/tr_abc");

        assertTrue(out.content().contains("full output parked at ref://tool-result/tr_abc"));
        assertTrue(out.content().contains("result_read(ref=\"ref://tool-result/tr_abc\""));
        assertTrue(out.content().contains("offset="), "marker must state the next page offset");
    }

    @Test
    void withoutHandle_keepsLegacyRefinementHint() {
        ToolResult r = ToolResult.success("c1", "z".repeat(15_000));
        ToolResult out = new ToolResultPruner(2000).prune(r, "file_read");
        assertTrue(out.content().contains("use file_read with offset/maxBytes"));
    }

    @Test
    void wouldTruncate_matchesPruningDecision() {
        ToolResultPruner p = new ToolResultPruner();
        assertTrue(p.wouldTruncate("x".repeat(16_001), "file_read"));
        assertFalse(p.wouldTruncate("x".repeat(100), "file_read"));
        assertFalse(p.wouldTruncate(null, "file_read"));
    }
}
