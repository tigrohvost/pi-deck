/**
 * Applies PI//DECK's private custom system prompt at Pi's final per-turn hook.
 *
 * The Python supervisor validates and writes the prompt before Pi starts. This extension repeats
 * the hash/size check inside the Node process and then applies the text after Pi has assembled
 * project context. That makes append mode reliably last, while replace mode is an honest complete
 * replacement. Prompt text never travels in argv or environment variables.
 */

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const MAX_SYSTEM_PROMPT_BYTES = 16 * 1024;
const CHAT_GUIDANCE = `You are PI//DECK's local assistant on this Android phone.
Answer the request directly, in the user's language. Be concise unless detail is requested.
Chat mode has no tools: do not claim to inspect files, run commands, or fetch current data.`;
/**
 * The three restraint failures below are the ones small models actually make, in the order
 * they were observed across a 21-model tool-calling comparison: firing on a keyword, missing
 * a negation, and calling a tool for data already sitting in the prompt. Each gets a worked
 * counter-example rather than a rule, because a rule is what the model already ignored.
 */
const MOBILE_AGENT_GUIDANCE = `PI//DECK mobile runtime guidance:
- Answer direct questions and explicit-format requests immediately. Do not inspect the workspace unless the request requires it.
- Use tools only when they materially help complete the request. Stop after a missing path instead of retrying equivalent lookups.
- When a direct live-data question exposes one tool, call it immediately once. After a successful result, answer in at most two short sentences; never verify it with shell, date, or a second tool.
- Tool paths are relative to the current workspace unless an absolute path starts with "/"; never prepend the workspace to an already absolute path.
- Prefer concise answers and the fewest necessary tool round-trips.

When not to call a tool. A keyword is not an instruction:
- "Что значит слово «погода» по-английски?" mentions weather and needs no weather call. Answer "weather".
- "Не проверяй погоду, просто открой отчёт" forbids the weather call. Honour the negation and read the report.
- "Сегодня 14 °C и дождь. Брать зонт?" already carries the data. Answer from it; calling weather repeats work the user has done.
- "Напиши функцию, которая сортирует список" needs no file read. Write the function.

Editing files:
- read prints each line as \`12:a3| текст\`, where \`12:a3\` is that line's anchor.
- PI//DECK BOUNDED PREFETCH is an authoritative managed read of explicitly named small files; use its anchors without calling read again.
- Prefer pideck_replace_lines with those anchors over retyping the original text.
- Anchors expire the moment a file changes. After any edit, read again before the next one.`;

type PromptSettings = {
	mode: "append" | "replace";
	text: string;
};

function configuredAgentMode(): "chat" | "agent" {
	const mode = process.env.PIDECK_AGENT_MODE;
	if (mode === "chat" || mode === "agent") return mode;
	throw new Error("PI//DECK system prompt has no valid agent mode");
}

export function composeManagedPrompt(
	agentMode: "chat" | "agent",
	basePrompt: string,
	settings: PromptSettings | undefined,
): string {
	if (settings?.mode === "replace") return settings.text;
	const managedBase = agentMode === "chat"
		? CHAT_GUIDANCE
		: [basePrompt, MOBILE_AGENT_GUIDANCE].filter(Boolean).join("\n\n");
	return [managedBase, settings?.text].filter(Boolean).join("\n\n");
}

function loadPromptSettings(): PromptSettings | undefined {
	const mode = process.env.PIDECK_SYSTEM_PROMPT_MODE;
	if (mode === undefined || mode === "default") return undefined;
	if (mode !== "append" && mode !== "replace") {
		throw new Error("PI//DECK system prompt mode is invalid");
	}

	const path = process.env.PIDECK_SYSTEM_PROMPT_PATH;
	const expectedHash = process.env.PIDECK_SYSTEM_PROMPT_SHA256;
	const expectedBytes = Number(process.env.PIDECK_SYSTEM_PROMPT_BYTES);
	if (
		!path
		|| !expectedHash?.match(/^[0-9a-f]{64}$/)
		|| !Number.isSafeInteger(expectedBytes)
		|| expectedBytes <= 0
		|| expectedBytes > MAX_SYSTEM_PROMPT_BYTES
	) {
		throw new Error("PI//DECK system prompt metadata is invalid");
	}

	const content = readFileSync(path);
	const actualHash = createHash("sha256").update(content).digest("hex");
	if (
		content.length !== expectedBytes
		|| content.includes(0)
		|| actualHash !== expectedHash
	) {
		throw new Error("PI//DECK system prompt failed integrity verification");
	}
	return { mode, text: content.toString("utf8") };
}

export default function pideckSystemPrompt(pi: ExtensionAPI) {
	const settings = loadPromptSettings();
	const agentMode = configuredAgentMode();

	pi.on("before_agent_start", (event) => ({
		systemPrompt: composeManagedPrompt(agentMode, event.systemPrompt, settings),
	}));
}
