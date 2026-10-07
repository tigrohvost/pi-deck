/**
 * Enables llama.cpp's request-local prompt/KV reuse for PI//DECK.
 *
 * The managed Pi process has exactly one provider (pideck) and that provider points at the
 * app-owned loopback llama-server. Pi rebuilds and resends the growing conversation for every
 * model/tool round; llama.cpp can keep an unchanged, monotonically growing prefix in the single
 * slot when cache_prompt is true. Unknown or non-object payloads are left untouched so a future
 * protocol change fails safe.
 */

import { createHash } from "node:crypto";

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/** Rendered markers where a user turn begins; the system+tools prefix ends right before one. */
const USER_TURN_MARKERS = [
	"<|im_start|>user",
	"<|start_header_id|>user",
	"<start_of_turn>user",
	"<|start_of_role|>user",
	"[INST]",
];
const SLOT_REQUEST_TIMEOUT_MS = 10 * 60 * 1000;

/**
 * The rendered text two prompts with different user messages share, cut before the user-turn
 * marker. A special-token boundary keeps its tokenisation identical to the start of every full
 * prompt, so a slot holding exactly this prefix is a strict prefix of the next request and a
 * hybrid recurrent model needs no rollback.
 */
export function systemPrefix(first: string, second: string): string | undefined {
	let common = 0;
	const limit = Math.min(first.length, second.length);
	while (common < limit && first.charCodeAt(common) === second.charCodeAt(common)) common += 1;
	const shared = first.slice(0, common);
	const cut = Math.max(...USER_TURN_MARKERS.map((marker) => shared.lastIndexOf(marker)));
	return cut > 0 ? shared.slice(0, cut) : undefined;
}

/** One snapshot per model artifact, request contract and system message. */
export function snapshotName(modelId: string, modelSha: string, contract: string, system: string): string {
	const key = createHash("sha256")
		.update(modelSha).update("\0").update(contract).update("\0").update(system)
		.digest("hex")
		.slice(0, 24);
	const safe = modelId.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 64);
	return `pideck-${safe}-${key}.bin`;
}

export type SlotResponse = { status: number; json: unknown };
export type SlotClient = { post(path: string, body: unknown): Promise<SlotResponse> };
export type SnapshotOutcome = "restored" | "saved" | "unsupported" | "failed";

function field(value: unknown, key: string): unknown {
	return typeof value === "object" && value !== null ? (value as Record<string, unknown>)[key] : undefined;
}

/**
 * Brings llama-server's slot to exactly the system+tools prefix of `payload`: restore a saved
 * snapshot when one exists (tens of milliseconds), otherwise evaluate the prefix once and save
 * it. Either way the real request then only evaluates what follows the prefix. A cold model load
 * therefore no longer pays the whole prefix again on its first request.
 */
export async function warmPrefixSnapshot(
	client: SlotClient,
	payload: Record<string, unknown>,
	name: string,
	allowSave: boolean,
): Promise<SnapshotOutcome> {
	const restored = await client.post("/slots/0?action=restore", { filename: name });
	const count = field(restored.json, "n_restored");
	if (restored.status === 200 && typeof count === "number" && count > 0) return "restored";
	if (restored.status === 501) return "unsupported";
	if (!allowSave) return "failed";
	const messages = Array.isArray(payload.messages) ? payload.messages : [];
	const { messages: _messages, stream: _stream, ...contract } = payload;
	const rendered: string[] = [];
	for (const probe of ["PI//DECK prefix probe A", "PI//DECK prefix probe B, different"]) {
		const response = await client.post("/apply-template", {
			...contract,
			messages: [messages[0], { role: "user", content: probe }],
		});
		const prompt = field(response.json, "prompt");
		if (response.status !== 200 || typeof prompt !== "string") return "failed";
		rendered.push(prompt);
	}
	const prefix = systemPrefix(rendered[0], rendered[1]);
	if (prefix === undefined) return "failed";
	const warm = await client.post("/completion", { prompt: prefix, n_predict: 0, cache_prompt: false });
	if (warm.status !== 200) return "failed";
	const saved = await client.post("/slots/0?action=save", { filename: name });
	return saved.status === 200 ? "saved" : "failed";
}

/** A client for the app-owned server only: anything but loopback HTTP is refused. */
export function loopbackSlotClient(baseUrl: string, apiKey: string | undefined): SlotClient | undefined {
	const root = baseUrl.replace(/\/v1\/?$/, "");
	if (!/^http:\/\/127\.0\.0\.1:\d{1,5}$/.test(root)) return undefined;
	return {
		async post(path, body) {
			const response = await fetch(root + path, {
				method: "POST",
				headers: {
					"Content-Type": "application/json",
					...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
				},
				body: JSON.stringify(body),
				signal: AbortSignal.timeout(SLOT_REQUEST_TIMEOUT_MS),
			});
			let json: unknown;
			try {
				json = await response.json();
			} catch {
				json = undefined;
			}
			return { status: response.status, json };
		},
	};
}

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

