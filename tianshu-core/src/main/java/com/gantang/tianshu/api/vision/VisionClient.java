package com.gantang.tianshu.api.vision;

/**
 * Strategy interface for image analysis / vision-language models.
 *
 * <p>Implementations may call OpenAI GPT-4V, Anthropic Claude vision,
 * local LLaVA, or any other vision-language endpoint.
 *
 * <p>Design pattern: <b>Strategy</b> — the tool delegates to whichever
 * implementation is configured, making it trivial to swap providers.
 */
public interface VisionClient {

    /**
     * Analyze an image and return a text description.
     *
     * @param imageUrl  HTTP(S) URL or data URI of the image
     * @param prompt    instruction for the vision model (e.g. "Describe this image")
     * @return          text response from the model
     */
    String analyze(String imageUrl, String prompt);

    /**
     * Whether this client is properly configured and available.
     * Tools can check this to give a clear error when no vision backend is set.
     */
    default boolean available() { return true; }
}
