#!/usr/bin/env node
/**
 * Host-only integration probe of the shipped prefix-cache extension and a pinned native server.
 * Usage: node tools/probe_prefix_cache.mjs <node_modules> <llama-server> <model-id> <gguf> <report.json> [--full-agent]
 * Uses an isolated, authenticated loopback server and synthetic tool results; executes no tools.
 */
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdtemp, mkdir, open, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import net from "node:net";
import os from "node:os";
import { dirname, join, resolve } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const [modules, binaryArg, modelId, artifactArg, outputArg, fixtureOption] = process.argv.slice(2);
if (!outputArg) throw new Error("Usage: probe_prefix_cache.mjs <node_modules> <llama-server> <model-id> <gguf> <report.json>");
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const binary = resolve(binaryArg);
const artifact = resolve(artifactArg);
const output = resolve(outputArg);
const catalog = JSON.parse(await readFile(join(root, "app/src/main/assets/models-v2.json"), "utf8"));
const model = catalog.models.find((entry) => entry.id === modelId);
assert.ok(model, "unknown catalog model");
async function sha256(path) {
	const digest = createHash("sha256");
	for await (const chunk of createReadStream(path)) digest.update(chunk);
	return digest.digest("hex");
}
assert.equal((await stat(artifact)).size, model.artifact.bytes);
assert.equal(await sha256(artifact), model.artifact.sha256, "model is not the pinned GGUF");
const piPackage = join(resolve(modules), "@earendil-works/pi-coding-agent/package.json");
const piVersion = JSON.parse(await readFile(piPackage, "utf8")).version;
assert.equal(piVersion, "0.82.1", "use PI//DECK's pinned Pi package");
const requirePi = createRequire(piPackage);
const { createJiti } = requirePi("jiti");
const extensionPath = join(root, "app/src/main/assets/runtime/pideck-local-cache.ts");
const cache = await createJiti(import.meta.url, { moduleCache: false }).import(extensionPath);
assert.ok(fixtureOption === undefined || fixtureOption === "--full-agent", "unknown fixture option");
let system = "Use the read tool to retrieve requested file contents. Never invent file contents. Reply exactly as requested.";
let tools = [{ type: "function", function: { name: "read", description: "Read the content of a text file.",
	parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] } } }];
if (fixtureOption === "--full-agent") {
	const fixture = spawnSync("python3", ["-c", "import json, sys; sys.path.insert(0, 'tools'); import adb_runtime_experiments as e; print(json.dumps({'system': e.agent_system_prompt(), 'tools': e.agent_tools()}))"], { cwd: root, encoding: "utf8" });
	assert.equal(fixture.status, 0, fixture.stderr);
	({ system, tools } = JSON.parse(fixture.stdout));
}
const work = await mkdtemp(join(os.tmpdir(), "pideck-prefix-probe-"));
const slots = join(work, "slots");
await mkdir(slots);
const listener = net.createServer();
await new Promise((resolveListen, reject) => { listener.once("error", reject); listener.listen(0, "127.0.0.1", resolveListen); });
const port = listener.address().port;
await new Promise((resolveClose) => listener.close(resolveClose));
const baseUrl = `http://127.0.0.1:${port}`;
const key = randomBytes(32).toString("hex");
const args = ["-m", artifact, "--alias", modelId, "--host", "127.0.0.1", "--port", String(port),
	"-c", String(model.runtime.recommendedContext), "-np", "1", "-t", "4", "-tb", "4",
	"--jinja", "--reasoning", model.runtime.reasoningMode,
	...model.runtime.serverArgs, "--slot-save-path", slots + "/", "--api-key", key];
for (const [flag, field] of [["--temp", "temperature"], ["--top-p", "topP"], ["--top-k", "topK"],
	["--min-p", "minP"], ["--presence-penalty", "presencePenalty"]]) args.push(flag, String(model.sampling[field]));
