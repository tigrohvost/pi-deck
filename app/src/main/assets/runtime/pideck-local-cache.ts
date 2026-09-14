/**
 * Enables llama.cpp's request-local prompt/KV reuse for PI//DECK.
 *
 * The managed Pi process has exactly one provider (pideck) and that provider points at the
 * app-owned loopback llama-server. Pi rebuilds and resends the growing conversation for every
 * model/tool round; llama.cpp can keep an unchanged, monotonically growing prefix in the single
 * slot when cache_prompt is true. Unknown or non-object payloads are left untouched so a future
 * protocol change fails safe.
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/**
 * Request fields that never change the rendered prompt. They steer sampling or the transport,
 * so a difference between two requests cannot make cached llama.cpp state stale. Every other
 * non-message field (model, tools, response_format, chat_template, ...) is part of the contract.
 */
const GENERATION_ONLY_FIELDS = new Set([
	"cache_prompt",
	"messages",
	"max_tokens",
	"max_completion_tokens",
	"n_predict",
	"temperature",
	"top_p",
	"top_k",
	"min_p",
	"typical_p",
	"presence_penalty",
	"frequency_penalty",
	"repeat_penalty",
	"repeat_last_n",
	"dry_multiplier",
	"dry_base",
	"dry_allowed_length",
	"dry_penalty_last_n",
	"xtc_probability",
	"xtc_threshold",
	"mirostat",
	"mirostat_tau",
	"mirostat_eta",
	"samplers",
	"seed",
	"stop",
	"stream",
	"stream_options",
	"n",
	"logprobs",
	"top_logprobs",
	"logit_bias",
	"n_probs",
	"t_max_predict_ms",
	"timings_per_token",
	"return_progress",
	"user",
	"metadata",
	"store",
]);

/**
 * `enable_thinking` only changes the assistant generation prefix appended after the last
 * message; with `preserve_thinking` earlier turns render identically. Any other template
 * keyword can rewrite the whole history and stays in the contract.
 */
function contractTemplateKwargs(value: unknown): unknown {
	if (typeof value !== "object" || value === null || Array.isArray(value)) return value;
	const { enable_thinking: _ignored, ...rest } = value as Record<string, unknown>;
	return rest;
}

/** Serializes only the request fields that shape the rendered prompt. */
export function promptContract(
	payload: Record<string, unknown>,
	stableToolChoicePrefix = false,
): string {
	const entries = Object.entries(payload)
		.filter(([key]) => !GENERATION_ONLY_FIELDS.has(key))
		// These pinned templates render auto/none identically; the choice only controls
		// generation. Keep required/named choices and all unknown contracts conservative.
		.filter(([key, value]) => !(stableToolChoicePrefix && key === "tool_choice"
			&& (value === "auto" || value === "none")))
		.map(([key, value]) =>
			key === "chat_template_kwargs" ? [key, contractTemplateKwargs(value)] : [key, value])
		.sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0));
	return JSON.stringify(Object.fromEntries(entries));
}

export default function pideckLocalCache(pi: ExtensionAPI) {
	const stableToolChoicePrefix = process.env.PIDECK_STABLE_TOOL_CHOICE_PREFIX === "1";
	// llama-server owns one slot across Pi sessions. Reusing a prefix from the
	// previous session is unsafe for hybrid recurrent models: their state cannot
	// be rolled back like a pure attention KV cache. The same applies when a new
	// task or an explicit tool load rewrites the early request contract: a
	// low-similarity LCP can leave llama.cpp at 100% prefill without producing a
	// token. Ordinary tool results keep their schema stable, and the tool router
	// keeps the schema append-only across prompts. Reuse is therefore allowed only
	// when the complete previous message list is an exact prefix and every
	// prompt-shaping request field is unchanged; sampling limits such as max_tokens
	// may differ freely.
	let previousMessages: string[] | undefined;
	let previousContract: string | undefined;
	pi.on("session_start", () => {
		previousMessages = undefined;
		previousContract = undefined;
	});

	pi.on("before_provider_request", (event) => {
		if (
			typeof event.payload !== "object"
			|| event.payload === null
			|| Array.isArray(event.payload)
		) {
			return undefined;
		}
		const payload = event.payload as Record<string, unknown>;
		const messages = Array.isArray(payload.messages) ? payload.messages : undefined;
		let messageSignatures: string[] | undefined;
		let contract: string | undefined;
		try {
			messageSignatures = messages?.map((message) => JSON.stringify(message) ?? "undefined");
			contract = promptContract(payload, stableToolChoicePrefix);
		} catch {
			// Circular/future payloads must not opt into recurrent-state reuse.
		}
		const messagesExtendPrevious = previousMessages !== undefined
			&& messageSignatures !== undefined
			&& messageSignatures.length >= previousMessages.length
			&& previousMessages.every((message, index) => messageSignatures?.[index] === message);
		const cachePrompt = messagesExtendPrevious
			&& previousContract !== undefined
			&& contract === previousContract;
		previousMessages = messageSignatures;
		previousContract = contract;
		return {
			...payload,
			cache_prompt: cachePrompt,
		};
	});
}
