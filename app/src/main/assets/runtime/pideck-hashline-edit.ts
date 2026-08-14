/**
 * Line-anchored editing for models too small to quote text back perfectly.
 *
 * Pi's edit tool matches an exact string. A 2B model has to reproduce indentation, whitespace
 * and punctuation byte for byte to use it, and when it fails it retries, which costs the one
 * thing a phone has least of: output tokens. This extension stamps every line `read` returns
 * with a short content hash and offers an edit tool that addresses lines by that anchor. The
 * model then recalls four pseudo-random characters instead of reproducing a line.
 *
 * The anchor is not just an address. It carries the content hash, so an edit written against a
 * stale read is rejected rather than applied to the wrong line.
 *
 * Mutation still goes through the one approval path in pideck-permission-gate.ts.
 */

import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import path, { extname } from "node:path";

import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

import {
	approved,
	decisionHeader,
	diffPreview,
	lineCount,
	pathRisk,
} from "./pideck-permission-gate.ts";

const MAX_ANNOTATED_LINES = 4_000;
const MAX_EDITS = 24;
const MAX_REPLACEMENT_CHARS = 16_000;
const SYNTAX_CHECK_TIMEOUT_MS = 5_000;
const ANCHOR = /^(\d{1,6}):([0-9a-f]{2})$/;
/** Trailing note `read` appends after the file body; it must not be annotated as content. */
const READ_NOTE = /^\[(?:Showing lines |.* more lines in file|Line \d+ is ).*\]$/;

type Anchored = { line: number; digest: string };

function lineDigest(line: number, text: string): string {
	return createHash("sha256")
		.update(String(line))
		.update("\0")
		.update(text)
		.digest("hex")
		.slice(0, 2);
}

function anchorFor(line: number, text: string): string {
	return `${line}:${lineDigest(line, text)}`;
}

/**
 * Stamps each line with `line:hash| `.
 *
 * `read` appends its own note about truncation after a blank separator. That tail is not file
 * content, so it is split off first and passed through untouched — annotating it would hand
 * the model an anchor for a line that does not exist.
 */
export function annotateReadText(text: string, firstLine = 1): string {
	const lines = text.split("\n");
	if (lines.length > MAX_ANNOTATED_LINES) return text;

	let bodyEnd = lines.length;
	for (let index = lines.length - 1; index >= 0; index--) {
		const line = lines[index].trim();
		if (line === "") continue;
		if (READ_NOTE.test(line)) {
			bodyEnd = index;
			while (bodyEnd > 0 && lines[bodyEnd - 1].trim() === "") bodyEnd--;
		}
		break;
	}

	const annotated = lines
		.slice(0, bodyEnd)
		.map((line, index) => `${anchorFor(firstLine + index, line)}| ${line}`);
	return [...annotated, ...lines.slice(bodyEnd)].join("\n");
}

function parseAnchor(value: string): Anchored {
	const match = ANCHOR.exec(value.trim());
	if (match === null) {
		throw new Error(
			`Якорь «${value}» не разобран. Ожидается вид 12:a3 из вывода read.`,
		);
	}
	return { line: Number(match[1]), digest: match[2] };
}

/**
 * A refusal that only says "read the file again" is a dead end for a small model: on device a
 * 2B invented an anchor, was correctly refused, and concluded the tool was broken rather than
 * reading first. So a refusal carries the real anchors around the target — enough to retry
 * immediately, without spending another read round-trip at 13 tok/s.
 */
const REFUSAL_WINDOW = 3;

function anchorWindow(fileLines: string[], line: number): string {
	const from = Math.max(1, line - REFUSAL_WINDOW);
	const to = Math.min(fileLines.length, line + REFUSAL_WINDOW);
	const shown = [];
	for (let number = from; number <= to; number++) {
		shown.push(`${anchorFor(number, fileLines[number - 1])}| ${fileLines[number - 1]}`);
	}
	return `Действующие якоря:\n${shown.join("\n")}\nПовтори правку с одним из них.`;
}

function verify(fileLines: string[], anchor: Anchored, label: string): number {
	const index = anchor.line - 1;
	if (index < 0 || index >= fileLines.length) {
		throw new Error(
			`${label} ${anchor.line} вне файла: в нём ${fileLines.length} строк.\n`
				+ anchorWindow(fileLines, Math.min(anchor.line, fileLines.length)),
		);
	}
	const actual = lineDigest(anchor.line, fileLines[index]);
	if (actual !== anchor.digest) {
		throw new Error(
			`${label} ${anchor.line} не совпала: якорь ${anchor.line}:${anchor.digest} устарел `
				+ `или был выдуман.\n${anchorWindow(fileLines, anchor.line)}`,
		);
	}
	return index;
}

type Replacement = {
	from: number;
	to: number;
	text: string;
	allowIndentationChange: boolean;
};

