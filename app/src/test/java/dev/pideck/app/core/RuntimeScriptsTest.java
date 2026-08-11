package dev.pideck.app.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuntimeScriptsTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void generatedBootstrapScriptsPassBashSyntaxCheck() throws Exception {
        assertBashSyntax(RuntimeScripts.probe());
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("models-v2.json", "{}".getBytes(StandardCharsets.UTF_8));
        contents.put("compatibility.json", "{}".getBytes(StandardCharsets.UTF_8));
        contents.put("runtime/AGENTS.default.md", "default\n".getBytes(StandardCharsets.UTF_8));
        contents.put("runtime/pideck_runtime/__init__.py", new byte[0]);
        assertBashSyntax(RuntimeAssetBundle.buildFromContents(contents, true));
        assertBashSyntax(RuntimeAssetBundle.buildFromContents(contents, false));
    }

    @Test
    public void promptCanOnlyTravelInStdinJsonNotArgv() {
        String marker = "PIDECK_SECRET_MARKER_012345";
        List<String> arguments = List.of(RuntimeScripts.runtimeArguments("agent-once"));
        assertFalse(arguments.contains(marker));
        assertFalse(String.join(" ", arguments).contains(marker));
        assertTrue(arguments.contains("pideck_runtime.launcher"));
        assertTrue(arguments.contains("agent-once"));
    }

    @Test
    public void runBashLoadsTermuxExecBeforeStartingTheShell() {
        assertArrayEquals(
                new String[]{
                        "LD_PRELOAD=" + TermuxBridge.TERMUX_EXEC,
                        TermuxBridge.PREFIX + "/bin/bash",
                        "-s"
                },
                TermuxBridge.bashArguments()
        );
    }

    @Test
    public void runtimeArgumentsAreFixedAndRejectShellSyntax() {
        List<String> arguments = List.of(RuntimeScripts.runtimeArguments("server-start"));
        assertTrue(arguments.contains("PYTHONPATH=" + TermuxBridge.HOME + "/.pideck/runtime"));
        assertTrue(arguments.contains("PI_OFFLINE=1"));
        try {
            RuntimeScripts.runtimeArguments("server-start; id");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Unsafe command was accepted");
    }

    @Test
    public void probeReadinessUsesStrictJsonFields() {
        String ready = """
                PIDECK_LINK_OK
                {"schemaVersion":1,"ok":true,"state":"READY","layoutReady":true,
                 "runtimeContractVersion":47,
                 "versionsCompatible":true,"piVersion":"0.82.1","nodeVersion":"v24.4.1",
                 "pythonVersion":"3.13","llamaVersion":"b10092"}
                """.replace("\n ", "");
        assertTrue(RuntimeScripts.isLinkProbeOutput(ready));
        assertTrue(RuntimeScripts.isReadyProbeOutput(ready));
        assertFalse(RuntimeScripts.isReadyProbeOutput(
                ready.replace("\"state\":\"READY\"", "\"state\":\"NOT_READY\"")
        ));
        assertFalse(RuntimeScripts.isReadyProbeOutput(
                ready.replace("\"runtimeContractVersion\":47", "\"runtimeContractVersion\":46")
        ));
        assertFalse(RuntimeScripts.isReadyProbeOutput(
                ready.replace("\"runtimeContractVersion\":47,", "")
        ));
        assertFalse(RuntimeScripts.isReadyProbeOutput(
                "noise {\"schemaVersion\":1,\"ok\":true,\"state\":\"READY\"}"
        ));
    }

    @Test
    public void productionRuntimeContainsNoBroadKillOrFloatingDependency() throws Exception {
        String all = "";
        for (Path path : runtimeFiles()) {
            all += new String(Files.readAllBytes(path), StandardCharsets.UTF_8) + "\n";
        }
        assertFalse(all.contains("pkill"));
        assertFalse(all.contains("@latest"));
        assertFalse(all.contains("storage/downloads/PiDeck/models"));
        assertTrue(all.contains("terminate_exact"));
        assertTrue(all.contains("start_new_session=True"));
    }

    @Test
    public void installerPreservesAgentsAndPinsNpmIntegrity() {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("models-v2.json", "{}".getBytes(StandardCharsets.UTF_8));
        contents.put("compatibility.json", "{}".getBytes(StandardCharsets.UTF_8));
        contents.put("runtime/AGENTS.default.md", "default\n".getBytes(StandardCharsets.UTF_8));
        contents.put(
                "runtime/pideck-system-prompt.ts",
                "export default function () {}\n".getBytes(StandardCharsets.UTF_8));
        String script = RuntimeAssetBundle.buildFromContents(contents, true);
        assertTrue(script.contains("if [ ! -e \"$BASE/workspace/AGENTS.md\" ]"));
        assertTrue(script.contains("PIDECK_AGENTS_PRESERVED"));
        assertTrue(script.contains("@earendil-works/pi-coding-agent"));
        assertTrue(script.contains("0.82.1"));
        assertTrue(script.contains("pideck-system-prompt.ts"));
        assertTrue(script.contains("PI_INTEGRITY="));
        assertFalse(script.contains("@latest"));
        assertFalse(script.contains("pkg install -y llama-cpp"));
        assertFalse(script.contains("python llama-cpp"));
    }

    @Test
    public void productionInstallerFitsBinderAndUsesCompressedAssets() throws Exception {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (String relative : List.of(
                "AGENTS.default.md",
                "pideck-agent-base-prompt.md",
                "pideck-benchmark-fixture-v2.json",
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
                "pideck_runtime/__init__.py",
                "pideck_runtime/common.py",
                "pideck_runtime/model_store.py",
                "pideck_runtime/server_supervisor.py",
                "pideck_runtime/bridge.py",
                "pideck_runtime/launcher.py"
        )) {
            Path path = runtimeRoot().resolve(relative);
            contents.put("runtime/" + relative, Files.readAllBytes(path));
        }
        Path assets = runtimeRoot().getParent();
        contents.put("models-v2.json", Files.readAllBytes(assets.resolve("models-v2.json")));
        contents.put(
                "compatibility.json",
                Files.readAllBytes(assets.resolve("compatibility.json"))
        );

        String script = RuntimeAssetBundle.buildFromContents(contents, true);
        assertTrue(script.contains("gzip.decompress"));
        assertTrue(
                script.getBytes(StandardCharsets.UTF_8).length
                        < RuntimeAssetBundle.MAX_INSTALL_SCRIPT_BYTES
        );
        assertBashSyntax(script);
    }

    @Test
    public void installingTwicePreservesUserAgentsByteForByte() throws Exception {
        Path base = temporary.newFolder("pideck-home").toPath();
        Path runtime = Files.createDirectories(base.resolve("runtime"));
        Path workspace = Files.createDirectories(base.resolve("workspace"));
        Files.write(
                runtime.resolve("AGENTS.default.md"),
                "template-v1\n".getBytes(StandardCharsets.UTF_8)
        );
        runWorkspaceInstructions(base);
        assertEquals(
                "template-v1\n",
                new String(
                        Files.readAllBytes(workspace.resolve("AGENTS.md")),
                        StandardCharsets.UTF_8
                )
        );

        byte[] custom = "user-owned\u0000bytes\n".getBytes(StandardCharsets.UTF_8);
        Files.write(workspace.resolve("AGENTS.md"), custom);
        Files.write(
                runtime.resolve("AGENTS.default.md"),
                "template-v2\n".getBytes(StandardCharsets.UTF_8)
        );
        runWorkspaceInstructions(base);

        assertArrayEquals(custom, Files.readAllBytes(workspace.resolve("AGENTS.md")));
        assertEquals(
                "template-v2\n",
                new String(
                        Files.readAllBytes(workspace.resolve("AGENTS.default.md")),
                        StandardCharsets.UTF_8
                )
        );
    }

    @Test
    public void termuxSuccessCodeMinusOneIsAccepted() {
        assertTrue(new CommandResult(
                OperationId.create(), OperationKind.PROBE_RUNTIME, "ok", "", 0, -1, ""
        ).isSuccess());
        assertTrue(new CommandResult(
                OperationId.create(), OperationKind.PROBE_RUNTIME, "ok", "", 0, 0, ""
        ).isSuccess());
    }

    private static List<Path> runtimeFiles() throws IOException {
        Path root = runtimeRoot();
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * The installer copies a hardcoded list. A Pi extension that exists in the assets but is
     * missing from that list never reaches the phone, and the bridge then refuses to start
     * with a missing-extension error. Comparing against the directory catches the omission
     * instead of relying on someone remembering to edit two places.
     */
    @Test
    public void everyBundledExtensionIsAlsoInstalled() throws Exception {
        String script = RuntimeAssetBundle.buildFromContents(
                Map.of("runtime/pideck-local-cache.ts", new byte[]{10}), true
        );
        assertFalse(script.isEmpty());
        try (var paths = Files.list(runtimeRoot())) {
            List<String> extensions = paths
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".ts"))
                    .sorted()
                    .toList();
            assertFalse(extensions.isEmpty());
            for (String name : extensions) {
                assertTrue(
                        "RuntimeAssetBundle does not install " + name,
                        RuntimeAssetBundle.installedAssets().contains("runtime/" + name)
                );
            }
        }
    }

    private static Path runtimeRoot() {
        return Files.exists(Path.of("src/main/assets/runtime"))
                ? Path.of("src/main/assets/runtime")
                : Path.of("app/src/main/assets/runtime");
    }

    private static void assertBashSyntax(String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-n").start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        int exit = process.waitFor();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(stderr, 0, exit);
    }

    private static void runWorkspaceInstructions(Path base)
            throws IOException, InterruptedException {
        String script = "set -eu\nBASE=\"$1\"\nRUNTIME=\"$BASE/runtime\"\n"
                + RuntimeAssetBundle.workspaceInstructionsScript();
        Process process = new ProcessBuilder(
                "bash", "-s", base.toString()
        ).start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        int exit = process.waitFor();
        String stderr = new String(
                process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8
        );
        assertEquals(stderr, 0, exit);
    }
}
