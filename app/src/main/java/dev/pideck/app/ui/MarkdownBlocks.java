package dev.pideck.app.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small Markdown subset local models actually write: fenced code, headings, bullets, bold
 * and inline code. Parsing is pure so it can be tested on the JVM; the deck turns the result
 * into views and spans once an answer is complete, never per streamed token.
 */
public final class MarkdownBlocks {
    public enum Kind {
        TEXT,
        CODE
    }

    public enum SpanKind {
        BOLD,
        CODE,
        HEADING
    }

    public static final class Block {
        public final Kind kind;
        public final String text;
        /** Fence info string for code, such as {@code python}; empty when absent. */
        public final String language;

        Block(Kind kind, String text, String language) {
            this.kind = kind;
            this.text = text;
            this.language = language;
        }
    }

    public static final class Span {
        public final SpanKind kind;
        public final int start;
        public final int end;

        Span(SpanKind kind, int start, int end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }
    }

    /** Display text with its formatting ranges; markup characters are already removed. */
    public static final class Inline {
        public final String text;
        public final List<Span> spans;

        Inline(String text, List<Span> spans) {
            this.text = text;
            this.spans = Collections.unmodifiableList(spans);
        }
    }

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```+|~~~+)\\s*([\\w+#.-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern INLINE = Pattern.compile("\\*\\*([^*\\n]+?)\\*\\*|`([^`\\n]+)`");

    private MarkdownBlocks() {
    }

    public static List<Block> parse(String markdown) {
        ArrayList<Block> blocks = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return blocks;
        String[] lines = markdown.split("\n", -1);
        StringBuilder text = new StringBuilder();
        StringBuilder code = null;
        String fence = "";
        String language = "";
        for (String line : lines) {
            Matcher marker = FENCE.matcher(line);
            if (code == null) {
                if (marker.matches()) {
                    flushText(blocks, text);
                    code = new StringBuilder();
                    fence = marker.group(1);
                    language = marker.group(2);
                } else {
                    if (text.length() > 0) text.append('\n');
                    text.append(line);
                }
            } else if (marker.matches() && marker.group(2).isEmpty()
                    && marker.group(1).charAt(0) == fence.charAt(0)
                    && marker.group(1).length() >= fence.length()) {
                blocks.add(new Block(Kind.CODE, code.toString(), language));
                code = null;
            } else {
                if (code.length() > 0) code.append('\n');
                code.append(line);
            }
        }
        // An unterminated fence (a truncated answer) still reads as code, not as prose.
        if (code != null) {
            blocks.add(new Block(Kind.CODE, code.toString().replaceAll("\\n+$", ""), language));
        }
        flushText(blocks, text);
        return blocks;
    }

    private static void flushText(List<Block> blocks, StringBuilder text) {
        String value = text.toString();
        text.setLength(0);
        if (value.trim().isEmpty()) return;
        blocks.add(new Block(Kind.TEXT, value.replaceAll("^\\n+|\\n+$", ""), ""));
    }

    public static Inline inline(String text) {
        StringBuilder out = new StringBuilder();
        ArrayList<Span> spans = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) out.append('\n');
            String line = lines[index];
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            int lineStart = out.length();
            if (heading.matches()) {
                appendInline(out, spans, heading.group(2));
                spans.add(new Span(SpanKind.HEADING, lineStart, out.length()));
            } else if (bullet.matches()) {
                out.append(bullet.group(1)).append("• ");
                appendInline(out, spans, bullet.group(2));
            } else {
                appendInline(out, spans, line);
            }
        }
        return new Inline(out.toString(), spans);
    }

    private static void appendInline(StringBuilder out, List<Span> spans, String line) {
        Matcher matcher = INLINE.matcher(line);
        int cursor = 0;
        while (matcher.find()) {
            out.append(line, cursor, matcher.start());
            int start = out.length();
            if (matcher.group(1) != null) {
                out.append(matcher.group(1));
                spans.add(new Span(SpanKind.BOLD, start, out.length()));
            } else {
                out.append(matcher.group(2));
                spans.add(new Span(SpanKind.CODE, start, out.length()));
            }
            cursor = matcher.end();
        }
        out.append(line, cursor, line.length());
    }
}
