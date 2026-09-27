package com.gantang.tianshu.api.tool;

import java.util.Map;

/**
 * A tool call parsed from LLM output.
 */
public record ToolCall(
    String callId,
    String toolName,
    Map<String, Object> arguments
) {}
