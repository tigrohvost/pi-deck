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
const MOBILE_AGENT_GUIDANCE = `PI//DECK mobile runtime guidance:
- Answer direct questions and explicit-format requests immediately. Do not inspect the workspace unless the request requires it.
- Use tools only when they materially help complete the request. Stop after a missing path instead of retrying equivalent lookups.
- Tool paths are relative to the current workspace unless an absolute path starts with "/"; never prepend the workspace to an already absolute path.
- Prefer concise answers and the fewest necessary tool round-trips.`;

type PromptSettings = {
	mode: "append" | "replace";
	text: string;
};

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

	pi.on("before_agent_start", (event) => ({
		systemPrompt: settings?.mode === "replace"
			? settings.text
			: [
				event.systemPrompt,
				MOBILE_AGENT_GUIDANCE,
				settings?.text,
			].filter(Boolean).join("\n\n"),
	}));
}
