/**
 * Keeps Qwen's bounded reasoning for hard agent work without charging every turn for it.
 *
 * The managed provider uses Pi's qwen-chat-template compatibility, so changing Pi's thinking
 * level produces llama.cpp's request-local chat_template_kwargs.enable_thinking flag and keeps
 * preserve_thinking enabled for tool-call continuity. A normal input chooses once for the whole
 * turn; steer/follow-up input may promote FAST to DEEP but never demotes an in-flight repair.
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export type AdaptiveThinkingLevel = "off" | "low";

const INTERNAL_RETRY_PREFIX = "[[PI//DECK:ANSWER_RETRY]]\n";

const FAST_OVERRIDE_CUES = [
	"без размышлений",
	"не размышляй",
	"ответь быстро",
	"только краткий ответ",
	"without thinking",
	"do not think",
	"answer quickly",
	"short answer only",
] as const;

const DEEP_OVERRIDE_CUES = [
	"подумай глубоко",
	"тщательно проанализируй",
	"глубокий анализ",
	"think deeply",
	"analyze thoroughly",
	"deep analysis",
] as const;

const COMPLEX_AGENT_CUES = [
	"исправ",
	"почини",
	"реализ",
	"примени",
	"добав",
	"создай",
	"измени",
	"удали",
	"обнови",
	"замени",
	"переимен",
	"перепиш",
	"внеси измен",
	"отрефактор",
	"рефактор",
	"отлад",
	"диагност",
	"найди причину",
	"разберись почему",
	"архитектур",
	"запусти тест",
	"прогони тест",
	"fix ",
	"repair ",
	"implement ",
	"apply ",
	"add ",
	"create ",
	"change ",
	"update ",
	"delete ",
	"remove ",
	"rename ",
	"rewrite ",
	"make the change",
	"refactor",
	"debug",
	"diagnos",
	"root cause",
	"architecture",
	"run the test",
	"run tests",
] as const;

function normalizedInput(text: string): string {
	const withoutRetry = text.startsWith(INTERNAL_RETRY_PREFIX)
		? text.slice(INTERNAL_RETRY_PREFIX.length)
		: text;
	return withoutRetry.toLocaleLowerCase().replace(/\s+/g, " ").trim();
}

/** Pure classifier kept deliberately narrow: ambiguous/direct work stays FAST. */
export function adaptiveThinkingLevel(
	text: string,
	agentMode: "chat" | "agent",
): AdaptiveThinkingLevel {
	if (agentMode === "chat") return "off";
	const candidate = normalizedInput(text);
	if (FAST_OVERRIDE_CUES.some((cue) => candidate.includes(cue))) return "off";
	if (DEEP_OVERRIDE_CUES.some((cue) => candidate.includes(cue))) return "low";
	if (COMPLEX_AGENT_CUES.some((cue) => candidate.includes(cue))) return "low";
	return "off";
}

function configuredMode(): "chat" | "agent" {
	const mode = process.env.PIDECK_AGENT_MODE;
	if (mode === "chat" || mode === "agent") return mode;
	throw new Error("PI//DECK adaptive thinking has no valid agent mode");
}

function adaptiveEnabled(): boolean {
	const value = process.env.PIDECK_ADAPTIVE_THINKING;
	if (value === "1") return true;
	if (value === "0" || value === undefined) return false;
	throw new Error("PI//DECK adaptive thinking flag is invalid");
}

export default function pideckAdaptiveThinking(pi: ExtensionAPI) {
	const mode = configuredMode();
	const enabled = adaptiveEnabled();
	let turnLevel: AdaptiveThinkingLevel = mode === "chat" ? "off" : "low";

	pi.on("session_start", () => {
		turnLevel = mode === "chat" ? "off" : "low";
		if (enabled) pi.setThinkingLevel(turnLevel);
	});

	pi.on("input", (event) => {
		if (!enabled) return { action: "continue" } as const;
		const isRetry = event.text.startsWith(INTERNAL_RETRY_PREFIX);
		if (!isRetry) {
			const requested = adaptiveThinkingLevel(event.text, mode);
			turnLevel = event.streamingBehavior !== undefined && turnLevel === "low"
				? "low"
				: requested;
		}
		pi.setThinkingLevel(turnLevel);
		return { action: "continue" } as const;
	});
}
