/**
 * Keeps local tool results proportional to a phone-sized context window.
 *
 * Pi's built-ins deliberately allow up to 50 KiB, which is sensible for large hosted contexts
 * but can consume an entire 4k-10k local window. The complete text is retained in PI//DECK's
 * private runtime directory and the model receives a useful head/tail plus a path for follow-up.
 */
import { createHash } from "node:crypto";
import {
	chmodSync,
	mkdirSync,
	readdirSync,
	statSync,
	unlinkSync,
	writeFileSync,
} from "node:fs";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const MAX_CONTEXT_BYTES = 12 * 1024;
const MAX_CONTEXT_LINES = 400;
const HEAD_BYTES = 8 * 1024;
const TAIL_BYTES = 3 * 1024;
const RETAINED_RESULTS = 48;
const PRUNE_MIN_SAVED_BYTES = 4 * 1024;
const PRUNE_MAX_RESULTS = 24;
const NO_MATCH_MIN_AGE_MESSAGES = 6;

function utf8Slice(value: string, start: number, end?: number): string {
	return Buffer.from(value, "utf8").subarray(start, end).toString("utf8");
}

function privateResultPath(toolCallId: string, text: string): string | undefined {
	const base = process.env.PIDECK_HOME;
	if (!base) return undefined;
	try {
		const directory = join(base, "tool-results");
		mkdirSync(directory, { recursive: true, mode: 0o700 });
		chmodSync(directory, 0o700);
		const digest = createHash("sha256")
			.update(toolCallId)
			.update("\0")
			.update(text)
			.digest("hex")
			.slice(0, 20);
		const path = join(directory, `result-${digest}.txt`);
		writeFileSync(path, text, { encoding: "utf8", mode: 0o600 });
		chmodSync(path, 0o600);
		const retained = readdirSync(directory)
			.filter((name) => /^result-[0-9a-f]{20}\.txt$/.test(name))
			.map((name) => {
				const candidate = join(directory, name);
				return { candidate, modified: statSync(candidate).mtimeMs };
			})
			.sort((left, right) => right.modified - left.modified);
		for (const stale of retained.slice(RETAINED_RESULTS)) unlinkSync(stale.candidate);
		return path;
	} catch {
		return undefined;
	}
}

function compactText(value: string): string {
	const lines = value.split("\n");
	const lineBounded = lines.length > MAX_CONTEXT_LINES
		? [
			...lines.slice(0, 300),
			`[... ${lines.length - 360} строк пропущено ...]`,
			...lines.slice(-60),
		].join("\n")
		: value;
	if (Buffer.byteLength(lineBounded, "utf8") <= MAX_CONTEXT_BYTES) return lineBounded;
	const bytes = Buffer.byteLength(lineBounded, "utf8");
	return `${utf8Slice(lineBounded, 0, HEAD_BYTES)}\n`
		+ `[... ${bytes - HEAD_BYTES - TAIL_BYTES} байт пропущено ...]\n`
		+ utf8Slice(lineBounded, Math.max(0, bytes - TAIL_BYTES));
}

type UnknownRecord = Record<string, unknown>;

function record(value: unknown): UnknownRecord | undefined {
	return typeof value === "object" && value !== null && !Array.isArray(value)
		? value as UnknownRecord
		: undefined;
}

function textContent(value: unknown): string | undefined {
	if (!Array.isArray(value)) return undefined;
	const parts: string[] = [];
	for (const part of value) {
		const item = record(part);
		if (item?.type === "image") return undefined;
		if (item?.type === "text" && typeof item.text === "string") parts.push(item.text);
	}
	return parts.join("\n");
}

function normalizedToolKey(name: string, input: UnknownRecord): string | undefined {
	if (name === "read") {
		const path = typeof input.path === "string" ? input.path.trim() : "";
		if (!path) return undefined;
		return JSON.stringify([
			"read",
			path.replace(/\\/g, "/"),
			input.offset ?? null,
			input.limit ?? null,
		]);
	}
	if (name === "code_nav") {
		const query = typeof input.query === "string" ? input.query.trim() : "";
		const path = typeof input.path === "string" ? input.path.trim() : "";
		if (!query) return undefined;
		return JSON.stringify(["code_nav", query, path.replace(/\\/g, "/")]);
	}
	return undefined;
}

