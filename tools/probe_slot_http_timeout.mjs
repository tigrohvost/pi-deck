/** Slow, opt-in reproduction of fetch's headers timeout during a cold prefix warm-up. */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [modules, baselineRef, output] = process.argv.slice(2);
if (!modules || !baselineRef || !output) {
    throw new Error("Usage: probe_slot_http_timeout.mjs <node_modules> <baseline-ref> <report.json> (takes 310 seconds)");
}
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = "app/src/main/assets/runtime/pideck-local-cache.ts";
const pkg = join(resolve(modules), "@earendil-works/pi-coding-agent");
const area = mkdtempSync(join(pkg, "pideck-slot-timeout-"));
const timers = new Set();
const server = createServer(async (request, response) => {
    for await (const _chunk of request) { /* Drain the request before delaying its response. */ }
    const timer = setTimeout(() => {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end('{"n_saved":1414}');
        timers.delete(timer);
    }, 310_000);
    timers.add(timer);
});
try {
    const commit = execFileSync("git", ["rev-parse", "--verify", `${baselineRef}^{commit}`], { cwd: root, encoding: "utf8" }).trim();
    const before = execFileSync("git", ["show", `${commit}:${source}`], { cwd: root });
    const after = readFileSync(join(root, source));
    writeFileSync(join(area, "baseline.ts"), before);
    cpSync(join(root, source), join(area, "candidate.ts"));
    const jiti = createRequire(join(pkg, "package.json"))("jiti").createJiti(import.meta.url, { moduleCache: false });
    const implementations = {
        baseline: await jiti.import(join(area, "baseline.ts")),
        candidate: await jiti.import(join(area, "candidate.ts")),
    };
    const sha = value => createHash("sha256").update(value).digest("hex");
    const report = {
        schemaVersion: 1, createdAt: new Date().toISOString(), node: process.version,
        scope: "Real loopback HTTP timeout reproduction; no model inference or speed comparison",
        baselineCommit: commit, baselineSourceSha256: sha(before), candidateSourceSha256: sha(after),
        responseDelaySeconds: 310, clientDeadlineSeconds: 600, results: [],
    };
    const save = () => {
        mkdirSync(dirname(resolve(output)), { recursive: true });
        writeFileSync(resolve(output), JSON.stringify(report, null, 2) + "\n");
    };
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const url = `http://127.0.0.1:${server.address().port}/v1`;
    await Promise.all(Object.entries(implementations).map(async ([variant, implementation]) => {
        const start = performance.now();
        let result;
        try {
            result = { variant, status: "resolved",
                response: await implementation.loopbackSlotClient(url, "test").post("/completion", {}) };
        } catch (error) {
            result = { variant, status: "rejected", error: error.message, cause: error.cause?.code };
        }
        result.elapsedSeconds = (performance.now() - start) / 1000;
        report.results.push(result);
        save();
        console.log(JSON.stringify(result));
    }));
    assert.equal(report.results.find(result => result.variant === "baseline").cause, "UND_ERR_HEADERS_TIMEOUT");
    assert.equal(report.results.find(result => result.variant === "candidate").response?.status, 200);
} finally {
    for (const timer of timers) clearTimeout(timer);
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    rmSync(area, { recursive: true, force: true });
}
