/** Paired real-ripgrep navigation timing; no model or handset inference is measured. */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { closeSync, cpSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, statSync, writeFileSync, writeSync } from "node:fs";
import { createRequire } from "node:module";
import { arch, cpus, platform, tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [modules, baselineRef, output] = process.argv.slice(2);
if (!modules || !baselineRef || !output) throw new Error("Usage: benchmark_navigation.mjs <node_modules> <baseline-ref> <report.json>");
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = "app/src/main/assets/runtime/pideck-code-nav.ts";
const pkg = join(resolve(modules), "@earendil-works/pi-coding-agent");
assert.equal(JSON.parse(readFileSync(join(pkg, "package.json"))).version, "0.82.1");
const area = mkdtempSync(join(pkg, "pideck-nav-benchmark-"));
const project = mkdtempSync(join(tmpdir(), "pideck-nav-fixture-"));
const sha = value => createHash("sha256").update(value).digest("hex");
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
    const fixture = join(project, "source.txt");
    const head = Array.from({ length: 1_024 }, (_, i) => `BENCH_NEEDLE marker ${i}\n`).join("");
    const filler = ("ordinary source data ".repeat(200) + "\n").repeat(256);
    const fd = openSync(fixture, "w");
    try {
        writeSync(fd, head);
        for (let i = 0; i < 64; i++) writeSync(fd, filler);
    } finally { closeSync(fd); }
    const fixtureSha256 = sha(readFileSync(fixture));
    let expected;
    const sample = async name => {
        const start = performance.now();
        const result = await implementations[name].navigate("BENCH_NEEDLE", ".", project);
        const elapsedMs = performance.now() - start;
        const matches = result.text.split("\n").filter(line => /^source\.txt:\d+:\d+:/u.test(line));
        assert.equal(result.status, "partial");
        assert.equal(result.textMatches, 24);
        assert.equal(matches.length, 24);
        expected ??= matches;
        assert.deepEqual(matches, expected);
        return { variant: name, elapsedMs, textMatches: result.textMatches,
            outputChars: result.text.length, matchesSha256: sha(JSON.stringify(matches)) };
    };
    // Exclude warm-up/loader compilation and warm the OS page cache for both variants.
    await sample("baseline"); await sample("candidate");
    const samples = [];
    for (let block = 0; block < 5; block++) {
        for (const variant of ["baseline", "candidate", "candidate", "baseline"]) {
            samples.push({ block, ...await sample(variant) });
        }
    }
    const median = name => {
        const values = samples.filter(s => s.variant === name).map(s => s.elapsedMs).sort((a, b) => a - b);
        return (values[4] + values[5]) / 2;
    };
    const report = { schemaVersion: 1, createdAt: new Date().toISOString(),
        scope: "Host code_nav latency only; no model inference or handset claim",
        environment: { platform: platform(), arch: arch(), cpu: cpus()[0]?.model, node: process.version,
            ripgrep: execFileSync("rg", ["--version"], { encoding: "utf8" }).split("\n")[0], pi: "0.82.1" },
        baselineCommit: commit, baselineSourceSha256: sha(before), candidateSourceSha256: sha(after),
        fixture: { bytes: statSync(fixture).size, sha256: fixtureSha256, matchingLines: 1_024,
            description: "one text file, matches at the beginning, nonmatching filler; warm OS cache" },
        method: "five ABBA blocks; 10 samples per variant; full navigate() call, excluding module load",
        baselineMedianMs: median("baseline"), candidateMedianMs: median("candidate"),
        medianSpeedup: median("baseline") / median("candidate"), exactReturnedMatchesEqual: true, samples };
    mkdirSync(dirname(resolve(output)), { recursive: true });
    writeFileSync(resolve(output), JSON.stringify(report, null, 2) + "\n");
    console.log(JSON.stringify({ report: resolve(output), baselineMedianMs: report.baselineMedianMs,
        candidateMedianMs: report.candidateMedianMs, medianSpeedup: report.medianSpeedup }));
} finally {
    rmSync(area, { recursive: true, force: true });
    rmSync(project, { recursive: true, force: true });
}
