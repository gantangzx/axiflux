package com.gantang.reaxon.eval.grading;

/**
 * One rubric line's score from the LLM judge.
 *
 * @param index     rubric line index (matches {@code Scenario.GradingSpec.rubric()})
 * @param criterion the criterion text, echoed for the report
 * @param score     1-5 (5 best); 0 when the judge failed for this line
 * @param reason    judge's short justification (Chinese/English per the prompt)
 * @param error     true when grading itself failed (judge unavailable / unparseable)
 */
public record GradeResult(int index, String criterion, int score, String reason, boolean error) {

    public static GradeResult of(int index, String criterion, int score, String reason) {
        return new GradeResult(index, criterion, Math.max(1, Math.min(5, score)), reason, false);
    }

    public static GradeResult failed(String reason) {
        return new GradeResult(-1, "(judge)", 0, reason, true);
    }
}
