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

export default function (pi: ExtensionAPI) {
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
