package com.gantang.reaxon.impl.llm;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class LlmErrorClassifierTest {

    @Test
    void authExceptionByClassName_mapsToAuthConfig() {
        // Stands in for dev.langchain4j.exception.AuthenticationException (not on core classpath).
        class AuthenticationException extends RuntimeException {
            AuthenticationException(String m) { super(m); }
        }
        assertEquals(LlmErrorClassifier.Kind.AUTH_CONFIG,
            LlmErrorClassifier.classify(new AuthenticationException("401 Unauthorized: bad api key")));
    }

    @Test
    void forbiddenMessage_mapsToAuthConfig() {
        assertEquals(LlmErrorClassifier.Kind.AUTH_CONFIG,
            LlmErrorClassifier.classify(new RuntimeException("HTTP 403 Forbidden")));
    }

    @Test
    void wrappedAuthCause_takesPrecedenceOverTimeoutWrapper() {
        Exception root = new RuntimeException("invalid api key");
        Exception wrapped = new RuntimeException("request failed", root);
        assertEquals(LlmErrorClassifier.Kind.AUTH_CONFIG, LlmErrorClassifier.classify(wrapped));
    }

    @Test
    void connectionRefused_mapsToUnavailable() {
        assertEquals(LlmErrorClassifier.Kind.UNAVAILABLE,
            LlmErrorClassifier.classify(new ConnectException("Connection refused")));
    }

    @Test
    void socketTimeout_mapsToUnavailable() {
        assertEquals(LlmErrorClassifier.Kind.UNAVAILABLE,
            LlmErrorClassifier.classify(new SocketTimeoutException("timed out")));
    }

    @Test
    void reactorTimeoutException_mapsToUnavailable() {
        // Reactor .timeout() raises a bare java.util.concurrent.TimeoutException
        // whose message does NOT contain the word "timeout" — the class name must
        // classify it so the watchdog timeout triggers the fallback chain.
        java.util.concurrent.TimeoutException reactorStyle =
            new java.util.concurrent.TimeoutException(
                "Did not observe any item or terminal signal within 120000ms");
        assertEquals(LlmErrorClassifier.Kind.UNAVAILABLE,
            LlmErrorClassifier.classify(reactorStyle));
    }

    @Test
    void genericError_mapsToOther() {
        assertEquals(LlmErrorClassifier.Kind.OTHER,
            LlmErrorClassifier.classify(new RuntimeException("NullPointerException somewhere")));
    }

    @Test
    void openaiContextLength_mapsToOverflow() {
        assertEquals(LlmErrorClassifier.Kind.OVERFLOW,
            LlmErrorClassifier.classify(new RuntimeException(
                "400 Bad Request: This model's maximum context length is 128000 tokens. "
                + "However, your messages resulted in 130522 tokens. Please reduce the length.")));
    }

    @Test
    void anthropicContextWindow_mapsToOverflow() {
        assertEquals(LlmErrorClassifier.Kind.OVERFLOW,
            LlmErrorClassifier.classify(new RuntimeException(
                "prompt is too long: 205000 tokens > 200000 maximum context window")));
    }

    @Test
    void bedrockRequestTooLarge_mapsToOverflow() {
        Exception root = new RuntimeException("request_too_large: input token count exceeds the maximum number of input tokens");
        Exception wrapped = new RuntimeException("Bedrock call failed", root);
        assertEquals(LlmErrorClassifier.Kind.OVERFLOW, LlmErrorClassifier.classify(wrapped));
    }

    @Test
    void chineseOverflow_mapsToOverflow() {
        assertEquals(LlmErrorClassifier.Kind.OVERFLOW,
            LlmErrorClassifier.classify(new RuntimeException("错误：上下文长度超出模型限制，请减少输入")));
    }

    // ===== P2-10: tightened matching — no false positives =====

    @Test
    void bareNumericSubstring_doesNotMapToAuth() {
        // "401" / "403" appearing as an unrelated number must not classify as auth.
        assertEquals(LlmErrorClassifier.Kind.OTHER,
            LlmErrorClassifier.classify(new RuntimeException("processed 40103 records, 40311 skipped")),
            "bare 401/403 substrings in unrelated numbers must not map to AUTH_CONFIG (P2-10)");
    }

    @Test
    void httpStatusWithContext_stillMapsToAuth() {
        assertEquals(LlmErrorClassifier.Kind.AUTH_CONFIG,
            LlmErrorClassifier.classify(new RuntimeException("HTTP 401 Unauthorized")));
        assertEquals(LlmErrorClassifier.Kind.AUTH_CONFIG,
            LlmErrorClassifier.classify(new RuntimeException("request failed with status 403")));
    }

    @Test
    void rateLimitChineseChaochu_doesNotMapToOverflow() {
        // "请求频率超出限制" is a rate-limit (transient), not a context overflow;
        // misclassifying it would trigger pointless compaction (P2-10).
        LlmErrorClassifier.Kind k = LlmErrorClassifier.classify(
            new RuntimeException("请求频率超出限制，请稍后重试"));
        assertNotEquals(LlmErrorClassifier.Kind.OVERFLOW, k,
            "rate-limit 超出 must not be treated as context overflow (P2-10)");
    }

    @Test
    void contextAnchoredChaochu_stillMapsToOverflow() {
        assertEquals(LlmErrorClassifier.Kind.OVERFLOW,
            LlmErrorClassifier.classify(new RuntimeException("输入超出上下文窗口，请压缩")));
    }

    @Test
    void overflowTakesPrecedenceOverGenericUnavailable() {
        // An overflow message contains "maximum"/"limit"; it must not collapse to UNAVAILABLE.
        assertEquals(LlmErrorClassifier.Kind.OVERFLOW,
            LlmErrorClassifier.classify(new RuntimeException("input is too long for the model's context window")));
    }

    @Test
    void friendlyMessage_isActionableForAuth() {
        String msg = LlmErrorClassifier.friendlyMessage(LlmErrorClassifier.Kind.AUTH_CONFIG,
            new RuntimeException("401"));
        assertTrue(msg.contains("API key") || msg.contains("api"), msg);
    }
}
