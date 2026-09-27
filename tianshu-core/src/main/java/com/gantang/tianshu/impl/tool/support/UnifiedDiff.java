package com.gantang.tianshu.impl.tool.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal line-based unified diff generator (LCS), no external dependency.
 *
 * <p>Produces standard {@code --- / +++ / @@} hunks that the console renders
 * with red/green lines. Intended for small/medium source files: the LCS table is
 * {@code O(n*m)} ints, so callers should cap input size (the file tools cap at a
 * few hundred KB).
 */
public final class UnifiedDiff {

    private UnifiedDiff() {}

    private enum Kind { EQUAL, DEL, INS }

    private record Op(Kind kind, String text) {}

    /**
     * @param label    file label shown in the {@code ---}/{@code +++} headers
     * @param oldText  original content
     * @param newText  modified content
     * @param context  number of unchanged context lines around each hunk (typically 3)
     */
    public static String of(String label, String oldText, String newText, int context) {
        List<String> a = splitLines(oldText);
        List<String> b = splitLines(newText);

        // LCS dynamic programming table.
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                    ? dp[i + 1][j + 1] + 1
                    : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }

        // Backtrack into an edit script, tracking old/new line positions.
        List<Op> ops = new ArrayList<>();
        List<Integer> oldNo = new ArrayList<>();
        List<Integer> newNo = new ArrayList<>();
        int i = 0, j = 0, oLine = 1, nLine = 1;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new Op(Kind.EQUAL, a.get(i)));
                oldNo.add(oLine++); newNo.add(nLine++);
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new Op(Kind.DEL, a.get(i)));
                oldNo.add(oLine++); newNo.add(0);
                i++;
            } else {
                ops.add(new Op(Kind.INS, b.get(j)));
                oldNo.add(0); newNo.add(nLine++);
                j++;
            }
        }
        while (i < n) { ops.add(new Op(Kind.DEL, a.get(i))); oldNo.add(oLine++); newNo.add(0); i++; }
        while (j < m) { ops.add(new Op(Kind.INS, b.get(j))); oldNo.add(0); newNo.add(nLine++); j++; }

        if (ops.stream().noneMatch(op -> op.kind() != Kind.EQUAL)) {
            return ""; // no changes
        }

        // Group change ops into hunks separated by runs of > 2*context equal lines.
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(label).append('\n');
        out.append("+++ b/").append(label).append('\n');

        int idx = 0;
        int total = ops.size();
        while (idx < total) {
            // advance to next change
            while (idx < total && ops.get(idx).kind() == Kind.EQUAL) idx++;
            if (idx >= total) break;
            int firstChange = idx;
            int lastChange = idx;
            int runEqual = 0;
            int k = idx;
            while (k < total) {
                if (ops.get(k).kind() == Kind.EQUAL) {
                    runEqual++;
                    if (runEqual > 2 * context) break;
                } else {
                    runEqual = 0;
                    lastChange = k;
                }
                k++;
            }
            int hunkStart = Math.max(0, firstChange - context);
            int hunkEnd = Math.min(total, lastChange + 1 + context);

            // compute hunk start line numbers and counts
            int hunkOldStart = 0, hunkNewStart = 0;
            int oldCount = 0, newCount = 0;
            for (int p = hunkStart; p < hunkEnd; p++) {
                Op op = ops.get(p);
                if (op.kind() == Kind.EQUAL) {
                    if (hunkOldStart == 0) { hunkOldStart = oldNo.get(p); hunkNewStart = newNo.get(p); }
                    oldCount++; newCount++;
                } else if (op.kind() == Kind.DEL) {
                    if (hunkOldStart == 0) {
                        hunkOldStart = oldNo.get(p);
                        hunkNewStart = p > 0 && newNo.get(p - 1) > 0 ? newNo.get(p - 1) : (newNo.get(p) > 0 ? newNo.get(p) : 1);
                    }
                    oldCount++;
                } else { // INS
                    if (hunkNewStart == 0) {
                        hunkNewStart = newNo.get(p);
                        hunkOldStart = p > 0 && oldNo.get(p - 1) > 0 ? oldNo.get(p - 1) : (oldNo.get(p) > 0 ? oldNo.get(p) : 1);
                    }
                    newCount++;
                }
            }
            if (hunkOldStart == 0) hunkOldStart = 1;
            if (hunkNewStart == 0) hunkNewStart = 1;

            out.append("@@ -").append(hunkOldStart);
            if (oldCount != 1) out.append(',').append(oldCount);
            out.append(" +").append(hunkNewStart);
            if (newCount != 1) out.append(',').append(newCount);
            out.append(" @@\n");

            for (int p = hunkStart; p < hunkEnd; p++) {
                Op op = ops.get(p);
                char prefix = switch (op.kind()) {
                    case EQUAL -> ' ';
                    case DEL -> '-';
                    case INS -> '+';
                };
                out.append(prefix).append(op.text()).append('\n');
            }

            idx = hunkEnd;
        }
        return out.toString();
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        // Drop a single trailing empty segment caused by a final newline so we don't
        // render a spurious blank line; real blank lines in the middle are kept.
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (int k = 0; k < parts.length; k++) {
            if (k == parts.length - 1 && parts[k].isEmpty()) break;
            lines.add(parts[k]);
        }
        return lines;
    }
}
