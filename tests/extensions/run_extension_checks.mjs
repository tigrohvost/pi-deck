/**
 * Loads every PI//DECK Pi extension through the same jiti loader Pi itself uses and
 * exercises the parts that can be checked off-device.
 *
 * Pi resolves an extension's imports relative to the extension file, which on the phone is
 * `$PIDECK_HOME/runtime/` next to the installed package. This copies the extensions into the
 * installed package so the same resolution applies here, rather than assuming a layout that
 * only holds on the device.
 *
 * Usage: node tests/extensions/run_extension_checks.mjs <path-to-installed-node_modules>
 */

import assert from "node:assert/strict";
import {
	chmodSync,
	cpSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const REPOSITORY = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const RUNTIME = join(REPOSITORY, "app", "src", "main", "assets", "runtime");
const EXTENSIONS = [
	"pideck-local-cache.ts",
	"pideck-system-prompt.ts",
	"pideck-hashline-edit.ts",
	"pideck-syntax-check.ts",
	"pideck-run-tests.ts",
	"pideck-context-guard.ts",
	"pideck-web-tools.ts",
	"pideck-code-nav.ts",
	"pideck-tool-router.ts",
	"pideck-permission-gate.ts",
];
const EXPECTED_TOOLS = [
	"pideck_replace_lines",
	"run_tests",
	"web_research",
	"weather",
	"code_nav",
	"pideck_load_tools",
	"pideck_bash",
	"pideck_edit",
	"pideck_write",
];

const modules = process.argv[2];
if (!modules) {
	console.error("Usage: run_extension_checks.mjs <path-to-node_modules>");
	process.exit(2);
}

// The extensions import both @earendil-works/pi-ai, which is nested inside the agent
// package, and @earendil-works/pi-coding-agent, which is not. Only a directory inside the
// installed package sees both, which is also the layout the installer builds on the phone.
const packageDirectory = join(resolve(modules), "@earendil-works", "pi-coding-agent");
const workspace = mkdtempSync(join(packageDirectory, "pideck-extension-check-"));
try {
	for (const name of EXTENSIONS) {
		cpSync(join(RUNTIME, name), join(workspace, name));
	}

	// Load through Pi's own loader rather than a hand-written stand-in for ExtensionAPI.
	// A mock only proves the file runs; this proves Pi accepts it, and surfaces the load
	// errors Pi would otherwise swallow into a diagnostics list at startup.
	// loadExtensions is not re-exported from the package entry, so it is imported from the
	// loader module Pi itself uses. Taking the public discoverAndLoadExtensions instead would
	// also scan the machine's Pi config, which is exactly what --no-extensions forbids.
	const { loadExtensions } = await import(
		pathToFileURL(
			join(packageDirectory, "dist", "core", "extensions", "loader.js"),
		).href
	);
	process.env.PIDECK_HASHLINE_APPROVAL = "none";
	process.env.PIDECK_ACCESS_PROFILE = "autonomous";
	process.env.PIDECK_AGENT_MODE = "agent";
	const loaded = await loadExtensions(
		EXTENSIONS.map((name) => join(workspace, name)),
		workspace,
	);
	assert.deepEqual(loaded.errors, [], "Pi reported extension load errors");
	assert.equal(loaded.extensions.length, EXTENSIONS.length, "an extension failed to load");
	let activeTools = [];
	loaded.runtime.getActiveTools = () => [...activeTools];
	loaded.runtime.setActiveTools = (names) => {
		activeTools = [...names];
	};

	const tools = new Map();
	const toolResultHandlers = [];
	for (const extension of loaded.extensions) {
		for (const [name, registered] of extension.tools) {
			assert.ok(!tools.has(name), `duplicate tool ${name}`);
			// Pi wraps each definition with its provenance; the callable is inside.
			tools.set(name, registered.definition);
		}
		toolResultHandlers.push(...(extension.handlers.get("tool_result") ?? []));
	}

	assert.deepEqual([...tools.keys()], EXPECTED_TOOLS, "registered tool set changed");

	const cacheExtension = loaded.extensions[0];
	const cacheSessionStart = cacheExtension.handlers.get("session_start")?.[0];
	const cacheProviderRequest = cacheExtension.handlers.get("before_provider_request")?.[0];
	assert.equal(typeof cacheSessionStart, "function", "local cache has no session reset");
	assert.equal(typeof cacheProviderRequest, "function", "local cache has no provider hook");
	await cacheSessionStart({ type: "session_start", reason: "new" });
	assert.deepEqual(
		await cacheProviderRequest({
			type: "before_provider_request",
			payload: { messages: [], tools: [{ name: "read" }] },
		}),
		{ messages: [], tools: [{ name: "read" }], cache_prompt: false },
		"a new session reused the previous llama slot",
	);
	assert.deepEqual(
		await cacheProviderRequest({
			type: "before_provider_request",
			payload: { messages: [{ role: "user", content: "read it" }], tools: [{ name: "read" }] },
		}),
		{
			messages: [{ role: "user", content: "read it" }],
			tools: [{ name: "read" }],
			cache_prompt: true,
		},
		"a same-session tool round lost prompt caching",
	);
	assert.equal(
		(await cacheProviderRequest({
			type: "before_provider_request",
			payload: {
				messages: [
					{ role: "user", content: "read it" },
					{ role: "assistant", content: "tool result" },
				],
				tools: [{ name: "write" }],
			},
		})).cache_prompt,
		false,
		"a changed tool schema reused hybrid recurrent state",
	);
	assert.equal(
		(await cacheProviderRequest({
			type: "before_provider_request",
			payload: { messages: [{ role: "user", content: "unrelated" }], tools: [{ name: "write" }] },
		})).cache_prompt,
		false,
		"a rewritten message prefix reused hybrid recurrent state",
	);
	await cacheSessionStart({ type: "session_start", reason: "resume" });
	assert.equal(
		(await cacheProviderRequest({ type: "before_provider_request", payload: {} })).cache_prompt,
		false,
		"a resumed session reused a stale llama slot",
	);

	const requireFromPackage = createRequire(join(packageDirectory, "package.json"));
	const { createJiti } = requireFromPackage("jiti");
	const jiti = createJiti(import.meta.url, { moduleCache: false });
	const router = await jiti.import(join(workspace, "pideck-tool-router.ts"));
	assert.deepEqual(
		router.coreTools("autonomous"),
		["read", "bash", "write", "pideck_replace_lines", "run_tests", "pideck_load_tools"],
	);
	assert.deepEqual(router.detectCapabilities("Объясни слово «погода»"), []);
	assert.deepEqual(router.detectCapabilities("поищи в интернете документацию Pi"), ["web"]);
	assert.deepEqual(router.detectCapabilities("Какая текущая версия Pi?"), ["web"]);
	assert.deepEqual(router.detectCapabilities("Найди функцию divide"), ["files"]);
	assert.deepEqual(
		router.detectCapabilities(
			"Найди определение функции divide. Сначала используй code_nav.",
		),
		["files"],
		"the suite-v2 navigation wording did not activate code_nav",
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Найди определение функции divide. Сначала используй code_nav, затем укажи относительный файл и номер строки. Ничего не меняй.",
		),
		["code_nav"],
		"a read-only navigation request retained broad shell or mutation tools",
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Найди функцию divide, объясни её и ничего не меняй.",
		),
		["read", "code_nav"],
		"a content-review request lost read",
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Найди функцию divide, исправь её и запусти тесты.",
		),
		router.coreTools("autonomous"),
		"a mutating navigation request lost its implementation tools",
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Вызови read ровно один раз. Не вызывай bash, code_nav или другие инструменты.",
		),
		["read"],
		"an explicit single-tool request retained unrelated tools",
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"В каталоге /workspace найди все TODO одним вызовом code_nav. Ничего не меняй.",
		),
		["code_nav"],
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Найди текущую версию и используй web_research ровно один раз.",
		),
		["web_research"],
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Какая погода? Используй weather, а не общий веб-поиск.",
		),
		["weather"],
	);
	assert.deepEqual(
		router.taskCoreTools(
			"autonomous",
			"Прочитай docs/literal.txt и ничего не меняй.",
		),
		["read"],
	);
	assert.equal(
		router.explicitReadTarget(
			"В каталоге /workspace/fixture прочитай docs/literal.txt и ничего не меняй.",
		),
		"/workspace/fixture/docs/literal.txt",
	);
	assert.equal(router.safeReadTarget("/workspace", "/workspace/fixture/a.txt"), "/workspace/fixture/a.txt");
	assert.equal(router.safeReadTarget("/workspace", "/outside/a.txt"), undefined);
	const repairPrompt = `В каталоге ${workspace} исправь только off-by-one в src/counter.py `
		+ "и запусти точный тест tests/test_counter.py. Не меняй другие файлы.";
	assert.deepEqual(
		router.explicitFilePaths(repairPrompt),
		["src/counter.py", "tests/test_counter.py"],
	);
	const repairTargets = router.explicitFileTargets(repairPrompt);
	assert.equal(
		router.selectScopedTarget(repairTargets[1], repairTargets, false),
		repairTargets[1],
		"an exact test target lost to the preferred source fallback",
	);
	assert.equal(
		router.normalizeScopedTestExpression("tests/test_counter.py", repairTargets[1]),
		undefined,
		"a test path would be retained as an impossible -k expression",
	);
	assert.equal(
		router.normalizeScopedTestExpression("pytest tests/test_counter.py::test_increment", repairTargets[1]),
		"test_increment",
	);
	assert.equal(
		router.normalizeScopedTestExpression("pytest -k test_increment", repairTargets[1]),
		"test_increment",
	);
	assert.equal(router.isScopedRepairRequest(repairPrompt), true);
	assert.deepEqual(
		router.taskCoreTools("autonomous", repairPrompt),
		["read", "pideck_replace_lines", "run_tests"],
		"bounded repair retained broad bash discovery",
	);
	const routerExtension = loaded.extensions.find((extension) =>
		extension.path.endsWith("pideck-tool-router.ts"));
	const routerSessionStart = routerExtension?.handlers.get("session_start")?.[0];
	const routerInput = routerExtension?.handlers.get("input")?.[0];
	const routerToolResult = routerExtension?.handlers.get("tool_result")?.[0];
	const routerToolCall = routerExtension?.handlers.get("tool_call")?.[0];
	assert.equal(typeof routerSessionStart, "function", "tool router has no session reset");
	assert.equal(typeof routerInput, "function", "tool router has no input hook");
	assert.equal(typeof routerToolResult, "function", "tool router has no result hook");
	assert.equal(typeof routerToolCall, "function", "tool router has no call hook");
	await routerSessionStart({ type: "session_start", reason: "new" });
	await routerInput({
		type: "input",
		text: "В каталоге /workspace найди все TODO одним вызовом code_nav. Ничего не меняй.",
		source: "rpc",
	});
	assert.deepEqual(activeTools, ["code_nav"], "one-shot input retained another tool");
	const oneShotResult = await routerToolResult({
		type: "tool_result",
		toolName: "code_nav",
		toolCallId: "one-shot",
		input: { query: "TODO", path: "/workspace" },
		isError: false,
		content: [{ type: "text", text: "TODO alpha" }],
	});
	assert.deepEqual(activeTools, [], "one-shot tool remained in the next provider schema");
	assert.match(
		oneShotResult.content.at(-1).text,
		/ответь пользователю обычным текстом/iu,
		"one-shot result did not tell the model to answer instead of repeating markup",
	);
	assert.match(
		oneShotResult.content[0].text,
		/TOOL SUCCEEDED/u,
		"one-shot result did not lead with authoritative success",
	);
	await routerInput({
		type: "input",
		text: `В каталоге ${workspace} прочитай docs/literal.txt и ничего не меняй.`,
		source: "rpc",
	});
	assert.deepEqual(activeTools, ["read"], "explicit read retained unrelated tools");
	const readCall = {
		type: "tool_call",
		toolName: "read",
		toolCallId: "scoped-read",
		input: { path: "/data/data/com.termux/files/home/.pideck/workspace/AGENTS.md" },
	};
	assert.equal(await routerToolCall(readCall, { cwd: workspace }), undefined);
	assert.equal(
		readCall.input.path,
		join(workspace, "docs", "literal.txt"),
		"model read path overrode the user's explicit file scope",
	);
	assert.equal(await routerToolResult({
		type: "tool_result",
		toolName: "read",
		toolCallId: "scoped-read",
		input: readCall.input,
		isError: true,
		content: [{ type: "text", text: "missing" }],
	}), undefined);
	assert.deepEqual(activeTools, ["read"], "ordinary read error consumed its retry");
	await routerInput({ type: "input", text: repairPrompt, source: "rpc" });
	assert.deepEqual(
		activeTools,
		["read", "pideck_replace_lines", "run_tests"],
		"live bounded repair retained broad bash discovery",
	);
	const repairRead = {
		type: "tool_call",
		toolName: "read",
		toolCallId: "repair-read",
		input: { path: "AGENTS.md", offset: 25, limit: 100 },
	};
	assert.equal(await routerToolCall(repairRead, { cwd: workspace }), undefined);
	assert.equal(repairRead.input.path, join(workspace, "src", "counter.py"));
	assert.equal(repairRead.input.offset, undefined, "corrected read retained a hallucinated offset");
	const repairReadResult = await routerToolResult({
		type: "tool_result",
		toolName: "read",
		toolCallId: "repair-read",
		input: repairRead.input,
		isError: false,
		content: [{
			type: "text",
			text: "1:b4| class Counter:\n6:e9|         self.value += 2",
		}],
	});
	assert.match(repairReadResult.content[0].text, /READ SUCCEEDED/u);
	assert.deepEqual(
		activeTools,
		["read", "pideck_replace_lines", "run_tests"],
		"successful repair read prematurely removed edit tools",
	);
	const repairRepeatRead = {
		type: "tool_call",
		toolName: "read",
		toolCallId: "repair-repeat-read",
		input: { path: "AGENTS.md", offset: 35, limit: 100 },
	};
	assert.equal(await routerToolCall(repairRepeatRead, { cwd: workspace }), undefined);
	assert.equal(
		repairRepeatRead.input.path,
		join(workspace, "tests", "test_counter.py"),
		"a repeated invented read did not advance to the next user-scoped file",
	);
	assert.equal(repairRepeatRead.input.offset, undefined);
	await routerToolResult({
		type: "tool_result",
		toolName: "read",
		toolCallId: "repair-repeat-read",
		input: repairRepeatRead.input,
		isError: false,
		content: [{ type: "text", text: "1:aa| def test_counter():" }],
	});
	assert.deepEqual(
		activeTools,
		["pideck_replace_lines", "run_tests"],
		"read remained available after every user-scoped file was read once",
	);
	const repairEdit = {
		type: "tool_call",
		toolName: "pideck_replace_lines",
		toolCallId: "repair-edit",
		input: {
			path: "AGENTS.md",
			edits: [{ anchor: "e9", text: "        self.value += 1" }],
		},
	};
	assert.equal(await routerToolCall(repairEdit, { cwd: workspace }), undefined);
	assert.equal(repairEdit.input.path, join(workspace, "src", "counter.py"));
	assert.equal(
		repairEdit.input.edits[0].anchor,
		"6:e9",
		"a unique digest from the authoritative read was not restored to its full anchor",
	);
	const failedRepairEdit = {
		type: "tool_result",
		toolName: "pideck_replace_lines",
		toolCallId: "repair-edit",
		input: repairEdit.input,
		isError: true,
		content: [{ type: "text", text: "stale anchor" }],
	};
	const firstRepairFailure = await routerToolResult(failedRepairEdit);
	assert.match(firstRepairFailure.content.at(-1).text, /One correction remains/u);
	assert.deepEqual(activeTools, ["pideck_replace_lines", "run_tests"]);
	const secondRepairFailure = await routerToolResult(failedRepairEdit);
	assert.match(secondRepairFailure.content[0].text, /EDIT RETRY LIMIT REACHED/u);
	assert.deepEqual(activeTools, [], "repeated scoped edit failure retained a looping tool schema");
	await routerInput({ type: "input", text: repairPrompt, source: "rpc" });
	const repairTest = {
		type: "tool_call",
		toolName: "run_tests",
		toolCallId: "repair-test",
		input: { expr: "tests/test_counter.py" },
	};
	assert.equal(await routerToolCall(repairTest, { cwd: workspace }), undefined);
	assert.equal(repairTest.input.path, join(workspace, "tests", "test_counter.py"));
	assert.equal(
		repairTest.input.expr,
		undefined,
		"a model-supplied test path remained active as a -k expression",
	);
	const failedRepairTest = await routerToolResult({
		type: "tool_result",
		toolName: "run_tests",
		toolCallId: "repair-test-failed",
		input: repairTest.input,
		isError: false,
		content: [{ type: "text", text: "1 failed" }],
		details: { status: 1 },
	});
	assert.match(failedRepairTest.content.at(-1).text, /unavailable until a source edit/u);
	assert.deepEqual(
		activeTools,
		["read", "pideck_replace_lines"],
		"a failed scoped test could be repeated without a source edit",
	);
	await routerToolResult({
		type: "tool_result",
		toolName: "pideck_replace_lines",
		toolCallId: "repair-after-test",
		input: { path: join(workspace, "src", "counter.py") },
		isError: false,
		content: [{ type: "text", text: "edited" }],
	});
	assert.deepEqual(
		activeTools,
		["read", "pideck_replace_lines", "run_tests"],
		"a successful correction did not re-enable the exact test",
	);
	assert.equal(await routerToolCall(repairTest, { cwd: workspace }), undefined);
	const repairTestResult = await routerToolResult({
		type: "tool_result",
		toolName: "run_tests",
		toolCallId: "repair-test",
		input: repairTest.input,
		isError: false,
		content: [{ type: "text", text: "1 passed" }],
		details: { status: 0 },
	});
	assert.match(repairTestResult.content[0].text, /TEST PASSED/u);
	assert.deepEqual(activeTools, [], "passing scoped test retained further tools");
	assert.deepEqual(router.detectCapabilities("Какая погода в Москве?"), ["weather"]);
	assert.deepEqual(
		router.detectCapabilities("Поищи в сети погоду в Москве"),
		["web", "weather"],
	);
	assert.deepEqual(router.detectCapabilities("Прочитай https://example.com/report"), ["web"]);
	assert.equal(router.disablesTools("Ответь ровно OK. Не используй инструменты."), true);
	assert.equal(
		router.disablesTools("Маркер уже дан в этом сообщении: OK. Верни только его."),
		true,
	);
	assert.equal(
		router.disablesTools("Прочитай файл и не вызывай после ошибки другие инструменты."),
		false,
	);
	const codeNav = tools.get("code_nav");
	writeFileSync(join(workspace, "nav-target.ts"), "export function locateMe() { return 7; }\n");
	const codeNavInput = loaded.extensions[7].handlers.get("input")?.[0];
	assert.equal(typeof codeNavInput, "function", "code_nav has no explicit-scope input hook");
	await codeNavInput({
		type: "input",
		text: `В каталоге ${workspace} найди определение функции locateMe.`,
		source: "rpc",
	});
	const navigation = await codeNav.execute(
		"nav",
		{
			query: "функция locateMe",
			path: join(dirname(workspace), "hallucinated-AGENTS.md"),
		},
		undefined,
		undefined,
		{ cwd: workspace, hasUI: false, mode: "rpc" },
	);
	assert.match(navigation.content[0].text, /nav-target\.ts:1:/);
	assert.match(navigation.content[0].text, /search terms: .*locateMe/);
	const queryFallback = await codeNav.execute(
		"nav-fallback",
		{ query: "", path: "/" },
		undefined,
		undefined,
		{ cwd: workspace, hasUI: false, mode: "rpc" },
	);
	assert.match(queryFallback.content[0].text, /code_nav query: locateMe/);
	const codeNavSessionStart = loaded.extensions[7].handlers.get("session_start")?.[0];
	assert.equal(typeof codeNavSessionStart, "function", "code_nav has no scope reset");
	await codeNavSessionStart({ type: "session_start", reason: "new" });
	await assert.rejects(
		codeNav.execute(
			"escape",
			{ query: "secret", path: ".." },
			undefined,
			undefined,
			{ cwd: workspace, hasUI: false, mode: "rpc" },
		),
		/must stay inside/,
	);
	symlinkSync(join(packageDirectory, "package.json"), join(workspace, "nav-outside"));
	await assert.rejects(
		codeNav.execute(
			"symlink-escape",
			{ query: "name", path: "nav-outside" },
			undefined,
			undefined,
			{ cwd: workspace, hasUI: false, mode: "rpc" },
		),
		/must not follow a symlink outside/,
	);
	const webTools = await jiti.import(join(workspace, "pideck-web-tools.ts"));
	const relevant = webTools.relevantPageText(
		"Unrelated introduction about flowers.\n\nAdreno 740 supports the measured GPU path.\n\nUnrelated ending.",
		"Adreno 740 GPU",
	);
	assert.match(relevant, /Adreno 740/);
	assert.doesNotMatch(relevant, /flowers/);
	assert.deepEqual(
		router.routeInput(`${router.INTERNAL_RETRY_PREFIX}Закончи исходный ответ.`),
		{
			text: "Закончи исходный ответ.",
			capabilities: [],
			additive: true,
			transformed: true,
		},
		"an idle bridge retry reset the original turn's optional tools",
	);
	assert.deepEqual(
		router.routeInput("Новый обычный запрос"),
		{
			text: "Новый обычный запрос",
			capabilities: [],
			additive: false,
			transformed: false,
		},
		"a normal idle prompt did not reset to the compact core",
	);
	const promptExtension = await jiti.import(join(workspace, "pideck-system-prompt.ts"));
	const compactChatPrompt = promptExtension.composeManagedPrompt("chat", "FULL PI PROMPT", undefined);
	assert.match(compactChatPrompt, /Chat mode has no tools/);
	assert.doesNotMatch(compactChatPrompt, /FULL PI PROMPT/);
	assert.match(
		promptExtension.composeManagedPrompt(
			"chat",
			"FULL PI PROMPT",
			{ mode: "append", text: "CUSTOM RULE" },
		),
		/CUSTOM RULE$/,
	);
	assert.equal(
		promptExtension.composeManagedPrompt(
			"agent",
			"FULL PI PROMPT",
			{ mode: "replace", text: "ONLY CUSTOM" },
		),
		"ONLY CUSTOM",
	);

	// Anchored editing: read is stamped, an anchor applies, and a stale anchor is refused.
	const target = join(workspace, "counter.py");
	writeFileSync(
		target,
		"class Counter:\n    def __init__(self):\n        self.value = 0\n\n    def bump(self):\n        self.value += 2\n",
	);
	const hashline = toolResultHandlers[0];
	const annotated = await hashline({
		type: "tool_result",
		toolName: "read",
		toolCallId: "check",
		input: { path: target },
		isError: false,
		content: [{ type: "text", text: readFileSync(target, "utf8") }],
	});
	const rendered = annotated.content[0].text;
	assert.match(rendered, /^1:[0-9a-f]{2}\| class Counter:$/m, "read was not anchored");

	const buggy = rendered.split("\n").find((line) => line.includes("self.value += 2"));
	const anchor = buggy.split("|")[0];
	const context = { cwd: workspace, hasUI: false, mode: "rpc" };
	await tools.get("pideck_replace_lines").execute(
		"check",
		{ path: target, edits: [{ anchor, text: "self.value += 1" }] },
		undefined,
		undefined,
		context,
	);
	assert.match(
		readFileSync(target, "utf8"),
		/^        self\.value \+= 1$/m,
		"anchored edit did not inherit a missing Python indent",
	);
	const beforeWrongLevel = readFileSync(target, "utf8");
	const methodAnchor = rendered.split("\n").find((line) => line.includes("def bump")).split("|")[0];
	await assert.rejects(
		tools.get("pideck_replace_lines").execute(
			"wrong-python-level",
			{
				path: target,
				edits: [{ anchor: methodAnchor, text: "        self.value += 1" }],
			},
			undefined,
			undefined,
			context,
		),
		/ведущий отступ Python/iu,
		"a body statement replaced a Python method definition at another indent level",
	);
	assert.equal(
		readFileSync(target, "utf8"),
		beforeWrongLevel,
		"a refused Python indentation change modified the file",
	);

	await assert.rejects(
		tools.get("pideck_replace_lines").execute(
			"check",
			{ path: target, edits: [{ anchor, text: "        self.value += 99" }] },
			undefined,
			undefined,
			context,
		),
		/не совпала/,
		"a stale anchor was accepted",
	);

	// A small model may send only the arithmetic fragment instead of the complete indented
	// replacement line. Reject it before write: the syntax-note hook runs after a mutation and
	// therefore cannot make a broken workspace atomic on its own.
	const atomicTarget = join(workspace, "atomic.py");
	const atomicBefore = "def value():\n    return 1\n";
	writeFileSync(atomicTarget, atomicBefore);
	const atomicRead = await hashline({
		type: "tool_result",
		toolName: "read",
		toolCallId: "atomic-read",
		input: { path: atomicTarget },
		isError: false,
		content: [{ type: "text", text: atomicBefore }],
	});
	const atomicAnchor = atomicRead.content[0].text
		.split("\n")
		.find((line) => line.includes("return 1"))
		.split("|")[0];
	await assert.rejects(
		tools.get("pideck_replace_lines").execute(
			"atomic-invalid",
			{ path: atomicTarget, edits: [{ anchor: atomicAnchor, text: "+ 1" }] },
			undefined,
			undefined,
			context,
		),
		/Правка не сохранена.*целую строку/su,
		"a syntactically invalid Python fragment was written",
	);
	assert.equal(
		readFileSync(atomicTarget, "utf8"),
		atomicBefore,
		"a refused Python edit changed the original file",
	);

	// Observed on device: refused with only "read it again", the model gave up on the tool.
	// A refusal must hand back anchors it can retry with immediately.
	await assert.rejects(
		tools.get("pideck_replace_lines").execute(
			"invented",
			{ path: target, edits: [{ anchor: "2:zz".replace("zz", "00"), text: "x" }] },
			undefined,
			undefined,
			context,
		),
		(error) => {
			assert.match(error.message, /Действующие якоря/, "refusal carried no anchors");
			assert.match(error.message, /^\d+:[0-9a-f]{2}\| /m, "refusal listed no usable anchor");
			return true;
		},
		"an invented anchor was accepted",
	);

	// The anchors are only trustworthy if Pi's own read returns the file byte for byte.
	// Anything that reformatted content on the way out — tab expansion, trimming — would
	// make every anchor stale on the first edit, so this drives the real read tool rather
	// than assuming its output equals the file.
	const { createReadTool } = await import(
		pathToFileURL(join(packageDirectory, "dist", "index.js")).href
	);
	const tabbed = join(workspace, "tabbed.py");
	writeFileSync(tabbed, "def f():\n\tif True:\t# tab indented\n\t\treturn 1\n");
	const readTool = createReadTool(workspace);
	const readResult = await readTool.execute("read", { path: tabbed }, undefined, undefined);
	const readText = readResult.content
		.filter((part) => part.type === "text")
		.map((part) => part.text)
		.join("\n");
	const stamped = await hashline({
		type: "tool_result",
		toolName: "read",
		toolCallId: "real-read",
		input: { path: tabbed },
		isError: false,
		content: [{ type: "text", text: readText }],
	});
	const stampedLines = stamped.content[0].text.split("\n");
	const tabAnchor = stampedLines.find((line) => line.includes("tab indented")).split("|")[0];
	await tools.get("pideck_replace_lines").execute(
		"real-read",
		{ path: tabbed, edits: [{ anchor: tabAnchor, text: "\tif False:" }] },
		undefined,
		undefined,
		context,
	);
	assert.match(
		readFileSync(tabbed, "utf8"),
		/\tif False:/,
		"an anchor taken from Pi's own read did not verify against the file",
	);

	// Observed on device: the model reached for an anchored edit before creating the file.
	// The raw ENOENT it got back named no next step, so the error now has to.
	await assert.rejects(
		tools.get("pideck_replace_lines").execute(
			"missing",
			{ path: "not-created-yet.py", edits: [{ anchor: "1:aa", text: "x" }] },
			undefined,
			undefined,
			context,
		),
		/Сначала создай его/,
		"a missing file did not tell the model what to do next",
	);

	// read's trailing truncation note is not file content and must not be anchored, or the
	// model is handed an address for a line past the end of the file.
	const noted = await hashline({
		type: "tool_result",
		toolName: "read",
		toolCallId: "noted",
		input: { path: target, offset: 1 },
		isError: false,
		content: [{
			type: "text",
			text: "alpha\nbeta\n\n[Showing lines 1-2 of 900. Use offset=3 to continue.]",
		}],
	});
	const notedLines = noted.content[0].text.split("\n");
	assert.match(notedLines[0], /^1:[0-9a-f]{2}\| alpha$/);
	assert.match(notedLines[1], /^2:[0-9a-f]{2}\| beta$/);
	assert.equal(notedLines[2], "");
	assert.equal(notedLines[3], "[Showing lines 1-2 of 900. Use offset=3 to continue.]");

	// Post-write syntax check: a broken file reports its error inside the same tool result,
	// a clean file costs nothing, and a missing checker fails open instead of blocking.
	const runToolResult = async (event) => {
		let content = event.content;
		for (const handler of toolResultHandlers) {
			const result = await handler({ ...event, content });
			if (result?.content) content = result.content;
		}
		return content
			.filter((part) => part.type === "text")
			.map((part) => part.text)
			.join("\n");
	};
	const mutationEvent = (toolName, path, text) => ({
		type: "tool_result",
		toolName,
		toolCallId: `syntax-${toolName}`,
		input: { path },
		isError: false,
		content: [{ type: "text", text }],
	});

	const brokenPy = join(workspace, "broken.py");
	writeFileSync(brokenPy, "def broken(:\n    pass\n");
	const brokenPyResult = await runToolResult(mutationEvent("write", brokenPy, "Wrote broken.py"));
	assert.match(brokenPyResult, /синтаксис/i, "a broken .py write carried no syntax note");
	assert.match(brokenPyResult, /line 1/, "the note does not name the failing line");
	assert.ok(
		!existsSync(join(workspace, "__pycache__")),
		"the python check left __pycache__ in the workspace",
	);

	const cleanPy = join(workspace, "clean.py");
	writeFileSync(cleanPy, "def ok():\n    return 1\n");
	assert.equal(
		await runToolResult(mutationEvent("pideck_replace_lines", cleanPy, "OK")),
		"OK",
		"a clean write must not grow the tool result",
	);

	const brokenMjs = join(workspace, "broken.mjs");
	writeFileSync(brokenMjs, "export default (\n");
	assert.match(
		await runToolResult(mutationEvent("edit", brokenMjs, "Edited broken.mjs")),
		/синтаксис/i,
		"a broken .mjs edit carried no syntax note",
	);

	const brokenJson = join(workspace, "broken.json");
	writeFileSync(brokenJson, "{ nope\n");
	assert.match(
		await runToolResult(mutationEvent("pideck_write", brokenJson, "Wrote broken.json")),
		/синтаксис/i,
		"a broken .json write carried no syntax note",
	);

	const plainText = join(workspace, "notes.txt");
	writeFileSync(plainText, "def broken(:\n");
	assert.equal(
		await runToolResult(mutationEvent("write", plainText, "OK")),
		"OK",
		"an unchecked file type must pass through untouched",
	);

	assert.equal(
		await runToolResult({ ...mutationEvent("write", brokenPy, "failed"), isError: true }),
		"failed",
		"an already-failed tool result must not be annotated",
	);

	// An unreadable .json target is the checker's problem, not a syntax error: the note
	// must not surface EISDIR/ENOENT as if the just-saved file were broken.
	const unreadableJson = join(workspace, "dir.json");
	mkdirSync(unreadableJson);
	assert.equal(
		await runToolResult(mutationEvent("write", unreadableJson, "OK")),
		"OK",
		"an unreadable .json path must fail open, not annotate",
	);

	process.env.PIDECK_SYNTAX_CHECK_PYTHON = join(workspace, "no-such-python");
	try {
		assert.equal(
			await runToolResult(mutationEvent("write", brokenPy, "OK")),
			"OK",
			"a missing checker must fail open, not annotate or throw",
		);
	} finally {
		delete process.env.PIDECK_SYNTAX_CHECK_PYTHON;
	}

	// run_tests: one bounded turn carries the verdict and the first failure verbatim,
	// leaves no cache artifacts in a diff-scored workspace, and refuses to escape it.
	const runTests = tools.get("run_tests");
	const passingWorkspace = join(workspace, "tests-pass");
	mkdirSync(passingWorkspace);
	writeFileSync(join(passingWorkspace, "test_ok.py"), "def test_ok():\n    assert True\n");
	const passing = await runTests.execute(
		"tests-pass",
		{},
		undefined,
		undefined,
		{ cwd: passingWorkspace, hasUI: false, mode: "rpc" },
	);
	const passingText = passing.content[0].text;
	assert.match(passingText, /1 passed/, "a passing run does not carry pytest's own verdict");
	assert.ok(passingText.length < 400, `a passing verdict should be one short block: ${passingText.length}`);
	assert.ok(
		!existsSync(join(passingWorkspace, ".pytest_cache"))
			&& !existsSync(join(passingWorkspace, "__pycache__")),
		"run_tests left cache artifacts in the workspace",
	);

	const failingWorkspace = join(workspace, "tests-fail");
	mkdirSync(failingWorkspace);
	writeFileSync(
		join(failingWorkspace, "test_math.py"),
		"def test_totals():\n    assert 1 + 1 == 3\n\ndef test_never_reached():\n    assert True\n",
	);
	const failing = await runTests.execute(
		"tests-fail",
		{},
		undefined,
		undefined,
		{ cwd: failingWorkspace, hasUI: false, mode: "rpc" },
	);
	const failingText = failing.content[0].text;
	assert.match(failingText, /1 failed/, "a failing run does not carry pytest's own verdict");
	assert.match(failingText, /test_totals/, "the first failure is not named");
	assert.match(failingText, /assert 1 \+ 1 == 3/, "the failing assertion is not shown verbatim");
	assert.ok(
		Buffer.byteLength(failingText, "utf8") <= 4 * 1024 + 256,
		`run_tests output is not bounded: ${Buffer.byteLength(failingText, "utf8")} bytes`,
	);

	await assert.rejects(
		runTests.execute(
			"tests-escape",
			{ path: "../outside" },
			undefined,
			undefined,
			{ cwd: failingWorkspace, hasUI: false, mode: "rpc" },
		),
		/workspace|рабоч/i,
		"a path outside the workspace was accepted",
	);

	// A hung suite must be reported as a timeout with the captured output intact — not
	// misclassified as a missing pytest by the spawn 'error' event racing 'close'.
	const hangingWorkspace = join(workspace, "tests-hang");
	mkdirSync(hangingWorkspace);
	writeFileSync(
		join(hangingWorkspace, "test_hang.py"),
		"import time\n\ndef test_hang():\n    time.sleep(60)\n",
	);
	process.env.PIDECK_RUN_TESTS_TIMEOUT_MS = "2000";
	try {
		const hung = await runTests.execute(
			"tests-hang",
			{},
			undefined,
			undefined,
			{ cwd: hangingWorkspace, hasUI: false, mode: "rpc" },
		);
		assert.match(
			hung.content[0].text,
			/не завершились/,
			"a hung suite must be reported as a timeout",
		);
	} finally {
		delete process.env.PIDECK_RUN_TESTS_TIMEOUT_MS;
	}

	process.env.PIDECK_RUN_TESTS_PYTHON = join(workspace, "no-such-python");
	try {
		const unavailable = await runTests.execute(
			"tests-no-runner",
			{},
			undefined,
			undefined,
			{ cwd: passingWorkspace, hasUI: false, mode: "rpc" },
		);
		assert.match(
			unavailable.content[0].text,
			/runner недоступен/i,
			"a missing Python runner was not reported honestly",
		);
	} finally {
		delete process.env.PIDECK_RUN_TESTS_PYTHON;
	}

	// Termux intentionally has Python but no pytest package in the base runtime. A bounded
	// zero-argument test must still be executable without silently pretending pytest exists.
	const noSitePython = join(workspace, "python-no-site");
	writeFileSync(noSitePython, '#!/bin/sh\nexec python3 -S "$@"\n');
	chmodSync(noSitePython, 0o700);
	process.env.PIDECK_RUN_TESTS_PYTHON = noSitePython;
	try {
		const fallback = await runTests.execute(
			"tests-zero-fixture",
			{ path: "test_ok.py", expr: "pytest -k test_ok" },
			undefined,
			undefined,
			{ cwd: passingWorkspace, hasUI: false, mode: "rpc" },
		);
		assert.match(fallback.content[0].text, /1 passed/);
		assert.match(fallback.content[0].text, /offline zero-fixture fallback/);
		assert.equal(fallback.details.runner, "zero-fixture");
		assert.equal(fallback.details.status, 0);
		assert.equal(fallback.details.expr, "test_ok");
	} finally {
		delete process.env.PIDECK_RUN_TESTS_PYTHON;
	}

	// The context guard must still be able to shrink a large result after annotation.
	const long = Array.from({ length: 900 }, (_, index) => `line ${index}`).join("\n");
	let content = [{ type: "text", text: long }];
	for (const handler of toolResultHandlers) {
		const result = await handler({
			type: "tool_result",
			toolName: "bash",
			toolCallId: "big",
			input: {},
			isError: false,
			content,
		});
		if (result?.content) content = result.content;
	}
	assert.ok(
		content[0].text.split("\n").length < 900,
		"context guard no longer bounds a large tool result",
	);

	console.log(`OK: ${EXTENSIONS.length} extensions, ${tools.size} tools, anchored editing verified`);
} finally {
	rmSync(workspace, { recursive: true, force: true });
}

// Pi's loader may leave internal handles alive after all assertions have completed. This is a
// one-shot verifier, so exit only after the cleanup above; assertion failures never reach here.
process.exit(0);
