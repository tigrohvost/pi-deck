/**
 * Enables llama.cpp's request-local prompt/KV reuse for PI//DECK.
 *
 * The managed Pi process has exactly one provider (pideck) and that provider points at the
 * app-owned loopback llama-server. Pi rebuilds and resends the growing conversation for every
 * model/tool round; llama.cpp can keep the common prefix in the single slot when cache_prompt is
 * true. Unknown or non-object payloads are left untouched so a future protocol change fails safe.
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function pideckLocalCache(pi: ExtensionAPI) {
	pi.on("before_provider_request", (event) => {
		if (
			typeof event.payload !== "object"
			|| event.payload === null
			|| Array.isArray(event.payload)
		) {
			return undefined;
		}
		return {
			...(event.payload as Record<string, unknown>),
			cache_prompt: true,
		};
	});
}
