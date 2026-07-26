/**
 * PI//DECK CONFIRM_CHANGES profile for Pi 0.82.1.
 *
 * Built-in bash/edit/write are not in the active tool allowlist. These differently named
 * equivalents ask through Pi's documented RPC extension UI protocol before delegating to the
 * original implementation. Missing UI, disconnect, malformed reply, or timeout resolves to deny.
 */

import type {
	ExtensionAPI,
	ExtensionContext,
} from "@earendil-works/pi-coding-agent";
import {
	createBashTool,
	createEditTool,
	createWriteTool,
} from "@earendil-works/pi-coding-agent";
import fs from "node:fs";
import path from "node:path";

const APPROVAL_TIMEOUT_MS = 30_000;
const MAX_PREVIEW = 4_096;
const MAX_DIFF_BYTES = 256 * 1024;
const PREVIEW_LINES = 4;

/**
 * Pi's confirm() carries a title and a message, so anything the Android side needs in a
 * structured form travels on the first line of the message and is lifted back off by the bridge.
 * The prose below it stays readable on its own if that header is ever dropped.
 */
const DECISION_PREFIX = "PIDECK-DECISION/1 ";

/** Files this Pi process created itself, which the deck may be told to overwrite silently. */
const createdHere = new Set<string>();

function decisionHeader(decision: Record<string, unknown>): string {
	return DECISION_PREFIX + JSON.stringify(decision) + "\n";
}

function readIfSmall(target: string): string | null {
	try {
		const stat = fs.statSync(target);
		if (!stat.isFile() || stat.size > MAX_DIFF_BYTES) return null;
		return fs.readFileSync(target, "utf8");
	} catch {
		return null;
	}
}

function lineCount(value: string): number {
	if (value.length === 0) return 0;
	return value.split("\n").length;
}

/** The first removed and added lines, marked, so the card can show what changes. */
function diffPreview(before: string, after: string): string[] {
	const removed = before.split("\n").filter((line) => line.trim().length > 0);
	const added = after.split("\n").filter((line) => line.trim().length > 0);
	const half = Math.floor(PREVIEW_LINES / 2);
	return [
		...removed.slice(0, half).map((line) => `−${line}`),
		...added.slice(0, PREVIEW_LINES - half).map((line) => `+${line}`),
	];
}

function preview(value: unknown): string {
	const rendered = typeof value === "string" ? value : JSON.stringify(value);
	return rendered.length <= MAX_PREVIEW
		? rendered
		: `${rendered.slice(0, MAX_PREVIEW)}\n[preview truncated]`;
}

function pathRisk(cwd: string, target: string): string {
	const resolved = path.resolve(cwd, target);
	const relative = path.relative(cwd, resolved);
	const outside = relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative);
	return `${resolved}\nWorkspace escape risk: ${outside ? "YES" : "NO"}`;
}

async function approved(
	ctx: ExtensionContext,
	title: string,
	message: string,
): Promise<boolean> {
	if (!ctx.hasUI || ctx.mode !== "rpc") return false;
	try {
		return (
			(await ctx.ui.confirm(title, message, {
				timeout: APPROVAL_TIMEOUT_MS,
			})) === true
		);
	} catch {
		return false;
	}
}

export default function pideckPermissionGate(pi: ExtensionAPI) {
	// Defense in depth: these names must never execute in CONFIRM_CHANGES even if a future
	// configuration mistake accidentally makes a built-in active.
	pi.on("tool_call", async (event) => {
		if (event.toolName === "bash" || event.toolName === "edit" || event.toolName === "write") {
			return { block: true, reason: "Ungated mutating built-in disabled by PI//DECK" };
		}
		return undefined;
	});

	const bashParameters = createBashTool(process.cwd()).parameters;
	pi.registerTool({
		name: "pideck_bash",
		label: "bash (approval required)",
		description:
			"Execute a shell command only after the Android user grants a one-time approval.",
		parameters: bashParameters,
		async execute(toolCallId, params, signal, onUpdate, ctx) {
			const command = String(params.command ?? "");
			const allow = await approved(
				ctx,
				"Allow shell command?",
				`Tool: pideck_bash\nCWD: ${ctx.cwd}\nWorkspace escape risk: possible\n\n${preview(command)}`,
			);
			if (!allow) throw new Error("PI//DECK approval denied or expired");
			return createBashTool(ctx.cwd).execute(toolCallId, params, signal, onUpdate);
		},
	});

	const editParameters = createEditTool(process.cwd()).parameters;
	pi.registerTool({
		name: "pideck_edit",
		label: "edit (approval required)",
		description:
			"Replace exact text in a file only after the Android user grants one-time approval.",
		parameters: editParameters,
		async execute(toolCallId, params, signal, onUpdate, ctx) {
			const target = String(params.path ?? "");
			const resolved = path.resolve(ctx.cwd, target);
			const edits = params.edits as Array<{ oldText?: string; newText?: string }>;
			const removedText = edits.map((edit) => String(edit.oldText ?? "")).join("\n");
			const addedText = edits.map((edit) => String(edit.newText ?? "")).join("\n");
			const allow = await approved(
				ctx,
				"Allow file edit?",
				decisionHeader({
					kind: "overwrite",
					path: resolved,
					reason: `Меняю ${edits.length === 1 ? "один фрагмент" : `${edits.length} фрагмента`} в файле.`,
					addedLines: lineCount(addedText),
					removedLines: lineCount(removedText),
					selfCreated: createdHere.has(resolved),
					preview: diffPreview(removedText, addedText),
				})
					+ `Tool: pideck_edit\nTarget: ${pathRisk(ctx.cwd, target)}\n`
					+ `Edit count: ${edits.length}\n\n${preview(params.edits)}`,
			);
			if (!allow) throw new Error("PI//DECK approval denied or expired");
			return createEditTool(ctx.cwd).execute(toolCallId, params, signal, onUpdate);
		},
	});

	const writeParameters = createWriteTool(process.cwd()).parameters;
	pi.registerTool({
		name: "pideck_write",
		label: "write (approval required)",
		description:
			"Create or overwrite a file only after the Android user grants one-time approval.",
		parameters: writeParameters,
		async execute(toolCallId, params, signal, onUpdate, ctx) {
			const target = String(params.path ?? "");
			const content = String(params.content ?? "");
			const resolved = path.resolve(ctx.cwd, target);
			const existing = readIfSmall(resolved);
			const replacing = existing !== null;
			const allow = await approved(
				ctx,
				replacing ? "Allow file overwrite?" : "Allow file write?",
				decisionHeader({
					kind: "overwrite",
					path: resolved,
					reason: replacing
						? "Заменяю содержимое существующего файла целиком."
						: "Создаю новый файл в рабочей папке.",
					addedLines: lineCount(content),
					removedLines: replacing ? lineCount(existing) : 0,
					selfCreated: createdHere.has(resolved),
					preview: diffPreview(existing ?? "", content),
				})
					+ `Tool: pideck_write\nTarget: ${pathRisk(ctx.cwd, target)}\nBytes: ${
						new TextEncoder().encode(content).length
					}\n\n${preview(content)}`,
			);
			if (!allow) throw new Error("PI//DECK approval denied or expired");
			const result = await createWriteTool(ctx.cwd).execute(
				toolCallId,
				params,
				signal,
				onUpdate,
			);
			// Only a write the agent actually completed makes the file one of its own.
			createdHere.add(resolved);
			return result;
		},
	});
}
