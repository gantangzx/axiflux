package com.gantang.tianshu.impl.skill.strategy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillResult;

import java.util.*;

/**
 * Mutable per-execution workflow state:
 *  - input, skill, agent context
 *  - variables (populated by step {@code output_var})
 *  - accumulated step results
 *
 * <p>Not thread-safe by itself; strategies that run steps in parallel must
 * guard writes.
 */
public final class WorkflowContext {

    private final Skill skill;
    private final String input;
    private final AgentContext agentContext;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final List<SkillResult.StepResult> stepResults =
        Collections.synchronizedList(new ArrayList<>());
    private volatile String lastOutput = "";

    public WorkflowContext(Skill skill, String input, AgentContext agentContext) {
        this.skill = Objects.requireNonNull(skill);
        this.input = input != null ? input : "";
        this.agentContext = Objects.requireNonNull(agentContext);
        variables.put("input", this.input);
        variables.put("session_id", agentContext.sessionId());
        variables.put("user_id", agentContext.userId());
    }

    public Skill skill()                        { return skill; }
    public String input()                       { return input; }
    public AgentContext agentContext()          { return agentContext; }
    public Map<String, Object> variables()      { return variables; }
    public List<SkillResult.StepResult> stepResults() { return stepResults; }
    public String finalOutput()                 { return lastOutput; }
    public String lastOutput()                  { return lastOutput; }
    public void setLastOutput(String v)         { this.lastOutput = v == null ? "" : v; }
    public void setVariable(String k, Object v) { variables.put(k, v); }
    public int nextStepIndex()                  { return stepResults.size(); }
    public void addResult(SkillResult.StepResult r) { stepResults.add(r); }
}
