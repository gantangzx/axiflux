package com.gantang.reaxon.impl.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    @Test
    void htmlToText_stripsTags() {
        String html = "<html><head><title>Test</title></head><body><h1>Hello</h1><p>World</p></body></html>";
        String text = WebFetchTool.htmlToText(html);
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("World"));
        assertFalse(text.contains("<"));
    }

    @Test
    void htmlToText_removesScriptAndStyle() {
        String html = "<div><script>alert('x')</script><style>.a{color:red}</style><p>Content</p></div>";
        String text = WebFetchTool.htmlToText(html);
        assertTrue(text.contains("Content"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color:red"));
    }

    @Test
    void htmlToText_emptyInput() {
        assertEquals("", WebFetchTool.htmlToText(""));
        assertEquals("", WebFetchTool.htmlToText(null));
    }
}
