/**
 * Reports a broken file inside the same tool result that saved it.
 *
 * At on-device speeds a separate discovery turn — run something, read the traceback,
 * realize the previous edit does not parse — costs tens of seconds of prefill and decode.
 * Validating the file immediately after a mutation removes that turn: the model sees the
 * error while the edit is still in context. The check is an accelerator, not a gate — the
 * approved mutation is already applied, a clean file adds zero tokens, and any failure of
 * the checker itself stays silent.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { extname } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const MUTATING_TOOLS = new Set([
	"write",
	"edit",
	"pideck_write",
	"pideck_edit",
	"pideck_replace_lines",
]);
const CHECK_TIMEOUT_MS = 5000;
const MAX_ERROR_LINES = 8;
const MAX_ERROR_BYTES = 1024;

// ast.parse instead of py_compile so no __pycache__ appears in the workspace; the suite
// scores a run by diffing the workspace. An unreadable file is the checker's problem,
// not a syntax error, so it exits 0.
const PYTHON_CHECK = [
	"import ast, sys",
	"try:",
	"    source = open(sys.argv[1], 'rb').read()",
	"except OSError:",
	"    sys.exit(0)",
	"try:",
	"    ast.parse(source, sys.argv[1])",
	"except SyntaxError as error:",
	"    sys.stderr.write('%s (line %s)\\n' % (error.msg, error.lineno))",
	"    sys.exit(1)",
	"except ValueError as error:",
	"    sys.stderr.write('%s\\n' % error)",
	"    sys.exit(1)",
].join("\n");

function bounded(text: string): string {
	const lines = text.trim().split("\n").slice(0, MAX_ERROR_LINES).join("\n");
	return Buffer.byteLength(lines, "utf8") <= MAX_ERROR_BYTES
		? lines
		: Buffer.from(lines, "utf8").subarray(0, MAX_ERROR_BYTES).toString("utf8");
}

/** Bounded stderr when the file fails to parse, undefined when clean or uncheckable. */
function checkerError(command: string, args: string[]): string | undefined {
	const result = spawnSync(command, args, {
		timeout: CHECK_TIMEOUT_MS,
		encoding: "utf8",
		stdio: ["ignore", "ignore", "pipe"],
	});
	if (result.error || result.signal !== null || result.status === 0) return undefined;
	const message = bounded(result.stderr ?? "");
	return message || undefined;
}

function syntaxError(path: string): string | undefined {
	switch (extname(path).toLowerCase()) {
		case ".py":
			return checkerError(
				process.env.PIDECK_SYNTAX_CHECK_PYTHON || "python3",
				["-c", PYTHON_CHECK, path],
			);
		case ".js":
		case ".mjs":
		case ".cjs":
			return checkerError(process.execPath, ["--check", path]);
		case ".json": {
			// An unreadable file is the checker's problem, not a syntax error.
			let source: string;
			try {
				source = readFileSync(path, "utf8");
			} catch {
				return undefined;
			}
			try {
				JSON.parse(source);
				return undefined;
			} catch (error) {
				return bounded(error instanceof Error ? error.message : String(error));
			}
		}
		default:
			return undefined;
	}
}

export default function (pi: ExtensionAPI) {
	pi.on("tool_result", (event) => {
		if (event.isError || !MUTATING_TOOLS.has(event.toolName)) return;
		const path = (event.input as { path?: unknown } | undefined)?.path;
		if (typeof path !== "string" || !existsSync(path)) return;
		const error = syntaxError(path);
		if (!error) return;
		return {
			content: [
				...event.content,
				{
					type: "text",
					text: "\n[PI//DECK: файл сохранён, но синтаксис не прошёл проверку:\n"
						+ `${error}\n`
						+ "Исправь ошибку и сохрани файл снова.]",
				},
			],
		};
	});
}