/**
 * Replaces only provider-facing copies of superseded reads and old no-match searches. Durable
 * session history and private oversized-result files remain unchanged. The transformation is
 * deterministic: when it rewrites an old prefix, pideck-local-cache observes that exact change
 * later in the request pipeline and disables recurrent-state reuse for that one request.
 */
export function pruneSupersededContext(messages: readonly unknown[]): unknown[] {
	const calls = new Map<string, { name: string; input: UnknownRecord; key?: string }>();
	for (const message of messages) {
		const candidate = record(message);
		if (candidate?.role !== "assistant" || !Array.isArray(candidate.content)) continue;
		for (const part of candidate.content) {
			const call = record(part);
			if (
				call?.type !== "toolCall"
				|| typeof call.id !== "string"
				|| typeof call.name !== "string"
			) {
				continue;
			}
			const input = record(call.arguments) ?? {};
			calls.set(call.id, {
				name: call.name,
				input,
				key: normalizedToolKey(call.name, input),
			});
		}
	}

	const lastSuccessful = new Map<string, { index: number; text: string }>();
	for (let index = 0; index < messages.length; index++) {
		const message = record(messages[index]);
		if (
			message?.role !== "toolResult"
			|| message.isError === true
			|| typeof message.toolCallId !== "string"
		) {
			continue;
		}
		const key = calls.get(message.toolCallId)?.key;
		const text = textContent(message.content);
		if (key !== undefined && text !== undefined) lastSuccessful.set(key, { index, text });
	}

	type Candidate = { index: number; marker: string; saved: number };
	const candidates: Candidate[] = [];
	for (let index = 0; index < messages.length; index++) {
		const message = record(messages[index]);
		if (
			message?.role !== "toolResult"
			|| message.isError === true
			|| typeof message.toolCallId !== "string"
		) {
			continue;
		}
		const call = calls.get(message.toolCallId);
		if (call === undefined) continue;
		const text = textContent(message.content);
		if (text === undefined) continue;
		let marker: string | undefined;
		const latest = call.key === undefined ? undefined : lastSuccessful.get(call.key);
		if (latest !== undefined && latest.index > index && latest.text === text) {
			marker = call.name === "read"
				? "[PI//DECK pruned: superseded by the later identical read result.]"
				: "[PI//DECK pruned: superseded by the later identical navigation result.]";
		} else if (
			call.name === "code_nav"
			&& index <= messages.length - NO_MATCH_MIN_AGE_MESSAGES
			&& /(?:no matches?|nothing found|ничего не найдено|совпадени\p{L}* не найден)/iu.test(text)
		) {
			marker = "[PI//DECK pruned: old bounded search returned no matches.]";
		}
		if (marker === undefined) continue;
		const saved = Buffer.byteLength(text, "utf8") - Buffer.byteLength(marker, "utf8");
		if (saved > 0) candidates.push({ index, marker, saved });
	}
	const selected = candidates.slice(0, PRUNE_MAX_RESULTS);
	if (selected.reduce((total, item) => total + item.saved, 0) < PRUNE_MIN_SAVED_BYTES) {
		return messages as unknown[];
	}
	const replacements = new Map(selected.map((item) => [item.index, item.marker]));
	return messages.map((message, index) => {
		const marker = replacements.get(index);
		if (marker === undefined) return message;
		return {
			...(record(message) ?? {}),
			content: [{ type: "text", text: marker }],
		};
	});
}

export default function (pi: ExtensionAPI) {
	pi.on("context", (event) => {
		const messages = pruneSupersededContext(event.messages);
		return messages === event.messages
			? undefined
			: { messages: messages as typeof event.messages };
	});

	pi.on("tool_result", (event) => {
		const text = event.content
			.filter((part) => part.type === "text")
			.map((part) => part.type === "text" ? part.text : "")
			.join("\n");
		if (
			Buffer.byteLength(text, "utf8") <= MAX_CONTEXT_BYTES
			&& text.split("\n").length <= MAX_CONTEXT_LINES
		) {
			return;
		}

		const fullPath = privateResultPath(event.toolCallId, text);
		const notice = fullPath
			? `\n\n[PI//DECK сократил большой вывод. Полная версия: ${fullPath}. `
				+ "Читайте её узким диапазоном только при необходимости.]"
			: "\n\n[PI//DECK сократил большой вывод. Повторите запрос с более узким диапазоном.]";
		const images = event.content.filter((part) => part.type !== "text");
		return {
			content: [
				{ type: "text", text: compactText(text) + notice },
				...images,
			],
		};
	});
}
