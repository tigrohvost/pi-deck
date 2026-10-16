/** Audit regressions against actual extensions, loaded with pinned Pi's loader. */
import assert from "node:assert/strict";
import { chmodSync, cpSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, existsSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const runtime = join(root, "app/src/main/assets/runtime");
const pkg = join(resolve(process.argv[2]), "@earendil-works/pi-coding-agent");
const area = mkdtempSync(join(pkg, "pideck-audit-"));
const failures = [];
async function check(name, run) {
    try { await run(); console.log(`PASS ${name}`); }
    catch (error) { failures.push(name); console.error(`FAIL ${name}: ${error.message}`); }
}
try {
    for (const name of readdirSync(runtime).filter(name => name.endsWith(".ts"))) {
        cpSync(join(runtime, name), join(area, name));
    }
    cpSync(join(runtime, "pideck-test-runner.py"), join(area, "pideck-test-runner.py"));
    const require = createRequire(join(pkg, "package.json"));
    const { createJiti } = require("jiti");
    const jiti = createJiti(import.meta.url, { moduleCache: false });
    const runner = await jiti.import(join(area, "pideck-run-tests.ts"));
    const planner = await jiti.import(join(area, "pideck-plan-ledger.ts"));
    const guard = await jiti.import(join(area, "pideck-context-guard.ts"));
    let runTests;
    runner.default({ registerTool(tool) { runTests = tool; }, on() {}, appendEntry() {} });
    const noSite = join(area, "python-no-site");
    writeFileSync(noSite, '#!/bin/sh\nexec python3 -S "$@"\n');
    chmodSync(noSite, 0o700);
    process.env.PIDECK_RUN_TESTS_PYTHON = noSite;
    for (const [name, body] of [
        ["async", "async def test_bad():\n    raise AssertionError('must execute')\n"],
        ["generator", "def test_bad():\n    raise AssertionError('must execute')\n    yield 1\n"],
        ["async-generator", "async def test_bad():\n    raise AssertionError('must execute')\n    yield 1\n"],
        ["returned-awaitable", "async def body():\n    raise AssertionError('must execute')\ndef test_bad():\n    return body()\n"],
    ]) {
        await check(`AG-01 ${name} never passes`, async () => {
            const cwd = join(area, name); mkdirSync(cwd);
            writeFileSync(join(cwd, "test_bad.py"), body);
            const result = await runTests.execute(name, {}, undefined, undefined, { cwd });
            assert.equal(result.details.receipt?.status, "unsupported");
            assert.doesNotMatch(result.content[0].text, /Тесты прошли/u);
        });
    }
    delete process.env.PIDECK_RUN_TESTS_PYTHON;
    await check("AG-01 no tests is not passed", async () => {
        const cwd = join(area, "empty"); mkdirSync(cwd);
        const result = await runTests.execute("empty", {}, undefined, undefined, { cwd });
        assert.equal(result.details.receipt?.status, "no_tests");
    });

    await check("AG-01 unsupported fallback selection and file bound", async () => {
        process.env.PIDECK_RUN_TESTS_PYTHON = noSite;
        const cwd = join(area, "bounded"); mkdirSync(cwd);
        writeFileSync(join(cwd, "test_one.py"), "def test_one():\n    pass\n");
        assert.equal((await runTests.execute("expr", { expr: "one or two" }, undefined, undefined, { cwd })).details.receipt.status, "unsupported");
        for (let i = 0; i < 200; i++) writeFileSync(join(cwd, `test_${i}.py`), "def test_one():\n    pass\n");
        assert.equal((await runTests.execute("bound", {}, undefined, undefined, { cwd })).details.receipt.status, "unsupported");
    });
    await check("AG-01 missing runner is infrastructure failure", async () => {
        process.env.PIDECK_RUN_TESTS_PYTHON = join(area, "missing-python");
        assert.equal((await runTests.execute("missing", {}, undefined, undefined, { cwd: area })).details.receipt.status, "infra_error");
    });
    await check("AG-01 current passing receipt expires after a source edit", async () => {
        process.env.PIDECK_RUN_TESTS_PYTHON = noSite;
        const cwd = join(area, "snapshot"); mkdirSync(cwd);
        const file = join(cwd, "test_one.py"); writeFileSync(file, "def test_one():\n    pass\n");
        const result = await runTests.execute("snapshot", {}, undefined, undefined, { cwd });
        assert.equal(result.details.receipt.status, "passed");
        assert.equal(runner.currentPassedReceipt(result.details.receipt, cwd), true);
        writeFileSync(file, "def test_one():\n    assert False\n");
        assert.equal(runner.currentPassedReceipt(result.details.receipt, cwd), false);
        assert.equal(runner.currentPassedReceipt(result.details.receipt, join(area, "missing")), false);
    });
    for (const reason of ["cancelled", "timed_out", "output_limit"]) {
        await check(`AG-01 ${reason} stops the process tree`, async () => {
            process.env.PIDECK_RUN_TESTS_PYTHON = noSite;
            process.env.PIDECK_RUN_TESTS_TIMEOUT_MS = reason === "timed_out" ? "500" : "10000";
            const cwd = join(area, reason); mkdirSync(cwd);
            writeFileSync(join(cwd, "test_process.py"), `import subprocess,sys,time\nfrom pathlib import Path\ndef test_child():\n    child=subprocess.Popen([sys.executable,'-c','import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)'])\n    Path('child.pid').write_text(str(child.pid))\n    time.sleep(.1)\n    ${reason === "output_limit" ? "print('x'*100000,flush=True)" : "time.sleep(30)"}\n`);
            const abort = new AbortController();
            const pending = runTests.execute(reason, {}, abort.signal, undefined, { cwd });
            if (reason === "cancelled") {
                for (let i = 0; i < 100 && !existsSync(join(cwd, "child.pid")); i++) await new Promise(r => setTimeout(r, 10));
                abort.abort();
            }
            const result = await pending;
            assert.equal(result.details.receipt.status, reason);
            assert.equal(result.details.receipt.terminationReason, reason);
            const pid = Number(readFileSync(join(cwd, "child.pid"), "utf8"));
            const running = () => {
                try { return !/State:\s+Z/u.test(readFileSync(`/proc/${pid}/status`, "utf8")); }
                catch { return false; }
            };
            for (let i = 0; i < 100 && running(); i++) await new Promise(r => setTimeout(r, 10));
            try { assert.equal(running(), false, `descendant ${pid} survived ${reason}`); }
            finally { if (running()) process.kill(pid, "SIGKILL"); }
            delete process.env.PIDECK_RUN_TESTS_TIMEOUT_MS;
        });
    }
    delete process.env.PIDECK_RUN_TESTS_PYTHON;
    await check("AG-02 host evidence is scoped and stale approvals cannot execute", async () => {
        process.env.PIDECK_AGENT_MODE = "agent";
        process.env.PIDECK_ACCESS_PROFILE = "confirm_changes";
        const handlers = new Map(); let latest, followups = 0;
        planner.default({ on(name, callback) { handlers.set(name, callback); },
            appendEntry(_type, value) { latest = structuredClone(value); }, sendUserMessage() { followups++; } });
        const cwd = join(area, "plan"); mkdirSync(cwd); writeFileSync(join(cwd, "a.py"), "value=1\n");
        const ctx = { cwd, hasUI: true, mode: "rpc", ui: { confirm: async () => true, setStatus() {} } };
        const input = () => handlers.get("input")({ text: planner.PLAN_REQUEST_PREFIX + "inspect project" }, ctx);
        const plan = { messages: [{ role: "assistant", content: [{ type: "text", text: "Plan:\n1. Inspect a.py [inspection:a.py]\n2. Explain the source\n3. Report findings" }] }] };
        const done = { message: { role: "assistant", content: [{ type: "text", text: "Finished\n[DONE:1] [DONE:2] [DONE:3]" }] } };
        input(); await handlers.get("agent_end")(plan, ctx);
        handlers.get("turn_end")(done, ctx);
        assert.equal(latest.phase, "unverified");
        assert.equal(latest.items.filter(item => item.status === "verified").length, 0);
        input(); await handlers.get("agent_end")(plan, ctx);
        handlers.get("tool_call")({ toolName: "read", toolCallId: "r", input: { path: "a.py" } }, ctx);
        handlers.get("tool_result")({ toolName: "read", toolCallId: "r", input: { path: "a.py" }, isError: false }, ctx);
        handlers.get("turn_end")({ message: { role: "assistant", content: [{ type: "text", text: "Read\n[DONE:1]" }] } }, ctx);
        assert.equal(latest.items[0].status, "verified");
        assert.equal(latest.items[0].verificationKind, "inspection");
        writeFileSync(join(cwd, "a.py"), "value=2\n");
        handlers.get("turn_end")(done, ctx);
        assert.equal(latest.items[0].status, "done_unverified");
        let decide;
        ctx.ui.confirm = () => new Promise(resolve => { decide = resolve; });
        input(); const approval = handlers.get("agent_end")(plan, ctx);
        const before = followups;
        input(); decide(true); await approval;
        assert.equal(latest.phase, "planning");
        assert.equal(followups, before);
        const changed = handlers.get("agent_end")(plan, ctx);
        writeFileSync(join(cwd, "a.py"), "value=3\n");
        decide(true); await changed;
        assert.equal(latest.phase, "cancelled");
        assert.equal(followups, before);
        ctx.ui.confirm = async () => true;
        input(); await handlers.get("agent_end")(plan, ctx);
        const previousHome = process.env.PIDECK_HOME;
        process.env.PIDECK_HOME = area;
        writeFileSync(join(area, "active-operation.json"), JSON.stringify({ schemaVersion: 1,
            operationId: "11111111-2222-4333-8444-555555555555", workspace: cwd }));
        handlers.get("input")({ text: "a different task", source: "rpc" }, ctx);
        assert.equal(handlers.get("before_agent_start")({}, ctx), undefined, "old plan authorization leaked into a new operation");
        if (previousHome === undefined) delete process.env.PIDECK_HOME;
        else process.env.PIDECK_HOME = previousHome;
    });

    const oldSearch = (text, details) => [
        { role: "assistant", content: [{ type: "toolCall", id: "nav", name: "code_nav", arguments: { query: "sample" } }] },
        { role: "toolResult", toolCallId: "nav", content: [{ type: "text", text }], details },
        ...Array.from({ length: 7 }, () => ({ role: "user", content: "next" })),
    ];
    await check("AG-03 source containing no matches is retained", () => {
        const messages = oldSearch('src/search.py:12:5: return "no matches"\n' + "source ".repeat(1000),
            { schemaVersion: 1, status: "complete", fileCandidates: 0, textMatches: 1, truncated: false });
        assert.equal(guard.pruneSupersededContext(messages), messages);
    });
    await check("AG-03 legacy and partial empty results are retained", () => {
        for (const details of [undefined, { status: "partial", fileCandidates: 0, textMatches: 0, truncated: true }]) {
            const messages = oldSearch("no matches\n" + "context ".repeat(1000), details);
            assert.equal(guard.pruneSupersededContext(messages), messages);
        }
    });
    await check("AG-03 typed empty is recognized independently of text", () => {
        const messages = oldSearch("No filename or text match.\n" + "scope ".repeat(1000),
            { schemaVersion: 1, status: "complete", fileCandidates: 0, textMatches: 0, truncated: false });
        assert.match(guard.pruneSupersededContext(messages)[1].content[0].text, /pruned/u);
    });
    await check("AG-02 examples and nonterminal tags do not advance plans", () => {
        for (const text of ["```text\n[DONE:1]\n```", "> [DONE:1]", "[DONE:1]\nMore text", "example [DONE:1]"]) {
            assert.deepEqual(planner.progressMarkers(text), []);
        }
        assert.deepEqual(planner.progressMarkers("Result\n[DONE:1] [BLOCKED:2]"),
            [{ kind: "done", step: 1 }, { kind: "blocked", step: 2 }]);
    });
} finally {
    delete process.env.PIDECK_RUN_TESTS_PYTHON;
    delete process.env.PIDECK_RUN_TESTS_TIMEOUT_MS;
    rmSync(area, { recursive: true, force: true });
}
console.log(`${failures.length} audit regressions failed`);
process.exit(failures.length ? 1 : 0);
