/**
 * One bounded, read-only navigation tool for the phone-sized agent.
 *
 * A small model should not have to choose between grep/find/ls and spend several turns
 * discovering that it chose the wrong primitive. This tool runs ripgrep without a shell,
 * returns both filename candidates and exact line matches, and never leaves the workspace.
 */

import { execFile } from "node:child_process";
import { realpathSync, statSync } from "node:fs";
import { promisify } from "node:util";
import { basename, dirname, resolve, relative, sep } from "node:path";

import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const execFileAsync = promisify(execFile);
const MAX_QUERY_CHARS = 240;
const MAX_PATH_CHARS = 1_024;
const MAX_OUTPUT_CHARS = 8_000;
const MAX_FILE_CANDIDATES = 12;
const MAX_TEXT_MATCHES = 24;
const MAX_COMMAND_BUFFER = 256 * 1024;
const GENERIC_QUERY_WORDS = new Set([
	"class",
	"definition",
	"file",
	"find",
	"function",
	"method",
	"the",
	"где",
	"класс",
	"метод",
	"найди",
	"найти",
	"определение",
	"файл",
	"функция",
	"функции",
]);

function cleanScope(value: string | undefined): string | undefined {
	let candidate = value?.trim();
	if (!candidate) return undefined;
	while (candidate.length > 1 && /[),;:!?]$/u.test(candidate)) {
		candidate = candidate.slice(0, -1);
	}
	return candidate || undefined;
}

