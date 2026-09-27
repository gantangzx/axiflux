package com.gantang.tianshu.api.scheduler;

/**
 * Parses cron expression strings into {@link CronExpression} values.
 *
 * <p>P1-4: the parser itself is an impl-level concern (each backend has its
 * own dialect / quirk set), so callers receive it as an interface and Spring
 * wires the concrete {@code SimpleCron} parser as a bean.
 */
@FunctionalInterface
public interface CronExpressionParser {

    /**
     * @throws IllegalArgumentException when {@code expr} is blank or malformed
     */
    CronExpression parse(String expr);
}