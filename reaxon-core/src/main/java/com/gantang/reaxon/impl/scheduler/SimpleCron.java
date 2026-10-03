package com.gantang.reaxon.impl.scheduler;

import com.gantang.reaxon.api.scheduler.CronExpression;

import java.time.*;
import java.util.*;

/**
 * Minimal 5-field cron parser: {@code minute hour day month day_of_week}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Numbers</li>
 *   <li>Ranges: {@code 0-5}</li>
 *   <li>Lists:  {@code 1,2,3}</li>
 *   <li>Steps:  {@code * / 5} or {@code 0-30 / 10}</li>
 *   <li>Wildcards: {@code *}</li>
 * </ul>
 *
 * <p><b<DOM/DOW semantics (P2-6):</b> when both day-of-month and day-of-week are
 * restricted (non-wildcard), this implementation requires <b>both</b> to match
 * (AND). This differs from classic POSIX cron, which fires when <b>either</b>
 * matches (OR). E.g. {@code 0 9 1 * 1} fires at 09:00 only when the 1st of the
 * month is also a Monday — not on every 1st and every Monday. Keep one of the
 * two fields as {@code *} to get intuitive behaviour.
 *
 * <p>Not a full Quartz-style parser; adequate for Axiflux's typical reminder
 * / periodic use cases.  Written in-house to keep reaxon-core free of a
 * Spring dependency.
 */
public final class SimpleCron implements CronExpression {

    private final int[] minutes;
    private final int[] hours;
    private final int[] daysOfMonth;
    private final int[] months;
    private final int[] daysOfWeek; // 1 = MON .. 7 = SUN (ISO)

    private SimpleCron(int[] minutes, int[] hours, int[] daysOfMonth, int[] months, int[] daysOfWeek) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
    }

    public static SimpleCron parse(String expr) {
        if (expr == null || expr.isBlank()) throw new IllegalArgumentException("empty cron");
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("cron must have 5 fields, got " + parts.length + ": " + expr);
        }
        return new SimpleCron(
            parseField(parts[0], 0, 59),
            parseField(parts[1], 0, 23),
            parseField(parts[2], 1, 31),
            parseField(parts[3], 1, 12),
            parseDow(parts[4]));
    }

    /** Compute the next fire time strictly after {@code from}. */
    @Override
    public LocalDateTime next(LocalDateTime from) {
        LocalDateTime candidate = from.withSecond(0).withNano(0).plusMinutes(1);
        for (int guard = 0; guard < 366 * 24 * 60; guard++) {
            if (!contains(months, candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            if (!contains(daysOfMonth, candidate.getDayOfMonth())
                || !contains(daysOfWeek, candidate.getDayOfWeek().getValue())) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!contains(hours, candidate.getHour())) {
                candidate = candidate.plusHours(1).withMinute(0);
                continue;
            }
            if (!contains(minutes, candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException("could not find next fire time within 1y for cron");
    }

    private static int[] parseField(String field, int min, int max) {
        Set<Integer> out = new TreeSet<>();
        for (String segment : field.split(",")) {
            int step = 1;
            String body = segment;
            int slash = segment.indexOf('/');
            if (slash > 0) {
                step = Integer.parseInt(segment.substring(slash + 1));
                body = segment.substring(0, slash);
                // step must be >= 1, otherwise the fill loop below never advances
                // (*/0 or x/-5 would spin `for(i=lo;i<=hi;i+=0)` forever and hang
                // the calling thread).
                if (step < 1) {
                    throw new IllegalArgumentException("cron step must be >= 1 in field '" + field + "'");
                }
            }
            int lo, hi;
            if ("*".equals(body)) {
                lo = min; hi = max;
            } else if (body.contains("-")) {
                String[] range = body.split("-");
                lo = Integer.parseInt(range[0]);
                hi = Integer.parseInt(range[1]);
            } else {
                lo = hi = Integer.parseInt(body);
            }
            if (lo < min || hi > max || lo > hi) {
                throw new IllegalArgumentException("field '" + field + "' out of range [" + min + "," + max + "]");
            }
            for (int i = lo; i <= hi; i += step) out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] parseDow(String field) {
        // In classic cron, both 0 and 7 mean Sunday. Parse over the full 0-7
        // domain (so common forms like 1-7 = MON-SUN and */7 are accepted), then
        // normalize BY VALUE: 7 -> 0 (Sunday), keeping 0 as Sunday too. This must
        // be a numeric remap, NOT a string replace — replace("7","0") corrupts
        // "1-7" into "1-0" (lo>hi) and "*/7" into "*/0" (a zero step).
        int[] raw = parseField(field, 0, 7);
        Set<Integer> iso = new TreeSet<>();
        for (int v : raw) iso.add(v == 7 ? 7 : (v == 0 ? 7 : v)); // 0/7 -> ISO 7 (SUN); 1-6 unchanged
        return iso.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean contains(int[] arr, int v) {
        for (int i : arr) if (i == v) return true;
        return false;
    }
}
