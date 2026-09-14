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
/** Short permanent rules; exact tools, file snapshots and repository rules arrive per task. */
const MOBILE_AGENT_GUIDANCE = `Answer in the user's language. Use tools only when the request needs them; keywords, negations and facts already supplied do not ask for a lookup.
Use exact user paths; never prepend the workspace to an absolute path. Report only observed actions and checks.
Follow the task's allowed tools and repository rules. Permissions are handled by the tools. After an error, correct the cause once; never repeat an identical failed call. A terminal result means answer now.
read shows file lines as line:hash| text. An outline is not full content: read the relevant offset/limit. Prefetched files are already available, and explicit read is allowed.
pideck_edit_text replaces unique oldText with newText. Copy literal file text without line:hash prefixes, including enough context for one match. It requires current, seen file contents. For several changes read the updated result between edits.
pideck_replace_lines is an optional whole-line editor using complete line:hash anchors. Never invent or shorten anchors.
code_nav locates paths or symbols without shell discovery. run_tests runs the exact test after edits; its verdict is authoritative.
For a direct weather or web request call the named tool once, then answer concisely from its result. Cite web URLs; weather observations come from Open-Meteo.
If a test passes, finish. If a limit or refusal stops work, state what was actually changed and what remains.`;

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
