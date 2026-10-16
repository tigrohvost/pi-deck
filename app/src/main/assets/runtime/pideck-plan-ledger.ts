/**
 * Phone-native, opt-in planning for Pi 0.82.1.
 *
 * Android owns entry into this mode and the plan/execute decision. The planning pass permits
 * only managed read-only tools: the guard blocks every other call in depth and the pass context
 * names the permitted tools, while the provider schema stays byte-stable so the KV prefix of the
 * session survives both the planning and the execution turn. A single execution follow-up is
 * queued only after the user confirms the bounded plan card through Pi's RPC extension UI.
 */

import type { AgentMessage } from "@earendil-works/pi-agent-core";
import type { AssistantMessage, TextContent } from "@earendil-works/pi-ai";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";

import { approved, decisionHeader } from "./pideck-permission-gate.ts";
import { createHash, randomUUID } from "node:crypto";
import { realpathSync } from "node:fs";
import { resolve, sep } from "node:path";
import { currentPassedReceipt, currentTestOperation, workspaceSnapshot } from "./pideck-run-tests.ts";

export const PLAN_REQUEST_PREFIX = "[[PI//DECK:PLAN_REQUEST]]\n";
export const PLAN_APPROVAL_TIMEOUT_MS = 120_000;
const PLAN_ENTRY_TYPE = "pideck-plan-ledger";
const MAX_PLAN_ITEMS = 7;
const MIN_PLAN_ITEMS = 3;
const MAX_ITEM_CHARS = 200;
const READ_ONLY_TOOLS = new Set(["read", "code_nav", "web_research", "weather"]);

export type PlanItemStatus = "pending" | "active" | "done_unverified" | "verified" | "blocked" | "cancelled";

export type PlanItem = {
	step: number;
	text: string;
	status: PlanItemStatus;
	verificationKind?: "test" | "inspection";
	evidenceIds?: string[];
	snapshotId?: string;
};

type PlanState = {
	phase: "idle" | "planning" | "planned" | "executing" | "unverified" | "blocked" | "complete" | "cancelled";
	goal: string;
	items: PlanItem[];
	revision?: string;
	operationId?: string;
	workspaceId?: string;
	scopeFingerprint?: string;
	reason?: string;
};

function isAssistantMessage(message: AgentMessage): message is AssistantMessage {
	return message.role === "assistant" && Array.isArray(message.content);
}

function assistantText(message: AssistantMessage): string {
	return message.content
		.filter((block): block is TextContent => block.type === "text")
		.map((block) => block.text)
		.join("\n");
}

function cleanItem(value: string): string {
	const cleaned = value
		.replace(/\*{1,2}([^*]+)\*{1,2}/gu, "$1")
		.replace(/`([^`]+)`/gu, "$1")
		.replace(/\s+/gu, " ")
		.trim();
	return cleaned.slice(0, MAX_ITEM_CHARS).trim();
}

/** Extracts only a bounded, explicit numbered section headed by Plan:/План:. */
export function extractPlanItems(message: string): PlanItem[] {
	const header = /(?:^|\n)\s*\*{0,2}(?:Plan|План):\*{0,2}\s*\n/iu.exec(message);
	if (header === null) return [];
	const section = message.slice(header.index + header[0].length);
	const items: PlanItem[] = [];
	for (const match of section.matchAll(/^\s*(\d+)[.)]\s+(.+)$/gmu)) {
		const expected = items.length + 1;
		if (Number(match[1]) !== expected) break;
		const text = cleanItem(match[2]);
		if (text.length < 4) break;
		items.push({ step: expected, text, status: "pending" });
		if (items.length === MAX_PLAN_ITEMS) break;
	}
	return items.length >= MIN_PLAN_ITEMS ? items : [];
}

export function progressMarkers(message: string): Array<{
	kind: "done" | "blocked";
	step: number;
}> {
	const result: Array<{ kind: "done" | "blocked"; step: number }> = [];
	const lines = message.trimEnd().split("\n");
	const last = lines.pop() ?? "";
	if (!/^(?:\[(?:DONE|BLOCKED):[1-7]\](?: |$))+$/iu.test(last)) return [];
	let fence: string | undefined;
	for (const line of lines) {
		const marker = /^ {0,3}(`{3,}|~{3,})/u.exec(line)?.[1];
		if (marker && (!fence || marker[0] === fence[0] && marker.length >= fence.length)) {
			fence = fence ? undefined : marker;
		}
	}
	if (fence) return [];
	for (const match of last.matchAll(/\[(DONE|BLOCKED):(\d+)\]/giu)) {
		const step = Number(match[2]);
		if (step >= 1 && step <= MAX_PLAN_ITEMS) {
			result.push({
				kind: match[1].toUpperCase() === "DONE" ? "done" : "blocked",
				step,
			});
		}
	}
	return result;
}

