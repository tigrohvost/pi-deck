/**
 * Starts each session without tool schemas and exposes only what the first task needs.
 * Later tasks may append tools; they never remove or reorder the session schema. A task
 * allowlist enforces the Android access profile. Completion sets provider tool_choice=none;
 * bounded retries and an abort guard stop a provider that ignores that instruction.
 */

import { createHash } from "node:crypto";
import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import {
	existsSync,
	lstatSync,
	readdirSync,
	readFileSync,
	realpathSync,
} from "node:fs";
import { basename, dirname, join, relative, resolve, sep } from "node:path";

import { explicitNavigationScope } from "./pideck-code-nav.ts";
import {
	annotateAuthoritativeRead,
	annotateReadText,
} from "./pideck-hashline-edit.ts";
import {
	classifyShellCommand,
	dedicatedToolForShell,
} from "./pideck-permission-gate.ts";

export type AccessProfile = "read_only" | "confirm_changes" | "autonomous";
export type AgentMode = "chat" | "agent";
export type ToolCapability = "files" | "web" | "weather" | "exact_edit";

const LOADER_TOOL = "pideck_load_tools";
export const INTERNAL_RETRY_PREFIX = "[[PI//DECK:ANSWER_RETRY]]\n";
export const DIRECT_LIVE_LOOKUP_MAX_TOKENS = 256;
const PREFETCH_MAX_FILES = 3;
const PREFETCH_MAX_FILE_BYTES = 4 * 1024;
const PREFETCH_MAX_TOTAL_BYTES = 6 * 1024;
const RULE_MAX_FILE_BYTES = 6 * 1024;
const RULE_MAX_TOTAL_BYTES = 12 * 1024;
const RULE_MAX_FILES = 8;
const CURSOR_RULE_MAX_FILES = 32;

type PrefetchedFile = {
	path: string;
	displayPath: string;
	annotated: string;
};

type RepoInstruction = {
	path: string;
	displayPath: string;
	content: string;
	fingerprint: string;
};

type RepoInstructionBundle = {
	content: string;
	paths: string[];
	fingerprints: string[];
};