/** Pi sends its system prompt first, as `system` or (reasoning models) `developer`. */
function isSystemMessage(message: unknown): boolean {
	if (typeof message !== "object" || message === null || Array.isArray(message)) return false;
	const role = (message as { role?: unknown }).role;
	return role === "system" || role === "developer";
}

export default function pideckLocalCache(pi: ExtensionAPI) {
	const stableToolChoicePrefix = process.env.PIDECK_STABLE_TOOL_CHOICE_PREFIX === "1";
	const crossSessionPrefix = process.env.PIDECK_CROSS_SESSION_PREFIX === "1";
	const slotSnapshots = process.env.PIDECK_SLOT_SNAPSHOTS === "1";
	const modelSha = process.env.PIDECK_MODEL_SHA256 ?? "";
	let snapshotsUnavailable = false;
	// llama-server owns one slot across Pi sessions. Hybrid recurrent state cannot be
	// rolled back like a pure attention KV cache, and when a new task or an explicit
	// tool load rewrites the early request contract a low-similarity LCP can leave
	// llama.cpp at 100% prefill without producing a token. Ordinary tool results keep
	// their schema stable, and the tool router keeps the schema fixed per profile.
	// Reuse is therefore allowed only when the complete previous message list is an
	// exact prefix and every prompt-shaping request field is unchanged; sampling
	// limits such as max_tokens may differ freely.
	//
	// One deliberate exception crosses session_start: the pinned server checkpoints the
	// recurrent state where the first user message begins, i.e. exactly after the
	// system+tools prefix, and keeps that checkpoint while later turns grow. A new
	// session whose system message and request contract are byte-identical to the
	// previous request can restore it instead of prefilling ~2k prefix tokens again;
	// if the checkpoint is gone the server falls back to a full prefill on its own.
	let previousMessages: string[] | undefined;
	let previousContract: string | undefined;
	let previousSystem: string | undefined;
	let sessionStarted = false;
	pi.on("session_start", () => {
		previousMessages = undefined;
		sessionStarted = true;
		if (!crossSessionPrefix) {
			previousContract = undefined;
			previousSystem = undefined;
		}
	});

	pi.on("before_provider_request", async (event, context) => {
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
		const sameContract = previousContract !== undefined && contract === previousContract;
		const leadingSystem = messages !== undefined && isSystemMessage(messages[0])
			? messageSignatures?.[0]
			: undefined;
		const sharesSessionPrefix = crossSessionPrefix
			&& sessionStarted
			&& previousMessages === undefined
			&& sameContract
			&& leadingSystem !== undefined
			&& leadingSystem === previousSystem
			&& (messageSignatures?.length ?? 0) >= 2;
		let cachePrompt = (messagesExtendPrevious && sameContract) || sharesSessionPrefix;
		// The first request of a session or Pi process starts from a snapshot of exactly its
		// system+tools prefix: restored when saved earlier, otherwise evaluated once and saved.
		// When the in-memory checkpoint already covers part of the prefix, only a restore is
		// worth trying; evaluating the prefix again would cost more than it saves.
		const firstOfSession = previousMessages === undefined;
		if (
			slotSnapshots
			&& !snapshotsUnavailable
			&& firstOfSession
			&& leadingSystem !== undefined
			&& contract !== undefined
			&& messages !== undefined
			&& messages.length >= 2
		) {
			try {
				const model = context?.model;
				const auth = model ? await context.modelRegistry.getApiKeyAndHeaders(model) : undefined;
				const client = model
					? loopbackSlotClient(String(model.baseUrl ?? ""), auth?.ok ? auth.apiKey : undefined)
					: undefined;
				if (client === undefined) {
					snapshotsUnavailable = true;
				} else {
					const name = snapshotName(String(payload.model ?? ""), modelSha, contract, leadingSystem);
					const outcome = await warmPrefixSnapshot(client, payload, name, !cachePrompt);
					if (outcome === "restored" || outcome === "saved") cachePrompt = true;
					if (outcome === "unsupported") snapshotsUnavailable = true;
				}
			} catch {
				// A snapshot is only an accelerator; the request proceeds as before.
			}
		}
		previousMessages = messageSignatures;
		previousContract = contract;
		previousSystem = leadingSystem;
		sessionStarted = false;
		return {
			...payload,
			cache_prompt: cachePrompt,
		};
	});
}