function boundedState(value: unknown): PlanState {
	if (typeof value !== "object" || value === null) {
		return { phase: "idle", goal: "", items: [] };
	}
	const raw = value as Partial<PlanState>;
	const phases = new Set<PlanState["phase"]>([
		"idle", "planning", "planned", "executing", "unverified", "blocked", "complete", "cancelled",
	]);
	const statuses = new Set<PlanItemStatus>(["pending", "active", "done_unverified", "verified", "blocked", "cancelled"]);
	const items = Array.isArray(raw.items)
		? raw.items.slice(0, MAX_PLAN_ITEMS).flatMap((item, index) => {
			if (typeof item !== "object" || item === null) return [];
			const candidate = item as Partial<PlanItem>;
			const text = cleanItem(typeof candidate.text === "string" ? candidate.text : "");
			if (text.length < 4) return [];
			return [{
				step: index + 1,
				text,
				// Historical model declarations have no host evidence and must not stay verified.
				status: candidate.status === "verified" ? "done_unverified" as const
					: statuses.has(candidate.status as PlanItemStatus)
					? candidate.status as PlanItemStatus
					: "pending" as const,
			}];
		})
		: [];
	return {
		phase: raw.phase === "complete" ? "unverified" : phases.has(raw.phase as PlanState["phase"])
			? raw.phase as PlanState["phase"]
			: "idle",
		goal: typeof raw.goal === "string" ? raw.goal.slice(0, 2_048) : "",
		items,
	};
}

