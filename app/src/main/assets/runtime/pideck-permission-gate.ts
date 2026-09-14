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

export type ShellRisk = {
	level: "normal" | "critical";
	reason?: string;
};

/**
 * Classifies commands whose blast radius is wider than a single agent workspace. This is a
 * deliberately small fail-closed boundary, not a claim that shell text can be sandboxed. In
 * CONFIRM_CHANGES the reason is shown to the user; AUTONOMOUS imports the same classifier and
 * refuses these commands because that profile has no per-command approval channel.
 */
export function classifyShellCommand(command: string): ShellRisk {
	const value = command
		.replace(/\\\r?\n/g, " ")
		.replace(/\s+/g, " ")
		.trim()
		.toLowerCase();
	const critical: Array<[RegExp, string]> = [
		[/\b(?:curl|wget)\b[^|\n]{0,2048}\|\s*(?:(?:sudo|env|command|nohup)\s+)*(?:\/(?:usr\/)?bin\/)?(?:ba|da|k|z)?sh\b/u,
			"remote download is piped directly into a shell"],
		[/\b(?:eval)\b/u, "eval can hide a second unclassified command"],
		[/\b(?:mkfs(?:\.[a-z0-9_+-]+)?|wipefs|fdisk|sfdisk|parted)\b/u,
			"disk or filesystem metadata may be destroyed"],
		[/\bdd\b[^\n]{0,2048}\bof\s*=\s*\/dev\//u, "dd writes directly to a device"],
		[/\b(?:reboot|poweroff|shutdown|halt)\b/u, "the phone or runtime may be stopped"],
		[/\b(?:pm\s+(?:clear|uninstall|disable-user)|cmd\s+package\s+(?:clear|uninstall|disable-user))\b/u,
			"Android application data or package state may be removed"],
		[/\b(?:apt(?:-get)?|pkg)\b[^;&|\n]{0,512}\b(?:remove|purge|uninstall)\b/u,
			"installed runtime packages may be removed"],
		[/\bsystemctl\b[^;&|\n]{0,512}\b(?:disable|mask|stop)\b/u,
			"a system service may be disabled"],
		[/\bgit\b[^;&|\n]{0,512}\breset\b[^;&|\n]{0,256}\s--hard\b/u,
			"uncommitted repository changes may be discarded"],
		[/\bgit\b[^;&|\n]{0,512}\bclean\b[^;&|\n]{0,256}(?:-[^\s]*f[^\s]*|--force)\b/u,
			"untracked repository data may be deleted"],
		[/\brm\s+[^\n]*(?:-[^\s]*r[^\s]*|--recursive)[^\n]*\s(?:\/[^\s;&|]*|~[^\s;&|]*|\$home[^\s;&|]*|\$\{home\}[^\s;&|]*|\.\.?|\*)(?=\s*(?:$|&&|\|\||;))/u,
			"recursive deletion targets a broad, absolute, home, or wildcard path"],
		[/\bfind\s+(?:\/\S*|~\S*|\$home\S*|\$\{home\}\S*|\.\.?)\s[^\n]*\s-delete\b/u,
			"recursive find deletion has a broad starting path"],
		[/\b(?:chmod|chown)\s+[^\n]*(?:-[^\s]*r[^\s]*|--recursive)\b[^\n]*(?:\/|~|\$home|\$\{home\})/u,
			"recursive ownership or permission changes escape a narrow workspace target"],
	];
	for (const [pattern, reason] of critical) {
		if (pattern.test(value)) return { level: "critical", reason };
	}
	return { level: "normal" };
}

/** Suggests a managed equivalent only for a simple command with no pipelines or redirection. */
export function dedicatedToolForShell(
	command: string,
	activeTools: readonly string[],
): string | undefined {
	const value = command.trim();
	if (!value || /[|&;<>\n\r]/u.test(value)) return undefined;
	const executable = /^(?:(?:[A-Za-z_][A-Za-z0-9_]*=[^\s]+)\s+)*(?:sudo\s+)?([^\s]+)/u
		.exec(value)?.[1]
		?.split("/")
		.at(-1)
		?.toLowerCase();
	if (["cat", "head", "tail"].includes(executable ?? "") && activeTools.includes("read")) {
		return "Use the managed read tool with an exact path/range instead of shell text output.";
	}
	if (
		["grep", "rg", "find", "fd"].includes(executable ?? "")
		&& activeTools.includes("code_nav")
	) {
		return "Use code_nav for one bounded workspace search instead of shell discovery.";
	}
	if (
		executable === "sed"
		&& /(?:^|\s)(?:-[^\s]*i[^\s]*|--in-place(?:=[^\s]*)?)(?:\s|$)/u.test(value)
		&& (activeTools.includes("pideck_replace_lines") || activeTools.includes("pideck_edit_text"))
	) {
		return "Use read plus " + (activeTools.includes("pideck_edit_text") ? "pideck_edit_text" : "pideck_replace_lines")
			+ "; direct sed -i bypasses snapshot-backed editing.";
	}
	return undefined;
}

/**
 * Pi's confirm() carries a title and a message, so anything the Android side needs in a
 * structured form travels on the first line of the message and is lifted back off by the bridge.
 * The prose below it stays readable on its own if that header is ever dropped.
 */
const DECISION_PREFIX = "PIDECK-DECISION/1 ";

/** Files this Pi process created itself, which the deck may be told to overwrite silently. */
const createdHere = new Set<string>();

export function decisionHeader(decision: Record<string, unknown>): string {
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

export function lineCount(value: string): number {
	if (value.length === 0) return 0;
	return value.split("\n").length;
}

/** The first removed and added lines, marked, so the card can show what changes. */
export function diffPreview(before: string, after: string): string[] {
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

export function pathRisk(cwd: string, target: string): string {
	const resolved = path.resolve(cwd, target);
	const relative = path.relative(cwd, resolved);
	const outside = relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative);
	return `${resolved}\nWorkspace escape risk: ${outside ? "YES" : "NO"}`;
}

/**
 * Exported so a sibling extension that also mutates files reuses this exact decision path
 * instead of copying it. A second copy of an approval routine is a second thing that can be
 * wrong; there is only ever one way for PI//DECK to ask.
 */
export async function approved(
	ctx: ExtensionContext,
	title: string,
	message: string,
	timeoutMs: number = APPROVAL_TIMEOUT_MS,
): Promise<boolean> {
	if (!ctx.hasUI || ctx.mode !== "rpc") return false;
	try {
		return (
			(await ctx.ui.confirm(title, message, {
				timeout: timeoutMs,
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
			const dedicated = dedicatedToolForShell(command, pi.getActiveTools());
			if (dedicated !== undefined) throw new Error(dedicated);
			const risk = classifyShellCommand(command);
			const allow = await approved(
				ctx,
				risk.level === "critical" ? "Allow critical shell command?" : "Allow shell command?",
				decisionHeader({
					kind: "shell",
					path: ctx.cwd,
					reason: risk.reason ?? "Shell can read or change data with the Termux user's permissions.",
					addedLines: 0,
					removedLines: 0,
					selfCreated: false,
					preview: [],
				})
					+ `Tool: pideck_bash\nRisk: ${risk.level}\nCWD: ${ctx.cwd}\n`
					+ `Workspace escape risk: possible\n\n${preview(command)}`,
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
