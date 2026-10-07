/**
 * Snapshot-backed line editing for a small local model.
 *
 * `read` prints compact line anchors, but an anchor alone is never authority to write. The
 * extension retains the exact file bytes plus the set of lines actually shown to the model.
 * An edit is accepted only while those bytes are still current and every addressed anchor came
 * from that authoritative set. Writes are preflighted completely, syntax-checked where cheap,
 * then committed through a same-directory fsync + rename.
 */

import { createHash, randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
	closeSync,
	fchmodSync,
	fsyncSync,
	lstatSync,
	openSync,
	readFileSync,
	realpathSync,
	renameSync,
	unlinkSync,
	writeFileSync,
} from "node:fs";
import path, { basename, dirname, extname, join } from "node:path";

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
const MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
const MAX_SNAPSHOT_TOTAL_BYTES = 4 * 1024 * 1024;
const MAX_SNAPSHOTS = 12;
const STRUCTURAL_READ_MIN_LINES = 100;
const STRUCTURAL_READ_MAX_LINES = 90;
const IDENTICAL_READ_LIMIT = 2;
const SYNTAX_CHECK_TIMEOUT_MS = 5_000;
const ANCHOR = /^(\d{1,6}):([0-9a-f]{8})$/;
/** Trailing note `read` appends after the file body; it must not become file authority. */
const READ_NOTE = /^\[(?:Showing lines |.* more lines in file|Line \d+ is ).*\]$/;
const SNAPSHOT_REGISTRY_PROPERTY = "__pideckHashlineSnapshotRegistryV2";

type Anchored = { line: number; digest: string; value: string };
type SeenLine = { anchor: string; line: number; text: string };
type Snapshot = {
	path: string;
	content: Buffer;
	sha256: string;
	seen: Map<string, number>;
};
type SnapshotRegistry = {
	snapshots: Map<string, Snapshot>;
	totalBytes: number;
};
type FileLayout = {
	bom: string;
	eol: string;
	lines: string[];
};
export type RenderedRead = {
	text: string;
	seen: SeenLine[];
	summarized: boolean;
};

function registry(): SnapshotRegistry {
	const host = globalThis as typeof globalThis & Record<string, unknown>;
	const existing = host[SNAPSHOT_REGISTRY_PROPERTY];
	if (
		typeof existing === "object"
		&& existing !== null
		&& (existing as SnapshotRegistry).snapshots instanceof Map
	) {
		return existing as SnapshotRegistry;
	}
	const created: SnapshotRegistry = { snapshots: new Map(), totalBytes: 0 };
	host[SNAPSHOT_REGISTRY_PROPERTY] = created;
	return created;
}

export function clearAuthoritativeSnapshots(): void {
	const state = registry();
	state.snapshots.clear();
	state.totalBytes = 0;
}

function sha256(content: Buffer): string {
	return createHash("sha256").update(content).digest("hex");
}

function lineDigest(line: number, text: string): string {
	return createHash("sha256")
		.update(String(line))
		.update("\0")
		.update(text)
		.digest("hex")
		.slice(0, 8);
}

function anchorFor(line: number, text: string): string {
	return `${line}:${lineDigest(line, text)}`;
}