function planEdits(
	fileLines: string[],
	edits: Array<{
		anchor?: unknown;
		throughAnchor?: unknown;
		text?: unknown;
		allowIndentationChange?: unknown;
	}>,
): Replacement[] {
	const planned: Replacement[] = [];
	for (const edit of edits) {
		const start = parseAnchor(String(edit.anchor ?? ""));
		const from = verify(fileLines, start, "Строка");
		let to = from;
		if (edit.throughAnchor !== undefined && edit.throughAnchor !== null
			&& String(edit.throughAnchor).trim() !== "") {
			const end = parseAnchor(String(edit.throughAnchor));
			to = verify(fileLines, end, "Конечная строка");
			if (to < from) throw new Error("throughAnchor стоит выше anchor");
		}
		const text = String(edit.text ?? "");
		if (text.length > MAX_REPLACEMENT_CHARS) {
			throw new Error("Замена длиннее допустимого размера");
		}
		planned.push({
			from,
			to,
			text,
			allowIndentationChange: edit.allowIndentationChange === true,
		});
	}
	planned.sort((left, right) => left.from - right.from);
	for (let index = 1; index < planned.length; index++) {
		if (planned[index].from <= planned[index - 1].to) {
			throw new Error("Диапазоны правок перекрываются");
		}
	}
	return planned;
}

/** Applied bottom-up so earlier indices stay valid while later ones are rewritten. */
function applyEdits(fileLines: string[], planned: Replacement[]): string[] {
	const result = fileLines.slice();
	for (let index = planned.length - 1; index >= 0; index--) {
		const { from, to, text } = planned[index];
		result.splice(from, to - from + 1, ...(text === "" ? [] : text.split("\n")));
	}
	return result;
}

/**
 * A 2B model often copies the complete statement but drops only its leading whitespace.
 * Repair that narrow case without guessing at operator fragments or multi-line structure.
 */

function normalizedPythonIndent(
	filename: string,
	fileLines: string[],
	planned: Replacement[],
): { planned: Replacement[]; inherited: boolean } {
	if (extname(filename).toLowerCase() !== ".py") {
		return { planned, inherited: false };
	}
	let inherited = false;
	const adjusted = planned.map((edit) => {
		if (edit.allowIndentationChange || edit.text === "") {
			return edit;
		}
		const originalLine = fileLines[edit.from];
		const replacementLine = edit.text.split("\n", 1)[0];
		if (originalLine.trim() === "" || replacementLine.trim() === "") return edit;
		const originalIndent = /^[\t ]*/.exec(originalLine)?.[0] ?? "";
		const replacementIndent = /^[\t ]*/.exec(replacementLine)?.[0] ?? "";
		if (originalIndent === replacementIndent) return edit;
		if (replacementIndent === "" && !edit.text.includes("\n")
			&& originalIndent !== "" && /^[A-Za-z_]/.test(edit.text)) {
			inherited = true;
			return { ...edit, text: originalIndent + edit.text };
		}
		const number = edit.from + 1;
		throw new Error(
			"Правка не сохранена: ведущий отступ Python отличается от адресованной строки. "
				+ "Для обычной замены выбери якорь нужной строки и передай целую строку с тем же отступом. "
				+ "allowIndentationChange=true используй только для намеренной перестройки блока.\n"
				+ anchorWindow(fileLines, number),
		);
	});
	return { planned: adjusted, inherited };
}

const PYTHON_SOURCE_CHECK = [
	"import ast, sys",
	"source = sys.stdin.buffer.read()",
	"try:",
	"    ast.parse(source, sys.argv[1])",
	"except (SyntaxError, ValueError) as error:",
	"    line = getattr(error, 'lineno', None)",
	"    sys.stderr.write('%s%s\\n' % (error, (' (line %s)' % line) if line else ''))",
	"    sys.exit(1)",
].join("\n");

/** Returns a bounded syntax error; checker failures themselves remain fail-open. */
function pythonSyntaxError(filename: string, source: string): string | undefined {
	if (extname(filename).toLowerCase() !== ".py") return undefined;
	const result = spawnSync(
		process.env.PIDECK_SYNTAX_CHECK_PYTHON || "python3",
		["-c", PYTHON_SOURCE_CHECK, filename],
		{
			input: source,
			encoding: "utf8",
			stdio: ["pipe", "ignore", "pipe"],
			timeout: SYNTAX_CHECK_TIMEOUT_MS,
			maxBuffer: 16 * 1024,
		},
	);
	if (result.error || result.signal !== null || result.status === 0) return undefined;
	return String(result.stderr ?? "").trim().slice(0, 1024) || "invalid Python syntax";
}