export default function pideckPlanLedger(pi: ExtensionAPI): void {
	let state: PlanState = { phase: "idle", goal: "", items: [] };
	let currentContext: ExtensionContext | undefined;
	const evidence = new Map<number, { id: string; kind: "test" | "inspection"; snapshot: string }>();
	const readSnapshots = new Map<string, string | null>();
	const supported = process.env.PIDECK_AGENT_MODE === "agent"
		&& process.env.PIDECK_ACCESS_PROFILE !== "read_only";

	function persist(): void {
		pi.appendEntry(PLAN_ENTRY_TYPE, state);
		currentContext?.ui?.setStatus?.(PLAN_ENTRY_TYPE, JSON.stringify({ schemaVersion: 1, ...state }));
	}

	pi.on("session_start", (_event, ctx) => {
		currentContext = ctx;
		evidence.clear();
		readSnapshots.clear();
		const entry = ctx.sessionManager.getEntries()
			.filter((candidate: { type: string; customType?: string }) =>
				candidate.type === "custom" && candidate.customType === PLAN_ENTRY_TYPE)
			.pop() as { data?: unknown } | undefined;
		state = boundedState(entry?.data);
		// RPC/UI confirmation is process-local and is never replayed after a restart. Likewise,
		// execution cannot resume invisibly from a durable session entry.
		if (["planning", "planned", "executing"].includes(state.phase)) {
			const interruptedExecution = state.phase === "executing";
			state = {
				phase: interruptedExecution ? "blocked" : "cancelled",
				goal: state.goal,
				items: state.items.map((item) => ({
					...item,
					status: interruptedExecution && item.status === "active"
						? "blocked"
						: item.status,
				})),
			};
			persist();
			return;
		}
	});

	pi.on("input", (event, ctx) => {
		currentContext = ctx;
		if (!event.text.startsWith(PLAN_REQUEST_PREFIX)) {
			if (event.source !== "extension" && state.operationId !== currentTestOperation(ctx?.cwd ?? process.cwd())) {
				state = { phase: "idle", goal: "", items: [] };
				evidence.clear();
				readSnapshots.clear();
			}
			return { action: "continue" } as const;
		}
		if (!supported) return { action: "handled" } as const;
		const goal = event.text.slice(PLAN_REQUEST_PREFIX.length).trim();
		state = {
			phase: "planning",
			goal: goal.slice(0, 2_048),
			items: [],
			revision: randomUUID(),
			operationId: currentTestOperation(ctx?.cwd ?? process.cwd()),
			workspaceId: createHash("sha256").update(realpathSync(ctx?.cwd ?? process.cwd())).digest("hex"),
		};
		evidence.clear();
		readSnapshots.clear();
		persist();
		return { action: "transform", text: goal, images: event.images } as const;
	});

	pi.on("tool_call", (event, ctx) => {
		if (state.phase === "executing" && event.toolName === "read") {
			readSnapshots.set(event.toolCallId, workspaceSnapshot(ctx?.cwd ?? process.cwd()));
		}
		if (state.phase !== "planning" && state.phase !== "planned") return undefined;
		if (READ_ONLY_TOOLS.has(event.toolName)) return undefined;
		return {
			block: true,
			reason: "PI//DECK plan pass is read-only; Android approval is required before execution",
		};
	});

	pi.on("tool_result", (event, ctx) => {
		const readSnapshot = readSnapshots.get(event.toolCallId);
		readSnapshots.delete(event.toolCallId);
		if (state.phase !== "executing" || event.isError) return;
		const cwd = ctx?.cwd ?? process.cwd();
		const input = event.input as { path?: string };
		const snapshot = workspaceSnapshot(cwd);
		if (!snapshot || !input.path) return;
		const receipt = (event.details as { receipt?: unknown } | undefined)?.receipt;
		const kind = event.toolName === "run_tests" && currentPassedReceipt(receipt, cwd) ? "test"
			: event.toolName === "read" && readSnapshot === snapshot ? "inspection" : undefined;
		if (!kind) return;
		for (const item of state.items) {
			// Evidence is matched to the exact declared path, never inferred from prose or DONE.
			const requirement = /\[(test|inspection):([^\]\n]+)\]/u.exec(item.text);
			if (requirement?.[1] !== kind) continue;
			const expected = resolve(cwd, requirement[2]);
			if (expected !== resolve(cwd) && !expected.startsWith(resolve(cwd) + sep)) continue;
			if (expected !== resolve(cwd, input.path)) continue;
			evidence.set(item.step, { id: kind === "test" ? (receipt as { receiptId: string }).receiptId : randomUUID(), kind, snapshot });
		}
	});

	pi.on("before_agent_start", (_event, ctx) => {
		if (ctx) currentContext = ctx;
		if (state.phase === "planning") {
			return {
				message: {
					customType: "pideck-plan-context",
					content: `[PI//DECK READ-ONLY PLAN PASS]\n\nExplore only what is necessary. This pass permits only ${[...READ_ONLY_TOOLS].join(", ")}; every other tool call is refused. Do not change files, run commands, install dependencies, or execute the proposed work. Return a concise plan under an exact \"Plan:\" header with 3-7 numbered, independently verifiable steps. For a step verified by a file read, append [inspection:relative/path]; for a test step append [test:relative/test/path]. Only actual successful tools matching that path and the current workspace snapshot can verify the step. Other completed steps remain unverified. Mention important risks inside the relevant step.`,
					display: false,
				},
			};
		}
		if (state.phase === "executing" && state.items.length > 0) {
			const remainingItems = state.items
				.filter((item) => item.status !== "verified");
			const remaining = remainingItems
				.map((item) => `${item.step}. ${item.text}`)
				.join("\n");
			const verifiedSuffix = remainingItems
				.map((item) => `[DONE:${item.step}]`)
				.join(" ");
			return {
				message: {
					customType: "pideck-plan-execution-context",
					content: `[PI//DECK EXECUTION AUTHORIZATION - ANDROID USER CONFIRMED]\n\nThe user pressed Execute in the Android plan card. The exact approved steps below are authoritative runtime state; do not claim that an approved plan is missing. Complete them in order and verify the result. Android reads only machine-status tags for checklist progress. DONE declares completion only; the host validates evidence independently. If all remaining steps are done, the FINAL LINE MUST BE EXACTLY:\n${verifiedSuffix}\nIf a step cannot be completed safely, stop and put [BLOCKED:n] for that step on the final line instead. Never omit the final status line.\n\nApproved remaining steps:\n${remaining}`,
					display: false,
				},
			};
		}
		return undefined;
	});

	pi.on("turn_end", (event, ctx) => {
		if (state.phase !== "executing" || !isAssistantMessage(event.message)) return;
		const snapshot = workspaceSnapshot(ctx?.cwd ?? currentContext?.cwd ?? process.cwd());
		for (const marker of progressMarkers(assistantText(event.message))) {
			const item = state.items.find((candidate) => candidate.step === marker.step);
			if (item === undefined) continue;
			const proof = evidence.get(item.step);
			item.status = marker.kind === "blocked" ? "blocked" : proof && proof.snapshot === snapshot ? "verified" : "done_unverified";
			item.verificationKind = item.status === "verified" ? proof?.kind : undefined;
			item.evidenceIds = item.status === "verified" && proof ? [proof.id] : [];
			item.snapshotId = item.status === "verified" && proof ? proof.snapshot : undefined;
		}
		for (const item of state.items) {
			if (item.status === "verified" && item.snapshotId !== snapshot) {
				item.status = "done_unverified"; item.evidenceIds = []; item.verificationKind = undefined;
			}
		}
		if (state.items.some((item) => item.status === "blocked")) state.phase = "blocked";
		else if (state.items.every((item) => item.status === "verified")) state.phase = "complete";
		else if (state.items.every((item) => ["verified", "done_unverified"].includes(item.status))) state.phase = "unverified";
		persist();
	});

	pi.on("agent_end", async (event, ctx: ExtensionContext) => {
		currentContext = ctx;
		if (state.phase !== "planning") return;
		const last = [...event.messages].reverse().find(isAssistantMessage);
		const items = last === undefined ? [] : extractPlanItems(assistantText(last));
		if (items.length === 0) {
			state = { phase: "blocked", goal: state.goal, items: [] };
			persist();
			return;
		}
		state.items = items;
		state.phase = "planned";
		state.scopeFingerprint = createHash("sha256").update(JSON.stringify(items.map(item => item.text))).digest("hex");
		const proposed = state;
		const cwd = ctx.cwd ?? process.cwd();
		const snapshot = workspaceSnapshot(cwd);
		const expiresAt = Date.now() + PLAN_APPROVAL_TIMEOUT_MS;
		persist();
		const confirmed = await approved(
			ctx,
			"Execute this plan?",
			decisionHeader({
				kind: "plan",
				path: "",
				reason: state.goal,
				addedLines: 0,
				removedLines: 0,
				selfCreated: false,
				preview: state.items.map((item) => item.text),
			}) + "The planning pass was read-only. Execution starts only after this approval.",
			PLAN_APPROVAL_TIMEOUT_MS,
		);
		if (state !== proposed) return;
		if (!confirmed || Date.now() >= expiresAt || proposed.operationId !== currentTestOperation(cwd)
			|| !snapshot || workspaceSnapshot(cwd) !== snapshot) {
			state = { ...state, phase: "cancelled", reason: "Approval expired, declined, or workspace changed" };
			persist();
			return;
		}
		state.phase = "executing";
		state.items = state.items.map((item, index) => ({
			...item,
			status: index === 0 ? "active" : "pending",
		}));
		persist();
		const steps = state.items.map((item) => `${item.step}. ${item.text}`).join("\n");
		const doneMarkers = state.items.map((item) => `[DONE:${item.step}]`).join(" ");
		// This is a user-role message because it is emitted only after the user physically presses
		// Execute. Small local models reliably distinguish that direct approval from an extension's
		// hidden custom context; followUp still produces exactly one additional model turn.
		pi.sendUserMessage(
			`I approve this exact plan in the Android UI. Execute it now and report completed progress; the host verifies evidence independently. Android requires a machine-readable final line. If every step succeeds, end with this exact line and nothing after it:\n${doneMarkers}\nIf a step is unsafe or cannot be completed, end with [BLOCKED:n] for that step instead.\n\nApproved plan:\n${steps}`,
			{ deliverAs: "followUp" },
		);
	});
}
