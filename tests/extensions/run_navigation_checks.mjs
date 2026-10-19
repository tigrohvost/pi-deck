/** Real-process navigation regressions; optional second argument selects a baseline source file. */
import assert from "node:assert/strict";
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const pkg = join(resolve(process.argv[2]), "@earendil-works/pi-coding-agent");
const area = mkdtempSync(join(pkg, "pideck-navigation-"));
const originalPath = process.env.PATH;
let failures = 0;
let checks = 0;
const check = async (name, run) => {
    checks++;
    try { await run(); console.log(`PASS ${name}`); }
    catch (error) { failures++; console.error(`FAIL ${name}: ${error.stack}`); }
    finally { process.env.PATH = originalPath; }
};
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const alive = pid => {
    try { return !/State:\s+Z/u.test(readFileSync(`/proc/${pid}/status`, "utf8")); }
    catch { return false; }
};
try {
    const modulePath = join(area, "pideck-code-nav.ts");
    cpSync(process.argv[3] ? resolve(process.argv[3])
        : join(root, "app/src/main/assets/runtime/pideck-code-nav.ts"), modulePath);
    const { createJiti } = createRequire(join(pkg, "package.json"))("jiti");
    const nav = await createJiti(import.meta.url, { moduleCache: false }).import(modulePath);
    const cwd = join(area, "workspace"); mkdirSync(cwd);
    const fakeBin = join(area, "bin"); mkdirSync(fakeBin);
    const fakeRg = source => {
        writeFileSync(join(fakeBin, "rg"), `#!/usr/bin/env python3\n${source}\n`);
        chmodSync(join(fakeBin, "rg"), 0o700);
        process.env.PATH = `${fakeBin}:${originalPath}`;
    };
    await check("an explicit dot-relative scope survives omitted model arguments", async () => {
        const scope = ".pideck-bench/current/fixture";
        const other = ".pideck-bench/other/fixture";
        for (const directory of [scope, other]) {
            mkdirSync(join(cwd, directory), { recursive: true });
            writeFileSync(join(cwd, directory, "calculator.py"), "def divide(left, right): return left / right\n");
        }
        const hooks = new Map(); let tool;
        nav.default({ on: (name, callback) => hooks.set(name, callback), registerTool: value => { tool = value; } });
        for (const prompt of [
            `In ${scope}, find the definition of function divide.`,
            `Under ./${scope}, find the definition of function divide.`,
            `В ${scope} найди определение функции divide.`,
        ]) {
            hooks.get("input")({ text: prompt });
            const result = await tool.execute("scope-check", { query: "divide" }, undefined, undefined, { cwd });
            assert.equal(result.details.scope, scope, "the model's omitted path widened the user's search");
            assert.equal(result.details.textMatches, 1);
            assert.doesNotMatch(result.content[0].text, /other\/fixture/u);
        }
        for (const prompt of ["Explain in English.", "Report speed in tokens/s."]) {
            assert.equal(nav.explicitNavigationScope(prompt), undefined);
        }
        hooks.get("input")({ text: "In ../outside, find function divide." });
        await assert.rejects(tool.execute("escape-check", { query: "divide" }, undefined, undefined, { cwd }), /stay inside/u);
        hooks.get("session_start")();
        const all = await tool.execute("reset-check", { query: "divide" }, undefined, undefined, { cwd });
        assert.equal(all.details.scope, ".", "a new session retained the previous user's scope");
    });
    await check("complete, empty and source text are distinguished", async () => {
        writeFileSync(join(cwd, "needle.py"), 'value = "no matches" # needle\n');
        const found = await nav.navigate("needle", ".", cwd);
        assert.equal(found.status, "complete");
        assert.equal(found.textMatches, 1);
        assert.equal(found.fileCandidates, 1);
        assert.match(found.text, /needle\.py:1:.*no matches/u);
        const empty = await nav.navigate("definitely_absent", ".", cwd);
        assert.equal(empty.status, "complete");
        assert.equal(empty.textMatches + empty.fileCandidates, 0);
        assert.match(empty.text, /No filename or text match/u);
    });
    await check("large real search returns bounded matches, not maxBuffer failure", async () => {
        writeFileSync(join(cwd, "large.txt"), "NEEDLE content\n".repeat(100_000));
        const found = await nav.navigate("NEEDLE", "large.txt", cwd);
        assert.equal(found.status, "partial");
        assert.equal(found.truncated, true);
        assert.equal(found.textMatches, 24);
        assert.ok(found.text.length <= 8_000);
        assert.match(found.text, /large\.txt:1:1:NEEDLE/u);
        assert.doesNotMatch(found.text, /Search complete/u);
    });
    await check("a long matching line retains a bounded, marked excerpt", async () => {
        writeFileSync(join(cwd, "long.txt"), "HUGE " + "длинная строка ".repeat(40_000));
        const found = await nav.navigate("HUGE", "long.txt", cwd);
        assert.equal(found.status, "partial");
        assert.equal(found.textMatches, 1);
        assert.match(found.text, /long\.txt:1:1:HUGE/u);
        assert.ok(found.text.length <= 8_000);
    });
    await check("option-shaped file paths remain operands", async () => {
        writeFileSync(join(cwd, "-needle.txt"), "OPT_NEEDLE\n");
        const found = await nav.navigate("OPT_NEEDLE", "-needle.txt", cwd);
        assert.equal(found.status, "complete");
        assert.equal(found.textMatches, 1);
    });
    await check("workspace and symlink escapes stay forbidden", async () => {
        symlinkSync(join(area, "pideck-code-nav.ts"), join(cwd, "outside"));
        await assert.rejects(nav.navigate("needle", "..", cwd), /stay inside/u);
        await assert.rejects(nav.navigate("needle", "outside", cwd), /symlink outside/u);
    });
    await check("bounded empty inventory never claims a complete negative", async () => {
        fakeRg("import sys\nif '--files' in sys.argv:\n    sys.stdout.write(('unrelated/' + 'x'*200 + '\\0')*10_000)\nelse:\n    sys.exit(1)");
        const found = await nav.navigate("not_seen", ".", cwd);
        assert.equal(found.status, "partial");
        assert.equal(found.textMatches + found.fileCandidates, 0);
        assert.doesNotMatch(found.text, /No filename or text match|Search complete/u);
        assert.match(found.text, /not establish|incomplete/iu);
    });
    await check("a file name containing a newline is one candidate", async () => {
        writeFileSync(join(cwd, "line\nneedle.txt"), "nothing\n");
        const found = await nav.navigate("needle", "line\nneedle.txt", cwd);
        assert.match(found.text, /line\\nneedle\.txt/u);
    });
    await check("pre-cancelled navigation launches no producers", async () => {
        fakeRg("from pathlib import Path\nPath('spawned').write_text('yes')");
        const abort = new AbortController(); abort.abort();
        await assert.rejects(nav.navigate("needle", ".", cwd, abort.signal));
        await pause(100);
        assert.equal(existsSync(join(cwd, "spawned")), false);
    });
    await check("a newline in a matching filename cannot forge extra text results", async () => {
        writeFileSync(join(cwd, "line\nneedle.txt"), "FILE_VALUE\n");
        const found = await nav.navigate("FILE_VALUE", "line\nneedle.txt", cwd);
        assert.equal(found.status, "complete");
        assert.equal(found.textMatches, 1);
        assert.match(found.text, /"line\\nneedle\.txt":1:1:FILE_VALUE/u);
    });
    const pids = () => existsSync(join(cwd, "pids"))
        ? readFileSync(join(cwd, "pids"), "utf8").trim().split(/\s+/u).filter(Boolean).map(Number) : [];
    for (const mode of ["cancel", "failure", "timeout"]) {
        await check(`${mode} reaps both producers and descendants`, async () => {
            rmSync(join(cwd, "pids"), { force: true });
            fakeRg(`import os,signal,subprocess,sys,time\nfrom pathlib import Path
child=subprocess.Popen([sys.executable,'-c','import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)'])
with open('pids','a') as f: f.write(str(os.getpid())+' '+str(child.pid)+'\\n')
${mode === "failure" ? "if '--files' in sys.argv:\n    time.sleep(.2)\n    sys.stderr.write('fixture search failed')\n    sys.exit(2)" : ""}
signal.signal(signal.SIGTERM,signal.SIG_IGN)
time.sleep(30)`);
            const abort = new AbortController();
            // Attach a rejection handler immediately; the deliberate failure may arrive first.
            const pending = nav.navigate("needle", ".", cwd, abort.signal).then(value => ({ value }), error => ({ error }));
            try {
                for (let i = 0; i < 150 && pids().length < 4; i++) await pause(10);
                assert.equal(pids().length, 4, "both producers must really start");
                if (mode === "cancel") abort.abort();
                const outcome = await pending;
                if (mode === "timeout") {
                    assert.equal(outcome.error, undefined);
                    assert.equal(outcome.value.status, "partial");
                    assert.match(outcome.value.text, /timeout|time limit/iu);
                    assert.doesNotMatch(outcome.value.text, /No filename or text match/u);
                } else assert.ok(outcome.error, "cancellation/failure must not become success");
                for (let i = 0; i < 100 && pids().some(alive); i++) await pause(10);
                assert.deepEqual(pids().filter(alive), [], "a producer or descendant survived");
            } finally {
                abort.abort();
                for (const pid of pids().filter(alive)) { try { process.kill(pid, "SIGKILL"); } catch {} }
                await pending;
            }
        });
    }
    await check("missing ripgrep is reported as a tool failure", async () => {
        process.env.PATH = join(area, "absent-bin");
        await assert.rejects(nav.navigate("needle", ".", cwd), /search failed/u);
    });
} finally {
    process.env.PATH = originalPath;
    rmSync(area, { recursive: true, force: true });
}
console.log(`${checks} navigation checks, ${failures} failures`);
process.exitCode = failures ? 1 : 0;