const CORE_TOOLS: Record<AccessProfile, readonly string[]> = {
	read_only: ["read", "code_nav"],
	confirm_changes: [
		"read",
		"code_nav",
		"pideck_bash",
		"pideck_write",
		"pideck_edit_text",
	],
	autonomous: ["read", "code_nav", "bash", "write", "pideck_edit_text", "run_tests"],
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
		exact_edit: ["pideck_replace_lines", "pideck_edit"],
	},
	autonomous: {
		files: [],
		web: ["web_research"],
		weather: ["weather"],
		exact_edit: ["pideck_replace_lines", "edit"],
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
		/(?:прочитай|прочти)(?:\s+файл)?\s+[`"'«]?([^\s`"'»<>]+)[`"'»]?|\bread(?:\s+(?:the\s+)?file)?\s+[`"']?([^\s`"'<>]+)[`"']?/iu,
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
			const preview = annotateReadText(text);
			const annotatedBytes = Buffer.byteLength(preview, "utf8");
			if (used + annotatedBytes > PREFETCH_MAX_TOTAL_BYTES) continue;
			const annotated = annotateAuthoritativeRead(actual, text, 1, {
				explicitRange: true,
				source: raw,
			});
			if (!/(?:^|\n)\d{1,6}:[0-9a-f]{8}\|/u.test(annotated)) continue;
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

function safeWorkspaceTarget(
	cwd: string,
	target: string,
): { root: string; lexical: string; relativePath: string } | undefined {
	try {
		const root = realpathSync(cwd);
		const lexical = resolve(root, target);
		if (lexical !== root && !lexical.startsWith(`${root}${sep}`)) return undefined;
		let existing = existsSync(lexical) ? lexical : dirname(lexical);
		while (!existsSync(existing) && existing !== root) existing = dirname(existing);
		const state = lstatSync(existing);
		if (state.isSymbolicLink()) return undefined;
		const actual = realpathSync(existing);
		if (actual !== root && !actual.startsWith(`${root}${sep}`)) return undefined;
		return {
			root,
			lexical,
			relativePath: relative(root, lexical).replace(/\\/g, "/"),
		};
	} catch {
		return undefined;
	}
}

function readInstructionFile(
	root: string,
	candidate: string,
	stripFrontmatter = false,
): RepoInstruction | undefined {
	try {
		const state = lstatSync(candidate);
		if (!state.isFile() || state.isSymbolicLink() || state.size > RULE_MAX_FILE_BYTES) {
			return undefined;
		}
		const actual = realpathSync(candidate);
		if (actual !== root && !actual.startsWith(`${root}${sep}`)) return undefined;
		const raw = readFileSync(actual);
		if (raw.includes(0) || raw.length > RULE_MAX_FILE_BYTES) return undefined;
		let content = raw.toString("utf8");
		if (!Buffer.from(content, "utf8").equals(raw)) return undefined;
		if (stripFrontmatter) {
			content = content.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/u, "");
		}
		content = content.trim();
		if (!content) return undefined;
		const displayPath = relative(root, actual).replace(/\\/g, "/") || basename(actual);
		return {
			path: actual,
			displayPath,
			content,
			fingerprint: createHash("sha256")
				.update(actual)
				.update("\0")
				.update(raw)
				.digest("hex"),
		};
	} catch {
		return undefined;
	}
}

function globPatternRegExp(pattern: string): RegExp | undefined {
	let value = pattern.trim().replace(/^['"]|['"]$/g, "").replace(/^\.\//, "");
	if (!value || value.includes("\0") || value.length > 512) return undefined;
	if (value.endsWith("/")) value += "**";
	let rendered = "^";
	for (let index = 0; index < value.length; index++) {
		const character = value[index];
		if (character === "*") {
			if (value[index + 1] === "*") {
				index += 1;
				if (value[index + 1] === "/") {
					index += 1;
					rendered += "(?:.*/)?";
				} else {
					rendered += ".*";
				}
			} else {
				rendered += "[^/]*";
			}
			continue;
		}
		if (character === "?") {
			rendered += "[^/]";
			continue;
		}
		if (character === "{") {
			const end = value.indexOf("}", index + 1);
			if (end > index + 1) {
				const alternatives = value.slice(index + 1, end).split(",");
				if (alternatives.every((item) => /^[A-Za-z0-9_.+-]+$/u.test(item))) {
					rendered += `(?:${alternatives
						.map((item) => item.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
						.join("|")})`;
					index = end;
					continue;
				}
			}
		}
		rendered += /[.*+^${}()|[\]\\]/u.test(character) ? `\\${character}` : character;
	}
	try {
		return new RegExp(`${rendered}$`, "u");
	} catch {
		return undefined;
	}
}

export function repoGlobMatches(pattern: string, relativePath: string): boolean {
	const matcher = globPatternRegExp(pattern);
	if (matcher === undefined) return false;
	const normalized = relativePath.replace(/\\/g, "/").replace(/^\.\//, "");
	return matcher.test(normalized)
		|| (!pattern.includes("/") && matcher.test(basename(normalized)));
}

function cursorRuleApplies(content: string, relativePath: string): boolean {
	const header = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/u.exec(content)?.[1];
	if (header === undefined) return false;
	if (/^\s*alwaysApply\s*:\s*true\s*$/imu.test(header)) return true;
	const patterns: string[] = [];
	const lines = header.split(/\r?\n/u);
	let readingGlobs = false;
	for (const line of lines) {
		const start = /^\s*globs\s*:\s*(.*)$/u.exec(line);
		if (start !== null) {
			readingGlobs = true;
			const rest = start[1].trim().replace(/^\[|\]$/g, "");
			if (rest) {
				const quoted = [...rest.matchAll(/(['"])(.*?)\1/gu)].map((match) => match[2]);
				if (quoted.length > 0) {
					patterns.push(...quoted.filter(Boolean));
				} else {
					let depth = 0;
					let token = "";
					for (const character of `${rest},`) {
						if (character === "{") depth += 1;
						if (character === "}") depth = Math.max(0, depth - 1);
						if (character === "," && depth === 0) {
							const value = token.trim();
							if (value) patterns.push(value);
							token = "";
						} else {
							token += character;
						}
					}
				}
			}
			continue;
		}
		if (!readingGlobs) continue;
		const item = /^\s*-\s*(.+?)\s*$/u.exec(line)?.[1];
		if (item !== undefined) {
			const value = item.replace(/^['"]|['"]$/g, "");
			if (value) patterns.push(value);
			continue;
		}
		if (/^\S/u.test(line)) readingGlobs = false;
	}
	return patterns.some((pattern) => repoGlobMatches(pattern, relativePath));
}

/** Deterministically resolves only instructions applicable to one workspace path. */
export function repoInstructionsForTarget(cwd: string, target: string): RepoInstruction[] {
	const safe = safeWorkspaceTarget(cwd, target);
	if (safe === undefined) return [];
	const collected: RepoInstruction[] = [];
	const seen = new Set<string>();
	const add = (instruction: RepoInstruction | undefined) => {
		if (instruction === undefined || seen.has(instruction.path)) return;
		seen.add(instruction.path);
		collected.push(instruction);
	};

	add(readInstructionFile(
		safe.root,
		join(safe.root, ".github", "copilot-instructions.md"),
	));
	const targetDirectory = safe.lexical === safe.root ? safe.root : dirname(safe.lexical);
	const directoryPath = relative(safe.root, targetDirectory);
	let current = safe.root;
	for (const part of directoryPath.split(sep).filter(Boolean)) {
		current = join(current, part);
		add(readInstructionFile(safe.root, join(current, "AGENTS.md")));
	}

	const cursorDirectory = join(safe.root, ".cursor", "rules");
	try {
		const state = lstatSync(cursorDirectory);
		if (state.isDirectory() && !state.isSymbolicLink()) {
			const entries = readdirSync(cursorDirectory, { withFileTypes: true })
				.filter((entry) => entry.isFile() && /\.(?:md|mdc)$/iu.test(entry.name))
				.sort((left, right) => left.name.localeCompare(right.name))
				.slice(0, CURSOR_RULE_MAX_FILES);
			for (const entry of entries) {
				const candidate = join(cursorDirectory, entry.name);
				const raw = readInstructionFile(safe.root, candidate, false);
				if (raw === undefined || !cursorRuleApplies(raw.content, safe.relativePath)) continue;
				add(readInstructionFile(safe.root, candidate, true));
			}
		}
	} catch {
		// An absent, unreadable, or symlinked optional rule directory contributes no rules.
	}
	return collected;
}

function repoInstructionBundle(
	cwd: string,
	targets: readonly string[],
	delivered: Set<string>,
): RepoInstructionBundle | undefined {
	const unique = new Map<string, RepoInstruction>();
	for (const target of targets) {
		for (const instruction of repoInstructionsForTarget(cwd, target)) {
			if (!delivered.has(instruction.fingerprint)) unique.set(instruction.path, instruction);
		}
	}
	let used = 0;
	const accepted: RepoInstruction[] = [];
	for (const instruction of unique.values()) {
		const bytes = Buffer.byteLength(instruction.content, "utf8");
		if (accepted.length >= RULE_MAX_FILES || used + bytes > RULE_MAX_TOTAL_BYTES) break;
		accepted.push(instruction);
		used += bytes;
	}
	if (accepted.length === 0) return undefined;
	return {
		content: [
			"PI//DECK PATH-SCOPED REPOSITORY INSTRUCTIONS. System and direct user instructions win; "
				+ "later, deeper files refine earlier repository rules for the touched path.",
			...accepted.flatMap((instruction) => [
				`--- RULE ${instruction.displayPath} ---`,
				instruction.content,
				`--- END RULE ${instruction.displayPath} ---`,
			]),
		].join("\n"),
		paths: accepted.map((instruction) => instruction.displayPath),
		fingerprints: accepted.map((instruction) => instruction.fingerprint),
	};
}

/** Extracts exact file paths named by the user, excluding the directory scope itself. */
export function explicitFilePaths(text: string): string[] {
	const matches = [...text.matchAll(
		/(?:^|[\s`"'«(])((?:\.{0,2}\/|\/)?(?:[\p{L}\p{N}_@.+-]+\/)*[\p{L}\p{N}_@+-][\p{L}\p{N}_@.+-]*\.[A-Za-z][A-Za-z0-9]{0,7})(?=$|[\s`"'»).,;:!?])/gu,
	)];
	return [...new Set(matches.map((match) => match[1])
		.filter((path) => Boolean(path) && !isMemberReference(path)))];
}

function isMemberReference(value: string): boolean {
	if (value.includes("/") || /\.(?:py|pyi|js|jsx|ts|tsx|java|kt|kts|c|h|cc|cpp|hpp|cs|go|rs|rb|php|swift|json|yaml|yml|toml|xml|md|txt|ini|cfg|conf|sh|sql)$/iu.test(value)) return false;
	return /^[A-Z][A-Za-z0-9_]*\.[a-z_][A-Za-z0-9_]*$/u.test(value);
}

export function explicitFileTargets(text: string): string[] {
	const scope = explicitNavigationScope(text);
	return explicitFilePaths(text).map((path) => {
		if (path.startsWith("/")) return path;
		return scope ? join(scope, path) : path;
	});
}

/** Resolve one local definition explicitly named as Class.member through a named Python test. */
export function relatedRepairTargets(cwd: string, text: string, targets: readonly string[]): string[] {
	const symbols = new Set([...text.matchAll(/\b([A-Z][A-Za-z0-9_]*)\.[a-z_][A-Za-z0-9_]*\b/gu)]
		.filter((match) => isMemberReference(match[0])).map((match) => match[1]));
	if (symbols.size === 0) return [...targets];
	const scoped = safeWorkspaceTarget(cwd, explicitNavigationScope(text) ?? ".");
	if (scoped === undefined) return [...targets];
	let project: string;
	try { project = realpathSync(scoped.lexical); } catch { return [...targets]; }
	const readLocal = (path: string): string | undefined => {
		const safe = safeWorkspaceTarget(cwd, path);
		if (safe === undefined) return undefined;
		try {
			const actual = realpathSync(safe.lexical);
			if (!actual.startsWith(`${project}${sep}`)) return undefined;
			const stat = lstatSync(safe.lexical);
			if (!stat.isFile() || stat.isSymbolicLink() || stat.size > PREFETCH_MAX_FILE_BYTES) return undefined;
			const raw = readFileSync(actual);
			if (raw.includes(0) || raw.length > PREFETCH_MAX_FILE_BYTES) return undefined;
			const content = raw.toString("utf8");
			return Buffer.from(content, "utf8").equals(raw) ? content : undefined;
		} catch { return undefined; }
	};
	const result = [...targets];
	for (const target of targets.slice(0, PREFETCH_MAX_FILES)) {
		if (!isTestTarget(target) || !target.endsWith(".py")) continue;
		const content = readLocal(target);
		if (content === undefined) continue;
		for (const match of content.matchAll(/^from ([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*) import ([A-Za-z_]\w*)\b/gmu)) {
			if (!symbols.has(match[2]) || result.length >= PREFETCH_MAX_FILES) continue;
			const source = join(project, ...match[1].split(".")) + ".py";
			const definition = readLocal(source);
			if (definition === undefined || !new RegExp(`^class ${match[2]}\\b`, "mu").test(definition)) continue;
			if (!result.includes(source)) result.push(source);
		}
	}
	return result;
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
		return ["read", "pideck_edit_text", "run_tests"];
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
	let scopedRepairTestFailed = false;
	let prefetchPending = false;
	let taskTerminal = false;
	let providerRounds = 0;
	let toolAttempts = 0;
	let failedResults = 0;
	let repeatedCalls = new Map<string, number>();
	let directLookupTool: "web_research" | "weather" | undefined;
	let directLookupCalls = 0;
	/** Provider-visible schema: the profile core from session_start, then append-only. */
	let sessionTools: string[] = [];
	/** Task allowlist enforced in depth; undefined means the whole session schema is usable. */
	let taskAllowedTools: Set<string> | undefined;
	const deliveredRepoRules = new Set<string>();
	const capabilities = optionalCapabilities(profile);
	const allowed = new Set([
		...CORE_TOOLS[profile],
		...capabilities.flatMap((capability) => OPTIONAL_TOOLS[profile][capability]),
		LOADER_TOOL,
	]);

	function consumeRepoInstructions(
		cwd: string,
		targets: readonly string[],
	): RepoInstructionBundle | undefined {
		const bundle = repoInstructionBundle(cwd, targets, deliveredRepoRules);
		if (bundle === undefined) return undefined;
		for (const fingerprint of bundle.fingerprints) deliveredRepoRules.add(fingerprint);
		return bundle;
	}

	function extendSessionTools(names: readonly string[]): void {
		const additions = unique(names).filter((name) =>
			allowed.has(name) && !sessionTools.includes(name));
		if (additions.length === 0) return;
		sessionTools = [...sessionTools, ...additions];
		pi.setActiveTools(sessionTools);
	}

	/**
	 * Grows the session schema when a request needs a group that is not active yet and records
	 * the task allowlist. An additive steer/follow-up or bridge retry belongs to the in-flight
	 * task and may only widen what that task is allowed to call.
	 */
	function activate(
		requested: readonly ToolCapability[],
		additive: boolean,
		text?: string,
	): string[] {
		if (mode === "chat") {
			sessionTools = [];
			taskAllowedTools = undefined;
			pi.setActiveTools([]);
			return [];
		}
		const additions = requested
			.flatMap((capability) => OPTIONAL_TOOLS[profile][capability] ?? [])
			.filter((name) => allowed.has(name));
		if (additive) {
			if (taskAllowedTools !== undefined) {
				for (const name of additions) taskAllowedTools.add(name);
			}
			extendSessionTools(additions);
			return [...sessionTools];
		}
		const core = coreTools(profile);
		const task = taskCoreTools(profile, text ?? "");
		const restricted = task.length !== core.length
			|| task.some((name) => !core.includes(name));
		taskAllowedTools = restricted
			? new Set([...task, ...additions].filter((name) => allowed.has(name)))
			: undefined;
		// The profile core leads every session schema even when the first task is narrow: a
		// later ordinary task then appends nothing, and every session shares one system+tools
		// prefix that llama.cpp can reuse. Narrow tasks are enforced by the tool_call guard.
		extendSessionTools([...core, ...task, ...additions]);
		return [...sessionTools];
	}

	/** Tells the model, at the end of the context, which tools this request may use. */
	function taskToolsNote(): string | undefined {
		if (taskAllowedTools === undefined) return undefined;
		if (taskAllowedTools.size === 0) {
			return "PI//DECK TASK TOOLS: none. Answer this request directly from the message; "
				+ "every tool call will be refused.";
		}
		return `PI//DECK TASK TOOLS: this request permits only ${[...taskAllowedTools].join(", ")}. `
			+ "Other tools are refused; if none of these fits, answer directly.";
	}

	function rememberAuthoritativeRead(actualPath: string, textParts: readonly string[]): boolean {
		const byDigest = new Map<string, string[]>();
		for (const text of textParts) {
			for (const match of text.matchAll(/(?:^|\n)(\d{1,6}:([0-9a-f]{8}))\|/gu)) {
				const anchors = byDigest.get(match[2]) ?? [];
				anchors.push(match[1]);
				byDigest.set(match[2], anchors);
			}
		}
		if (byDigest.size === 0) return false;
		scopedRepairAnchors.set(actualPath, byDigest);
		return true;
	}

	function markScopedRepairTerminal(): void {
		taskTerminal = true;
		prefetchPending = false;
		scopedRepairTargets = [];
		scopedRepairAnchors = new Map();
		scopedRepairEditFailures = new Map();
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
		scopedRepairTestFailed = false;
		prefetchPending = false;
		taskTerminal = false;
		directLookupTool = undefined;
		directLookupCalls = 0;
		taskAllowedTools = undefined;
		deliveredRepoRules.clear();
		// Fixed per profile from the first request, so a tool-free or narrow first task cannot
		// make the next task rewrite the schema (and with it the whole cached prefix).
		sessionTools = mode === "chat" ? [] : coreTools(profile).filter((name) => allowed.has(name));
		pi.setActiveTools(sessionTools);
	});

	pi.on("input", (event) => {
		// A queued correction belongs to the active task. Add what it needs without removing a
		// tool that may be referenced by the in-flight conversation. A bridge retry crosses an
		// idle boundary deliberately and carries a stripped internal marker for the same reason.
		const routed = routeInput(event.text, event.streamingBehavior);
		if (!routed.additive) {
			providerRounds = 0;
			toolAttempts = 0;
			failedResults = 0;
			repeatedCalls = new Map();
		}
		if (disablesTools(routed.text)) {
			oneShotTool = undefined;
			oneShotStopsOnError = false;
			scopedReadTarget = undefined;
			scopedRepairTargets = [];
			scopedRepairAnchors = new Map();
			scopedRepairEditFailures = new Map();
			scopedRepairTestFailed = false;
			prefetchPending = false;
			taskTerminal = true;
			directLookupTool = undefined;
			directLookupCalls = 0;
			// The schema stays byte-stable; the guard refuses every call and the task note
			// tells the model to answer from the message.
			taskAllowedTools = new Set();
		} else {
			// A normal input starts a new task. Remember an explicit one-tool contract so
			// the execution guard can make it terminal without rewriting the provider schema.
			// Additive steer/follow-up messages belong to the in-flight task and must not
			// silently reset an already consumed contract.
			if (!routed.additive) {
				scopedRepairAnchors = new Map();
				scopedRepairEditFailures = new Map();
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

	pi.on("before_provider_request", (event, context) => {
		providerRounds += 1;
		const roundLimit = scopedRepairTargets.length > 0 ? 10 : 26;
		if (providerRounds > roundLimit) {
			context?.abort?.();
			throw new Error("PI//DECK: достигнут предел шагов задачи; выполненные изменения сохранены.");
		}
		const payload = capDirectLookupProviderRequest(event.payload, directLookupTool !== undefined)
			?? event.payload;
		if (typeof payload !== "object" || payload === null || Array.isArray(payload)) return undefined;
		// The provider enforces completion. A verbal refusal alone leaves a small model looping.
		return taskTerminal || taskAllowedTools?.size === 0
			? { ...payload, tool_choice: "none", parallel_tool_calls: false }
			: { ...payload, parallel_tool_calls: false };
	});

	pi.on("before_agent_start", (event, context) => {
		const contentParts: string[] = [];
		const detailPaths: string[] = [];
		if (prefetchPending && scopedRepairTargets.length > 0) {
			scopedRepairTargets = relatedRepairTargets(context.cwd, event.prompt, scopedRepairTargets);
		}
		const note = taskToolsNote();
		if (note !== undefined) contentParts.push(note);
		const directReadTarget = explicitReadTarget(event.prompt);
		const explicitTargets = unique([
			".",
			...explicitFileTargets(event.prompt),
			...(directReadTarget ? [directReadTarget] : []),
			...scopedRepairTargets,
		]);
		const instructions = consumeRepoInstructions(context.cwd, explicitTargets);
		if (instructions !== undefined) {
			contentParts.push(instructions.content);
			detailPaths.push(...instructions.paths);
		}

		if (prefetchPending && scopedRepairTargets.length > 0) {
			prefetchPending = false;
			const snapshots = boundedRepairPrefetch(context.cwd, scopedRepairTargets);
			for (const snapshot of snapshots) {
				rememberAuthoritativeRead(snapshot.path, [snapshot.annotated]);
			}
			if (snapshots.length > 0) {
				contentParts.push([
					"PI//DECK BOUNDED PREFETCH: small task files and directly imported definitions named by the user.",
					"These are current file contents. Edit unique literal oldText with pideck_edit_text; read remains available for an explicit check or a skipped file.",
					...snapshots.flatMap((snapshot) => [
						`--- FILE ${snapshot.displayPath} ---`,
						`Exact tool path: ${snapshot.path}`,
						snapshot.annotated,
						`--- END FILE ${snapshot.displayPath} ---`,
					]),
				].join("\n"));
				detailPaths.push(...snapshots.map((snapshot) => snapshot.displayPath));
			}
		}
		if (contentParts.length === 0) return undefined;
		return {
			message: {
				customType: "pideck-managed-context",
				content: contentParts.join("\n\n"),
				display: false,
				details: { paths: unique(detailPaths) },
			},
		};
	});

	pi.on("tool_result", (event, context) => {
		if (!event.isError && ["pideck_edit_text", "pideck_replace_lines", "edit", "write", "pideck_edit", "pideck_write"].includes(event.toolName)) repeatedCalls.clear();
		failedResults = event.isError ? failedResults + 1 : 0;
		if (failedResults >= 3) taskTerminal = true;
		let routedContent = event.content;
		if (failedResults >= 3) routedContent = [...routedContent, {
			type: "text" as const,
			text: "Three consecutive calls failed. Stop and report the actual results and remaining work.",
		}];
		// The user's exact file has been delivered once; later reads in this task keep the
		// model's own path so a request naming several files cannot loop on the first one.
		// An error keeps the redirect for the retry.
		const scopedReadPath = scopedReadTarget;
		if (event.toolName === "read" && !event.isError && scopedReadTarget !== undefined) {
			scopedReadTarget = undefined;
		}
		if (event.toolName === "read" && !event.isError) {
			const actualPath = String((event.input as { path?: unknown }).path ?? "");
			const instructions = consumeRepoInstructions(context?.cwd ?? process.cwd(), [actualPath]);
			if (instructions !== undefined) {
				routedContent = [
					{ type: "text" as const, text: instructions.content },
					...routedContent,
				];
			}
		}
		const structuralRead = event.toolName === "read"
			&& !event.isError
			&& event.content.some((part) =>
				part.type === "text" && part.text.includes("[PI//DECK STRUCTURAL READ:"));
		if (
			oneShotTool === "read"
			&& structuralRead
			&& !oneShotStopsOnError
		) {
			return {
				content: [
					...routedContent,
					{
						type: "text" as const,
						text: "This was only a structural outline. Make one exact read with offset/limit "
							+ "for the relevant range before answering; do not rediscover the path.",
					},
				],
			};
		}
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
				: event.toolName === "read" && scopedReadPath !== undefined
					? `READ SUCCEEDED: точный файл ${scopedReadPath} уже прочитан. `
						+ "Следующий text block — его авторитетное содержимое."
					: "TOOL SUCCEEDED: следующий text block — авторитетный результат вызова.";
			return {
				content: [
					{ type: "text" as const, text: authoritativeStatus },
					...routedContent,
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
			let editableRange = false;
			if (!event.isError) {
				editableRange = rememberAuthoritativeRead(
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
					...routedContent,
					{
						type: "text" as const,
						text: event.isError
							? "Correct the named path once; do not rediscover the workspace."
							: editableRange
								? "Use pideck_edit_text with unique oldText copied from this content (without line prefixes), or use complete anchors with pideck_replace_lines."
								: structuralRead
									? "This outline grants no edit anchors. Read one exact offset/limit for the needed range now."
									: "This read supplied no safe edit anchors; stop or request one exact text range.",
					},
				],
			};
		}
		if (scopedRepairTargets.length > 0 && ["pideck_replace_lines", "pideck_edit_text"].includes(event.toolName)) {
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
							...routedContent,
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
				// The committed edit returns the current anchors of the changed range; index
				// them so a bare digest in the next edit still resolves to a full anchor.
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
							? `EDIT FAILED for ${actualPath}.`
							: `EDIT SUCCEEDED for ${actualPath}.`,
					},
					...routedContent,
					{
						type: "text" as const,
						text: event.isError
							? "One correction remains. Follow the error above; copy exact oldText from read, or a full line:hash anchor for pideck_replace_lines."
							: "The changed range is current. Edit another named file if needed, then run the exact named test.",
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
					...routedContent,
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
		if (taskTerminal) {
			// Defend against a provider that ignores tool_choice=none.
			context?.abort?.();
			return { block: true, reason: "Task stopped; answer without tools" };
		}
		toolAttempts += 1;
		const signature = `${event.toolName}\0${JSON.stringify(event.input)}`;
		const repeats = (repeatedCalls.get(signature) ?? 0) + 1;
		repeatedCalls.set(signature, repeats);
		if (toolAttempts > (scopedRepairTargets.length > 0 ? 8 : 24) || repeats > 2) {
			taskTerminal = true;
			return { block: true, reason: "Tool retry limit reached. Report the completed work and the last result." };
		}
		if (!allowed.has(event.toolName) || mode === "chat") {
			return {
				block: true,
				reason: "Tool is outside the active PI//DECK access profile",
				};
		}
		if (event.toolName === "bash" || event.toolName === "pideck_bash") {
			const command = String((event.input as { command?: unknown }).command ?? "");
			const dedicated = dedicatedToolForShell(command, pi.getActiveTools());
			if (dedicated !== undefined) return { block: true, reason: dedicated };
			const risk = classifyShellCommand(command);
			if (event.toolName === "bash" && risk.level === "critical") {
				return {
					block: true,
					reason: `Critical shell command refused in AUTONOMOUS: ${risk.reason}. `
						+ "Switch to CONFIRM_CHANGES for an explicit one-time Android approval.",
				};
			}
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
		if (taskAllowedTools !== undefined && !taskAllowedTools.has(event.toolName)) {
			return {
				block: true,
				reason: taskAllowedTools.size === 0
					? "This request must be answered without tools; reply directly from the message"
					: `This request permits only ${[...taskAllowedTools].join(", ")}; `
						+ "call one of those or answer the user directly",
			};
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
			const requestedName = requested.replace(/\\/g, "/").split("/").at(-1) ?? "";
			const targetName = target.replace(/\\/g, "/").split("/").at(-1) ?? "";
			if (scopedRepairTargets.length > 0 && requestedName !== targetName) {
				delete input.offset;
				delete input.limit;
			}
			input.path = target;
		}
		if (["pideck_replace_lines", "pideck_edit_text"].includes(event.toolName) && scopedRepairTargets.length > 0) {
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
						if (!/^[0-9a-f]{8}$/u.test(digest)) continue;
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
		if (["edit", "write", "pideck_edit", "pideck_write", "pideck_replace_lines", "pideck_edit_text"]
			.includes(event.toolName)) {
			const target = String((event.input as { path?: unknown }).path ?? "");
			const instructions = consumeRepoInstructions(context.cwd, [target]);
			if (instructions !== undefined) {
				return {
					block: true,
					reason: `${instructions.content}\n\nRepository rules were delivered before mutation. `
						+ "Re-evaluate the edit against them, then call the tool again only if compliant.",
				};
			}
		}
		return undefined;
	});
}
