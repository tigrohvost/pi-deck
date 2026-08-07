/**
 * Runs the workspace's pytest suite and answers in one bounded turn.
 *
 * Through bash, pytest's output reaches the model via the context guard's generic head/tail
 * window — which is precisely where the failure summary is not. This tool shapes the run
 * instead: stop at the first failure, short traceback, no cache artifacts in a workspace the
 * suite scores by diffing, and a result that always fits a phone-sized context. It exists only
 * in the autonomous profile: executing conftest and fixtures is the same trust class as bash.
 */
import { spawn } from "node:child_process";
import { resolve, sep } from "node:path";
import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const RUN_TIMEOUT_MS = 120_000;

/** Read per call, not at load: the deck sets the environment before each Pi start. */
function runTimeoutMs(): number {
	return Number(process.env.PIDECK_RUN_TESTS_TIMEOUT_MS) || RUN_TIMEOUT_MS;
}
const MAX_RESULT_BYTES = 4 * 1024;
const MAX_CAPTURED_BYTES = 64 * 1024;
const MAX_PATH_CHARS = 500;
const MAX_EXPR_CHARS = 200;

type RunOutcome = {
	status: number | null;
	signal: NodeJS.Signals | null;
	output: string;
	spawnError?: string;
};

function runPytest(
	cwd: string,
	extra: string[],
	signal?: AbortSignal,
): Promise<RunOutcome> {
	return new Promise((resolvePromise) => {
		const child = spawn(
			process.env.PIDECK_RUN_TESTS_PYTHON || "python3",
			["-m", "pytest", "-x", "-q", "--tb=short", "-p", "no:cacheprovider", ...extra],
			{
				cwd,
				env: { ...process.env, PYTHONDONTWRITEBYTECODE: "1" },
				stdio: ["ignore", "pipe", "pipe"],
				signal,
				timeout: runTimeoutMs(),
				killSignal: "SIGKILL",
			},
		);
		const chunks: Buffer[] = [];
		let captured = 0;
		const collect = (chunk: Buffer) => {
			if (captured >= MAX_CAPTURED_BYTES) {
				child.kill("SIGKILL");
				return;
			}
			captured += chunk.byteLength;
			chunks.push(chunk);
		};
		child.stdout.on("data", collect);
		child.stderr.on("data", collect);
		// A spawned process always reaches 'close', which carries the exit status and the
		// captured output; resolving on 'error' there would race it and drop both. Only a
		// process that never spawned (no pid, no 'close' guaranteed) resolves from 'error'.
		let settled = false;
		child.on("error", (error) => {
			if (settled || child.pid !== undefined) return;
			settled = true;
			resolvePromise({
				status: null,
				signal: null,
				output: "",
				spawnError: error.message,
			});
		});
		child.on("close", (status, killedBy) => {
			if (settled) return;
			settled = true;
			resolvePromise({
				status,
				signal: killedBy,
				output: Buffer.concat(chunks).toString("utf8"),
				spawnError: undefined,
			});
		});
	});
}

/** The verdict pytest prints last, e.g. "1 failed, 2 passed in 0.12s". */
function summaryLine(output: string): string {
	const lines = output.trim().split("\n");
	for (let index = lines.length - 1; index >= 0; index--) {
		const line = lines[index].replace(/^=+ | =+$/g, "").trim();
		if (/\b(passed|failed|error|errors|no tests ran)\b/.test(line)) return line;
	}
	return lines[lines.length - 1]?.trim() ?? "";
}

function bounded(text: string): string {
	if (Buffer.byteLength(text, "utf8") <= MAX_RESULT_BYTES) return text;
	const cut = Buffer.from(text, "utf8").subarray(0, MAX_RESULT_BYTES).toString("utf8");
	return `${cut}\n[... вывод сокращён ...]`;
}

function formatResult(outcome: RunOutcome): string {
	if (outcome.spawnError || /No module named pytest/.test(outcome.output)) {
		return "pytest недоступен в этом окружении. Запусти тесты через bash "
			+ `(например, python3 -m unittest). Детали: ${
				outcome.spawnError || "No module named pytest"}`;
	}
	if (outcome.signal !== null) {
		return `Тесты не завершились за ${runTimeoutMs() / 1000} с и были остановлены. `
			+ "Запусти меньшее подмножество через path или expr.";
	}
	const summary = summaryLine(outcome.output);
	if (outcome.status === 0) {
		return `Тесты прошли: ${summary || "0 tests"}.`;
	}
	// -x stops at the first failure, so everything before the summary is that failure.
	const body = outcome.output.trim();
	return bounded(`Тесты упали: ${summary}.\nПервая ошибка:\n${body}`);
}

export default function pideckRunTests(pi: ExtensionAPI) {
	pi.registerTool({
		name: "run_tests",
		label: "Run tests",
		description:
			"Run the workspace's pytest tests and return the verdict plus the first failure verbatim.",
		promptSnippet: "Run pytest and get the verdict with the first failure",
		promptGuidelines: [
			"Use run_tests instead of running pytest through bash: the result is bounded and always contains the first failure in full.",
			"After a failure, fix the named test or code and run run_tests again; pass path or expr to narrow the run.",
		],
		parameters: Type.Object({
			path: Type.Optional(Type.String({
				description: "File or directory with tests, relative to the workspace",
				maxLength: MAX_PATH_CHARS,
			})),
			expr: Type.Optional(Type.String({
				description: "pytest -k expression to select tests",
				maxLength: MAX_EXPR_CHARS,
			})),
		}),
		async execute(_toolCallId, params, signal, _onUpdate, context) {
			const cwd = resolve(String(context?.cwd ?? process.cwd()));
			const extra: string[] = [];
			const rawPath = params.path === undefined ? "" : String(params.path).trim();
			if (rawPath) {
				if (rawPath.length > MAX_PATH_CHARS) throw new Error("path is too long");
				const target = resolve(cwd, rawPath);
				if (target !== cwd && !target.startsWith(cwd + sep)) {
					throw new Error("path must stay inside the workspace");
				}
				extra.push(target);
			}
			const expr = params.expr === undefined ? "" : String(params.expr).trim();
			if (expr) {
				if (expr.length > MAX_EXPR_CHARS) throw new Error("expr is too long");
				extra.push("-k", expr);
			}
			const outcome = await runPytest(cwd, extra, signal);
			return {
				content: [{ type: "text", text: formatResult(outcome) }],
				details: {
					status: outcome.status,
					timedOut: outcome.signal !== null,
					bytes: Buffer.byteLength(outcome.output, "utf8"),
				},
			};
		},
	});
}
