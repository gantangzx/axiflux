package com.gantang.reaxon.api.scheduler;

import java.time.LocalDateTime;

/**
 * A parsed cron expression: given a reference instant, compute the next fire
 * time strictly after it. Implementations are immutable and thread-safe.
 *
 * <p>P1-4: the SPI surface that {@link com.gantang.reaxon.api.scheduler.TaskScheduler}
 * consumers (e.g. the Spring service layer) need in order to project the next
 * run without depending on a concrete parser. The parsing logic itself lives in
 * the impl package and is reached through {@link CronExpressionParser}, keeping
 * the API layer free of any concrete parser.
 */
public interface CronExpression {

    /**
     * Compute the next fire time strictly after {@code from}. May return
     * {@code null} when no future match exists within the parser's time
     * horizon (e.g. a fixed-anchor cron that has already passed).
     */
    LocalDateTime next(LocalDateTime from);
}