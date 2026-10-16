/** Bounded project execution. Stdout/exit zero alone never proves tests ran. */
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, writeFileSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const MAX_CAPTURE = 64 * 1024;
const RUNNER = join(dirname(fileURLToPath(import.meta.url)), "pideck-test-runner.py");
const states = new Set(["passed", "failed", "no_tests", "unsupported", "timed_out", "cancelled", "output_limit", "infra_error"]);
const sharedKey = Symbol.for("pideck.test-receipts.v1");
const shared = ((globalThis as any)[sharedKey] ??= { operationId: randomUUID() });
const digest = (value: string | Buffer) => createHash("sha256").update(value).digest("hex");

export type TestReceipt = {
    schemaVersion: 1; receiptId: string; operationId: string; workspaceId: string;
    snapshotId: string | null; currentSnapshotId: string | null;
    runner: { name: string; version: string | null }; invocationFingerprint: string;
    selection: { path: string; expression: string };
    selected: number | null; passed: number | null; failed: number | null; skipped: number | null;
    status: string; terminationReason: string; exitCode: number | null; durationMs: number;
    artifactId: string | null;
};

/** Oversized, symlinked or unreadable trees have an explicitly unknown snapshot. */
export function workspaceSnapshot(cwd: string): string | null {
    try {
        const hash = createHash("sha256");
        let files = 0, bytes = 0;
        function visit(directory: string, prefix: string): void {
            for (const name of readdirSync(directory).sort()) {
                if ([".git", "__pycache__", ".pytest_cache"].includes(name)) continue;
                const path = join(directory, name), relative = prefix + name, stat = lstatSync(path);
                if (stat.isSymbolicLink()) throw new Error("symlink");
                if (stat.isDirectory()) { visit(path, relative + "/"); continue; }
                if (!stat.isFile() || ++files > 2048 || (bytes += stat.size) > 16 * 1024 * 1024) throw new Error("bound");
                hash.update(relative).update("\0").update(digest(readFileSync(path))).update("\0");
            }
        }
        visit(realpathSync(cwd), "");
        return hash.digest("hex");
    } catch { return null; }
}

export function currentTestOperation(cwd: string): string {
    try {
        if (process.env.PIDECK_HOME) {
            const value = JSON.parse(readFileSync(join(process.env.PIDECK_HOME, "active-operation.json"), "utf8"));
            if (value.schemaVersion === 1 && value.workspace === realpathSync(cwd)
                && /^[0-9a-f-]{36}$/u.test(value.operationId)) return value.operationId;
        }
    } catch { /* Direct Pi use has no Android bridge operation. */ }
    return shared.operationId;
}

export function currentReceipt(value: unknown, cwd: string): value is TestReceipt {
    const receipt = value as Partial<TestReceipt> | undefined;
    try {
        return receipt?.schemaVersion === 1 && receipt.terminationReason === "exited"
            && receipt.operationId === currentTestOperation(cwd) && receipt.workspaceId === digest(realpathSync(cwd))
            && typeof receipt.snapshotId === "string" && receipt.snapshotId === receipt.currentSnapshotId
            && receipt.snapshotId === workspaceSnapshot(cwd);
    } catch { return false; }
}

export function currentPassedReceipt(value: unknown, cwd: string): value is TestReceipt {
    const receipt = value as Partial<TestReceipt> | undefined;
    return currentReceipt(value, cwd) && receipt!.status === "passed" && receipt!.exitCode === 0
        && typeof receipt.selected === "number" && receipt.selected > 0
        && typeof receipt.passed === "number" && receipt.passed > 0 && receipt.failed === 0;
}

type Outcome = { exitCode: number | null; termination: string; output: string; facts?: Record<string, any> };
function run(cwd: string, target: string, expr: string, signal?: AbortSignal): Promise<Outcome> {
    if (signal?.aborted) return Promise.resolve({ exitCode: null, termination: "cancelled", output: "" });
    return new Promise((done) => {
        const child = spawn(process.env.PIDECK_RUN_TESTS_PYTHON || "python3", ["-u", RUNNER, target, expr], {
            cwd, detached: process.platform !== "win32", env: { ...process.env, PYTHONDONTWRITEBYTECODE: "1" },
            stdio: ["ignore", "pipe", "pipe", "pipe"],
        });
        const chunks: Buffer[] = [];
        let bytes = 0, receipt = "", termination = "exited", settled = false;
        let escalation: ReturnType<typeof setTimeout> | undefined;
        const kill = (force: boolean) => {
            try {
                if (child.pid && process.platform !== "win32") process.kill(-child.pid, force ? "SIGKILL" : "SIGTERM");
                else child.kill(force ? "SIGKILL" : "SIGTERM");
            } catch { /* The process group has already exited. */ }
        };
        const stop = (reason: string) => {
            if (termination !== "exited") return;
            termination = reason; kill(false);
            escalation = setTimeout(() => kill(true), 250);
        };
        const abort = () => stop("cancelled");
        const timeout = Math.max(100, Math.min(120_000, Number(process.env.PIDECK_RUN_TESTS_TIMEOUT_MS) || 120_000));
        const timer = setTimeout(() => stop("timed_out"), timeout);
        signal?.addEventListener("abort", abort, { once: true });
        if (signal?.aborted) abort();
        const collect = (chunk: Buffer) => {
            const room = MAX_CAPTURE - bytes;
            chunks.push(chunk.subarray(0, Math.max(0, room))); bytes += Math.min(chunk.length, Math.max(0, room));
            if (chunk.length > room) stop("output_limit");
        };
        child.stdout!.on("data", collect); child.stderr!.on("data", collect);
        child.stdio[3]!.on("data", (chunk: Buffer) => {
            if (receipt.length + chunk.length > 4096) stop("output_limit");
            else receipt += chunk.toString("utf8");
        });
        const finish = (exitCode: number | null) => {
            if (settled) return;
            settled = true; clearTimeout(timer); signal?.removeEventListener("abort", abort);
            if (termination !== "exited") kill(true);
            if (escalation) clearTimeout(escalation);
            let facts;
            try { facts = JSON.parse(receipt); } catch { /* Missing receipt is never success. */ }
            done({ exitCode, termination, output: Buffer.concat(chunks).toString("utf8"), facts });
        };
        child.on("error", (error) => {
            termination = "spawn_error"; collect(Buffer.from(error.message));
            if (!child.pid) finish(null);
        });
        child.on("close", (code, killedBy) => {
            if (killedBy && termination === "exited") termination = "signal";
            finish(code);
        });
    });
}

