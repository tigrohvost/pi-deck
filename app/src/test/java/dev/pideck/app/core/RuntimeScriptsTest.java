package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class RuntimeScriptsTest {
    @Test
    public void generatedScriptsPassBashSyntaxCheck() throws Exception {
        List<String> scripts = List.of(
                RuntimeScripts.probe(),
                RuntimeScripts.installCore(),
                RuntimeScripts.updateAgent(),
                RuntimeScripts.startServer(ModelCatalog.EDGE, 6),
                RuntimeScripts.stopServer(),
                RuntimeScripts.newSession(),
                RuntimeScripts.abortAgent()
        );
        for (String script : scripts) {
            assertBashSyntax(script);
        }
    }

    @Test
    public void agentPromptRemainsOneLiteralArgument() {
        String hostileLookingPrompt = "write x; rm -rf / $(echo nope) ' \"\nthen test it";
        String[] arguments = RuntimeScripts.agentArguments(
                ModelCatalog.EDGE, true, hostileLookingPrompt
        );
        assertEquals(hostileLookingPrompt, arguments[arguments.length - 1]);
        assertTrue(List.of(arguments).contains("--continue"));
        assertTrue(List.of(arguments).contains("--approve"));
        assertTrue(List.of(arguments).contains("read,bash,edit,write,grep,find,ls"));
    }

    @Test
    public void serverStaysOnLoopbackAndDisablesReasoning() {
        String script = RuntimeScripts.startServer(ModelCatalog.CORE, 32);
        assertTrue(script.contains("--host 127.0.0.1"));
        assertTrue(script.contains("--reasoning off"));
        assertTrue(script.contains("-t 8"));
        assertTrue(script.contains("Qwen_Qwen3.5-4B-Q4_K_M.gguf"));
    }

    @Test
    public void generatedPiModelConfigIsValidJson() throws Exception {
        String script = RuntimeScripts.installCore();
        String begin = "cat > \"$HOME/.pideck/pi/models.json\" <<'PIDECK_MODELS'\n";
        String end = "\nPIDECK_MODELS\n";
        int from = script.indexOf(begin) + begin.length();
        int to = script.indexOf(end, from);
        assertTrue(from >= begin.length());
        assertTrue(to > from);

        JSONObject config = new JSONObject(script.substring(from, to));
        JSONObject provider = config.getJSONObject("providers").getJSONObject("pideck");
        assertEquals("http://127.0.0.1:8080/v1", provider.getString("baseUrl"));
        assertEquals(4, provider.getJSONArray("models").length());
        assertTrue(script.contains("@earendil-works/pi-coding-agent@0.82.1"));
    }

    @Test
    public void newSessionArchivesSessionsStoredInPerDirectorySubfolders() throws Exception {
        // Pi writes sessions/<encoded-cwd>/<id>.jsonl, never a flat *.jsonl in the session dir.
        Path home = Files.createTempDirectory("pideck-home");
        Path sessions = home.resolve(".pideck/sessions/-data-data-com-termux-files-home-pideck");
        Files.createDirectories(sessions);
        Files.write(sessions.resolve("abc.jsonl"), "{}\n".getBytes(StandardCharsets.UTF_8));

        String stdout = runScript(RuntimeScripts.newSession(), home);

        assertTrue(stdout.contains("PIDECK_NEW_SESSION"));
        assertFalse(Files.exists(sessions.resolve("abc.jsonl")));
        try (Stream<Path> archived = Files.walk(home.resolve(".pideck/session-archive"))) {
            assertTrue(archived.anyMatch(path -> path.getFileName().toString().equals("abc.jsonl")));
        }
    }

    @Test
    public void newSessionLeavesNoEmptyArchiveWhenThereIsNothingToMove() throws Exception {
        Path home = Files.createTempDirectory("pideck-home");
        Files.createDirectories(home.resolve(".pideck/sessions"));

        runScript(RuntimeScripts.newSession(), home);

        Path archive = home.resolve(".pideck/session-archive");
        try (Stream<Path> children = Files.list(archive)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    public void coreInstallNeverStopsOnAConffilePrompt() {
        String script = RuntimeScripts.installCore();
        assertTrue(script.contains("DEBIAN_FRONTEND=noninteractive"));
        assertTrue(script.contains("-o Dpkg::Options::=--force-confold"));
    }

    @Test
    public void abortTargetsThePiProcessOnly() {
        assertTrue(RuntimeScripts.abortAgent()
                .contains("pkill -INT -f '/data/data/com.termux/files/usr/bin/pi'"));
    }

    @Test
    public void termuxSuccessCodeMinusOneIsAccepted() {
        assertTrue(new CommandResult("probe:1", "ok", "", 0, -1, "").isSuccess());
        assertTrue(new CommandResult("probe:2", "ok", "", 0, 0, "").isSuccess());
    }

    private static String runScript(String script, Path home) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", "-s");
        builder.environment().put("HOME", home.toString());
        builder.directory(home.toFile());
        Process process = builder.start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(stderr, 0, process.waitFor());
        return stdout;
    }

    private static void assertBashSyntax(String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-n").start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        int exit = process.waitFor();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(stderr, 0, exit);
    }
}
