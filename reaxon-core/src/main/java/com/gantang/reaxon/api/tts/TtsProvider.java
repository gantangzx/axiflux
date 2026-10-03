package com.gantang.reaxon.api.tts;

/**
 * Strategy interface for text-to-speech providers.
 *
 * <p>Implementations may call OpenAI TTS, ElevenLabs, Azure Cognitive Services,
 * a local model, etc.
 *
 * <p>Design pattern: <b>Strategy</b> — the TtsTool delegates to whichever
 * provider is configured at runtime.
 */
public interface TtsProvider {

    /**
     * Synthesize speech from text.
     *
     * @param text   text to speak
     * @param voice  voice identifier (provider-specific; null for default)
     * @return       audio data with format info
     */
    AudioResult synthesize(String text, String voice);

    /** Whether this provider is configured and available. */
    default boolean available() { return true; }

    /** Default voice for this provider. */
    default String defaultVoice() { return "alloy"; }

    /**
     * Audio synthesis result.
     *
     * @param data      raw audio bytes
     * @param mimeType  MIME type (e.g. "audio/mpeg", "audio/wav")
     * @param extension file extension (e.g. "mp3", "wav")
     */
    record AudioResult(byte[] data, String mimeType, String extension) {}
}
