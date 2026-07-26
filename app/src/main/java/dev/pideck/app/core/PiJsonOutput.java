package dev.pideck.app.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class PiJsonOutput {
    public static final class Trace {
        public final String text;
        public final boolean error;
        /** The tool that ran, for the trace feed's leading column. */
        public final String verb;
        /** What it was pointed at, for the feed's middle column. */
        public final String argument;

        Trace(String text, boolean error) {
            this(text, error, "", "");
        }

        Trace(String text, boolean error, String verb, String argument) {
            this.text = text;
            this.error = error;
            this.verb = verb;
            this.argument = argument;
        }
    }

    public static final class Parsed {
        public final boolean recognized;
        public final String answer;
        public final List<Trace> traces;
        public final List<String> protocolErrors;

        Parsed(
                boolean recognized,
                String answer,
                List<Trace> traces,
                List<String> protocolErrors
        ) {
            this.recognized = recognized;
            this.answer = answer;
            this.traces = traces;
            this.protocolErrors = protocolErrors;
        }
    }

    private PiJsonOutput() {
    }

    public static Parsed parse(String raw) {
        boolean recognized = false;
        String answer = "";
        ArrayList<Trace> traces = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        if (raw == null) return new Parsed(false, "", traces, errors);

        for (String sourceLine : raw.split("\\n")) {
            String line = sourceLine.trim();
            if (line.isEmpty()) continue;
            try {
                JSONObject event = new JSONObject(line);
                String type = event.optString("type");
                if (!type.isBlank()) recognized = true;
                switch (type) {
                    case "tool_execution_start" -> {
                        String tool = event.optString("toolName", "tool");
                        String args = compact(event.opt("args"));
                        traces.add(new Trace(
                                "› " + tool + (args.isBlank() ? "" : "\n" + args),
                                false,
                                tool,
                                args
                        ));
                    }
                    case "tool_execution_end" -> {
                        if (event.optBoolean("isError", false)) {
                            String tool = event.optString("toolName", "tool");
                            traces.add(new Trace(
                                    "× " + tool + "\n" + compact(event.opt("result")),
                                    true,
                                    tool,
                                    compact(event.opt("result"))
                            ));
                        }
                    }
                    case "message_end" -> {
                        String candidate = assistantText(event.optJSONObject("message"));
                        if (!candidate.isBlank()) answer = candidate;
                    }
                    case "agent_end" -> {
                        JSONArray messages = event.optJSONArray("messages");
                        if (messages != null) {
                            for (int i = 0; i < messages.length(); i++) {
                                String candidate = assistantText(messages.optJSONObject(i));
                                if (!candidate.isBlank()) answer = candidate;
                            }
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
                if (errors.size() < 8) {
                    errors.add("MALFORMED_JSON:" + Integer.toHexString(line.hashCode()));
                }
            }
        }
        return new Parsed(recognized, answer.trim(), traces, errors);
    }

    private static String assistantText(JSONObject message) {
        if (message == null || !"assistant".equals(message.optString("role"))) return "";
        Object content = message.opt("content");
        if (content instanceof String) return ((String) content).trim();
        if (!(content instanceof JSONArray parts)) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null || !"text".equals(part.optString("type"))) continue;
            String value = part.optString("text");
            if (value.isBlank()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(value);
        }
        return text.toString().trim();
    }

    private static String compact(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        String raw = value instanceof String ? (String) value : String.valueOf(value);
        raw = raw.trim();
        if (raw.length() <= 360) return raw;
        return raw.substring(0, 350) + "…";
    }
}
