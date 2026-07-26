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
import path from "node:path";

const APPROVAL_TIMEOUT_MS = 30_000;
const MAX_PREVIEW = 4_096;

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
			const allow = await approved(
				ctx,
				"Allow file edit?",
				`Tool: pideck_edit\nTarget: ${pathRisk(ctx.cwd, target)}\n`
					+ `Edit count: ${params.edits.length}\n\n${preview(params.edits)}`,
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
			const allow = await approved(
				ctx,
				"Allow file write?",
				`Tool: pideck_write\nTarget: ${pathRisk(ctx.cwd, target)}\nBytes: ${
					new TextEncoder().encode(content).length
				}\n\n${preview(content)}`,
			);
			if (!allow) throw new Error("PI//DECK approval denied or expired");
			return createWriteTool(ctx.cwd).execute(toolCallId, params, signal, onUpdate);
		},
	});
}
