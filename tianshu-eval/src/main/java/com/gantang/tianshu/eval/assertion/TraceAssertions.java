package com.gantang.tianshu.eval.assertion;

import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.trace.Trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a scenario's declared expectations against the observed trace.
 * Returns a list of human-readable failure descriptions (empty = pass).
 *
 * <p>Assertions target observable behavior only: final response, tool-call
 * sequence/arguments, tool results, emitted events, and terminal status.
 */
public final class TraceAssertions {

    private TraceAssertions() {}

    public static List<String> evaluate(Trace trace, Scenario.Expect expect) {
        List<String> failures = new ArrayList<>();
        if (expect == null) {
            return failures;
        }

        // Whitespace-insensitive for natural-language assertions: Chinese typesetting
        // freely inserts/omits spaces ("8号" vs "8 号"), and models drift between
        // them. Both sides are normalized (all whitespace stripped) so the assertion
        // text can read naturally. Structured tool-result assertions stay exact.
        String finalText = normalizeWs(trace.finalResponse() == null ? "" : trace.finalResponse());
        List<Trace.ToolInvocation> calls = trace.toolInvocations();

        // --- final response content ---
        for (String s : expect.finalContains()) {
            if (!finalText.contains(normalizeWs(s))) {
                failures.add("finalResponse should contain '" + s + "', actual: "
                        + abbreviate(trace.finalResponse()));
            }
        }
        for (String s : expect.finalNotContains()) {
            if (finalText.contains(normalizeWs(s))) {
                failures.add("finalResponse should NOT contain '" + s + "', actual: "
                        + abbreviate(trace.finalResponse()));
            }
        }

        // --- tool call counts ---
        for (Scenario.ToolCallExpect e : expect.toolCalled()) {
            List<Trace.ToolInvocation> matching = calls.stream()
                    .filter(c -> c.name().equals(e.name())).toList();
            long n = matching.size();
            if (e.times() != null) {
                if (n != e.times()) {
                    failures.add("tool '" + e.name() + "' expected " + e.times()
                            + " call(s), actual " + n);
                }
            } else if (n < 1) {
                failures.add("tool '" + e.name() + "' expected to be called at least once, never was");
            }
            if (!e.argsContains().isEmpty()) {
                boolean anyMatch = matching.stream().anyMatch(c -> argsMatch(c, e.argsContains()));
                if (!anyMatch) {
                    failures.add("tool '" + e.name() + "' called with unexpected arguments; expected "
                            + e.argsContains() + ", actual: " + matching.stream()
                            .map(c -> String.valueOf(c.args())).toList());
                }
            }
            if (e.fromTurn() != null) {
                List<Trace.ToolInvocation> early = matching.stream()
                        .filter(c -> c.turn() < e.fromTurn()).toList();
                if (!early.isEmpty()) {
                    failures.add("tool '" + e.name() + "' must not be called before turn "
                            + e.fromTurn() + ", but was called on turn(s) "
                            + early.stream().map(c -> String.valueOf(c.turn())).toList()
                            + " (missing info should trigger a clarifying question first)");
                }
            }
        }
        for (String name : expect.toolNotCalled()) {
            long n = calls.stream().filter(c -> c.name().equals(name)).count();
            if (n > 0) {
                failures.add("tool '" + name + "' should NOT be called, but was called " + n + " time(s)");
            }
        }

        // --- tool call order (subsequence match) ---
        if (!expect.toolOrder().isEmpty()) {
            List<String> actual = calls.stream().map(Trace.ToolInvocation::name).toList();
            if (!containsSubsequence(actual, expect.toolOrder())) {
                failures.add("tool call order expected to contain " + expect.toolOrder()
                        + " in order, actual order: " + actual);
            }
        }

        // --- tool results ---
        for (Scenario.ToolResultExpect e : expect.toolResult()) {
            List<Trace.ToolInvocation> matching = calls.stream()
                    .filter(c -> c.name().equals(e.name())).toList();
            if (matching.isEmpty()) {
                failures.add("toolResult expectation on '" + e.name() + "': tool was never called");
                continue;
            }
            boolean anyMatch = matching.stream().anyMatch(c -> resultMatches(c, e));
            if (!anyMatch) {
                failures.add("toolResult expectation on '" + e.name() + "' not met: "
                        + "resultContains='" + e.resultContains() + "' isError=" + e.isError()
                        + "; results were: " + matching.stream()
                            .map(c -> "(success=" + c.success() + ", "
                                    + abbreviate(c.success() ? c.result() : c.error()) + ")")
                            .toList());
            }
        }

        // --- events ---
        for (String ev : expect.eventsContains()) {
            if (!trace.eventTypes().contains(ev)) {
                failures.add("event '" + ev + "' should be emitted, but was not; event sequence: "
                        + trace.eventTypes());
            }
        }
        for (String ev : expect.eventsNone()) {
            if (trace.eventTypes().contains(ev)) {
                failures.add("event '" + ev + "' should not be emitted, but was; event sequence: "
                        + trace.eventTypes());
            }
        }

        // --- terminal status ---
        if (expect.status() != null && !expect.status().isBlank()) {
            if (!expect.status().equals(trace.status())) {
                failures.add("terminal status expected " + expect.status()
                        + ", actual " + trace.status());
            }
        }

        // --- hard errors during the run always fail ---
        for (String err : trace.errors()) {
            failures.add("scenario run raised: " + err);
        }

        return failures;
    }

    private static boolean resultMatches(Trace.ToolInvocation c, Scenario.ToolResultExpect e) {
        if (e.isError() != null && e.isError() != !c.success()) {
            return false;
        }
        if (e.resultContains() != null && !e.resultContains().isBlank()) {
            String haystack = c.success()
                    ? (c.result() != null ? c.result() : "")
                    : (c.error() != null ? c.error() : "");
            if (!haystack.contains(e.resultContains())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsSubsequence(List<String> actual, List<String> expected) {
        int i = 0;
        for (String a : actual) {
            if (i < expected.size() && a.equals(expected.get(i))) {
                i++;
            }
        }
        return i == expected.size();
    }

    /** Strip all whitespace for natural-language substring matching. */
    private static String normalizeWs(String s) {
        return s.replaceAll("\\s+", "");
    }

    /** True when every expected parameter is present and its value contains the needle. */
    private static boolean argsMatch(Trace.ToolInvocation call, Map<String, String> expected) {
        Map<String, Object> args = call.args();
        if (args == null) return expected.isEmpty();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Object raw = args.get(e.getKey());
            if (raw == null) return false;
            String value = normalizeWs(String.valueOf(raw));
            if (!value.contains(normalizeWs(e.getValue()))) return false;
        }
        return true;
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        s = s.strip();
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }
}