function normalizedReadText(text: string): string {
	const withoutBom = text.startsWith("\uFEFF") ? text.slice(1) : text;
	return withoutBom.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

function splitReadBody(text: string): { lines: string[]; tail: string[] } {
	const lines = normalizedReadText(text).split("\n");
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
	return { lines: lines.slice(0, bodyEnd), tail: lines.slice(bodyEnd) };
}

function annotatedLines(lines: readonly string[], firstLine: number): SeenLine[] {
	return lines.map((text, index) => {
		const line = firstLine + index;
		return { anchor: anchorFor(line, text), line, text };
	});
}

/** Pure formatter retained for bounded callers that register the snapshot separately. */
export function annotateReadText(text: string, firstLine = 1): string {
	const { lines, tail } = splitReadBody(text);
	if (lines.length > MAX_ANNOTATED_LINES) return text;
	const seen = annotatedLines(lines, firstLine);
	return [
		...seen.map((item) => `${item.anchor}| ${item.text}`),
		...tail,
	].join("\n");
}

const STRUCTURAL_SOURCE_EXTENSIONS = new Set([
	".c", ".cc", ".cpp", ".cxx", ".h", ".hpp",
	".java", ".kt", ".kts", ".py", ".rb", ".rs",
	".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx",
	".go", ".swift", ".sh", ".bash", ".zsh", ".md", ".markdown",
]);

function structuralPriority(filename: string, line: string): number | undefined {
	const extension = extname(filename).toLowerCase();
	if (!STRUCTURAL_SOURCE_EXTENSIONS.has(extension)) return undefined;
	const value = line.trim();
	if (!value) return undefined;
	if (extension === ".md" || extension === ".markdown") {
		return /^#{1,6}\s+\S/u.test(value) ? 0 : undefined;
	}
	if (/^(?:package|namespace|module)\s+\S/u.test(value)) return 0;
	if (/^(?:import|from\s+\S+\s+import|using|#include)\b/u.test(value)) return 2;
	if (
		/^(?:export\s+)?(?:default\s+)?(?:abstract\s+|public\s+|private\s+|protected\s+|internal\s+|static\s+|final\s+|sealed\s+|open\s+|data\s+)*(?:class|interface|enum|object|struct|trait|type|record|protocol|extension)\b/u.test(value)
	) {
		return 0;
	}
	if (
		/^(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:def|function|func|fn)\s+[$\p{L}_][\p{L}\p{N}_$]*/u.test(value)
		|| /^(?:async\s+def|class)\s+[\p{L}_][\p{L}\p{N}_]*/u.test(value)
	) {
		return 0;
	}
	if (
		/^(?:(?:public|private|protected|internal|static|final|open|override|suspend|inline|async)\s+){0,6}(?:fun\s+)?[$\p{L}_][\p{L}\p{N}_$<>?]*\s*\([^;{}]*\)\s*(?::|throws\b|\{|=>|$)/u.test(value)
	) {
		return 1;
	}
	if (/^[$\p{L}_][\p{L}\p{N}_$]*\s*\(\)\s*\{/u.test(value)) return 1;
	return undefined;
}

/** Returns a bounded declaration outline, or undefined when exact output is already cheap. */
export function structuralReadSummary(
	filename: string,
	text: string,
	firstLine = 1,
): RenderedRead | undefined {
	const { lines, tail } = splitReadBody(text);
	if (lines.length < STRUCTURAL_READ_MIN_LINES) return undefined;
	const candidates = lines
		.map((line, index) => ({ index, priority: structuralPriority(filename, line) }))
		.filter((item): item is { index: number; priority: number } => item.priority !== undefined)
		.sort((left, right) => left.priority - right.priority || left.index - right.index);
	if (candidates.length < 3) return undefined;
	const selected = candidates
		.slice(0, STRUCTURAL_READ_MAX_LINES)
		.map((item) => item.index)
		.sort((left, right) => left - right);
	const seen = selected.map((index) => {
		const line = firstLine + index;
		const value = lines[index];
		return { anchor: anchorFor(line, value), line, text: value };
	});
	return {
		text: [
			`[PI//DECK STRUCTURAL READ: ${basename(filename)}, ${lines.length} строк. `
				+ "Показаны imports/declarations и номера, не edit-якоря; перед правкой запроси точный offset/limit.]",
			...seen.map((item) => `${item.line}| ${item.text}`),
			"[Структурный обзор скрыл тело файла. Используй read(path, offset, limit) "
				+ "только для нужного диапазона.]",
			...tail,
		].join("\n"),
		seen: [],
		summarized: true,
	};
}

function renderRead(
	filename: string,
	text: string,
	firstLine: number,
	explicitRange: boolean,
): RenderedRead {
	if (!explicitRange) {
		const summary = structuralReadSummary(filename, text, firstLine);
		if (summary !== undefined) return summary;
	}
	const { lines, tail } = splitReadBody(text);
	if (lines.length > MAX_ANNOTATED_LINES) {
		return { text, seen: [], summarized: false };
	}
	const seen = annotatedLines(lines, firstLine);
	return {
		text: [...seen.map((item) => `${item.anchor}| ${item.text}`), ...tail].join("\n"),
		seen,
		summarized: false,
	};
}

function fileLayout(content: Buffer): FileLayout {
	if (content.length > MAX_SNAPSHOT_BYTES) throw new Error("file is too large for a safe snapshot");
	if (content.includes(0)) throw new Error("binary files cannot use line anchors");
	let value = content.toString("utf8");
	if (!Buffer.from(value, "utf8").equals(content)) {
		throw new Error("line anchors require valid UTF-8");
	}
	const bom = value.startsWith("\uFEFF") ? "\uFEFF" : "";
	if (bom) value = value.slice(1);
	const endings = value.match(/\r\n|\n|\r/g) ?? [];
	const styles = new Set(endings);
	if (styles.size > 1) throw new Error("mixed line endings require an exact-text edit");
	const eol = endings[0] ?? "\n";
	return { bom, eol, lines: value.split(eol) };
}

function inspectRegularFile(target: string): {
	path: string;
	content: Buffer;
	mode: number;
	dev: number;
	ino: number;
} {
	const lexical = path.resolve(target);
	const lexicalState = lstatSync(lexical);
	if (lexicalState.isSymbolicLink()) throw new Error("symbolic-link targets are not edited by hashline");
	if (!lexicalState.isFile()) throw new Error("hashline target must be a regular file");
	const actual = realpathSync(lexical);
	const state = lstatSync(actual);
	if (state.isSymbolicLink() || !state.isFile()) {
		throw new Error("hashline target must resolve to a regular non-symlink file");
	}
	return {
		path: actual,
		content: readFileSync(actual),
		mode: state.mode,
		dev: state.dev,
		ino: state.ino,
	};
}

function putSnapshot(target: string, content: Buffer, seenLines: readonly SeenLine[]): boolean {
	if (content.length > MAX_SNAPSHOT_BYTES) return false;
	let layout: FileLayout;
	try {
		layout = fileLayout(content);
	} catch {
		return false;
	}
	for (const item of seenLines) {
		if (item.line < 1 || item.line > layout.lines.length || layout.lines[item.line - 1] !== item.text) {
			return false;
		}
	}
	const state = registry();
	const existing = state.snapshots.get(target);
	const sameContent = existing?.content.equals(content) === true;
	const seen = sameContent ? new Map(existing?.seen) : new Map<string, number>();
	for (const item of seenLines) seen.set(item.anchor, item.line);
	if (existing !== undefined) {
		state.totalBytes -= existing.content.length;
		state.snapshots.delete(target);
	}
	const snapshot: Snapshot = {
		path: target,
		content: Buffer.from(content),
		sha256: sha256(content),
		seen,
	};
	state.snapshots.set(target, snapshot);
	state.totalBytes += snapshot.content.length;
	while (state.snapshots.size > MAX_SNAPSHOTS || state.totalBytes > MAX_SNAPSHOT_TOTAL_BYTES) {
		const oldest = state.snapshots.entries().next().value as [string, Snapshot] | undefined;
		if (oldest === undefined) break;
		state.snapshots.delete(oldest[0]);
		state.totalBytes -= oldest[1].content.length;
	}
	return state.snapshots.has(target);
}

/**
 * Formats a trusted read and records exact backing bytes. If the path changed, is a symlink,
 * is binary, or cannot be snapshotted, it returns the original read without unusable anchors.
 */
export function annotateAuthoritativeRead(
	target: string,
	text: string,
	firstLine = 1,
	options: { explicitRange?: boolean; source?: Buffer } = {},
): string {
	try {
		const inspected = inspectRegularFile(target);
		const content = options.source ?? inspected.content;
		if (!content.equals(inspected.content)) return text;
		const rendered = renderRead(inspected.path, text, firstLine, options.explicitRange === true);
		return putSnapshot(inspected.path, content, rendered.seen) ? rendered.text : text;
	} catch {
		return text;
	}
}

function parseAnchor(value: string): Anchored {
	const normalized = value.trim();
	const match = ANCHOR.exec(normalized);
	if (match === null) {
		throw new Error(
			`Якорь «${value}» не разобран. Ожидается вид 12:a3f97c21 из вывода read.`,
		);
	}
	return { line: Number(match[1]), digest: match[2], value: normalized };
}

class AnchorRefusal extends Error {
	constructor(message: string, readonly line: number) {
		super(message);
	}
}

function verify(snapshot: Snapshot, fileLines: string[], anchor: Anchored, label: string): number {
	const index = anchor.line - 1;
	if (index < 0 || index >= fileLines.length) {
		throw new AnchorRefusal(
			`${label} ${anchor.line} вне файла: в нём ${fileLines.length} строк.`,
			Math.max(1, Math.min(anchor.line, fileLines.length)),
		);
	}
	if (snapshot.seen.get(anchor.value) !== anchor.line) {
		throw new AnchorRefusal(
			`${label} ${anchor.line}:${anchor.digest} отсутствует в авторитетно показанных строках `
				+ "или относится к устаревшему снимку.",
			anchor.line,
		);
	}
	return index;
}

const REFUSAL_WINDOW = 3;
const REFRESHED_ANCHOR_MAX_LINES = 80;

function authoritativeWindow(target: string, content: Buffer, line: number): string {
	let layout: FileLayout;
	try {
		layout = fileLayout(content);
	} catch {
		return "Сделай новый точный read перед повторной правкой.";
	}
	const center = Math.max(1, Math.min(line, layout.lines.length));
	const from = Math.max(1, center - REFUSAL_WINDOW);
	const to = Math.min(layout.lines.length, center + REFUSAL_WINDOW);
	const shown: SeenLine[] = [];
	for (let number = from; number <= to; number++) {
		const text = layout.lines[number - 1];
		shown.push({ anchor: anchorFor(number, text), line: number, text });
	}
	putSnapshot(target, content, shown);
	return `Действующие авторитетные якоря:\n${shown
		.map((item) => `${item.anchor}| ${item.text}`)
		.join("\n")}\nПовтори правку с одним из них.`;
}

type Replacement = {
	preserveEmpty?: boolean;
	from: number;
	to: number;
	text: string;
	allowIndentationChange: boolean;
};

/**
 * Re-arms the snapshot on the committed bytes for every edited range plus a small context
 * window, and renders those lines with their current anchors. A follow-up edit of the same
 * file can then proceed from the tool result instead of paying for another read round.
 * Returns undefined when the committed file cannot be snapshotted, in which case the caller
 * falls back to requiring a fresh read.
 */
function refreshedAnchors(
	target: string,
	updated: Buffer,
	planned: readonly Replacement[],
): string | undefined {
	let layout: FileLayout;
	try {
		layout = fileLayout(updated);
	} catch {
		return undefined;
	}
	const total = layout.lines.length;
	const windows = (context: number): Array<[number, number]> => {
		const ranges: Array<[number, number]> = [];
		let delta = 0;
		for (const edit of planned) {
			const inserted = edit.text === "" && !edit.preserveEmpty ? 0 : edit.text.split("\n").length;
			const start = edit.from + delta + 1;
			const last = inserted === 0 ? start - 1 : start + inserted - 1;
			const from = Math.max(1, Math.min(start, total) - context);
			const to = Math.min(total, Math.max(last, from) + context);
			if (from <= to) {
				const previous = ranges.at(-1);
				if (previous !== undefined && from <= previous[1] + 1) previous[1] = Math.max(previous[1], to);
				else ranges.push([from, to]);
			}
			delta += inserted - (edit.to - edit.from + 1);
		}
		return ranges;
	};
	let ranges = windows(REFUSAL_WINDOW);
	const shownLines = (value: Array<[number, number]>) =>
		value.reduce((sum, [from, to]) => sum + (to - from + 1), 0);
	if (shownLines(ranges) > REFRESHED_ANCHOR_MAX_LINES) ranges = windows(0);
	if (ranges.length === 0 || shownLines(ranges) > REFRESHED_ANCHOR_MAX_LINES) return undefined;
	const shown: SeenLine[] = [];
	const blocks: string[] = [];
	for (const [from, to] of ranges) {
		const block: string[] = [];
		for (let number = from; number <= to; number++) {
			const text = layout.lines[number - 1];
			const item = { anchor: anchorFor(number, text), line: number, text };
			shown.push(item);
			block.push(`${item.anchor}| ${item.text}`);
		}
		blocks.push(block.join("\n"));
	}
	if (!putSnapshot(target, updated, shown)) return undefined;
	return blocks.join("\n⋯\n");
}

function normalizeReplacement(value: string): string {
	return value.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

function planEdits(
	snapshot: Snapshot,
	fileLines: string[],
	edits: Array<{
		anchor?: unknown;
		throughAnchor?: unknown;
		text?: unknown;
		allowIndentationChange?: unknown;
	}>,
	preserveEmpty = false,
): Replacement[] {
	const planned: Replacement[] = [];
	for (const edit of edits) {
		const start = parseAnchor(String(edit.anchor ?? ""));
		const from = verify(snapshot, fileLines, start, "Строка");
		let to = from;
		if (
			edit.throughAnchor !== undefined
			&& edit.throughAnchor !== null
			&& String(edit.throughAnchor).trim() !== ""
		) {
			const end = parseAnchor(String(edit.throughAnchor));
			to = verify(snapshot, fileLines, end, "Конечная строка");
			if (to < from) throw new Error("throughAnchor стоит выше anchor");
		}
		const text = normalizeReplacement(String(edit.text ?? ""));
		if (text.length > MAX_REPLACEMENT_CHARS) throw new Error("Замена длиннее допустимого размера");
		planned.push({
			from,
			to,
			text,
			allowIndentationChange: edit.allowIndentationChange === true,
			preserveEmpty,
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
		const { from, to, text, preserveEmpty } = planned[index];
		result.splice(from, to - from + 1, ...(text === "" && !preserveEmpty ? [] : text.split("\n")));
	}
	return result;
}

/** Repair only the observed single-line Python indentation omission. */
function normalizedPythonIndent(
	filename: string,
	fileLines: string[],
	planned: Replacement[],
): { planned: Replacement[]; inherited: boolean } {
	if (extname(filename).toLowerCase() !== ".py") return { planned, inherited: false };
	let inherited = false;
	const adjusted = planned.map((edit) => {
		if (edit.allowIndentationChange || edit.text === "") return edit;
		const originalLine = fileLines[edit.from];
		const replacementLine = edit.text.split("\n", 1)[0];
		if (originalLine.trim() === "" || replacementLine.trim() === "") return edit;
		const originalIndent = /^[\t ]*/.exec(originalLine)?.[0] ?? "";
		const replacementIndent = /^[\t ]*/.exec(replacementLine)?.[0] ?? "";
		if (originalIndent === replacementIndent) return edit;
		if (
			replacementIndent === ""
			&& !edit.text.includes("\n")
			&& originalIndent !== ""
			&& /^[A-Za-z_]/.test(edit.text)
		) {
			inherited = true;
			return { ...edit, text: originalIndent + edit.text };
		}
		throw new AnchorRefusal(
			"Правка не сохранена: ведущий отступ Python отличается от адресованной строки. "
				+ "Для обычной замены передай целую строку с тем же отступом. "
				+ "allowIndentationChange=true используй только для намеренной перестройки блока.",
			edit.from + 1,
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
function pythonSyntaxError(filename: string, source: Buffer): string | undefined {
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

function fsyncDirectory(directory: string): void {
	let descriptor: number | undefined;
	try {
		descriptor = openSync(directory, "r");
		fsyncSync(descriptor);
	} catch {
		// Some Android filesystems reject directory fsync. The file itself was already fsynced.
	} finally {
		if (descriptor !== undefined) closeSync(descriptor);
	}
}

class StaleCommit extends Error {
	constructor() {
		super("file changed after edit preflight");
	}
}

function atomicReplace(
	target: string,
	expected: Buffer,
	replacement: Buffer,
	expectedIdentity: { dev: number; ino: number },
	mode: number,
): void {
	const directory = dirname(target);
	const temporary = join(
		directory,
		`.${basename(target)}.pideck-${process.pid}-${randomBytes(6).toString("hex")}.tmp`,
	);
	let descriptor: number | undefined;
	let renamed = false;
	try {
		descriptor = openSync(temporary, "wx", mode & 0o777);
		fchmodSync(descriptor, mode & 0o777);
		writeFileSync(descriptor, replacement);
		fsyncSync(descriptor);
		closeSync(descriptor);
		descriptor = undefined;

		const currentState = lstatSync(target);
		if (
			currentState.isSymbolicLink()
			|| !currentState.isFile()
			|| currentState.dev !== expectedIdentity.dev
			|| currentState.ino !== expectedIdentity.ino
		) {
			throw new StaleCommit();
		}
		const current = readFileSync(target);
		if (!current.equals(expected)) throw new StaleCommit();
		renameSync(temporary, target);
		renamed = true;
		fsyncDirectory(directory);
	} finally {
		if (descriptor !== undefined) closeSync(descriptor);
		if (!renamed) {
			try {
				unlinkSync(temporary);
			} catch {
				// Nothing to clean, or the OS already removed the unopened temporary path.
			}
		}
	}
}

/** Convert a unique literal replacement to fully seen lines of the current snapshot. */
export function exactTextLineEdits(cwd: string, target: string, oldText: string, newText: string): Array<Record<string, unknown>> {
	const resolved = path.resolve(cwd, target);
	const scoped = path.relative(path.resolve(cwd), resolved);
	if (scoped === ".." || scoped.startsWith(`..${path.sep}`) || path.isAbsolute(scoped)) {
		throw new Error("Edit path must stay inside the workspace");
	}
	if (!oldText || oldText.length > MAX_REPLACEMENT_CHARS || newText.length > MAX_REPLACEMENT_CHARS) {
		throw new Error("Exact replacement is empty or exceeds the size limit");
	}
	const inspected = inspectRegularFile(resolved);
	const actualScope = path.relative(realpathSync(cwd), inspected.path);
	if (actualScope === ".." || actualScope.startsWith(`..${path.sep}`) || path.isAbsolute(actualScope)) {
		throw new Error("Edit path must stay inside the workspace, including parent links");
	}
	const snapshot = registry().snapshots.get(inspected.path);
	if (snapshot === undefined || !snapshot.content.equals(inspected.content)) {
		throw new Error("File changed or has not been read. Read the exact file before editing.");
	}
	const layout = fileLayout(inspected.content);
	const text = layout.lines.join("\n");
	const before = normalizeReplacement(oldText);
	const after = normalizeReplacement(newText);
	const offset = text.indexOf(before);
	if (offset < 0 || text.indexOf(before, offset + 1) >= 0) {
		throw new Error("oldText must match exactly once. Include enough unchanged surrounding text from read.");
	}
	if (before === after) throw new Error("Replacement makes no change");
	const from = text.slice(0, offset).split("\n").length - 1;
	const to = text.slice(0, offset + before.length - 1).split("\n").length - 1;
	for (let line = from; line <= to; line++) {
		const anchor = anchorFor(line + 1, layout.lines[line]);
		if (snapshot.seen.get(anchor) !== line + 1) {
			throw new Error("Replacement covers unseen lines. Read that exact offset/limit first.");
		}
	}
	const lineStart = from === 0 ? 0 : layout.lines.slice(0, from).join("\n").length + 1;
	const lineEnd = layout.lines.slice(0, to + 1).join("\n").length;
	// If oldText includes its final newline, replace that line break as part of the edit.
	const includesBreak = offset + before.length > lineEnd;
	const replacement = text.slice(lineStart, offset) + after
		+ text.slice(offset + before.length, lineEnd);
	if (includesBreak) {
		// Include the following line so line-based commit preserves the literal replacement.
		if (to + 1 >= layout.lines.length) throw new Error("Read a replacement range without the final newline");
		const nextAnchor = anchorFor(to + 2, layout.lines[to + 1]);
		if (snapshot.seen.get(nextAnchor) !== to + 2) throw new Error("Read the following line before replacing its boundary");
		return [{ anchor: anchorFor(from + 1, layout.lines[from]), throughAnchor: nextAnchor,
			text: replacement + layout.lines[to + 1], allowIndentationChange: true }];
	}
	return [{ anchor: anchorFor(from + 1, layout.lines[from]),
		throughAnchor: anchorFor(to + 1, layout.lines[to]), text: replacement, allowIndentationChange: true }];
}

export default function pideckHashlineEdit(pi: ExtensionAPI) {
	const identicalReads = new Map<string, number>();

	pi.on("session_start", () => {
		clearAuthoritativeSnapshots();
		identicalReads.clear();
	});
	pi.on("input", () => {
		// The guard stops a model loop, not a later user request in the same durable session.
		identicalReads.clear();
	});

	pi.on("tool_result", (event) => {
		if (event.toolName !== "read" || event.isError) return undefined;
		const input = event.input as { path?: unknown; offset?: unknown; limit?: unknown };
		const offset = Number(input.offset ?? 1);
		const firstLine = Number.isSafeInteger(offset) && offset > 0 ? offset : 1;
		const images = event.content.filter((part) => part.type !== "text");
		const text = event.content
			.filter((part) => part.type === "text")
			.map((part) => (part.type === "text" ? part.text : ""))
			.join("\n");
		if (!text) return undefined;
		const requested = String(input.path ?? "");
		const target = path.resolve(process.cwd(), requested);
		const rendered = annotateAuthoritativeRead(target, text, firstLine, {
			explicitRange: input.offset !== undefined || input.limit !== undefined,
		});

		let repeatKey: string | undefined;
		try {
			const inspected = inspectRegularFile(target);
			repeatKey = [
				inspected.path,
				sha256(inspected.content),
				String(input.offset ?? ""),
				String(input.limit ?? ""),
			].join("\0");
		} catch {
			// A failed snapshot is still a valid ordinary read, just without repetition state.
		}
		if (repeatKey !== undefined) {
			const count = (identicalReads.get(repeatKey) ?? 0) + 1;
			identicalReads.set(repeatKey, count);
			if (count > IDENTICAL_READ_LIMIT) {
				return {
					content: [
						{
							type: "text" as const,
							text: "[PI//DECK остановил третье одинаковое чтение. "
								+ "Используй уже показанные якоря либо запроси другой точный offset/limit.]",
						},
						...images,
					],
				};
			}
		}
		return { content: [{ type: "text", text: rendered }, ...images] };
	});

	const lineEditTool = {
		name: "pideck_replace_lines",
		label: "replace lines (approval required)",
		description:
			"Replace whole lines from an exact authoritative read snapshot, after Android approval "
			+ "when the selected profile requires it. Use complete line:hash anchors exactly as read; "
			+ "never invent or shorten them.",
		parameters: Type.Object({
			path: Type.String({
				description: "File to edit, relative to the workspace unless absolute",
				minLength: 1,
				maxLength: 1024,
			}),
			edits: Type.Array(
				Type.Object({
					anchor: Type.String({
						description: "Eight-hex anchor of the first line, for example 12:a3f97c21",
					}),
					throughAnchor: Type.Optional(Type.String({
						description: "Anchor of the last line for a multi-line range",
					})),
					text: Type.String({
						description: "Replacement whole lines without anchors; empty deletes",
					}),
					allowIndentationChange: Type.Optional(Type.Boolean({
						description: "Explicitly allow changing the first Python line's indentation",
					})),
				}),
				{ minItems: 1, maxItems: MAX_EDITS },
			),
		}),
		async execute(_toolCallId: string, params: { path: string; edits: Array<Record<string, unknown>> }, _signal: AbortSignal | undefined, _onUpdate: unknown, ctx: import("@earendil-works/pi-coding-agent").ExtensionContext, literal = false) {
			const target = String(params.path ?? "");
			const resolved = path.resolve(ctx.cwd, target);
			let inspected: ReturnType<typeof inspectRegularFile>;
			try {
				inspected = inspectRegularFile(resolved);
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
			const layout = fileLayout(inspected.content);
			const state = registry();
			const snapshot = state.snapshots.get(inspected.path);
			const rawEdits = params.edits as Array<Record<string, unknown>>;
			const firstClaim = parseAnchor(String(rawEdits[0]?.anchor ?? ""));
			if (snapshot === undefined || !snapshot.content.equals(inspected.content)) {
				throw new Error(
					"Правка не сохранена: точный авторитетный снимок отсутствует или файл изменился.\n"
						+ authoritativeWindow(inspected.path, inspected.content, firstClaim.line),
				);
			}

			let planned: Replacement[];
			let normalizedIndent: { planned: Replacement[]; inherited: boolean };
			try {
				planned = planEdits(snapshot, layout.lines, rawEdits, literal);
				normalizedIndent = normalizedPythonIndent(inspected.path, layout.lines, planned);
				planned = normalizedIndent.planned;
			} catch (error) {
				if (error instanceof AnchorRefusal) {
					throw new Error(
						`${error.message}\n${authoritativeWindow(
							inspected.path,
							inspected.content,
							error.line,
						)}`,
					);
				}
				throw error;
			}
			const updatedLines = applyEdits(layout.lines, planned);
			const updatedText = layout.bom + updatedLines.join(layout.eol);
			const updated = Buffer.from(updatedText, "utf8");
			const syntaxFailure = pythonSyntaxError(inspected.path, updated);
			if (syntaxFailure !== undefined) {
				const reusable = planned
					.map((edit) => {
						const number = edit.from + 1;
						return `${anchorFor(number, layout.lines[edit.from])}| ${layout.lines[edit.from]}`;
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
					.map((edit) => layout.lines.slice(edit.from, edit.to + 1).join("\n"))
					.join("\n");
				const added = planned.map((edit) => edit.text).join("\n");
				const allow = await approved(
					ctx,
					"Allow file edit?",
					decisionHeader({
						kind: "overwrite",
						path: inspected.path,
						reason: `Атомарно меняю ${planned.length === 1 ? "одну строку" : `${planned.length} участка`} по точному снимку.`,
						addedLines: lineCount(added),
						removedLines: lineCount(removed),
						selfCreated: false,
						preview: diffPreview(removed, added),
					})
						+ `Tool: pideck_replace_lines\nTarget: ${pathRisk(ctx.cwd, target)}\n`
						+ `Edit count: ${planned.length}\nSnapshot: ${snapshot.sha256}\n`,
				);
				if (!allow) throw new Error("PI//DECK approval denied or expired");
			}

			try {
				atomicReplace(
					inspected.path,
					inspected.content,
					updated,
					{ dev: inspected.dev, ino: inspected.ino },
					inspected.mode,
				);
			} catch (error) {
				if (error instanceof StaleCommit) {
					throw new Error(
						"Правка не сохранена: файл изменился во время ожидания подтверждения. "
							+ "Сделай новый точный read перед повторной правкой.",
					);
				}
				throw error;
			}
			state.totalBytes -= snapshot.content.length;
			state.snapshots.delete(inspected.path);
			const summary = planned
				.map((edit) => `${edit.from + 1}-${edit.to + 1} → ${edit.text === "" && edit.preserveEmpty ? 1 : lineCount(edit.text)} строк`)
				.join("; ");
			const refreshed = refreshedAnchors(inspected.path, updated, planned);
			return {
				content: [{
					type: "text" as const,
					text: `Готово: ${target}. Атомарно заменено ${summary}. `
						+ (refreshed === undefined
							? "Снимок и якоря аннулированы — перед следующей правкой нужен новый read."
							: "Старые якоря недействительны. Действующие якоря изменённого диапазона:\n"
								+ `${refreshed}\n`
								+ "Для следующей правки этого файла используй их без нового read."),
				}],
				details: {
					path: inspected.path,
					edits: planned.length,
					atomic: true,
					inheritedIndentation: normalizedIndent.inherited,
					refreshedAnchors: refreshed !== undefined,
				},
			};
		},
	};
	pi.registerTool(lineEditTool);
	pi.registerTool({
		name: "pideck_edit_text",
		label: "replace exact text",
		description: "Replace one unique exact text from a file already read. Preserves the rest; rejects stale or unseen content. Approval follows the access profile.",
		parameters: Type.Object({
			path: Type.String({ minLength: 1, maxLength: 1024 }),
			oldText: Type.String({ minLength: 1, maxLength: MAX_REPLACEMENT_CHARS }),
			newText: Type.String({ maxLength: MAX_REPLACEMENT_CHARS }),
		}),
		async execute(id, params, signal, onUpdate, ctx) {
			const edits = exactTextLineEdits(ctx.cwd, params.path, params.oldText, params.newText);
			return lineEditTool.execute(id, { path: params.path, edits }, signal, onUpdate, ctx, true);
		},
	});
}
