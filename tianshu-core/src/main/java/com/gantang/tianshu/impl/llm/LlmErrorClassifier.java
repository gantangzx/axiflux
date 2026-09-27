package com.gantang.tianshu.impl.llm;

/**
 * Classifies LLM / model-provider failures so the web layer can answer with
 * the right HTTP status and an actionable message instead of a bare 500.
 *
 * <p>Authentication / configuration problems (bad or missing API key, 401/403)
 * are the operator's to fix and map to {@code 503 Service Unavailable} with a
 * configuration hint; a provider being temporarily unreachable maps the same
 * way but with a retry-oriented message. Everything else is an ordinary
 * internal error.
 */
public final class LlmErrorClassifier {

    public enum Kind {
        /** Invalid/missing API key, 401/403, rejected credentials — a configuration problem. */
        AUTH_CONFIG,
        /** Provider unreachable: connection refused, timeout, 502/503 — transient, try another model. */
        UNAVAILABLE,
        /** Prompt exceeded the model's context window — needs compaction before retry. */
        OVERFLOW,
        /** Commercial plan gate: the caller must upgrade (maps to HTTP 402). */
        PLAN_GATE,
        /** Anything else. */
        OTHER
    }

    private LlmErrorClassifier() {}

    public static Kind classify(Throwable e) {
        boolean sawUnavailable = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName().toLowerCase();
            String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";

            // Commercial gate is the most specific, non-retryable signal: detect
            // it first so it is never mistaken for a transient provider failure.
            if (t instanceof com.gantang.tianshu.impl.llm.router.AdvancedModelGateException
                || name.contains("advancedmodelgate")) {
                return Kind.PLAN_GATE;
            }

            if (name.contains("authentication")
                || name.contains("unauthorized")
                || name.contains("forbidden")
                || name.contains("credential")
                || name.contains("invalidkey")
                || msg.contains("api key")
                || msg.contains("api-key")
                || msg.contains("apikey")
                || msg.contains("invalid token")
                || msg.contains("unauthorized")
                || msg.contains("authentication")
                // P2-10: bare "401"/"403" substrings can hit unrelated numbers
                // (port ids, counts); require an HTTP-ish context.
                || msg.contains("http 401")
                || msg.contains("http 403")
                || msg.contains("status 401")
                || msg.contains("status 403")
                || msg.contains("status code 401")
                || msg.contains("status code 403")
                || msg.contains("401 unauthorized")
                || msg.contains("403 forbidden")) {
                // Auth is the more specific root cause; report it even if an
                // outer wrapper looks like a timeout.
                return Kind.AUTH_CONFIG;
            }
            if (isOverflowMessage(msg)) {
                // Context-window overflow is actionable only by shrinking the
                // prompt (compaction), so it gets its own kind ahead of generic
                // unavailability.
                return Kind.OVERFLOW;
            }
            if (name.contains("connectexception")
                || name.contains("unknownhost")
                || name.contains("sockettimeout")
                || name.contains("connecttimeoutexception")
                || name.contains("timeoutexception")  // incl. Reactor .timeout() → java.util.concurrent.TimeoutException
                || msg.contains("connection refused")
                || msg.contains("connection reset")
                || msg.contains("unknown host")
                || msg.contains("timed out")
                || msg.contains("timeout")
                || msg.contains("502")
                || msg.contains("503")
                || msg.contains("bad gateway")
                || msg.contains("service unavailable")
                || msg.contains("unavailable")) {
                sawUnavailable = true;
            }
        }
        return sawUnavailable ? Kind.UNAVAILABLE : Kind.OTHER;
    }

    /**
     * Provider-specific context-overflow error phrasing across OpenAI, Anthropic,
     * Gemini, Bedrock, Ollama, OpenRouter and the OpenAI-compatible Chinese
     * providers (ARK/DeepSeek/Qwen). Matched on the lower-cased message.
     */
    private static boolean isOverflowMessage(String msg) {
        return msg.contains("context length")
            || msg.contains("context_length")
            || msg.contains("context window")
            || msg.contains("maximum context")
            || msg.contains("too many tokens")
            || msg.contains("request too large")
            || msg.contains("request_too_large")
            || msg.contains("input is too long")
            || msg.contains("input too long")
            || msg.contains("maximum number of tokens")
            || msg.contains("maximum tokens")
            || msg.contains("token limit")
            || msg.contains("prompt is too long")
            || msg.contains("prompt too long")
            || msg.contains("exceeds the maximum")
            || msg.contains("exceed the maximum")
            || msg.contains("exceeded the maximum")
            || msg.contains("reduce the length")
            || msg.contains("string too long")
            || msg.contains("上下文长度")
            || msg.contains("上下文窗口")
            // P2-10: a bare "超出" also matches rate-limit/quota phrasing
            // ("请求频率超出限制"), which must NOT trigger context compaction;
            // require an explicit context/token anchor.
            || msg.contains("超出上下文")
            || msg.contains("上下文超出")
            || msg.contains("超出模型")
            || msg.contains("超出限制") && (msg.contains("上下文") || msg.contains("token"))
            || msg.contains("tokens") && (msg.contains("maximum") || msg.contains("limit") || msg.contains("exceed"));
    }

    /** Human-friendly, non-leaky message for the classified failure. */
    public static String friendlyMessage(Kind kind, Throwable e) {
        return switch (kind) {
            case AUTH_CONFIG ->
                "LLM 认证失败或未配置有效的 API key（模型提供方拒绝访问，HTTP 401/403）。"
                + "请检查 tianshu.llm 的 api-key / 端点配置是否正确。";
            case OVERFLOW ->
                "对话上下文超出模型上下文窗口，已自动压缩历史并重试；若仍失败，请新开会话或减少单次输入长度。";
            case UNAVAILABLE ->
                "LLM 服务暂时不可达（连接超时或被拒绝）。请稍后重试，或检查模型提供方端点与网络。";
            case PLAN_GATE -> e.getMessage();
            case OTHER -> "Error: " + e.getMessage();
        };
    }
}
