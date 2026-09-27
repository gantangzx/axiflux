package com.gantang.tianshu.impl.agent;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orchestrates a best-of-n critique group (DelegationRequest.Mode#CRITIQUE):
 * N independent answerer sessions run the same task in parallel (different
 * approaches via independent sampling + an explicit role split), then one critic
 * session scores them and returns the best answer or a synthesis (following
 * "More Agents Is All You Need").
 *
 * <p>Branch failures are tolerated; a single surviving branch is used directly
 * (critic adds no value), and the critic failing degrades to the longest branch
 * answer. When a final answer is produced it is handed to the aggregation phase
 * via {@code onComplete}.
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1) with no
 * behavioural change.
 */
final class CritiqueOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CritiqueOrchestrator.class);

    private final SubAgentChildPipeline pipe;
    private final SubAgentEventBus events;
    private final Consumer<SubAgentTaskInfo> onComplete;

    CritiqueOrchestrator(SubAgentChildPipeline pipe, SubAgentEventBus events,
                         Consumer<SubAgentTaskInfo> onComplete) {
        this.pipe = pipe;
        this.events = events;
        this.onComplete = onComplete;
    }

    /** One answerer branch's outcome. */
    private static final class BranchOutcome {
        final int index;
        final boolean ok;
        final String answer;      // presentable answer when ok
        final TurnUsage usage;
        final String error;
        BranchOutcome(int index, boolean ok, String answer, TurnUsage usage, String error) {
            this.index = index; this.ok = ok;
            this.answer = answer; this.usage = usage; this.error = error;
        }
    }

    /** Critic verdict: chosen is 1-based branch number, 0 means a free synthesis. */
    private record CriticVerdict(String answer, int chosen, TurnUsage usage) {}

    Mono<Void> runGroup(SubAgentTaskInfo ti, String childUserId, int childDepth,
                        List<String> branchSessions) {
        int n = ti.n;

        return Flux.range(0, n)
            .flatMap(idx -> runBranch(ti, childUserId, childDepth, idx, branchSessions.get(idx)), n)
            .collectList()
            .flatMap(outcomes -> {
                if (ti.cancelled.get()) return Mono.empty();
                List<BranchOutcome> ok = outcomes.stream().filter(o -> o.ok).toList();
                for (BranchOutcome o : outcomes) {
                    // Tokens were spent even on branches that terminated in failure
                    // (ERROR status carried on DONE); account for them regardless.
                    if (o.usage != null) ti.accumulate(o.usage);
                }

                if (ok.isEmpty()) {
                    ti.status = "failed";
                    String errors = outcomes.stream()
                        .map(o -> "分支" + (o.index + 1) + ": " + o.error).toList().toString();
                    log.warn("critique task={} all {} branches failed", ti.taskId, n);
                    events.emit(ti, "spawn_failed", Map.of(
                        "taskId", ti.taskId, "parentSessionId", ti.parentSessionId,
                        "mode", "critique",
                        "error", "所有 " + n + " 个并行分支均失败 " + errors));
                    return Mono.empty();
                }
                if (ok.size() < n) {
                    log.info("critique task={}: {}/{} branches succeeded, continuing",
                        ti.taskId, ok.size(), n);
                }

                if (ok.size() == 1) {
                    // Only one survivor: a critic with a single candidate adds no
                    // value; use it directly but flag the degradation.
                    BranchOutcome only = ok.get(0);
                    ti.criticDegraded = true;
                    return finish(ti, outcomes, ok, only.answer, only.index + 1, null);
                }
                return runCritic(ti, childUserId, childDepth, ok)
                    .flatMap(critic -> finish(ti, outcomes, ok,
                        critic.answer(), critic.chosen(), critic.usage()))
                    .onErrorResume(e -> {
                        log.warn("critique critic turn failed task={}, degrading to longest branch: {}",
                            ti.taskId, e.toString());
                        ti.criticDegraded = true;
                        return finish(ti, outcomes, ok, null, 0, null);
                    });
            })
            .then();
    }

    private Mono<BranchOutcome> runBranch(SubAgentTaskInfo ti, String childUserId, int childDepth,
                                          int idx, String sessionId) {
        events.emit(ti, "spawn_branch_start", Map.of(
            "taskId", ti.taskId, "parentSessionId", ti.parentSessionId,
            "index", idx, "childSessionId", sessionId));
        // Nudge independent approaches; temperature sampling already differs, but
        // an explicit role split reduces near-duplicate answers.
        String branchTask = ti.task
            + "\n\n[编排提示] 你是 " + ti.n + " 个独立解题者中的第 " + (idx + 1)
            + " 个。请采用与其他解题者不同的角度/方案独立完成，直接给出完整结论，不要提及本提示。";
        return pipe.prepareChild(sessionId, childUserId, childDepth, ti.parentSessionId, ti.taskId)
            .thenMany(Flux.defer(() -> pipe.agent.processStream(AgentContext.builder()
                .sessionId(sessionId).userId(childUserId).currentQuery(branchTask)
                .metadata(SubAgentChildPipeline.childMeta(ti.caller, childDepth))
                .build())))
            .doOnNext(ev -> pipe.forwardChildEvent(ti, ev, idx))
            .filter(ev -> ev.type() == AgentEvent.Type.DONE)
            .next()
            .map(ev -> {
                AgentResponse resp = pipe.parseDone(ev);
                TurnUsage usage = TurnUsage.fromMetadata(resp != null ? resp.metadata() : null);
                String fail = SubAgentChildPipeline.terminalFailure(resp);
                if (fail != null) {
                    BranchOutcome o = new BranchOutcome(idx, false, "", usage, fail);
                    log.warn("critique branch {} task={} terminal failure: {}", idx + 1, ti.taskId, fail);
                    events.emit(ti, "spawn_branch_done", Map.of(
                        "taskId", ti.taskId, "index", idx, "status", "failed",
                        "error", fail));
                    return o;
                }
                String answer = SubAgentChildPipeline.presentableAnswer(resp);
                BranchOutcome o = new BranchOutcome(idx, true, answer, usage, null);
                events.emit(ti, "spawn_branch_done", Map.of(
                    "taskId", ti.taskId, "index", idx, "status", "success",
                    "answerLength", answer.length(),
                    "inputTokens", usage.inputTokens(),
                    "outputTokens", usage.outputTokens()));
                return o;
            })
            .onErrorResume(e -> {
                String msg = e.getMessage() != null ? e.getMessage() : "branch error";
                BranchOutcome o = new BranchOutcome(idx, false, "", new TurnUsage(), msg);
                log.warn("critique branch {} task={} failed: {}", idx + 1, ti.taskId, e.toString());
                events.emit(ti, "spawn_branch_done", Map.of(
                    "taskId", ti.taskId, "index", idx, "status", "failed",
                    "error", msg));
                return Mono.just(o);
            });
    }

    private Mono<CriticVerdict> runCritic(SubAgentTaskInfo ti, String childUserId,
                                          int childDepth, List<BranchOutcome> branches) {
        String criticSession = SubAgentChildPipeline.newChildSessionId(childUserId);
        ti.addChild(criticSession);
        ti.criticSessionId = criticSession;
        events.emit(ti, "spawn_critic_start", Map.of(
            "taskId", ti.taskId, "childSessionId", criticSession,
            "candidates", branches.size()));

        StringBuilder prompt = new StringBuilder();
        prompt.append("下面给你同一个高价值任务的 ").append(branches.size())
            .append(" 个独立解题方案。你是严格的评审者：请从正确性、完整性、风险与可执行性角度比较，选出最佳方案（1 基编号），"
                + "或在最佳方案基础上综合出一个更完善的最终答案。不要和稀泥，明确指出落选方案的关键缺陷。\n\n")
            .append("===== 原始任务 =====\n").append(ti.task).append("\n");
        for (BranchOutcome b : branches) {
            prompt.append("\n===== 方案 ").append(b.index + 1).append(" =====\n")
                .append(b.answer).append("\n");
        }
        prompt.append("\n===== 输出格式（严格遵守，不要输出其他内容）=====\n")
            .append("[[CHOSEN]]\n")
            .append("选中方案的编号（1-").append(branches.size())
            .append("；若你做了跨方案综合则填 0）\n")
            .append("[[ANSWER]]\n")
            .append("最终交付给用户的完整答案（中文）\n");

        return pipe.prepareChild(criticSession, childUserId, childDepth, ti.parentSessionId, ti.taskId)
            .thenMany(Flux.defer(() -> pipe.agent.processStream(AgentContext.builder()
                .sessionId(criticSession).userId(childUserId).currentQuery(prompt.toString())
                .metadata(SubAgentChildPipeline.childMeta(ti.caller, childDepth))
                .build())))
            .doOnNext(ev -> pipe.forwardChildEvent(ti, ev, -1))
            .filter(ev -> ev.type() == AgentEvent.Type.DONE)
            .next()
            .map(ev -> {
                AgentResponse resp = pipe.parseDone(ev);
                TurnUsage usage = TurnUsage.fromMetadata(resp.metadata());
                String raw = resp.content() != null ? resp.content() : "";
                int chosen = 0;
                String answer = raw;
                int mIdx = raw.indexOf("[[CHOSEN]]");
                int aIdx = raw.indexOf("[[ANSWER]]");
                if (mIdx >= 0 && aIdx > mIdx) {
                    String num = raw.substring(mIdx + "[[CHOSEN]]".length(), aIdx).trim();
                    try { chosen = Integer.parseInt(num.replaceAll("[^0-9-]", "")); }
                    catch (NumberFormatException ignored) { chosen = 0; }
                    answer = raw.substring(aIdx + "[[ANSWER]]".length()).trim();
                }
                if (answer.isBlank()) {
                    answer = raw.isBlank() ? SubAgentChildPipeline.presentableAnswer(resp) : raw;
                }
                if (chosen < 0 || chosen > branches.size()) chosen = 0;
                return new CriticVerdict(answer, chosen, usage);
            });
    }

    private Mono<Void> finish(SubAgentTaskInfo ti, List<BranchOutcome> all,
                              List<BranchOutcome> branches, String finalAnswer,
                              int chosen, TurnUsage criticUsage) {
        if (criticUsage != null) ti.accumulate(criticUsage);
        if (finalAnswer == null || finalAnswer.isBlank()) {
            // Critic produced nothing usable: longest successful branch answer wins.
            BranchOutcome longest = branches.get(0);
            for (BranchOutcome b : branches) {
                if (b.answer.length() > longest.answer.length()) longest = b;
            }
            finalAnswer = longest.answer;
            chosen = longest.index + 1;
            ti.criticDegraded = true;
        }
        ti.status = "done";
        ti.result = finalAnswer;
        ti.chosenBranch = chosen;

        List<Map<String, Object>> branchInfos = new ArrayList<>();
        for (BranchOutcome b : all) {
            Map<String, Object> bi = new HashMap<>();
            bi.put("index", b.index);
            bi.put("status", b.ok ? "success" : "failed");
            bi.put("answerLength", b.answer.length());
            bi.put("inputTokens", b.usage.inputTokens());
            bi.put("outputTokens", b.usage.outputTokens());
            bi.put("modelCalls", b.usage.modelCallsTotal());
            if (!b.ok) bi.put("error", b.error);
            branchInfos.add(bi);
        }
        ti.branchSummaries = branchInfos;

        Map<String, Object> criticInfo = new HashMap<>();
        criticInfo.put("chosen", chosen);
        criticInfo.put("degraded", ti.criticDegraded);
        if (criticUsage != null) {
            criticInfo.put("inputTokens", criticUsage.inputTokens());
            criticInfo.put("outputTokens", criticUsage.outputTokens());
        }
        ti.criticInfo = criticInfo;

        log.info("critique done task={} branches={}/{} chosen={} degraded={} tokens in={} out={}",
            ti.taskId, branches.size(), ti.n, chosen, ti.criticDegraded,
            ti.inputTokens, ti.outputTokens);

        Map<String, Object> fields = ResultFields.of(ti, finalAnswer);
        fields.put("branches", branchInfos);
        fields.put("critic", criticInfo);
        events.emit(ti, "spawn_result", fields);
        onComplete.accept(ti);
        return Mono.empty();
    }
}