const report = {
	schemaVersion: 1, modelId, artifact: model.artifact, runtimeBuild: model.runtime.minimumLlamaCppVersion,
	serverSha256: await sha256(binary), extensionSha256: await sha256(extensionPath),
	piVersion,
	platform: `${os.platform()} ${os.arch()} ${os.cpus()[0]?.model}`, threads: 4,
	context: model.runtime.recommendedContext, serverArgs: model.runtime.serverArgs,
	requestSampling: { temperature: 0, seed: 7, max_tokens: 128 },
	promptFixture: fixtureOption === "--full-agent"
		? "adb_runtime_experiments.agent_system_prompt/agent_tools; representative, not captured Pi traffic"
		: "Bounded system prompt + one read tool; not the full Pi agent prompt",
	limits: "Host CPU timings only; no Android throughput or full agent admission claim. Tool results are synthetic.",
	startedAt: new Date().toISOString(), cases: [], passed: false,
};
let child;
let closed;
let log;
let launches = 0;
const interrupted = new AbortController();
const interrupt = () => interrupted.abort(new Error("probe interrupted"));
process.once("SIGINT", interrupt);
process.once("SIGTERM", interrupt);
async function stop() {
	if (child && child.exitCode === null && child.signalCode === null) {
		child.kill("SIGTERM");
		const timer = setTimeout(() => child?.kill("SIGKILL"), 10000);
		try { await closed; } finally { clearTimeout(timer); }
	}
	await log?.close();
	child = undefined;
	log = undefined;
}
async function post(path, body) {
	const response = await fetch(baseUrl + path, { method: "POST", headers: {
		"Content-Type": "application/json", Authorization: `Bearer ${key}`,
	}, body: JSON.stringify(body), signal: AbortSignal.any([interrupted.signal, AbortSignal.timeout(600000)]) });
	const value = await response.json();
	assert.equal(response.status, 200, JSON.stringify(value));
	return value;
}
async function start() {
	await stop();
	log = await open(join(work, `server-${++launches}.log`), "w");
	child = spawn(binary, args, { stdio: ["ignore", log.fd, log.fd] });
	let spawnError;
	child.once("error", (error) => { spawnError = error; });
	closed = new Promise((resolveExit) => child.once("close", resolveExit));
	for (let attempt = 0; attempt < 600; attempt++) {
		interrupted.signal.throwIfAborted();
		if (spawnError) throw spawnError;
		assert.equal(child.exitCode, null, "native server exited; see retained logs");
		assert.equal(child.signalCode, null, "native server was killed; see retained logs");
		try {
			const health = await fetch(baseUrl + "/health", { headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(1000) });
			if (health.ok && (await health.json()).status === "ok") return;
		} catch { /* server still loading */ }
		await delay(200);
	}
	throw new Error("server readiness deadline");
}
function payload(user) {
	return { model: modelId, messages: [{ role: "system", content: system }, { role: "user", content: user }],
		tools, tool_choice: "auto", parallel_tool_calls: false, temperature: 0, seed: 7, max_tokens: 128, stream: false };
}
function freshHook() {
	const hooks = new Map();
	cache.default({ on: (name, handler) => hooks.set(name, handler) });
	const context = { model: { baseUrl: baseUrl + "/v1" }, signal: interrupted.signal,
		modelRegistry: { getApiKeyAndHeaders: async () => ({ ok: true, apiKey: key }) } };
	return (body) => hooks.get("before_provider_request")({ payload: body }, context);
}
function answer(response) {
	const message = response.choices[0].message;
	return { content: message.content, reasoning: message.reasoning_content,
		calls: (message.tool_calls ?? []).map(({ function: fn }) => ({ name: fn.name, arguments: JSON.parse(fn.arguments) })) };
}
async function sample(label, body, hook) {
	const started = performance.now();
	const request = hook ? await hook(body) : { ...body, cache_prompt: false };
	const hookMs = performance.now() - started;
	const response = await post("/v1/chat/completions", request);
	const result = { label, hookMs, wallMs: performance.now() - started, cachePrompt: request.cache_prompt,
		timings: response.timings, answer: answer(response), finishReason: response.choices[0].finish_reason };
	assert.notEqual(result.finishReason, "length", "bounded output was truncated");
	report.cases.push(result);
	process.stdout.write(JSON.stringify({ label, wallMs: result.wallMs, timings: result.timings }) + "\n");
	return response;
}
try {
	process.env.PIDECK_SLOT_SNAPSHOTS = "1";
	process.env.PIDECK_CROSS_SESSION_PREFIX = "0";
	process.env.PIDECK_MODEL_SHA256 = model.artifact.sha256;
	const exact = payload("Reply with exactly CACHE_OK and nothing else. Do not call tools.");
	await start();
	const auto = await post("/apply-template", exact);
	const none = await post("/apply-template", { ...exact, tool_choice: "none" });
	report.sameAutoNoneTemplate = auto.prompt === none.prompt;
	assert.ok(report.sameAutoNoneTemplate, "auto/none changes the prompt; do not enable the stable choice gate");
	process.env.PIDECK_STABLE_TOOL_CHOICE_PREFIX = "1";
	await sample("create-snapshot", exact, freshHook());
	report.snapshots = await Promise.all((await readdir(slots)).map(async (name) => ({ name, bytes: (await stat(join(slots, name))).size })));
	assert.ok(report.snapshots.length > 0, "extension did not save a prefix");
	// Alternating full-prefill/restore pairs; every sample uses a fresh native process.
	for (const [index, cached] of [false, true, true, false].entries()) {
		await start();
		await sample(`restart-${index}-${cached ? "restore" : "full"}`, exact, cached ? freshHook() : undefined);
		const result = report.cases.at(-1);
		assert.deepEqual(result.answer, report.cases[0].answer);
		assert.equal(result.answer.content.trim(), "CACHE_OK");
		assert.equal(result.answer.calls.length, 0);
		if (cached) assert.ok(result.timings.cache_n > 0, "restored prefix was re-evaluated");
	}
	const read = payload("Use the read tool to read smoke.txt. Then reply with exactly the file contents.");
	const hook = freshHook();
	const call = await sample("tool-restore", read, hook);
	const message = call.choices[0].message;
	assert.equal(message.tool_calls.length, 1);
	assert.equal(message.tool_calls[0].function.name, "read");
	assert.deepEqual(JSON.parse(message.tool_calls[0].function.arguments), { path: "smoke.txt" });
	const final = { ...read, tool_choice: "none", messages: [...read.messages, message,
		{ role: "tool", tool_call_id: message.tool_calls[0].id, content: "CACHE_TOOL_OK" }] };
	const reused = await sample("tool-result-live", final, hook);
	assert.ok(report.cases.at(-1).timings.cache_n > 0, "auto/none discarded the live tool prefix");
	const control = await sample("tool-result-full", final);
	assert.deepEqual(answer(reused), answer(control));
	assert.equal(answer(reused).content.trim(), "CACHE_TOOL_OK");
	const callControl = await sample("tool-full", read);
	assert.deepEqual(answer(call), answer(callControl));
	// Rewriting an earlier user message in the same session must recover only the system prefix.
	const rewritten = { ...exact, messages: [exact.messages[0], { role: "user", content: "Reply with exactly NEW_CACHE_OK. Do not call tools." }] };
	const recovery = await sample("rewritten-history-restore", rewritten, hook);
	assert.ok(report.cases.at(-1).timings.cache_n > 0);
	const recoveryControl = await sample("rewritten-history-full", rewritten);
	assert.deepEqual(answer(recovery), answer(recoveryControl));
	assert.equal(answer(recovery).content.trim(), "NEW_CACHE_OK");
	const mean = (rows, value) => rows.reduce((sum, row) => sum + value(row), 0) / rows.length;
	const full = report.cases.filter(({ label }) => /^restart-\d-full$/.test(label));
	const restored = report.cases.filter(({ label }) => /^restart-\d-restore$/.test(label));
	report.restartComparison = {
		fullWallMs: mean(full, (row) => row.wallMs), restoredWallMs: mean(restored, (row) => row.wallMs),
		wallSpeedup: mean(full, (row) => row.wallMs) / mean(restored, (row) => row.wallMs),
		fullPromptTokens: mean(full, (row) => row.timings.prompt_n),
		restoredPromptTokens: mean(restored, (row) => row.timings.prompt_n),
	};
	report.passed = true;
} catch (error) {
	report.error = String(error?.stack ?? error);
	process.exitCode = 1;
} finally {
	await stop();
	process.removeListener("SIGINT", interrupt);
	process.removeListener("SIGTERM", interrupt);
	report.finishedAt = new Date().toISOString();
	report.nativeLaunches = launches;
	await mkdir(dirname(output), { recursive: true });
	await writeFile(output, JSON.stringify(report, null, 2) + "\n");
	if (report.passed) await rm(work, { recursive: true, force: true });
	else process.stderr.write(`Probe failed; native logs retained in ${work}\n${report.error}\n`);
}
