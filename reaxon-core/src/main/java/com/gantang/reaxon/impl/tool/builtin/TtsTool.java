package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.api.tts.TtsProvider;
import com.gantang.reaxon.impl.tool.support.AbstractTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Text-to-speech tool.
 *
 * <p>Synthesizes speech via a pluggable {@link TtsProvider} and saves
 * the audio to a temporary file, returning the path.
 *
 * <p>Design patterns:
 * <ul>
 *   <li><b>Strategy</b> — delegates synthesis to TtsProvider</li>
 *   <li><b>Template Method</b> — extends AbstractTool</li>
 * </ul>
 */
public class TtsTool extends AbstractTool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "text": {
              "type": "string",
              "description": "Text to synthesize into speech"
            },
            "voice": {
              "type": "string",
              "description": "Voice identifier (provider-specific, default: alloy)"
            },
            "outputDir": {
              "type": "string",
              "description": "Directory to save audio file (default: system temp)"
            }
          },
          "required": ["text"]
        }
        """);

    private final TtsProvider ttsProvider;

    public TtsTool(TtsProvider ttsProvider) {
        this.ttsProvider = ttsProvider;
    }

    @Override public String name()        { return "tts"; }
    @Override public String description() { return "Convert text to speech audio. Returns the path to the generated audio file."; }
    @Override public JsonNode parameters(){ return SCHEMA; }

    @Override
    protected ToolResult doExecute(String callId, Params params, AgentContext context) {
        if (ttsProvider == null || !ttsProvider.available()) {
            return ToolResult.failure(callId,
                "No TTS provider configured. Set axiflux.tts.api-key to enable text-to-speech.");
        }

        String text = params.getString("text");
        String voice = params.getString("voice", ttsProvider.defaultVoice());

        TtsProvider.AudioResult audio = ttsProvider.synthesize(text, voice);

        try {
            Path outputDir;
            String dir = params.getString("outputDir");
            if (dir != null && !dir.isBlank()) {
                outputDir = Path.of(dir);
                Files.createDirectories(outputDir);
            } else {
                outputDir = Path.of(System.getProperty("java.io.tmpdir"), "axiflux-tts");
                Files.createDirectories(outputDir);
            }

            String filename = "tts-" + UUID.randomUUID() + "." + audio.extension();
            Path outputPath = outputDir.resolve(filename);
            Files.write(outputPath, audio.data());

            String result = "Audio saved to: " + outputPath.toAbsolutePath() + "\n" +
                "Format: " + audio.mimeType() + "\n" +
                "Size: " + audio.data().length + " bytes";

            return ToolResult.success(callId, result, Map.of(
                "path", outputPath.toAbsolutePath().toString(),
                "mimeType", audio.mimeType(),
                "sizeBytes", audio.data().length,
                "voice", voice));
        } catch (Exception e) {
            return ToolResult.failure(callId, "Failed to save audio: " + e.getMessage());
        }
    }
}