export default function pideckHashlineEdit(pi: ExtensionAPI) {
	pi.on("tool_result", (event) => {
		if (event.toolName !== "read" || event.isError) return undefined;
		const offset = Number((event.input as { offset?: unknown }).offset ?? 1);
		const firstLine = Number.isSafeInteger(offset) && offset > 0 ? offset : 1;
		const images = event.content.filter((part) => part.type !== "text");
		const text = event.content
			.filter((part) => part.type === "text")
			.map((part) => (part.type === "text" ? part.text : ""))
			.join("\n");
		if (!text) return undefined;
		return {
			content: [
				{ type: "text", text: annotateReadText(text, firstLine) },
				...images,
			],
		};
	});

	pi.registerTool({
		name: "pideck_replace_lines",
		label: "replace lines (approval required)",
		description:
			"Replace whole lines addressed by the line:hash anchors that read prints, after the "
			+ "Android user grants one-time approval. Preferred over quoting text back exactly.",
		promptSnippet: "Replace lines by their read anchor instead of quoting text",
		promptGuidelines: [
			"Order matters: use an anchor from read or from PI//DECK BOUNDED PREFETCH, then call this tool. An anchor absent from authoritative context is rejected.",
			"Every line read returns looks like `12:a3| text`, and `12:a3` is that line's anchor.",
			"If this tool rejects an anchor it lists the current ones. Retry with one of those; do not switch tools.",
			"Give the replacement as whole lines without the anchor prefix; an empty text deletes the line.",
			"Set throughAnchor to replace a run of lines in one edit.",
		],
		parameters: Type.Object({
			path: Type.String({
				description: "File to edit, relative to the workspace unless absolute",
				minLength: 1,
				maxLength: 1024,
			}),
			edits: Type.Array(
				Type.Object({
					anchor: Type.String({
						description: "Anchor of the first line to replace, for example 12:a3",
					}),
					throughAnchor: Type.Optional(Type.String({
						description: "Anchor of the last line to replace, for a multi-line range",
					})),
					text: Type.String({
						description: "Replacement lines without anchors; empty deletes",
					}),
					allowIndentationChange: Type.Optional(Type.Boolean({
						description: "Explicitly allow changing the first Python line's indentation",
					})),
				}),
				{ minItems: 1, maxItems: MAX_EDITS },
			),
		}),
		async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
			const target = String(params.path ?? "");
			const resolved = path.resolve(ctx.cwd, target);
			// A missing file is the most likely mistake here — the model reaches for an edit
			// before creating anything — and a raw ENOENT gives it nowhere to go. Name the
			// next tool instead.
			let original: string;
			try {
				original = readFileSync(resolved, "utf8");
			} catch (error) {
				const code = (error as { code?: string }).code;
				if (code === "ENOENT") {
					throw new Error(
						`Файла ${target} нет. Сначала создай его, затем прочитай и правь по якорям.`,
					);
				}
				if (code === "EISDIR") throw new Error(`${target} — это каталог, а не файл`);
				throw error;
			}
			const fileLines = original.split("\n");
			const edits = params.edits as Array<Record<string, unknown>>;
			let planned = planEdits(fileLines, edits);
			const normalizedIndent = normalizedPythonIndent(resolved, fileLines, planned);
			planned = normalizedIndent.planned;
			let updated = applyEdits(fileLines, planned).join("\n");
			let syntaxFailure = pythonSyntaxError(resolved, updated);
			if (syntaxFailure !== undefined) {
				const reusable = planned
					.map((edit) => {
						const number = edit.from + 1;
						return `${anchorFor(number, fileLines[edit.from])}| ${fileLines[edit.from]}`;
					})
					.join("\n");
				throw new Error(
					"Правка не сохранена: получился синтаксически неверный Python. "
						+ "Поле text должно содержать целую строку с отступом, а не фрагмент.\n"
						+ `${syntaxFailure}\nДействующий якорь исходника:\n${reusable}`,
				);
			}

			if (process.env.PIDECK_HASHLINE_APPROVAL !== "none") {
				const removed = planned
					.map((edit) => fileLines.slice(edit.from, edit.to + 1).join("\n"))
					.join("\n");
				const added = planned.map((edit) => edit.text).join("\n");
				const allow = await approved(
					ctx,
					"Allow file edit?",
					decisionHeader({
						kind: "overwrite",
						path: resolved,
						reason: `Меняю ${planned.length === 1 ? "одну строку" : `${planned.length} участка`} по якорям.`,
						addedLines: lineCount(added),
						removedLines: lineCount(removed),
						selfCreated: false,
						preview: diffPreview(removed, added),
					})
						+ `Tool: pideck_replace_lines\nTarget: ${pathRisk(ctx.cwd, target)}\n`
						+ `Edit count: ${planned.length}\n`,
				);
				if (!allow) throw new Error("PI//DECK approval denied or expired");
			}

			writeFileSync(resolved, updated, { encoding: "utf8" });
			const summary = planned
				.map((edit) => `${edit.from + 1}-${edit.to + 1} → ${lineCount(edit.text)} строк`)
				.join("; ");
			return {
				content: [{
					type: "text",
					text: `Готово: ${target}. Заменено ${summary}. `
						+ "Якоря устарели — прочитай файл заново перед следующей правкой.",
				}],
				details: {
					path: resolved,
					edits: planned.length,
					inheritedIndentation: normalizedIndent.inherited,
				},
			};
		},
	});
}
