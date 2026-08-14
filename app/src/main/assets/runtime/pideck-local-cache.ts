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

export default function pideckLocalCache(pi: ExtensionAPI) {
	// llama-server owns one slot across Pi sessions. Reusing a prefix from the
	// previous session is unsafe for hybrid recurrent models: their state cannot
	// be rolled back like a pure attention KV cache. The same applies when a new
	// task, adaptive-thinking transition, or explicit tool load rewrites the early
	// request contract: a low-similarity LCP can leave llama.cpp at 100% prefill
	// without producing a token. Ordinary tool results keep their schema stable. Reuse is
	// therefore allowed only when the complete previous message list is an exact
	// prefix and every non-message request field is unchanged.
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
			contract = JSON.stringify(Object.fromEntries(
				Object.entries(payload).filter(([key]) => key !== "messages" && key !== "cache_prompt"),
			));
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
