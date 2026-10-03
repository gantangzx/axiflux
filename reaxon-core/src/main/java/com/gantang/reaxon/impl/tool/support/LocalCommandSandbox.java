package com.gantang.reaxon.impl.tool.support;

import com.gantang.reaxon.api.tool.CommandSandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * Default sandbox: run the command directly on the host via the platform shell.
 *
 * <p><b>The {@code command} argument is a complete shell command line.</b> On
 * Windows it runs as {@code cmd.exe /c <command>}, on POSIX as
 * {@code sh -c <command>} — every shell metacharacter ({@code &}, {@code |},
 * {@code >}, {@code ;}, command substitution) is interpreted by the shell
 * (audit toolsec P2-5). Callers must therefore treat the whole line as
 * attacker-controlled input: the approval gate around this sandbox is the only
 * review step, and approvers must read the entire command, including anything
 * chained after a separator.
 *
 * <p>Relies on the surrounding controls for safety — the tool is classified
 * DESTRUCTIVE and approval-gated, the environment is scrubbed of secrets, and
 * execution is timeout- and output-bounded. No network or filesystem isolation
 * is provided; use a container sandbox for untrusted commands. The working
 * directory is expected to have been vetted by the caller
 * ({@code CodeExecutorTool} runs it through {@code PathGuard} against the
 * file allowed-roots before it reaches this class).
 */
public final class LocalCommandSandbox implements CommandSandbox {

    @Override
    public String name() {
        return "local";
    }

    @Override
    public ProcessBuilderSpec build(boolean hostWindows, String command, Path workDir) {
        List<String> argv = hostWindows
            ? List.of("cmd", "/c", "chcp 65001 >nul && " + command)
            : List.of("sh", "-c", command);
        return new ProcessBuilderSpec(argv, workDir);
    }
}