function artifact(output: string): string | null {
    try {
        if (!process.env.PIDECK_HOME) return null;
        const directory = join(process.env.PIDECK_HOME, "test-results"), id = digest(output);
        mkdirSync(directory, { recursive: true, mode: 0o700 });
        // Preserve existing evidence; explicitly stop retaining new output at the quota.
        if (readdirSync(directory).length >= 64) return null;
        writeFileSync(join(directory, id + ".txt"), output, { mode: 0o600 });
        return "test-output:" + id;
    } catch { return null; }
}

function format(receipt: TestReceipt, output: string): string {
    const labels: Record<string, string> = {
        passed: `Тесты прошли: ${receipt.passed} passed.`, failed: `Тесты упали: ${receipt.failed} failed.`,
        no_tests: "Нет выполненных тестов: проверка не подтверждена.",
        unsupported: "Эти тесты требуют поддерживаемого runner; offline fallback их не проверил.",
        timed_out: "Тесты не завершились в отведённое время и были остановлены.",
        cancelled: "Проверка отменена пользователем.", output_limit: "Проверка остановлена: превышен лимит вывода.",
        infra_error: "Python test runner недоступен или завершился с ошибкой окружения. Проверка не подтверждена.",
    };
    const fallback = receipt.runner.name === "zero-fixture" ? " offline zero-fixture fallback." : "";
    const head = `${labels[receipt.status]}${fallback}\nReceipt: ${receipt.receiptId}`;
    const text = receipt.status === "passed" ? head : `${head}\n${output.trim()}`;
    return Buffer.byteLength(text) <= 4096 ? text : Buffer.from(text).subarray(0, 4096).toString("utf8") + "\n[... вывод сокращён ...]";
}

export default function pideckRunTests(pi: ExtensionAPI) {
    pi.on("input", (event) => { if (event.source !== "extension") shared.operationId = randomUUID(); });
    pi.registerTool({
        name: "run_tests", label: "Run tests",
        description: "Run pytest tests with a structured receipt. Missing pytest permits only synchronous zero-argument tests without fixtures, marks or -k.",
        parameters: Type.Object({
            path: Type.Optional(Type.String({ description: "Test file or directory inside the workspace", maxLength: 500 })),
            expr: Type.Optional(Type.String({ description: "pytest -k expression (requires pytest)", maxLength: 200 })),
        }),
        async execute(_toolCallId, params, signal, _onUpdate, context) {
            const cwd = realpathSync(resolve(String(context?.cwd ?? process.cwd())));
            const rawPath = String(params.path ?? "").trim(), rawExpr = String(params.expr ?? "").trim();
            if (rawPath.length > 500 || rawExpr.length > 200) throw new Error("Test selection is too long");
            const target = resolve(cwd, rawPath || ".");
            if (target !== cwd && !target.startsWith(cwd + sep)) throw new Error("path must stay inside the workspace");
            let expr = rawExpr.replace(/^(?:(?:python3?|py)\s+-m\s+)?pytest\s+-k\s+|^-k\s+/iu, "").trim();
            if ((expr.startsWith('"') && expr.endsWith('"')) || (expr.startsWith("'") && expr.endsWith("'"))) expr = expr.slice(1, -1);
            const operationId = currentTestOperation(cwd), before = workspaceSnapshot(cwd), started = performance.now();
            const outcome = await run(cwd, target, expr, signal), facts = outcome.facts;
            const count = (key: string) => Number.isSafeInteger(facts?.[key]) && facts![key] >= 0 ? facts![key] as number : null;
            let status = outcome.termination === "exited" && states.has(facts?.status) ? facts!.status : "infra_error";
            if (["cancelled", "timed_out", "output_limit"].includes(outcome.termination)) status = outcome.termination;
            if (status === "passed" && (outcome.exitCode !== 0 || !count("selected") || !count("passed") || count("failed") !== 0)) status = "infra_error";
            const receipt: TestReceipt = {
                schemaVersion: 1, receiptId: randomUUID(), operationId, workspaceId: digest(cwd),
                snapshotId: before, currentSnapshotId: workspaceSnapshot(cwd),
                runner: { name: typeof facts?.runner === "string" ? facts.runner : "python", version: typeof facts?.version === "string" ? facts.version : null },
                invocationFingerprint: digest(JSON.stringify([process.env.PIDECK_RUN_TESTS_PYTHON || "python3", target, expr])),
                selection: { path: rawPath || ".", expression: expr },
                selected: count("selected"), passed: count("passed"), failed: count("failed"), skipped: count("skipped"),
                status, terminationReason: outcome.termination, exitCode: outcome.exitCode,
                durationMs: Math.round(performance.now() - started), artifactId: artifact(outcome.output),
            };
            return { content: [{ type: "text", text: format(receipt, outcome.output) }],
                details: { receipt, runner: receipt.runner.name, path: target, expr, status: outcome.exitCode,
                    timedOut: status === "timed_out", bytes: Buffer.byteLength(outcome.output) } };
        },
    });
}
