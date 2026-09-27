package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.memory.TokenCounter;

/**
 * Model-agnostic token estimator used when no exact tokenizer is wired.
 *
 * <p>Rules (conservative — errs high, never low, so budget trimming protects
 * the provider's context window rather than overflowing it):
 * <ul>
 *   <li>Ideographic / wide scripts (CJK Unified + Ext A, Hiragana/Katakana,
 *       Hangul, CJK punctuation, full-width forms): ~1 token per char.</li>
 *   <li>Everything else (Latin, digits, punctuation, JSON structure):
 *       ~1 token per 4 chars (typical BPE ratio for English/code is 3.5–4.5).</li>
 * </ul>
 */
public final class HeuristicTokenCounter implements TokenCounter {

    public static final HeuristicTokenCounter INSTANCE = new HeuristicTokenCounter();

    /** Chars-per-token for non-ideographic text (kept coarse: 4 is the safe end). */
    private static final double LATIN_CHARS_PER_TOKEN = 4.0;

    private HeuristicTokenCounter() {}

    @Override
    public int countText(String text) {
        if (text == null || text.isEmpty()) return 0;
        int wide = 0;
        int other = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isWide(cp)) wide++;
            else other++;
        }
        int tokens = wide + (int) Math.ceil(other / LATIN_CHARS_PER_TOKEN);
        return Math.max(1, tokens);
    }

    /** True for code points that typically tokenize at ~1 token per character. */
    private static boolean isWide(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF)   // CJK radicals/Kangxi/symbols, hiragana/katakana, CJK Ext A + Unified
            || (cp >= 0xAC00 && cp <= 0xD7AF)   // Hangul syllables
            || (cp >= 0xF900 && cp <= 0xFAFF)   // CJK compatibility ideographs
            || (cp >= 0xFF00 && cp <= 0xFFEF)   // full-width forms
            || (cp >= 0x1F300 && cp <= 0x1FAFF); // emoji & symbols (tokenize expensively)
    }
}
