package com.gantang.axiflux.spring.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.tool.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-function coverage for {@link LangChain4jLlmClientAdapter} — the single
 * egress for every LLM call in the system.
 *
 * <p>Everything exercised here is request/response <em>mapping</em>: no network,
 * no provider, no API cost. The adapter's factories only build model objects
 * (no connection is opened until a call is made), so a dummy key/base URL is
 * enough to obtain an instance.
 *
 * <p>Why this matters: a silent regression in {@code toOpenAiMessages} (tool
 * call/result pairing) or {@code cachedTokens} (per-provider usage shapes)
 * breaks either every tool loop or all cost accounting, and no other test in
 * the suite touches this class.
 */
class LangChain4jLlmClientAdapterTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Offline instance: factories build model objects without connecting. */
    private static LangChain4jLlmClientAdapter adapter() {
        return LangChain4jLlmClientAdapter.openAi("test-key", "http://127.0.0.1:1/v1", "test-model");
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== cachedTokens: per-provider usage shapes ====================

    @Nested
    @DisplayName("cachedTokens: prompt-cache hits across provider shapes")
    class CachedTokens {

        @Test
        void openAiAndArkShape() {
            // OpenAI / ARK: usage.prompt_tokens_details.cached_tokens
            assertEquals(1536, adapter().cachedTokens(json("""
                {"prompt_tokens":2048,"completion_tokens":64,
                 "prompt_tokens_details":{"cached_tokens":1536}}""")));
        }

        @Test
        void deepSeekShape() {
            // DeepSeek: usage.prompt_cache_hit_tokens
            assertEquals(900, adapter().cachedTokens(json("""
                {"prompt_tokens":1000,"prompt_cache_hit_tokens":900,"prompt_cache_miss_tokens":100}""")));
        }

        @Test
        void anthropicShape() {
            assertEquals(4096, adapter().cachedTokens(json("""
                {"prompt_tokens":5000,"cache_read_input_tokens":4096}""")));
        }

        @Test
        void absentOrZeroYieldsZero() {
            var a = adapter();
            assertEquals(0, a.cachedTokens(null), "null usage");
            assertEquals(0, a.cachedTokens(json("{\"prompt_tokens\":10}")), "no cache fields");
            assertEquals(0, a.cachedTokens(json("{\"prompt_tokens_details\":{\"cached_tokens\":0}}")),
                "explicit zero");
            assertEquals(0, a.cachedTokens(json("{\"prompt_tokens_details\":{}}")), "empty details");
        }

        @Test
        void openAiShapeWinsWhenSeveralArePresent() {
            // Defensive: a proxy that echoes several dialects must not double-count.
            assertEquals(700, adapter().cachedTokens(json("""
                {"prompt_tokens_details":{"cached_tokens":700},
                 "prompt_cache_hit_tokens":500,"cache_read_input_tokens":300}""")));
        }
    }

    // ==================== toOpenAiMessages: tool call/result pairing ====================

    @Nested
    @DisplayName("toOpenAiMessages: transcript -> OpenAI chat shape")
    class MessageMapping {

        private final LangChain4jLlmClientAdapter a = adapter();

        private ToolCall call(String id, String name) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", "/tmp/x");
            return new ToolCall(id, name, args);
        }

        @Test
        void answeredToolCallKeepsAssistantAndToolMessagePaired() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.system("sys"),
                Message.user("读一下文件"),
                Message.assistant(null, List.of(call("c1", "file_read"))),
                Message.tool("c1", "file contents")));

            assertEquals(4, out.size(), out.toString());
            assertEquals("system", out.get(0).path("role").asText());
            assertEquals("user", out.get(1).path("role").asText());

            JsonNode asst = out.get(2);
            assertEquals("assistant", asst.path("role").asText());
            assertTrue(asst.path("content").isNull(), "no text -> content must be explicit null");
            assertEquals(1, asst.path("tool_calls").size());
            JsonNode tc = asst.path("tool_calls").get(0);
            assertEquals("c1", tc.path("id").asText());
            assertEquals("function", tc.path("type").asText());
            assertEquals("file_read", tc.path("function").path("name").asText());
            // arguments must be a JSON *string*, not an object (OpenAI wire format)
            assertTrue(tc.path("function").path("arguments").isTextual(), "arguments must be stringified");
            assertEquals("/tmp/x",
                json(tc.path("function").path("arguments").asText()).path("path").asText());

            JsonNode tool = out.get(3);
            assertEquals("tool", tool.path("role").asText());
            assertEquals("c1", tool.path("tool_call_id").asText());
            assertEquals("file contents", tool.path("content").asText());
        }

        @Test
        void assistantTextIsKeptAlongsideAnsweredCalls() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.assistant("先看文件", List.of(call("c1", "file_read"))),
                Message.tool("c1", "ok")));

            assertEquals("先看文件", out.get(0).path("content").asText());
            assertEquals(1, out.get(0).path("tool_calls").size());
        }

        @Test
        void orphanToolCallWithoutTextDropsTheWholeAssistantTurn() {
            // Interrupted turn: assistant asked for a tool, result was never persisted.
            // OpenAI rejects a tool_call with no matching response; lenient providers
            // answer but silently disable reasoning output.
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.user("q"),
                Message.assistant(null, List.of(call("dead", "file_read")))));

            assertEquals(1, out.size(), "orphan-only assistant turn must be dropped: " + out);
            assertEquals("user", out.get(0).path("role").asText());
        }

        @Test
        void orphanToolCallWithTextDegradesToPlainAssistantText() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.assistant("我先解释一下", List.of(call("dead", "file_read")))));

            assertEquals(1, out.size());
            assertEquals("assistant", out.get(0).path("role").asText());
            assertEquals("我先解释一下", out.get(0).path("content").asText());
            assertFalse(out.get(0).has("tool_calls"), "orphan call must not be emitted");
        }

        @Test
        @DisplayName("a dropped stray tool response must NOT truncate the rest of the transcript")
        void strayToolResponseDoesNotSwallowLaterMessages() {
            // Regression guard for control flow: the TOOL branch uses `break` inside
            // an arrow-switch that sits in a for-loop. If that break ever targeted the
            // loop instead of the switch, one stray tool response would silently
            // truncate the whole conversation — invisible in production, catastrophic.
            //
            // NOTE the tool_call_id here is never emitted as an assistant tool_call,
            // which is what makes it stray and forces the `break`. (Pairing a response
            // WITH its assistant call — even a late one — is legitimately kept: see
            // lateToolResponseStillCountsAsAnswered below.)
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.user("第一个问题"),
                Message.tool("never-emitted", "结果来晚了"),   // stray -> hits `break`
                Message.user("第二个问题"),
                Message.assistant("答复", List.of())));

            List<String> roles = new java.util.ArrayList<>();
            out.forEach(n -> roles.add(n.path("role").asText()));
            assertEquals(List.of("user", "user", "assistant"), roles,
                "messages after the dropped stray tool response must survive: " + out);
            assertEquals("第二个问题", out.get(1).path("content").asText());
            assertEquals("答复", out.get(2).path("content").asText());
        }

        @Test
        void lateToolResponseStillCountsAsAnswered() {
            // Pass 1 scans the whole transcript for tool responses, so a result that
            // arrived after other turns still validates its call — the pair is kept.
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.assistant(null, List.of(call("c1", "file_read"))),
                Message.user("催一下"),
                Message.tool("c1", "结果来晚了")));

            List<String> roles = new java.util.ArrayList<>();
            out.forEach(n -> roles.add(n.path("role").asText()));
            assertEquals(List.of("assistant", "user", "tool"), roles, out.toString());
            assertEquals(1, out.get(0).path("tool_calls").size());
        }

        @Test
        void toolResponseWithoutMatchingCallIsDropped() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.user("q"),
                Message.tool("never-called", "orphan result")));

            assertEquals(1, out.size(), out.toString());
            assertEquals("user", out.get(0).path("role").asText());
        }

        @Test
        void nullToolContentBecomesEmptyStringNotNull() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.assistant(null, List.of(call("c1", "t"))),
                Message.tool("c1", null)));

            assertEquals("", out.get(1).path("content").asText(), "null tool content must not be JSON null");
        }

        @Test
        void blankAndNullTextMessagesAreDropped() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.system("   "),
                Message.user(""),
                Message.assistant("   ", List.of()),
                Message.user("real")));

            assertEquals(1, out.size(), "blank content must not reach the provider: " + out);
            assertEquals("real", out.get(0).path("content").asText());
        }

        @Test
        void nullAndEmptyTranscriptsAreSafe() {
            assertEquals(0, a.toOpenAiMessages(null).size());
            assertEquals(0, a.toOpenAiMessages(List.of()).size());
        }
    }

    // ==================== multimodal: image attachments ====================

    @Nested
    @DisplayName("multimodal: user image attachments")
    class MultimodalMapping {

        private final LangChain4jLlmClientAdapter a = adapter();

        @Test
        void langChainUserMessageWithImageBecomesMultimodal() {
            Message m = Message.user("看看这张图",
                Map.of("image", "data:image/png;base64,AAA"));

            List<dev.langchain4j.data.message.ChatMessage> out = a.toLangChainMessages(List.of(m));

            assertEquals(1, out.size());
            dev.langchain4j.data.message.UserMessage um =
                (dev.langchain4j.data.message.UserMessage) out.get(0);
            assertFalse(um.hasSingleText(), "multimodal message must not collapse to plain text");
            assertEquals(2, um.contents().size(), um.contents().toString());
            assertInstanceOf(dev.langchain4j.data.message.TextContent.class, um.contents().get(0));
            assertEquals("看看这张图",
                ((dev.langchain4j.data.message.TextContent) um.contents().get(0)).text());
            assertInstanceOf(dev.langchain4j.data.message.ImageContent.class, um.contents().get(1));
            assertEquals("data:image/png;base64,AAA",
                ((dev.langchain4j.data.message.ImageContent) um.contents().get(1)).image().url().toString());
        }

        @Test
        void langChainImageOnlyMessageStillEmitted() {
            // No text at all: pure-image user message must survive (LC4j allows it).
            Message m = Message.user("", Map.of("image", "https://example.com/x.png"));

            List<dev.langchain4j.data.message.ChatMessage> out = a.toLangChainMessages(List.of(m));

            assertEquals(1, out.size(), "image-only user message must not be dropped");
            dev.langchain4j.data.message.UserMessage um =
                (dev.langchain4j.data.message.UserMessage) out.get(0);
            assertEquals(1, um.contents().size());
            assertInstanceOf(dev.langchain4j.data.message.ImageContent.class, um.contents().get(0));
        }

        @Test
        void openAiUserMessageWithImageUsesContentArray() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.user("描述一下", Map.of("image", "data:image/png;base64,AAA"))));

            assertEquals(1, out.size(), out.toString());
            JsonNode n = out.get(0);
            assertEquals("user", n.path("role").asText());
            assertTrue(n.path("content").isArray(), "content must be the multimodal array shape");
            JsonNode parts = n.path("content");
            assertEquals(2, parts.size());
            assertEquals("text", parts.get(0).path("type").asText());
            assertEquals("描述一下", parts.get(0).path("text").asText());
            assertEquals("image_url", parts.get(1).path("type").asText());
            assertEquals("data:image/png;base64,AAA",
                parts.get(1).path("image_url").path("url").asText());
        }

        @Test
        void openAiUserMessageWithoutAttachmentsKeepsPlainStringContent() {
            // Existing wire behaviour must not change for text-only messages.
            ArrayNode out = a.toOpenAiMessages(List.of(Message.user("plain")));

            assertTrue(out.get(0).path("content").isTextual(),
                "text-only user messages must keep the plain-string content shape");
            assertEquals("plain", out.get(0).path("content").asText());
        }

        @Test
        void nonImageAttachmentsAreIgnoredForMultimodal() {
            ArrayNode out = a.toOpenAiMessages(List.of(
                Message.user("带上文件", Map.of("file", "https://example.com/a.pdf"))));

            assertTrue(out.get(0).path("content").isTextual(),
                "file attachments do not upgrade to the multimodal shape");
        }
    }

    // ==================== toOpenAiTools: schema wrapping ====================

    @Nested
    @DisplayName("toOpenAiTools: flat schema -> {type:function}")
    class ToolSchemaMapping {

        private final LangChain4jLlmClientAdapter a = adapter();

        @Test
        void wrapsNameDescriptionAndParameters() {
            ArrayNode out = a.toOpenAiTools(json("""
                [{"name":"file_read","description":"read a file",
                  "properties":{"path":{"type":"string"}},"required":["path"]}]"""));

            assertNotNull(out);
            assertEquals(1, out.size());
            JsonNode w = out.get(0);
            assertEquals("function", w.path("type").asText());
            assertEquals("file_read", w.path("function").path("name").asText());
            assertEquals("read a file", w.path("function").path("description").asText());
            JsonNode params = w.path("function").path("parameters");
            assertEquals("object", params.path("type").asText());
            assertEquals("string", params.path("properties").path("path").path("type").asText());
            assertEquals("path", params.path("required").get(0).asText());
        }

        @Test
        void missingPropertiesAndRequiredBecomeEmptyContainers() {
            // A bare object/array must still be present: some providers reject absent keys.
            JsonNode params = a.toOpenAiTools(json("[{\"name\":\"noargs\"}]"))
                .get(0).path("function").path("parameters");

            assertTrue(params.path("properties").isObject(), "properties must be an object");
            assertEquals(0, params.path("properties").size());
            assertTrue(params.path("required").isArray(), "required must be an array");
            assertEquals(0, params.path("required").size());
        }

        @Test
        void namelessEntriesAreSkipped() {
            ArrayNode out = a.toOpenAiTools(json("""
                [{"description":"no name"},{"name":"","description":"blank"},{"name":"good"}]"""));

            assertEquals(1, out.size(), out.toString());
            assertEquals("good", out.get(0).path("function").path("name").asText());
        }

        @Test
        void nullEmptyOrNonArrayYieldsNullSoNoToolsKeyIsSent() {
            var a2 = adapter();
            assertNull(a2.toOpenAiTools(null));
            assertNull(a2.toOpenAiTools(json("[]")));
            assertNull(a2.toOpenAiTools(json("{\"name\":\"not-an-array\"}")));
        }
    }

    // ==================== buildStreamRequest ====================

    @Nested
    @DisplayName("buildStreamRequest: SSE request body")
    class StreamRequest {

        @Test
        void carriesModelStreamFlagSamplingAndUsageOptIn() {
            ObjectNode body = adapter().buildStreamRequest(
                CompletionRequest.builder()
                    .messages(List.of(Message.user("hi")))
                    .temperature(0.3)
                    .maxTokens(256)
                    .build(),
                null);

            assertEquals("test-model", body.path("model").asText());
            assertTrue(body.path("stream").asBoolean(), "must request streaming");
            assertEquals(0.3, body.path("temperature").asDouble(), 1e-9);
            assertEquals(256, body.path("max_tokens").asInt());
            assertEquals(1, body.path("messages").size());
            // Without this opt-in the provider never sends a usage chunk and all
            // token/cost accounting silently reports zero.
            assertTrue(body.path("stream_options").path("include_usage").asBoolean(),
                "include_usage is what makes usage accounting work at all");
            assertFalse(body.has("tools"));
            assertFalse(body.has("tool_choice"));
            assertFalse(body.has("thinking"), "thinking is opt-in");
        }

        @Test
        void attachesToolsAndAutoChoiceWhenToolsExist() {
            ObjectNode body = adapter().buildStreamRequest(
                CompletionRequest.builder().messages(List.of(Message.user("hi"))).build(),
                json("[{\"name\":\"calculator\",\"properties\":{}}]"));

            assertEquals(1, body.path("tools").size());
            assertEquals("auto", body.path("tool_choice").asText());
        }

        @Test
        void emptyToolArrayDoesNotAddToolKeys() {
            ObjectNode body = adapter().buildStreamRequest(
                CompletionRequest.builder().messages(List.of(Message.user("hi"))).build(),
                json("[]"));

            assertFalse(body.has("tools"), "empty tool list must not send tools:[]");
            assertFalse(body.has("tool_choice"));
        }

        @Test
        void thinkingBlockAppearsOnlyWhenEnabled() {
            ObjectNode body = adapter().withThinkingEnabled(true).buildStreamRequest(
                CompletionRequest.builder().messages(List.of(Message.user("hi"))).build(), null);

            assertEquals("enabled", body.path("thinking").path("type").asText());
        }

        @Test
        void nullSamplingParamsAreOmittedRatherThanSentAsNull() {
            ObjectNode body = adapter().buildStreamRequest(
                CompletionRequest.builder()
                    .messages(List.of(Message.user("hi")))
                    .temperature(null)
                    .maxTokens(null)
                    .build(),
                null);

            assertFalse(body.has("temperature"));
            assertFalse(body.has("max_tokens"));
        }
    }

    // ==================== buildStreamedResponse ====================

    @Nested
    @DisplayName("buildStreamedResponse: SSE fragments -> CompletionResponse")
    class StreamedResponse {

        private LangChain4jLlmClientAdapter.ToolCallAcc acc(String id, String name, String args) {
            var t = new LangChain4jLlmClientAdapter.ToolCallAcc();
            t.id = id;
            t.name = name;
            t.args.append(args);
            return t;
        }

        @Test
        void textTurnCarriesUsageCachedTokensAndProvider() {
            CompletionResponse r = adapter().buildStreamedResponse(
                new StringBuilder("答复正文"), new StringBuilder("思考"),
                new java.util.LinkedHashMap<>(), "stop",
                json("{\"prompt_tokens\":100,\"completion_tokens\":20,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":80}}"));

            assertEquals("答复正文", r.content());
            assertEquals("思考", r.reasoning());
            assertEquals("stop", r.finishReason());
            assertEquals(100, r.inputTokens());
            assertEquals(20, r.outputTokens());
            assertEquals(80, r.cachedInputTokens());
            assertEquals("test-model", r.model());
            assertEquals("openai", r.provider());
            assertTrue(r.toolCalls().isEmpty());
        }

        @Test
        void outputTokensFallBackToOutputTokensAlias() {
            CompletionResponse r = adapter().buildStreamedResponse(
                new StringBuilder("x"), new StringBuilder(), new java.util.LinkedHashMap<>(),
                "stop", json("{\"prompt_tokens\":5,\"output_tokens\":7}"));

            assertEquals(7, r.outputTokens(), "providers using output_tokens must still be counted");
        }

        @Test
        void toolCallTurnParsesAccumulatedArgumentFragments() {
            var accs = new java.util.LinkedHashMap<Integer, LangChain4jLlmClientAdapter.ToolCallAcc>();
            accs.put(0, acc("c1", "file_read", "{\"path\":\"/a\"}"));
            accs.put(1, acc("c2", "calculator", "{\"expression\":\"1+1\"}"));

            CompletionResponse r = adapter().buildStreamedResponse(
                new StringBuilder(), new StringBuilder(), accs, "tool_calls", null);

            assertEquals("tool_calls", r.finishReason());
            assertEquals("", r.content());
            assertEquals(2, r.toolCalls().size());
            assertEquals("c1", r.toolCalls().get(0).callId());
            assertEquals("file_read", r.toolCalls().get(0).toolName());
            assertEquals("/a", r.toolCalls().get(0).arguments().get("path"));
            assertEquals("1+1", r.toolCalls().get(1).arguments().get("expression"));
        }

        @Test
        void toolCallsAreDetectedEvenWhenFinishReasonIsMissing() {
            // Some OpenAI-compatible gateways omit finish_reason on the last chunk.
            var accs = new java.util.LinkedHashMap<Integer, LangChain4jLlmClientAdapter.ToolCallAcc>();
            accs.put(0, acc("c1", "file_read", "{}"));

            CompletionResponse r = adapter().buildStreamedResponse(
                new StringBuilder(), new StringBuilder(), accs, null, null);

            assertEquals("tool_calls", r.finishReason(), "missing finish_reason must not lose the tool call");
            assertEquals(1, r.toolCalls().size());
        }

        @Test
        void missingToolCallIdGetsSyntheticIdSoPairingStillWorks() {
            var accs = new java.util.LinkedHashMap<Integer, LangChain4jLlmClientAdapter.ToolCallAcc>();
            accs.put(0, acc("  ", null, "{}"));

            CompletionResponse r = adapter().buildStreamedResponse(
                new StringBuilder(), new StringBuilder(), accs, "tool_calls", null);

            ToolCall c = r.toolCalls().get(0);
            assertNotNull(c.callId());
            assertFalse(c.callId().isBlank(), "a blank id would break tool-result pairing");
            assertEquals("unknown_tool", c.toolName());
        }

        @Test
        void absentUsageDegradesToZerosAndDefaultFinishReason() {
            CompletionResponse r = adapter().buildStreamedResponse(
                new StringBuilder("hi"), new StringBuilder(), new java.util.LinkedHashMap<>(), null, null);

            assertEquals("stop", r.finishReason());
            assertEquals(0, r.inputTokens());
            assertEquals(0, r.outputTokens());
            assertEquals(0, r.cachedInputTokens());
            assertNull(r.reasoning(), "empty reasoning buffer must stay null, not \"\"");
        }
    }

    // ==================== joinUrl ====================

    @Nested
    @DisplayName("joinUrl: base + path")
    class JoinUrl {

        @Test
        void collapsesDuplicateSlashAndPreservesPlainConcatenation() {
            assertEquals("http://h/v1/chat/completions",
                LangChain4jLlmClientAdapter.joinUrl("http://h/v1/", "/chat/completions"));
            assertEquals("http://h/v1/chat/completions",
                LangChain4jLlmClientAdapter.joinUrl("http://h/v1", "/chat/completions"));
        }
    }
}
