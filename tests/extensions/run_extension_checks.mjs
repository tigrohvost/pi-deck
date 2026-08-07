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
	cpSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
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
	"pideck-tool-router.ts",
	"pideck-permission-gate.ts",
];
const EXPECTED_TOOLS = [
	"pideck_replace_lines",
	"run_tests",
	"web_search",
	"web_fetch",
	"weather",
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
	assert.deepEqual(router.detectCapabilities("Какая погода в Москве?"), ["weather"]);
	assert.deepEqual(
		router.detectCapabilities("Поищи в сети погоду в Москве"),
		["web", "weather"],
	);
	assert.deepEqual(router.detectCapabilities("Прочитай https://example.com/report"), ["web"]);
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
		{ path: target, edits: [{ anchor, text: "        self.value += 1" }] },
		undefined,
		undefined,
		context,
	);
	assert.match(readFileSync(target, "utf8"), /self\.value \+= 1/, "anchored edit did not apply");

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
			/bash/,
			"a missing pytest must name the bash fallback instead of crashing",
		);
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
