package com.gantang.tianshu.api.memory;

/**
 * Strategy for generating summaries of memory content.
 *
 * <p>Implementations may use an LLM, extractive summarization, or simple
 * truncation. The default strategy ({@link #truncating()}) truncates to
 * ~200 characters.
 *
 * <p>Design pattern: <b>Strategy</b> — pluggable summarization algorithm.
 */
@FunctionalInterface
public interface SummaryGenerator {

    /**
     * Generate a concise summary of the given content.
     *
     * @param content the full memory content
     * @return a condensed summary
     */
    String summarize(String content);

    /**
     * Default truncating summarizer — uses first ~200 characters.
     */
    static SummaryGenerator truncating() {
        return content -> {
            if (content == null || content.length() <= 200) return content;
            int cutoff = content.lastIndexOf(' ', 180);
            return (cutoff > 0 ? content.substring(0, cutoff) : content.substring(0, 180)) + "…";
        };
    }
}
