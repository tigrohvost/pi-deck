/**
 * Keeps the small local model's initial tool schema proportional to the task.
 *
 * Pi registers every trusted bundled tool, but only the profile's compact core is active for a
 * normal turn. Explicit current-data requests activate the matching managed tools before Pi
 * builds the prompt. The model can load a remaining optional group when the core is insufficient.
 * No route can cross the Android-selected access profile.
 */

import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { lstatSync, readFileSync, realpathSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

import { explicitNavigationScope } from "./pideck-code-nav.ts";
import { annotateReadText } from "./pideck-hashline-edit.ts";

export type AccessProfile = "read_only" | "confirm_changes" | "autonomous";
export type AgentMode = "chat" | "agent";
export type ToolCapability = "files" | "web" | "weather" | "exact_edit";

const LOADER_TOOL = "pideck_load_tools";
export const INTERNAL_RETRY_PREFIX = "[[PI//DECK:ANSWER_RETRY]]\n";
export const DIRECT_LIVE_LOOKUP_MAX_TOKENS = 256;
const PREFETCH_MAX_FILES = 3;
const PREFETCH_MAX_FILE_BYTES = 4 * 1024;
const PREFETCH_MAX_TOTAL_BYTES = 6 * 1024;

type PrefetchedFile = {
	path: string;
	displayPath: string;
	annotated: string;
};

const CORE_TOOLS: Record<AccessProfile, readonly string[]> = {
	read_only: ["read", "code_nav"],
	confirm_changes: [
		"read",
		"code_nav",
		"pideck_bash",
		"pideck_write",
		"pideck_replace_lines",
	],
	autonomous: ["read", "bash", "write", "pideck_replace_lines", "run_tests"],
};

const OPTIONAL_TOOLS: Record<AccessProfile, Record<ToolCapability, readonly string[]>> = {
	read_only: {
		files: [],
		web: ["web_research"],
		weather: ["weather"],
		exact_edit: [],
	},
	confirm_changes: {
		files: [],
		web: ["web_research"],
		weather: ["weather"],
		exact_edit: ["pideck_edit"],
	},
	autonomous: {
		files: ["code_nav"],
		web: ["web_research"],
		weather: ["weather"],
		exact_edit: ["edit"],
	},
};

const WEB_CUES = [
	"поищи в сети",
	"найди в сети",
	"посмотри в сети",
	"проверь в сети",
	"поиск в сети",
	"поищи в интернете",
	"найди в интернете",
	"посмотри в интернете",
	"проверь в интернете",
	"поиск в интернете",
	"поищи онлайн",
	"найди онлайн",
	"search the web",
	"search online",
	"browse the web",
	"look up online",
	"find online",
] as const;

const CURRENT_WEB_CUES = [
	"последние новости",
	"что произошло сегодня",
	"кто сейчас ",
	"актуальная версия",
	"текущая версия",
	"сколько сейчас стоит",
	"цена сейчас",
	"latest version",
	"latest release",
	"today's news",
	"current price",
	"who is currently ",
] as const;

const CODE_CUES = [
	"code_nav",
	"где определ",
	"найди определение",
	"найди символ",
	"найди функцию",
	"найди класс",
	"найди файл",
	"структур проекта",
	"структур репозитор",
	"stack trace",
	"стектрейс",
	"definition of",
	"find definition",
	"find the function",
	"find the class",
	"find the file",
	"project structure",
	"repository structure",
] as const;

const READ_ONLY_NAVIGATION_CUES = [
	"ничего не меняй",
	"не меняй ничего",
	"ничего не изменяй",
	"не изменяй ничего",
	"без изменений файлов",
	"только для чтения",
	"do not change anything",
	"don't change anything",
	"make no changes",
	"without changing files",
	"read-only",
] as const;

const LOCATION_ONLY_CUES = [
	"номер строки",
	"строку определения",
	"файл и строк",
	"путь и строк",
	"line number",
	"file and line",
	"path and line",
] as const;

const CONTENT_REVIEW_CUES = [
	"объясни",
	"прочитай",
	"покажи содержимое",
	"суммируй",
	"explain",
	"read the",
	"show the contents",
	"summarize",
] as const;

const SCOPED_CHANGE_CUES = [
	"не меняй другие файлы",
	"другие файлы не меняй",
	"не трогай другие файлы",
	"только в ",
	"do not change other files",
	"don't change other files",
	"change only ",
] as const;

const MUTATION_CUES = [
	"исправ",
	"переимен",
	"обнови",
	"замени",
	"почини",
	"fix ",
	"repair ",
	"rename ",
	"update ",
] as const;

const COMPLEX_LIVE_LOOKUP_CUES = [
	"затем",
	"после этого",
	"сравни",
	"проанализ",
	"подробно",
	"исследуй",
	"несколько источников",
	"составь отчёт",
	"составь отчет",
	"then ",
	"after that",
	"compare",
	"analyz",
	"in detail",
	"research",
	"multiple sources",
	"write a report",
] as const;

const WEATHER_CUES = [
	"какая погод",
	"погода в ",
	"погоду в ",
	"погоды в ",
	"погода на ",
	"прогноз погод",
	"сейчас",
	"weather in ",
	"weather for ",
	"forecast in ",
	"forecast for ",
] as const;

function unique(values: readonly string[]): string[] {
	return [...new Set(values)];
}

function configuredProfile(): AccessProfile {
	const value = process.env.PIDECK_ACCESS_PROFILE;
	if (value === "read_only" || value === "confirm_changes" || value === "autonomous") {
		return value;
	}
	throw new Error("PI//DECK tool router has no valid access profile");
}

function configuredMode(): AgentMode {
	const value = process.env.PIDECK_AGENT_MODE;
	if (value === "chat" || value === "agent") return value;
	throw new Error("PI//DECK tool router has no valid agent mode");
}

export function coreTools(profile: AccessProfile): string[] {
	return [...CORE_TOOLS[profile], LOADER_TOOL];
}

export function isReadOnlyNavigationRequest(text: string): boolean {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	return detectCapabilities(candidate).includes("files")
		&& READ_ONLY_NAVIGATION_CUES.some((cue) => candidate.includes(cue));
}

export function explicitlyRequestedSoleTool(text: string): string | undefined {
	const candidate = text
		.toLocaleLowerCase()
		.replace(/[`"']/g, "")
		.replace(/\s+/g, " ")
		.trim();
	const forbidsOthers = [
		"другие инструменты",
		"других инструментов",
		"никаких других инструментов",
		"no other tools",
		"without other tools",
	].some((cue) => candidate.includes(cue));
	const requestsOneRead = [
		"вызови read ровно один раз",
		"используй read ровно один раз",
		"вызови инструмент read ровно один раз",
		"используй инструмент read ровно один раз",
		"call read exactly once",
		"use read exactly once",
		"call the read tool exactly once",
		"use the read tool exactly once",
	].some((cue) => candidate.includes(cue));
	if (forbidsOthers && requestsOneRead) return "read";

	const forbidsChanges = READ_ONLY_NAVIGATION_CUES.some((cue) =>
		candidate.includes(cue));
	const requestsOneCodeNav = [
		"одним вызовом code_nav",
		"вызови code_nav ровно один раз",
		"используй code_nav ровно один раз",
		"call code_nav exactly once",
		"use code_nav exactly once",
	].some((cue) => candidate.includes(cue));
	if (forbidsChanges && requestsOneCodeNav) return "code_nav";

	const requestsOneWebResearch = [
		"web_research ровно один раз",
		"одним вызовом web_research",
		"call web_research exactly once",
		"use web_research exactly once",
	].some((cue) => candidate.includes(cue));
	if (requestsOneWebResearch) return "web_research";

	const requestsWeatherInstead = [
		"используй weather, а не",
		"вызови weather, а не",
		"use weather, not",
		"use weather instead of",
	].some((cue) => candidate.includes(cue));
	if (requestsWeatherInstead) return "weather";

	const requestsReadOnlyFile = forbidsChanges
		&& /(?:прочитай|прочти|read)\s+\S*(?:\/|\.[a-z0-9]{1,8})(?:\s|$)/iu.test(candidate);
	return requestsReadOnlyFile ? "read" : undefined;
}

function cleanExplicitPath(value: string | undefined): string | undefined {
	let candidate = value?.trim();
	if (!candidate) return undefined;
	while (candidate.length > 1 && /[),;:!?]$/u.test(candidate)) {
		candidate = candidate.slice(0, -1);
	}
	return candidate || undefined;
}

/** Finds a file path the user explicitly attached to a read request. */
export function explicitReadPath(text: string): string | undefined {
	const forFile = text.match(
		/(?:для\s+файла|for\s+(?:the\s+)?file)\s+[`"'«]?([^\s`"'»<>]+)[`"'»]?/iu,
	);
	if (forFile?.[1]) return cleanExplicitPath(forFile[1]);
	const direct = text.match(
		/(?:прочитай|прочти)(?:\s+файл)?\s+[`"'«]?([^\s`"'»<>]+)[`"'»]?|(?:read)(?:\s+(?:the\s+)?file)?\s+[`"']?([^\s`"'<>]+)[`"']?/iu,
	);
	return cleanExplicitPath(direct?.[1] ?? direct?.[2]);
}

/** Combines the user's explicit directory and file without trusting model arguments. */
export function explicitReadTarget(text: string): string | undefined {
	const requested = explicitReadPath(text);
	if (!requested) return undefined;
	if (requested.startsWith("/")) return requested;
	const scope = explicitNavigationScope(text);
	return scope ? join(scope, requested) : requested;
}

/** Lexically confines an explicit read target to Pi's current workspace. */
export function safeReadTarget(cwd: string, target: string): string | undefined {
	const root = resolve(cwd);
	const candidate = resolve(root, target);
	return candidate === root || candidate.startsWith(`${root}${sep}`)
		? candidate
		: undefined;
}

/**
 * Reads only complete, small, explicitly named regular files without following a symlink out of
 * the workspace. A skipped file remains available through the ordinary read tool.
 */
export function boundedRepairPrefetch(
	cwd: string,
	targets: readonly string[],
): PrefetchedFile[] {
	let root: string;
	try {
		root = realpathSync(cwd);
	} catch {
		return [];
	}
	let used = 0;
	const snapshots: PrefetchedFile[] = [];
	for (const requested of targets.slice(0, PREFETCH_MAX_FILES)) {
		const lexical = safeReadTarget(root, requested);
		if (lexical === undefined) continue;
		try {
			const state = lstatSync(lexical);
			if (!state.isFile() || state.isSymbolicLink() || state.size > PREFETCH_MAX_FILE_BYTES) {
				continue;
			}
			const actual = realpathSync(lexical);
			if (actual !== root && !actual.startsWith(`${root}${sep}`)) continue;
			const raw = readFileSync(actual);
			if (raw.includes(0) || raw.byteLength > PREFETCH_MAX_FILE_BYTES
				|| used + raw.byteLength > PREFETCH_MAX_TOTAL_BYTES) {
				continue;
			}
			const text = raw.toString("utf8");
			if (!Buffer.from(text, "utf8").equals(raw)) continue;
			const annotated = annotateReadText(text);
			const annotatedBytes = Buffer.byteLength(annotated, "utf8");
			if (used + annotatedBytes > PREFETCH_MAX_TOTAL_BYTES) continue;
			used += annotatedBytes;
			snapshots.push({
				path: actual,
				displayPath: relative(root, actual) || ".",
				annotated,
			});
		} catch {
			// Missing, unreadable, changing, or non-text files fall back to managed read.
		}
	}
	return snapshots;
}

/** Extracts exact file paths named by the user, excluding the directory scope itself. */
export function explicitFilePaths(text: string): string[] {
	const matches = [...text.matchAll(
		/(?:^|[\s`"'«(])((?:\.{0,2}\/|\/)?(?:[\p{L}\p{N}_@.+-]+\/)*[\p{L}\p{N}_@+-][\p{L}\p{N}_@.+-]*\.[A-Za-z][A-Za-z0-9]{0,7})(?=$|[\s`"'»).,;:!?])/gu,
	)];
	return [...new Set(matches.map((match) => match[1]).filter(Boolean))];
}

export function explicitFileTargets(text: string): string[] {
	const scope = explicitNavigationScope(text);
	return explicitFilePaths(text).map((path) => {
		if (path.startsWith("/")) return path;
		return scope ? join(scope, path) : path;
	});
}

/** A bounded existing-file repair can run without a general-purpose shell. */
export function isScopedRepairRequest(text: string): boolean {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	return explicitFilePaths(text).length > 0
		&& SCOPED_CHANGE_CUES.some((cue) => candidate.includes(cue))
		&& MUTATION_CUES.some((cue) => candidate.includes(cue))
		&& /(?:тест\p{L}*|pytest|\btests?\b)/iu.test(candidate);
}

function isTestTarget(path: string): boolean {
	const candidate = path.replace(/\\/g, "/").toLocaleLowerCase();
	const name = candidate.slice(candidate.lastIndexOf("/") + 1);
	return candidate.includes("/tests/") || name.startsWith("test_");
}

/** Matches a model path to a user-named target, with a safe source-file fallback. */
export function selectScopedTarget(
	requestedPath: string,
	targets: readonly string[],
	preferTest = false,
): string | undefined {
	const normalized = requestedPath.trim().replace(/\\/g, "/").replace(/^\.\//, "");
	if (normalized) {
		// A model may legitimately ask to read either a named source or a named test.
		// Honour that exact user-scoped file before applying the source/test preference,
		// which exists only to choose a safe fallback for invented paths.
		const exact = targets.find((target) => {
			const normalizedTarget = target.replace(/\\/g, "/");
			return normalizedTarget === normalized
				|| normalizedTarget.endsWith(`/${normalized.replace(/^\/+/, "")}`)
				|| normalizedTarget.slice(normalizedTarget.lastIndexOf("/") + 1)
					=== normalized.slice(normalized.lastIndexOf("/") + 1);
		});
		if (exact) return exact;
	}
	const eligible = targets.filter((target) => isTestTarget(target) === preferTest);
	const pool = eligible.length > 0 ? eligible : [...targets];
	return pool[0];
}

function sameFileReference(reference: string, target: string): boolean {
	const normalizedReference = reference
		.trim()
		.replace(/^["']|["']$/g, "")
		.replace(/\\/g, "/")
		.replace(/^\.\//, "");
	const normalizedTarget = target.replace(/\\/g, "/").replace(/^\.\//, "");
	if (!normalizedReference) return false;
	return normalizedReference === normalizedTarget
		|| normalizedTarget.endsWith(`/${normalizedReference.replace(/^\/+/, "")}`)
		|| normalizedReference.endsWith(`/${normalizedTarget.replace(/^\/+/, "")}`)
		|| normalizedReference.slice(normalizedReference.lastIndexOf("/") + 1)
			=== normalizedTarget.slice(normalizedTarget.lastIndexOf("/") + 1);
}

/**
 * A small model sometimes puts a pytest path into `expr` instead of `path`.
 * Once the router has enforced the exact user-named file, retaining that value
 * as `pytest -k <path>` selects zero tests. Preserve a real -k expression (or a
 * pytest node ID), but remove the duplicate path-only filter.
 */
export function normalizeScopedTestExpression(raw: string, target: string): string | undefined {
	let candidate = raw.trim();
	if (!candidate) return undefined;
	const explicitFilter = /(?:^|\s)-k\s+(.+)$/iu.exec(candidate);
	if (explicitFilter?.[1]) return explicitFilter[1].trim();
	candidate = candidate.replace(/^(?:(?:python3?|py)\s+-m\s+)?pytest\s+/iu, "").trim();
	const [pathPart, ...nodeParts] = candidate.split("::");
	const pathToken = pathPart.trim().split(/\s+/u, 1)[0] ?? "";
	if (!sameFileReference(pathToken, target)) return raw;
	const nodeExpression = nodeParts.map((part) => part.trim()).filter(Boolean).at(-1);
	return nodeExpression || undefined;
}

export function requiresExactlyOneToolCall(text: string): boolean {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	return [
		"ровно один раз",
		"одним вызовом",
		"exactly once",
		"one call",
	].some((cue) => candidate.includes(cue));
}

export function isLocationOnlyNavigationRequest(text: string): boolean {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	return isReadOnlyNavigationRequest(candidate)
		&& LOCATION_ONLY_CUES.some((cue) => candidate.includes(cue))
		&& !CONTENT_REVIEW_CUES.some((cue) => candidate.includes(cue));
}

export function taskCoreTools(profile: AccessProfile, text: string): string[] {
	const soleTool = explicitlyRequestedSoleTool(text);
	if (soleTool) return [soleTool];
	if (profile === "autonomous" && isScopedRepairRequest(text)) {
		return ["read", "pideck_replace_lines", "run_tests"];
	}
	if (isLocationOnlyNavigationRequest(text)) return ["code_nav"];
	const directLookup = directLiveLookupTool(text);
	if (directLookup) return [directLookup];
	return isReadOnlyNavigationRequest(text)
		? ["read", "code_nav"]
		: coreTools(profile);
}

export function optionalCapabilities(profile: AccessProfile): ToolCapability[] {
	return (Object.keys(OPTIONAL_TOOLS[profile]) as ToolCapability[]).filter(
		(capability) => OPTIONAL_TOOLS[profile][capability].length > 0,
	);
}

export function detectCapabilities(text: string): ToolCapability[] {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	const webRequested = [...WEB_CUES, ...CURRENT_WEB_CUES]
		.some((cue) => candidate.includes(cue));
	const urlProvided = /(?:^|\s)https?:\/\/\S+/i.test(text);
	const codeNavigationRequested = CODE_CUES.some((cue) => candidate.includes(cue));
	const weatherMentioned = /(?:^|[^\p{L}\p{N}_])(?:погод\p{L}*|weather|forecast)(?:$|[^\p{L}\p{N}_])/u
		.test(candidate);
	const weatherRequested = weatherMentioned && (
		webRequested || WEATHER_CUES.some((cue) => candidate.includes(cue))
	);
	const result: ToolCapability[] = [];
	if (codeNavigationRequested) result.push("files");
	if (webRequested || urlProvided) result.push("web");
	if (weatherRequested) result.push("weather");
	return result;
}

/**
 * A short current-data question is a bounded lookup, not a general agent task. Keep mixed,
 * multi-step, URL, code and mutation requests on the normal router path.
 */
export function directLiveLookupTool(text: string): "web_research" | "weather" | undefined {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	if (!candidate || candidate.length > 320 || /(?:^|\s)https?:\/\/\S+/iu.test(text)) {
		return undefined;
	}
	const detected = detectCapabilities(candidate);
	if (detected.length !== 1) return undefined;
	if (
		COMPLEX_LIVE_LOOKUP_CUES.some((cue) => candidate.includes(cue))
		|| MUTATION_CUES.some((cue) => candidate.includes(cue))
		|| CODE_CUES.some((cue) => candidate.includes(cue))
	) {
		return undefined;
	}
	if (detected[0] === "web") return "web_research";
	if (detected[0] === "weather") return "weather";
	return undefined;
}

/** Caps both the tool-selection round and its final-answer round without touching other tasks. */
export function capDirectLookupProviderRequest(
	payload: unknown,
	active: boolean,
): unknown {
	if (!active || typeof payload !== "object" || payload === null || Array.isArray(payload)) {
		return undefined;
	}
	const request = payload as Record<string, unknown>;
	const configured = request.max_tokens;
	if (typeof configured !== "number" || !Number.isFinite(configured) || configured <= 0) {
		return undefined;
	}
	return {
		...request,
		max_tokens: Math.min(configured, DIRECT_LIVE_LOOKUP_MAX_TOKENS),
	};
}

/** Hard-removes every tool when the user explicitly wants a direct, provided answer. */
export function disablesTools(text: string): boolean {
	const candidate = text.toLocaleLowerCase().replace(/\s+/g, " ").trim();
	const explicitForbid = [
		"не используй инструмент",
		"не вызывай инструмент",
		"без инструментов",
		"do not use tools",
		"do not use any tools",
		"don't use tools",
		"without tools",
	].some((cue) => candidate.includes(cue));
	const answerIsProvided = [
		"уже дан в этом сообщении",
		"из текста этого сообщения",
		"already given in this message",
		"from this message",
	].some((cue) => candidate.includes(cue));
	const exactOutput = [
		"верни только",
		"ответь ровно",
		"return only",
		"reply exactly",
	].some((cue) => candidate.includes(cue));
	return explicitForbid || (answerIsProvided && exactOutput);
}

export function routeInput(
	text: string,
	streamingBehavior?: "steer" | "followUp",
): {
	text: string;
	capabilities: ToolCapability[];
	additive: boolean;
	transformed: boolean;
} {
	const transformed = text.startsWith(INTERNAL_RETRY_PREFIX);
	const routedText = transformed ? text.slice(INTERNAL_RETRY_PREFIX.length) : text;
	return {
		text: routedText,
		capabilities: detectCapabilities(routedText),
		// A retry is an idle RPC prompt only because Pi has already settled. It still
		// belongs to the previous task, so retain every optional tool that task enabled.
		additive: transformed || streamingBehavior !== undefined,
		transformed,
	};
}

export default function pideckToolRouter(pi: ExtensionAPI) {
	const profile = configuredProfile();
	const mode = configuredMode();
	let oneShotTool: string | undefined;
	let oneShotStopsOnError = false;
	let scopedReadTarget: string | undefined;
	let scopedRepairTargets: string[] = [];
	let scopedRepairAnchors = new Map<string, Map<string, string[]>>();
	let scopedRepairEditFailures = new Map<string, number>();
	let scopedRepairReadTargets = new Set<string>();
	let scopedRepairTestFailed = false;
	let prefetchPending = false;
	let taskTerminal = false;
	let directLookupTool: "web_research" | "weather" | undefined;
	let directLookupCalls = 0;
	const capabilities = optionalCapabilities(profile);
	const allowed = new Set([
		...CORE_TOOLS[profile],
		...capabilities.flatMap((capability) => OPTIONAL_TOOLS[profile][capability]),
		LOADER_TOOL,
	]);

	function activate(
		requested: readonly ToolCapability[],
		additive: boolean,
		text?: string,
	): string[] {
		if (mode === "chat") {
			pi.setActiveTools([]);
			return [];
		}
		const base = additive
			? pi.getActiveTools().filter((name) => allowed.has(name))
			: taskCoreTools(profile, text ?? "");
		const additions = requested.flatMap((capability) =>
			OPTIONAL_TOOLS[profile][capability] ?? []);
		const active = unique([...base, ...additions]).filter((name) => allowed.has(name));
		pi.setActiveTools(active);
		return active;
	}

	function rememberAuthoritativeRead(actualPath: string, textParts: readonly string[]): void {
		const byDigest = new Map<string, string[]>();
		for (const text of textParts) {
			for (const match of text.matchAll(/(?:^|\n)(\d{1,6}:([0-9a-f]{2}))\|/gu)) {
				const anchors = byDigest.get(match[2]) ?? [];
				anchors.push(match[1]);
				byDigest.set(match[2], anchors);
			}
		}
		if (byDigest.size > 0) scopedRepairAnchors.set(actualPath, byDigest);
		scopedRepairReadTargets.add(actualPath);
	}

	function markScopedRepairTerminal(): void {
		taskTerminal = true;
		prefetchPending = false;
		scopedRepairTargets = [];
		scopedRepairAnchors = new Map();
		scopedRepairEditFailures = new Map();
		scopedRepairReadTargets = new Set();
		scopedRepairTestFailed = false;
	}

	const capabilitySchema = Type.Union(
		capabilities.map((capability) => Type.Literal(capability)),
	);
	pi.registerTool({
		name: LOADER_TOOL,
		label: "load optional tools",
		description:
			"Enable one optional capability only when the active tools cannot finish the task.",
		parameters: Type.Object({
			capability: capabilitySchema,
		}),
		async execute(_toolCallId, params) {
			const capability = String(params.capability) as ToolCapability;
			if (!capabilities.includes(capability)) {
				throw new Error(`Capability is unavailable in ${profile}: ${capability}`);
			}
			const before = new Set(pi.getActiveTools());
			const active = activate([capability], true);
			const added = active.filter((name) => !before.has(name));
			return {
				content: [{
					type: "text" as const,
					text: added.length > 0
						? `Enabled: ${added.join(", ")}`
						: `Already enabled: ${capability}`,
				}],
				details: { capability, added },
			};
		},
	});

	pi.on("session_start", () => {
		oneShotTool = undefined;
		oneShotStopsOnError = false;
		scopedReadTarget = undefined;
		scopedRepairTargets = [];
		scopedRepairAnchors = new Map();
		scopedRepairEditFailures = new Map();
		scopedRepairReadTargets = new Set();
		scopedRepairTestFailed = false;
		prefetchPending = false;
		taskTerminal = false;
		directLookupTool = undefined;
		directLookupCalls = 0;
		activate([], false);
	});

	pi.on("input", (event) => {
		// A queued correction belongs to the active task. Add what it needs without removing a
		// tool that may be referenced by the in-flight conversation. A bridge retry crosses an
		// idle boundary deliberately and carries a stripped internal marker for the same reason.
		const routed = routeInput(event.text, event.streamingBehavior);
		if (disablesTools(routed.text)) {
			oneShotTool = undefined;
			oneShotStopsOnError = false;
			scopedReadTarget = undefined;
			scopedRepairTargets = [];
			scopedRepairAnchors = new Map();
			scopedRepairEditFailures = new Map();
			scopedRepairReadTargets = new Set();
			scopedRepairTestFailed = false;
			prefetchPending = false;
			taskTerminal = true;
			directLookupTool = undefined;
			directLookupCalls = 0;
			pi.setActiveTools([]);
		} else {
			// A normal input starts a new task. Remember an explicit one-tool contract so
			// the execution guard can make it terminal without rewriting the provider schema.
			// Additive steer/follow-up messages belong to the in-flight task and must not
			// silently reset an already consumed contract.
			if (!routed.additive) {
				scopedRepairAnchors = new Map();
				scopedRepairEditFailures = new Map();
				scopedRepairReadTargets = new Set();
				scopedRepairTestFailed = false;
				prefetchPending = false;
				taskTerminal = false;
				const explicitlyRequested = explicitlyRequestedSoleTool(routed.text);
				directLookupTool = directLiveLookupTool(routed.text);
				directLookupCalls = 0;
				oneShotTool = explicitlyRequested ?? directLookupTool;
				oneShotStopsOnError = explicitlyRequested !== undefined
					&& requiresExactlyOneToolCall(routed.text);
				scopedReadTarget = explicitReadTarget(routed.text);
				scopedRepairTargets = isScopedRepairRequest(routed.text)
					? explicitFileTargets(routed.text)
					: [];
				prefetchPending = scopedRepairTargets.length > 0;
			}
			activate(routed.capabilities, routed.additive, routed.text);
		}
		return routed.transformed
			? { action: "transform", text: routed.text, images: event.images }
			: { action: "continue" };
	});

	pi.on("before_provider_request", (event) =>
		capDirectLookupProviderRequest(event.payload, directLookupTool !== undefined));

	pi.on("before_agent_start", (_event, context) => {
		if (!prefetchPending || scopedRepairTargets.length === 0) return undefined;
		prefetchPending = false;
		const snapshots = boundedRepairPrefetch(context.cwd, scopedRepairTargets);
		if (snapshots.length === 0) return undefined;
		for (const snapshot of snapshots) {
			rememberAuthoritativeRead(snapshot.path, [snapshot.annotated]);
		}
		const content = [
			"PI//DECK BOUNDED PREFETCH: authoritative snapshots of small files explicitly named by the user.",
			"Do not call read for a file shown below. Use its line:hash anchors directly; skipped files remain readable with the read tool.",
			...snapshots.flatMap((snapshot) => [
				`--- FILE ${snapshot.displayPath} ---`,
				snapshot.annotated,
				`--- END FILE ${snapshot.displayPath} ---`,
			]),
		].join("\n");
		return {
			message: {
				customType: "pideck-bounded-prefetch",
				content,
				display: false,
				details: { paths: snapshots.map((snapshot) => snapshot.displayPath) },
			},
		};
	});

	pi.on("tool_result", (event) => {
		if (
			oneShotTool !== undefined
			&& event.toolName === oneShotTool
			&& (oneShotStopsOnError || !event.isError)
		) {
			// Keep the provider schema byte-stable for prompt-cache reuse. The tool_call
			// guard below makes completion structural even though the schema stays visible.
			oneShotTool = undefined;
			oneShotStopsOnError = false;
			taskTerminal = true;
			const authoritativeStatus = event.isError
				? "TOOL RESULT: вызов завершился ошибкой; сообщи её как факт."
				: event.toolName === "read" && scopedReadTarget !== undefined
					? `READ SUCCEEDED: точный файл ${scopedReadTarget} уже прочитан. `
						+ "Следующий text block — его авторитетное содержимое."
					: "TOOL SUCCEEDED: следующий text block — авторитетный результат вызова.";
			return {
				content: [
					{ type: "text" as const, text: authoritativeStatus },
					...event.content,
					{
						type: "text" as const,
						text:
							"Одноразовый вызов завершён. Сейчас ответь пользователю обычным текстом "
							+ "по результату выше; не создавай, не копируй и не повторяй tool call. "
							+ "При успехе не заявляй, что доступ, чтение или поиск невозможны.",
					},
				],
			};
		}
		if (scopedRepairTargets.length > 0 && event.toolName === "read") {
			const actualPath = String((event.input as { path?: unknown }).path ?? "");
			if (!event.isError) {
				rememberAuthoritativeRead(
					actualPath,
					event.content
						.filter((part) => part.type === "text")
						.map((part) => part.type === "text" ? part.text : ""),
				);
			}
			return {
				content: [
					{
						type: "text" as const,
						text: event.isError
							? `READ FAILED for ${actualPath}: the following error is authoritative.`
							: `READ SUCCEEDED for ${actualPath}: the following text is the authoritative file content.`,
					},
					...event.content,
					{
						type: "text" as const,
						text: event.isError
							? "Correct the named path once; do not rediscover the workspace."
							: "Use this content now. Do not reread this path or rediscover it with shell commands.",
					},
				],
			};
		}
		if (scopedRepairTargets.length > 0 && event.toolName === "pideck_replace_lines") {
			const actualPath = String((event.input as { path?: unknown }).path ?? "");
			if (event.isError) {
				const failures = (scopedRepairEditFailures.get(actualPath) ?? 0) + 1;
				scopedRepairEditFailures.set(actualPath, failures);
				if (failures >= 2) {
					markScopedRepairTerminal();
					return {
						content: [
							{
								type: "text" as const,
								text: `EDIT RETRY LIMIT REACHED for ${actualPath}.`,
							},
							...event.content,
							{
								type: "text" as const,
								text: "Stop now and report that the scoped edit could not be applied safely; do not emit another tool call.",
							},
						],
					};
				}
			} else {
				scopedRepairEditFailures.delete(actualPath);
				if (scopedRepairTestFailed) scopedRepairTestFailed = false;
			}
			return {
				content: [
					{
						type: "text" as const,
						text: event.isError
							? `EDIT FAILED for ${actualPath}.`
							: `EDIT SUCCEEDED for ${actualPath}.`,
					},
					...event.content,
					{
						type: "text" as const,
						text: event.isError
							? "One correction remains. Use exactly one full line:hash anchor from the authoritative read or the returned current anchors."
							: "Do not reread the changed file. Edit any other named file, or run the exact named test now.",
					},
				],
			};
		}
		if (scopedRepairTargets.length > 0 && event.toolName === "run_tests") {
			const status = (event.details as { status?: unknown } | undefined)?.status;
			if (status === 0) {
				markScopedRepairTerminal();
			} else {
				scopedRepairTestFailed = true;
			}
			return {
				content: [
					{
						type: "text" as const,
						text: status === 0
							? "TEST PASSED: this verdict is authoritative."
							: "TEST FAILED: use the following failure as authoritative evidence.",
					},
					...event.content,
					{
						type: "text" as const,
						text: status === 0
							? "The requested repair is verified. Finish with a concise factual answer and no more tools."
							: "The same test is now unavailable until a source edit succeeds. Fix only a named source file; then run_tests will be enabled again.",
					},
				],
			};
		}
		return undefined;
	});

	pi.on("tool_call", (event, context) => {
		if (!allowed.has(event.toolName) || mode === "chat") {
			return {
				block: true,
				reason: "Tool is outside the active PI//DECK access profile",
			};
		}
		if (directLookupTool !== undefined) {
			if (event.toolName !== directLookupTool) {
				return {
					block: true,
					reason: `Direct live lookup is restricted to ${directLookupTool}`,
				};
			}
			directLookupCalls += 1;
			if (directLookupCalls > 2) {
				taskTerminal = true;
				return {
					block: true,
					reason: "Direct live lookup retry limit reached; report the last result without another tool call",
				};
			}
		}
		if (taskTerminal) {
			return {
				block: true,
				reason: "The current PI//DECK task is complete or stopped; answer the user without another tool call",
			};
		}
		if (scopedRepairTestFailed && event.toolName === "run_tests") {
			return {
				block: true,
				reason: "The exact test already failed; edit a named source file before running it again",
			};
		}
		if (event.toolName === "read" && (scopedReadTarget !== undefined || scopedRepairTargets.length > 0)) {
			const input = event.input as { path?: unknown; offset?: unknown; limit?: unknown };
			const requested = String(input.path ?? "");
			const intended = scopedReadTarget
				?? selectScopedTarget(requested, scopedRepairTargets, false);
			let target = intended === undefined
				? undefined
				: safeReadTarget(context.cwd, intended);
			if (target === undefined) {
				return {
					block: true,
					reason: "The user-scoped read path must stay inside the current workspace",
				};
			}
			if (scopedRepairTargets.length > 0 && scopedRepairReadTargets.has(target)) {
				const unread = scopedRepairTargets
					.map((candidate) => safeReadTarget(context.cwd, candidate))
					.find((candidate): candidate is string =>
						candidate !== undefined && !scopedRepairReadTargets.has(candidate));
				if (unread === undefined) {
					return {
						block: true,
						reason: "Every user-scoped repair file is already available; use its authoritative anchors instead of reading again",
					};
				}
				target = unread;
			}
			const requestedName = requested.replace(/\\/g, "/").split("/").at(-1) ?? "";
			const targetName = target.replace(/\\/g, "/").split("/").at(-1) ?? "";
			if (scopedRepairTargets.length > 0 && requestedName !== targetName) {
				delete input.offset;
				delete input.limit;
			}
			input.path = target;
		}
		if (event.toolName === "pideck_replace_lines" && scopedRepairTargets.length > 0) {
			const input = event.input as { path?: unknown; edits?: unknown };
			const intended = selectScopedTarget(String(input.path ?? ""), scopedRepairTargets, false);
			const target = intended === undefined
				? undefined
				: safeReadTarget(context.cwd, intended);
			if (target === undefined) {
				return { block: true, reason: "Edit target must be one of the user-scoped files" };
			}
			input.path = target;
			const anchorIndex = scopedRepairAnchors.get(target);
			if (anchorIndex !== undefined && Array.isArray(input.edits)) {
				for (const edit of input.edits) {
					if (edit === null || typeof edit !== "object" || Array.isArray(edit)) continue;
					const fields = edit as Record<string, unknown>;
					for (const field of ["anchor", "throughAnchor"] as const) {
						const digest = String(fields[field] ?? "").trim();
						if (!/^[0-9a-f]{2}$/u.test(digest)) continue;
						const matches = anchorIndex.get(digest) ?? [];
						if (matches.length === 1) fields[field] = matches[0];
					}
				}
			}
		}
		if (event.toolName === "run_tests" && scopedRepairTargets.length > 0) {
			const input = event.input as { path?: unknown; expr?: unknown };
			const requestedPath = String(input.path ?? "");
			const requestedExpr = String(input.expr ?? "");
			const intended = selectScopedTarget(
				requestedPath || requestedExpr,
				scopedRepairTargets,
				true,
			);
			const target = intended === undefined
				? undefined
				: safeReadTarget(context.cwd, intended);
			if (target === undefined) {
				return { block: true, reason: "Test target must be one of the user-scoped files" };
			}
			input.path = target;
			if (requestedExpr) {
				const expression = normalizeScopedTestExpression(requestedExpr, target);
				if (expression === undefined) delete input.expr;
				else input.expr = expression;
			}
		}
		return undefined;
	});
}
