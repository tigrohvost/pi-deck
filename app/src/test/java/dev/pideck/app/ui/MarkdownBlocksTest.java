package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MarkdownBlocksTest {
    @Test
    public void fencedCodeIsSeparatedFromProseWithItsLanguage() {
        List<MarkdownBlocks.Block> blocks = MarkdownBlocks.parse(
                "Исправил функцию:\n\n```python\ndef divide(a, b):\n    return a / b\n```\nГотово."
        );
        assertEquals(3, blocks.size());
        assertEquals(MarkdownBlocks.Kind.TEXT, blocks.get(0).kind);
        assertEquals("Исправил функцию:", blocks.get(0).text);
        assertEquals(MarkdownBlocks.Kind.CODE, blocks.get(1).kind);
        assertEquals("python", blocks.get(1).language);
        assertEquals("def divide(a, b):\n    return a / b", blocks.get(1).text);
        assertEquals("Готово.", blocks.get(2).text);
    }

    @Test
    public void anUnterminatedFenceStillRendersAsCode() {
        List<MarkdownBlocks.Block> blocks = MarkdownBlocks.parse("```\nls -la\n");
        assertEquals(1, blocks.size());
        assertEquals(MarkdownBlocks.Kind.CODE, blocks.get(0).kind);
        assertEquals("ls -la", blocks.get(0).text);
    }

    @Test
    public void aShorterOrDifferentFenceDoesNotCloseTheBlock() {
        List<MarkdownBlocks.Block> blocks = MarkdownBlocks.parse("````md\n```\ninner\n```\n````");
        assertEquals(1, blocks.size());
        assertEquals("```\ninner\n```", blocks.get(0).text);
    }

    @Test
    public void inlineMarkupIsRemovedAndRecordedAsSpans() {
        MarkdownBlocks.Inline inline = MarkdownBlocks.inline(
                "## Итог\n- файл `calc.py` **исправлен**\n* тест прошёл"
        );
        assertEquals("Итог\n• файл calc.py исправлен\n• тест прошёл", inline.text);
        assertTrue(inline.spans.stream().anyMatch(span ->
                span.kind == MarkdownBlocks.SpanKind.HEADING
                        && inline.text.substring(span.start, span.end).equals("Итог")));
        assertTrue(inline.spans.stream().anyMatch(span ->
                span.kind == MarkdownBlocks.SpanKind.CODE
                        && inline.text.substring(span.start, span.end).equals("calc.py")));
        assertTrue(inline.spans.stream().anyMatch(span ->
                span.kind == MarkdownBlocks.SpanKind.BOLD
                        && inline.text.substring(span.start, span.end).equals("исправлен")));
    }

    @Test
    public void plainTextAndLoneMarkersAreLeftAlone() {
        MarkdownBlocks.Inline inline = MarkdownBlocks.inline("2 * 3 = 6, a `b and **c");
        assertEquals("2 * 3 = 6, a `b and **c", inline.text);
        assertTrue(inline.spans.isEmpty());
        assertTrue(MarkdownBlocks.parse("").isEmpty());
        assertTrue(MarkdownBlocks.parse("\n\n").isEmpty());
    }
}
