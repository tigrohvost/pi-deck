/**
 * One bounded, read-only navigation tool for the phone-sized agent.
 *
 * A small model should not have to choose between grep/find/ls and spend several turns
 * discovering that it chose the wrong primitive. This tool runs ripgrep without a shell,
 * returns both filename candidates and exact line matches, and never leaves the workspace.
 */

import { spawn } from "node:child_process";
import { realpathSync, statSync } from "node:fs";
import { StringDecoder } from "node:string_decoder";
import { basename, dirname, resolve, relative, sep } from "node:path";

import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const MAX_QUERY_CHARS = 240;
const MAX_PATH_CHARS = 1_024;
const MAX_OUTPUT_CHARS = 8_000;
const MAX_FILE_CANDIDATES = 12;
const MAX_TEXT_MATCHES = 24;
const MAX_SCAN_BYTES = 256 * 1024;
const MAX_RECORD_CHARS = 2_048;
const SEARCH_TIMEOUT_MS = 8_000;
type LimitReason = "file_limit" | "match_limit" | "byte_limit" | "line_limit" | "output_limit" | "timeout";
type SearchResult = { lines: string[]; limit?: LimitReason };
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
	// Dot-relative paths are unambiguous even without "directory": small models
	// often omit path after "In .pideck-bench/..." and otherwise search every fixture.
	// Do not treat ordinary prose such as "in English" or "in tokens/s" as a path.
	const relative = text.match(
		/(?:^|\s)(?:in|under|в)\s+[`"'«]?((?:\.{1,2}\/|\.[\p{L}\p{N}_-][\p{L}\p{N}._-]*\/)[^\s`"'»<>]*)[`"'»]?/iu,
	);
	if (relative?.[1]) return cleanScope(relative[1]);
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

/** Read and filter while the producer runs; never capture an unbounded result first. */
function ripgrep(
	args: string[],
	cwd: string,
	options: { delimiter: string; accepts(line: string): boolean; maxRecords: number;
		maxChars: number; countLimit: LimitReason; fileNames?: boolean; nulPath?: boolean },
	signal?: AbortSignal,
): Promise<SearchResult> {
	return new Promise((resolveResult, reject) => {
		if (signal?.aborted) { reject(signal.reason); return; }
		const child = spawn("rg", args, { cwd, detached: true, stdio: ["ignore", "pipe", "pipe"] });
		const decoder = new StringDecoder("utf8");
		const lines: string[] = [];
		let pending = "", stderr = "", bytes = 0, chars = 0;
		let limit: LimitReason | undefined;
		let failure: Error | undefined;
		let stopping = false, closed = false;
		let hardKill: ReturnType<typeof setTimeout> | undefined;
		// Android/Termux and the host runner use POSIX process groups. Killing only the
		// direct process leaves descendants holding pipes open after cancellation.
		const kill = (kind: NodeJS.Signals) => {
			if (child.pid) { try { process.kill(-child.pid, kind); } catch {} }
		};
		const stop = () => {
			if (stopping || closed) return;
			stopping = true;
			kill("SIGTERM");
			hardKill = setTimeout(() => kill("SIGKILL"), 250);
		};
		const limited = (reason: LimitReason) => { limit ??= reason; stop(); };
		const abort = () => stop();
		const timer = setTimeout(() => limited("timeout"), SEARCH_TIMEOUT_MS);
		signal?.addEventListener("abort", abort, { once: true });
		const record = (raw: string, clipped = false) => {
			let line = raw.replace(/^\.\/+/, "");
			if (options.nulPath) {
				const end = line.indexOf("\0");
				if (end < 0) return; // A clipped path cannot identify a matching source line.
				const path = line.slice(0, end);
				line = (/\p{Cc}/u.test(path) ? JSON.stringify(path) : path) + ":" + line.slice(end + 1);
			}
			if (!line || !options.accepts(line)) return;
			if (lines.length >= options.maxRecords) { limited(options.countLimit); return; }
			if (options.fileNames) {
				// A clipped filename cannot identify a real path. Quote embedded control
				// characters so one candidate cannot masquerade as several result rows.
				if (clipped) return;
				if (/[\x00-\x1f\x7f]/u.test(line)) line = JSON.stringify(line);
			} else line = line.trimEnd();
			const remaining = options.maxChars - chars - 1;
			if (line.length > remaining || clipped) {
				if (!options.fileNames && remaining > 80) {
					line = line.slice(0, Math.max(0, remaining - 20)) + " …[line clipped]";
					lines.push(line); chars += line.length + 1;
				}
				limited(clipped ? "line_limit" : "output_limit");
				return;
			}
			lines.push(line); chars += line.length + 1;
		};
		const consume = (text: string) => {
			pending += text;
			while (!stopping) {
				const pathEnd = options.nulPath ? pending.indexOf("\0") : -1;
				const end = options.nulPath && pathEnd < 0 ? -1
					: pending.indexOf(options.delimiter, pathEnd + 1);
				if (end < 0) break;
				const value = pending.slice(0, Math.min(end, MAX_RECORD_CHARS));
				pending = pending.slice(end + options.delimiter.length);
				record(value, end > MAX_RECORD_CHARS);
				if (end > MAX_RECORD_CHARS) limited("line_limit");
			}
			if (!stopping && pending.length > MAX_RECORD_CHARS) {
				record(pending.slice(0, MAX_RECORD_CHARS), true);
				limited("line_limit");
			}
			if (stopping) pending = "";
		};
		child.stdout.on("data", (chunk: Buffer) => {
			if (stopping) return;
			const accepted = chunk.subarray(0, Math.max(0, MAX_SCAN_BYTES - bytes));
			bytes += accepted.length;
			consume(decoder.write(accepted));
			if (!stopping && bytes >= MAX_SCAN_BYTES) limited("byte_limit");
		});
		child.stderr.on("data", (chunk: Buffer) => {
			const remaining = 4_096 - Buffer.byteLength(stderr);
			stderr += chunk.subarray(0, Math.max(0, remaining)).toString("utf8");
			if (chunk.length > remaining) {
				failure ??= new Error("ripgrep error output exceeded its limit"); stop();
			}
		});
		child.on("error", error => { failure ??= error; stop(); });
		child.on("exit", (code, exitSignal) => {
			if ((code !== null && code > 1) || (!stopping && code === null)) {
				failure ??= new Error(stderr.trim() || `ripgrep exited ${code ?? exitSignal}`); stop();
			}
		});
		child.on("close", (code, exitSignal) => {
			if (!stopping) {
				consume(decoder.end());
				if (pending && !stopping) record(pending);
			}
			closed = true;
			clearTimeout(timer); clearTimeout(hardKill);
			signal?.removeEventListener("abort", abort);
			if (stopping) kill("SIGKILL");
			if (signal?.aborted) reject(signal.reason);
			else if (failure || (limit === undefined && code !== 0 && code !== 1)) {
				reject(new Error(`code_nav search failed: ${failure?.message || stderr.trim() || exitSignal}`));
			} else resolveResult({ lines, limit });
		});
	});
}

export async function navigate(
	query: string,
	requestedPath: string,
	cwd: string,
	signal?: AbortSignal,
): Promise<{ text: string; schemaVersion: 1; status: "complete" | "partial";
	scope: string; truncated: boolean; fileCandidates: number; textMatches: number;
	limitReasons: LimitReason[] }> {
	signal?.throwIfAborted();
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
	const header = [
		`code_nav query: ${normalizedQuery}`,
		...(searchTerms.length > 1 ? [`search terms: ${searchTerms.join(" | ")}`] : []),
		`scope: ${scopeLabel}`,
	].join("\n");
	const textBudget = MAX_OUTPUT_CHARS - Math.min(header.length, 1_800) - 1_200 - 512;
	const patternArguments = searchTerms.flatMap((term) => ["--regexp", term]);
	const common = ["--hidden", "--glob", "!.git/**", "--glob", "!build/**"];

	const foldedTerms = searchTerms.map((term) => term.toLocaleLowerCase());
	const siblingAbort = new AbortController();
	const searchSignal = signal ? AbortSignal.any([signal, siblingAbort.signal]) : siblingAbort.signal;
	const pending = [
		ripgrep(["--files", "--null", ...common, "--", targetArgument], searchCwd, {
			delimiter: "\0", accepts: value => foldedTerms.some(term => value.toLocaleLowerCase().includes(term)),
			maxRecords: MAX_FILE_CANDIDATES, maxChars: 1_200, countLimit: "file_limit", fileNames: true,
		}, searchSignal),
		ripgrep([
			"--line-buffered",
			"--with-filename",
			"--null",
			// One extra matching line proves that the public result limit was exceeded.
			// Bound each file inside rg as well, before its worker can buffer the file.
			"--max-count", String(MAX_TEXT_MATCHES + 1),
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
		], searchCwd, { delimiter: "\n", accepts: () => true,
			maxRecords: MAX_TEXT_MATCHES, maxChars: textBudget, countLimit: "match_limit", nulPath: true }, searchSignal),
	];
	let results: SearchResult[];
	try { results = await Promise.all(pending); }
	catch (error) {
		siblingAbort.abort();
		await Promise.allSettled(pending);
		throw error;
	}
	const [files, matches] = results.map(result => result.lines);
	const limitReasons = [...new Set(results.flatMap(result => result.limit ? [result.limit] : []))];

	const lines = [header.slice(0, 1_800)];
	if (header.length > 1_800 && !limitReasons.includes("output_limit")) limitReasons.push("output_limit");
	if (files.length > 0) lines.push("", "File candidates:", ...files);
	if (matches.length > 0) lines.push("", "Text matches:", ...matches);
	if (limitReasons.length > 0) {
		lines.push("", `Partial search result (${limitReasons.join(", ")}); narrow query or path. `
			+ "Search is incomplete and does not establish absence of other matches.");
	} else if (files.length === 0 && matches.length === 0) {
		lines.push("", "No filename or text match. Stop or try one narrower synonym.");
	} else {
		lines.push(
			"",
			"Search complete. Answer from these matches; read the exact range only if file contents are needed before editing.",
		);
	}
	const text = lines.join("\n");
	if (text.length > MAX_OUTPUT_CHARS) throw new Error("code_nav output budget exceeded");
	const truncated = limitReasons.length > 0;
	return {
		text,
		schemaVersion: 1,
		status: truncated ? "partial" : "complete",
		scope: scopeLabel,
		truncated,
		fileCandidates: files.length,
		textMatches: matches.length,
		limitReasons,
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
					schemaVersion: result.schemaVersion,
					status: result.status,
					scope: result.scope,
					truncated: result.truncated,
					query: query.trim(),
					requestedQuery,
					path,
					requestedPath,
					fileCandidates: result.fileCandidates,
					textMatches: result.textMatches,
					limitReasons: result.limitReasons,
				},
			};
		},
	});
}
