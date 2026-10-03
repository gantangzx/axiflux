package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.api.vision.VisionClient;
import com.gantang.reaxon.impl.tool.support.AbstractTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;

import java.util.Map;

/**
 * Analyze an image using a pluggable {@link VisionClient}.
 *
 * <p>The vision model is injected via constructor — this tool has no
 * hard dependency on any specific provider. Swap OpenAI for Claude,
 * LLaVA, etc. by providing a different {@link VisionClient} bean.
 *
 * <p>Design patterns:
 * <ul>
 *   <li><b>Strategy</b> — delegates image analysis to VisionClient</li>
 *   <li><b>Template Method</b> — extends AbstractTool for param validation</li>
 * </ul>
 */
public class ImageAnalyzeTool extends AbstractTool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "image_url": {
              "type": "string",
              "description": "HTTP(S) URL or data URI of the image to analyze"
            },
            "prompt": {
              "type": "string",
              "description": "Instruction for the vision model (default: 'Describe this image in detail')"
            }
          },
          "required": ["image_url"]
        }
        """);

    private final VisionClient visionClient;

    public ImageAnalyzeTool(VisionClient visionClient) {
        this.visionClient = visionClient;
    }

    @Override public String name()        { return "image_analyze"; }
    @Override public String description() { return "Analyze an image and return a text description. Accepts an image URL or data URI."; }
    @Override public JsonNode parameters(){ return SCHEMA; }

    @Override
    protected ToolResult doExecute(String callId, Params params, AgentContext context) {
        if (visionClient == null || !visionClient.available()) {
            return ToolResult.failure(callId,
                "No vision client configured. Set axiflux.vision.api-key to enable image analysis.");
        }

        String imageUrl = params.getString("image_url");
        String prompt = params.getString("prompt",
            "Describe this image in detail. If it contains text, transcribe it.");

        String description = visionClient.analyze(imageUrl, prompt);
        return ToolResult.success(callId, description, Map.of(
            "imageUrl", imageUrl,
            "prompt", prompt));
    }
}
