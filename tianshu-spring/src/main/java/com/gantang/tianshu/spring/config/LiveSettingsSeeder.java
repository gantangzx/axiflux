package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.spring.config.props.AgentProperties;
import com.gantang.tianshu.spring.config.props.ToolsProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the process-wide {@link LiveSettings} snapshot from the
 * {@code tianshu.tools} / {@code tianshu.agent} configuration properties at
 * boot. Console changes afterwards mutate the same bean directly (see
 * {@code RuntimeConfigService}); this only seeds it.
 */
public final class LiveSettingsSeeder {

    private LiveSettingsSeeder() {}

    public static LiveSettings from(ToolsProperties tools, AgentProperties agent) {
        ToolsProperties tp = tools != null
            ? tools : new ToolsProperties();
        AgentProperties ap = agent != null
            ? agent : new AgentProperties();

        String mode = (tp.getPolicyMode() == null || tp.getPolicyMode().isBlank())
            ? "all" : tp.getPolicyMode().trim().toUpperCase(Locale.ROOT);

        return new LiveSettings(LiveSettings.builder()
            .maxIterations(ap.getMaxIterations())
            .toolTimeoutSeconds(ap.getToolTimeoutSeconds())
            .toolPolicyMode(mode)
            .allowedTools(splitCsv(tp.getPolicyAllowed()))
            .blockedTools(splitCsv(tp.getPolicyBlocked()))
            .autoApproveTools(splitCsv(tp.getAutoApprove()))
            .allowPrivateNetwork(tp.isAllowPrivateNetwork())
            .fileAllowedRoots(parseRoots(tp.getFileAllowedRoots()))
            .budgetEnabled(tp.isAutoApproveBudgetEnabled())
            .budgetRiskCeiling(tp.getAutoApproveBudgetRiskCeiling() == null
                ? "WRITE" : tp.getAutoApproveBudgetRiskCeiling().trim().toUpperCase(Locale.ROOT))
            .budgetDefault(tp.getAutoApproveBudgetDefault())
            .toolBudgets(tp.getAutoApproveBudgets())
            .build());
    }

    static Set<String> splitCsv(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split("[,;]")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static List<Path> parseRoots(String csv) {
        return FileRootsResolver.resolve(csv);
    }
}