/** Extracts a directory/file that the user explicitly scoped the navigation request to. */
export function explicitNavigationScope(text: string): string | undefined {
	const cue = text.match(
		/(?:в\s+(?:каталоге|папке|директории)|(?:in|under)\s+(?:the\s+)?(?:directory|folder))\s+[`"'«]?([^\s`"'»<>]+)[`"'»]?/iu,
	);
	if (cue?.[1]) return cleanScope(cue[1]);
	const absolute = text.match(/(?:^|\s)(\/[^\s`"'«»<>]+)/u);
	return cleanScope(absolute?.[1]);
}

/** Extracts the code-shaped symbol the user explicitly asked to locate. */
export function explicitNavigationQuery(text: string): string | undefined {
	const quoted = text.match(/[`"'«]([A-Za-z_][A-Za-z0-9_.$:-]*)[`"'»]/u);
	if (quoted?.[1]) return quoted[1];
	const typed = text.match(
		/(?:функци(?:ю|и)|класс(?:а)?|метод(?:а)?|function|class|method)\s+([A-Za-z_][A-Za-z0-9_.$:-]*)/iu,
	);
	if (typed?.[1]) return typed[1];
	const constant = text.match(/(?:^|[^A-Za-z0-9_])([A-Z_][A-Z0-9_]{2,})(?:$|[^A-Za-z0-9_])/u);
	return constant?.[1];
}

/** Keeps the user's explicit safe scope authoritative over any model-hallucinated path. */
export function effectiveNavigationPath(
	requestedPath: string,
	explicitScope: string | undefined,
): string {
	const requested = requestedPath.trim();
	if (!explicitScope) return requested || ".";
	// The user's scope is authoritative for this turn. This is both safer and more useful than
	// trusting a 2B model that may substitute AGENTS.md, `/`, or an invented sibling path. The
	// workspaceTarget check below still rejects a user-supplied scope outside the workspace.
	return explicitScope;
}

/** Searches both the natural-language phrase and its useful symbol-like terms in one call. */
export function navigationQueryTerms(query: string): string[] {
	const normalized = query.trim();
	const tokens = [...normalized.matchAll(/[\p{L}_][\p{L}\p{N}_.$:-]*/gu)]
		.map((match) => match[0])
		.filter((value) => value.length >= 2)
		.filter((value) => !GENERIC_QUERY_WORDS.has(value.toLocaleLowerCase()));
	return [...new Set([normalized, ...tokens])].filter(Boolean).slice(0, 4);
}

function workspaceTarget(cwd: string, requested: string): { root: string; target: string } {
	const lexicalRoot = resolve(cwd);
	const requestedTarget = resolve(lexicalRoot, requested || ".");
	if (requestedTarget !== lexicalRoot && !requestedTarget.startsWith(`${lexicalRoot}${sep}`)) {
		throw new Error("code_nav path must stay inside the current workspace");
	}
	let root: string;
	let target: string;
	try {
		root = realpathSync(lexicalRoot);
		target = realpathSync(requestedTarget);
	} catch {
		throw new Error("code_nav path does not exist");
	}
	if (target !== root && !target.startsWith(`${root}${sep}`)) {
		throw new Error("code_nav path must not follow a symlink outside the workspace");
	}
	return { root, target };
}

function displayPath(root: string, absolute: string): string {
	const value = relative(root, absolute);
	return value || ".";
}

async function ripgrep(
	args: string[],
	cwd: string,
	signal?: AbortSignal,
): Promise<string> {
	try {
		const result = await execFileAsync("rg", args, {
			cwd,
			encoding: "utf8",
			maxBuffer: MAX_COMMAND_BUFFER,
			timeout: 8_000,
			signal,
		});
		return result.stdout;
	} catch (error) {
		const value = error as { code?: number | string; killed?: boolean; message?: string };
		if (value.code === 1 || value.code === "1") return "";
		if (signal?.aborted) throw error;
		throw new Error(`code_nav search failed: ${value.message || "ripgrep error"}`);
	}
}

function bounded(lines: string[]): string {
	const output: string[] = [];
	let size = 0;
	for (const line of lines) {
		const next = line.length + 1;
		if (size + next > MAX_OUTPUT_CHARS) {
			output.push("…output truncated; narrow query or path");
			break;
		}
		output.push(line);
		size += next;
	}
	return output.join("\n");
}

export async function navigate(
	query: string,
	requestedPath: string,
	cwd: string,
	signal?: AbortSignal,
): Promise<{ text: string; fileCandidates: number; textMatches: number }> {
	const normalizedQuery = query.trim();
	if (!normalizedQuery || normalizedQuery.length > MAX_QUERY_CHARS) {
		throw new Error("code_nav query is empty or too long");
	}
	if (requestedPath.length > MAX_PATH_CHARS) {
		throw new Error("code_nav path is too long");
	}
	const { root, target } = workspaceTarget(cwd, requestedPath);
	const targetIsDirectory = statSync(target).isDirectory();
	const searchCwd = targetIsDirectory ? target : dirname(target);
	const targetArgument = targetIsDirectory ? "." : basename(target);
	const scopeLabel = displayPath(root, target);
	const searchTerms = navigationQueryTerms(normalizedQuery);
	const patternArguments = searchTerms.flatMap((term) => ["--regexp", term]);
	const common = ["--hidden", "--glob", "!.git/**", "--glob", "!build/**"];

	const [rawFiles, rawMatches] = await Promise.all([
		ripgrep(["--files", ...common, targetArgument], searchCwd, signal),
		ripgrep([
			"--line-number",
			"--column",
			"--smart-case",
			"--no-heading",
			"--color",
			"never",
			"--fixed-strings",
			...patternArguments,
			...common,
			"--",
			targetArgument,
		], searchCwd, signal),
	]);

	const foldedTerms = searchTerms.map((term) => term.toLocaleLowerCase());
	const files = rawFiles
		.split("\n")
		.map((value) => value.trim())
		.filter(Boolean)
		.map((value) => value.replace(/^\.\/+/, ""))
		.filter((value) => foldedTerms.some((term) =>
			value.toLocaleLowerCase().includes(term)))
		.slice(0, MAX_FILE_CANDIDATES);
	const matches = rawMatches
		.split("\n")
		.map((value) => value.trimEnd())
		.filter(Boolean)
		.map((value) => value.replace(/^\.\/+/, ""))
		.slice(0, MAX_TEXT_MATCHES);

	const lines = [
		`code_nav query: ${normalizedQuery}`,
		...(searchTerms.length > 1 ? [`search terms: ${searchTerms.join(" | ")}`] : []),
		`scope: ${scopeLabel}`,
	];
	if (files.length > 0) lines.push("", "File candidates:", ...files);
	if (matches.length > 0) lines.push("", "Text matches:", ...matches);
	if (files.length === 0 && matches.length === 0) {
		lines.push("", "No filename or text match. Stop or try one narrower synonym.");
	} else {
		lines.push(
			"",
			"Search complete. Answer from these matches; read the exact range only if file contents are needed before editing.",
		);
	}
	return {
		text: bounded(lines),
		fileCandidates: files.length,
		textMatches: matches.length,
	};
}

export default function pideckCodeNav(pi: ExtensionAPI) {
	let explicitScope: string | undefined;
	let explicitQuery: string | undefined;
	pi.on("session_start", () => {
		explicitScope = undefined;
		explicitQuery = undefined;
	});
	pi.on("input", (event) => {
		explicitScope = explicitNavigationScope(event.text);
		explicitQuery = explicitNavigationQuery(event.text);
	});

	pi.registerTool({
		name: "code_nav",
		label: "Code navigation",
		description:
			"Find relevant files, symbols, definitions, references, or exact text in one bounded read-only workspace search.",
		promptSnippet: "Find files and exact code locations in one bounded search",
		promptGuidelines: [
			"Use code_nav instead of chaining ls, find, and grep.",
			"Copy an explicit directory path from the user verbatim; never shorten it to / or only its final segment.",
			"After code_nav, read only the relevant file range before editing.",
			"If code_nav returns no match, stop or try one narrower synonym; do not scan the whole device.",
		],
		parameters: Type.Object({
			query: Type.String({
				description: "Filename, symbol, function, class, error text, or exact phrase to locate",
				minLength: 1,
				maxLength: MAX_QUERY_CHARS,
			}),
			path: Type.Optional(Type.String({
				description: "Optional scope: copy the user's exact directory/file path. Absolute paths are accepted only inside the workspace; defaults to its root",
				maxLength: MAX_PATH_CHARS,
			})),
		}),
		async execute(_toolCallId, params, signal, _onUpdate, context) {
			const requestedQuery = String(params.query ?? "").trim();
			const query = requestedQuery || explicitQuery || "";
			const requestedPath = String(params.path ?? ".").trim() || ".";
			const path = effectiveNavigationPath(requestedPath, explicitScope);
			const result = await navigate(query, path, context.cwd, signal);
			return {
				content: [{ type: "text" as const, text: result.text }],
				details: {
					query: query.trim(),
					requestedQuery,
					path,
					requestedPath,
					fileCandidates: result.fileCandidates,
					textMatches: result.textMatches,
				},
			};
		},
	});
}
