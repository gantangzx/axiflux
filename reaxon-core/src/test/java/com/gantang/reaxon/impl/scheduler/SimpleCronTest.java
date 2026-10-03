package com.gantang.reaxon.impl.scheduler;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SimpleCronTest {

    @Test
    void every_minute_advances_by_one_minute() {
        SimpleCron c = SimpleCron.parse("* * * * *");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        LocalDateTime next = c.next(base);
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 1), next);
    }

    @Test
    void step_expression_every_five_minutes() {
        SimpleCron c = SimpleCron.parse("*/5 * * * *");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 2, 0);
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 5), c.next(base));
    }

    @Test
    void hour_and_minute_specific() {
        SimpleCron c = SimpleCron.parse("30 8 * * *");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 8, 0);
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 30), c.next(base));
        assertEquals(LocalDateTime.of(2026, 1, 2, 8, 30),
            c.next(LocalDateTime.of(2026, 1, 1, 8, 30)));
    }

    @Test
    void day_of_week_monday_only() {
        SimpleCron c = SimpleCron.parse("0 9 * * 1");   // 09:00 every Monday
        // 2026-01-05 is a Monday
        LocalDateTime sun = LocalDateTime.of(2026, 1, 4, 10, 0);
        assertEquals(LocalDateTime.of(2026, 1, 5, 9, 0), c.next(sun));
    }

    @Test
    void invalid_field_count_throws() {
        assertThrows(IllegalArgumentException.class, () -> SimpleCron.parse("1 2 3"));
    }

    @Test
    void range_and_list() {
        SimpleCron c = SimpleCron.parse("0,15,30,45 9-17 * * 1-5");
        LocalDateTime base = LocalDateTime.of(2026, 1, 5, 9, 0);  // Monday 09:00
        assertEquals(LocalDateTime.of(2026, 1, 5, 9, 15), c.next(base));
    }

    // ===== P0 fix: zero/negative step must be rejected, not hang the thread =====

    @Test
    void zero_step_throws_instead_of_hanging() {
        assertThrows(IllegalArgumentException.class, () -> SimpleCron.parse("*/0 * * * *"));
    }

    @Test
    void negative_step_throws_instead_of_hanging() {
        assertThrows(IllegalArgumentException.class, () -> SimpleCron.parse("*/-5 * * * *"));
    }

    // ===== P0 fix: 1-7 (MON-SUN, extremely common) must parse, not corrupt to 1-0 =====

    @Test
    void dow_range_1_to_7_means_every_day() {
        SimpleCron c = SimpleCron.parse("0 9 * * 1-7");   // 09:00 every day of the week
        // 2026-01-04 is a Sunday; next 09:00 after it should be the same day if before 9,
        // else Monday. Use a Saturday base so the next fire is Sunday (proves 7 is included).
        LocalDateTime sat = LocalDateTime.of(2026, 1, 3, 10, 0);  // Saturday 10:00
        assertEquals(LocalDateTime.of(2026, 1, 4, 9, 0), c.next(sat)); // Sunday 09:00
    }

    @Test
    void dow_7_alone_means_sunday() {
        SimpleCron c = SimpleCron.parse("0 9 * * 7");   // 09:00 Sunday only
        LocalDateTime sat = LocalDateTime.of(2026, 1, 3, 10, 0);  // Saturday
        assertEquals(LocalDateTime.of(2026, 1, 4, 9, 0), c.next(sat)); // Sunday
    }

    @Test
    void dow_0_also_means_sunday() {
        SimpleCron c = SimpleCron.parse("0 9 * * 0");   // classic cron 0 = Sunday
        LocalDateTime sat = LocalDateTime.of(2026, 1, 3, 10, 0);
        assertEquals(LocalDateTime.of(2026, 1, 4, 9, 0), c.next(sat));
    }

    @Test
    void dow_step_7_still_terminates() {
        // */7 over 0-7 yields {0,7} -> Sunday; must not hang and must parse.
        SimpleCron c = SimpleCron.parse("0 9 * * */7");
        LocalDateTime sat = LocalDateTime.of(2026, 1, 3, 10, 0);
        assertEquals(LocalDateTime.of(2026, 1, 4, 9, 0), c.next(sat)); // Sunday
    }

    // ===== P2-6: DOM/DOW are ANDed (documented; differs from POSIX OR) =====

    @Test
    void dom_and_dow_are_ANDed_not_ORed() {
        // "0 9 5 * 1" = 09:00 when the 5th of the month is ALSO a Monday.
        // POSIX cron would fire on every 5th AND every Monday; SimpleCron requires both.
        SimpleCron c = SimpleCron.parse("0 9 5 * 1");
        // 2026-01-05 is a Monday and the 5th -> matches both, so it fires then.
        LocalDateTime before = LocalDateTime.of(2026, 1, 1, 0, 0); // Thursday Jan 1
        LocalDateTime next = c.next(before);
        assertEquals(LocalDateTime.of(2026, 1, 5, 9, 0), next,
            "first day that is both the 5th and a Monday (P2-6 AND semantics)");

        // A plain 5th that is NOT a Monday must NOT fire: 2026-02-05 is a Thursday.
        // After Jan 5, the next match is 2026-04-06? No: the next month where the 5th
        // is a Monday. 2026-05-05 is a Tuesday; 2026-06-05 is a Friday. Verify the
        // next fire after Jan 5 09:00 is NOT Feb 5 (Thursday) — proving it's not OR.
        LocalDateTime after = c.next(LocalDateTime.of(2026, 1, 5, 9, 0));
        assertNotEquals(LocalDateTime.of(2026, 2, 5, 9, 0), after,
            "a non-Monday 5th must not fire (would be the POSIX OR behaviour)");
        assertEquals(1, after.getDayOfWeek().getValue(), "next fire must be a Monday");
        assertEquals(5, after.getDayOfMonth(), "next fire must be the 5th");
    }
}
