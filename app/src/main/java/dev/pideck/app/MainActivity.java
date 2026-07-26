package dev.pideck.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import dev.pideck.app.core.CommandEvents;
import dev.pideck.app.core.CommandResult;
import dev.pideck.app.core.DeckPreferences;
import dev.pideck.app.core.ModelCatalog;
import dev.pideck.app.core.ModelDownloadManager;
import dev.pideck.app.core.ModelSpec;
import dev.pideck.app.core.PiJsonOutput;
import dev.pideck.app.core.RuntimeScripts;
import dev.pideck.app.core.TermuxBridge;
import dev.pideck.app.ui.ConsoleEntry;
import dev.pideck.app.ui.DeckView;
import dev.pideck.app.ui.Palette;

public final class MainActivity extends Activity implements DeckView.Listener, CommandEvents.Listener {
    private static final int REQUEST_RUN_COMMAND = 41;
    private static final String HANDSHAKE_COMMAND =
            "mkdir -p ~/.termux && " +
            "(grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || " +
            "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties) && " +
            "termux-reload-settings && termux-setup-storage";
    private static final Pattern ANSI = Pattern.compile(
            "(?:\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))|(?:\\u001B\\[[0-?]*[ -/]*[@-~])"
    );

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DeckView deck;
    private Palette palette;
    private DeckPreferences prefs;
    private TermuxBridge termux;
    private ModelDownloadManager modelDownloads;
    private ModelSpec selectedModel;
    private long totalRam;
    private long freeStorage;
    private int cpuThreads;

    private boolean linkConfirmed;
    private boolean serverReady;
    private boolean serverHealthInFlight;
    private boolean busy;
    private boolean verifying;
    private int verificationPercent;
    private String verificationFault = "";
    private String lastPrompt = "";
    private boolean retriedWithoutSession;
    private Dialog modelsDialog;
    private LinearLayout modelRows;
    private Dialog coreDialog;
    private Runnable watchdog;
    private String watchdogKind = "";
    private int heartbeatTick;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            // Capacity probing hits StatFs and ActivityManager, so it does not need every tick.
            if (heartbeatTick % 4 == 0) updateCapacity();
            heartbeatTick++;
            refreshUi();
            checkServerHealth();
            if (modelsDialog != null && modelsDialog.isShowing()) renderModelRows();
            main.postDelayed(this, heartbeatDelay());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new DeckPreferences(this);
        palette = Palette.forId(prefs.colorScheme());

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        termux = new TermuxBridge(this);
        modelDownloads = new ModelDownloadManager(this, prefs);
        updateCapacity();
        String savedModel = prefs.selectedModelId();
        selectedModel = savedModel == null
                ? ModelCatalog.recommend(totalRam, freeStorage)
                : ModelCatalog.byId(savedModel);
        prefs.setSelectedModelId(selectedModel.id);
        linkConfirmed = prefs.isCoreReady();

        deck = new DeckView(this, this, palette);
        setContentView(deck);
        deck.setEntries(prefs.loadTranscript());
        deck.setEngineLine(deviceLine());
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CommandEvents.addListener(this);
        main.post(heartbeat);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CommandResult pending = prefs.consumePendingResult();
        if (pending != null) handleCommandResult(pending);
        refreshUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CommandEvents.removeListener(this);
        main.removeCallbacks(heartbeat);
        prefs.saveTranscript(deck.entries());
    }

    @Override
    protected void onDestroy() {
        if (modelsDialog != null) modelsDialog.dismiss();
        if (coreDialog != null) coreDialog.dismiss();
        cancelWatchdog();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RUN_COMMAND) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toast("Транспорт Termux разрешён");
            refreshUi();
        } else {
            append(ConsoleEntry.Channel.ERROR,
                    "Android не выдал RUN_COMMAND. Откройте сведения о PI//DECK → разрешения → дополнительные разрешения.");
            refreshUi();
        }
    }

    @Override
    public void onSend(String prompt) {
        if (busy) {
            toast("Pi уже выполняет задачу");
            return;
        }
        if (!canRunAgent()) {
            refreshUi();
            toast("Сначала завершите boot sequence");
            return;
        }

        lastPrompt = prompt;
        retriedWithoutSession = false;
        append(ConsoleEntry.Channel.USER, prompt);
        setBusy(true, "PI AGENT // RUNNING");
        try {
            termux.run(
                    "agent",
                    TermuxBridge.PREFIX + "/bin/env",
                    RuntimeScripts.agentArguments(selectedModel, prefs.hasSession(), prompt),
                    null,
                    TermuxBridge.WORKSPACE
            );
            armWatchdog("agent");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    @Override
    public void onModels() {
        showModelsDialog();
    }

    @Override
    public void onCore() {
        showCoreDialog();
    }

    @Override
    public void onTermux() {
        if (termux.isInstalled()) termux.openTermux();
        else termux.openTermuxPage();
    }

    @Override
    public void onClear() {
        deck.clearEntries();
        prefs.saveTranscript(deck.entries());
    }

    @Override
    public void onCommandResult(CommandResult result) {
        runOnUiThread(() -> {
            prefs.consumePendingResult();
            handleCommandResult(result);
        });
    }

    private void handleCommandResult(CommandResult result) {
        String kind = result.kind();
        if (kind.equals(watchdogKind)) cancelWatchdog();
        switch (kind) {
            case "probe" -> {
                setBusy(false, null);
                if (result.isSuccess() && result.stdout.contains("PIDECK_LINK_OK")) {
                    linkConfirmed = true;
                    boolean runtimeFound = result.stdout.contains("PI=")
                            && result.stdout.contains("LLAMA=ready")
                            && result.stdout.contains("PYTHON=");
                    if (runtimeFound) prefs.setCoreReady(true);
                    append(ConsoleEntry.Channel.TOOL,
                            "Termux bridge online.\n" + clean(result.stdout).trim());
                } else {
                    linkConfirmed = false;
                    append(ConsoleEntry.Channel.ERROR,
                            "Termux пока не принимает команды.\n" + result.usefulError()
                                    + "\n\nВыполните строку из шага LINK вручную в Termux.");
                }
            }
            case "install" -> {
                setBusy(false, null);
                if (result.isSuccess() && result.stdout.contains("PIDECK_CORE_READY")) {
                    prefs.setCoreReady(true);
                    linkConfirmed = true;
                    append(ConsoleEntry.Channel.SYSTEM,
                            "Pi runtime развёрнут.\n" + tail(clean(result.stdout), 9));
                } else {
                    prefs.setCoreReady(false);
                    append(ConsoleEntry.Channel.ERROR,
                            "Установка ядра не завершилась.\n" + result.usefulError()
                                    + "\n\nМожно повторить: пакетный менеджер Termux продолжит с места остановки.");
                }
            }
            case "start" -> {
                setBusy(false, null);
                if (result.isSuccess()) {
                    append(ConsoleEntry.Channel.TOOL, clean(result.stdout).trim());
                    main.postDelayed(this::checkServerHealth, 900L);
                } else {
                    serverReady = false;
                    append(ConsoleEntry.Channel.ERROR,
                            "LLM-ядро не запустилось.\n" + result.usefulError());
                }
            }
            case "stop" -> {
                setBusy(false, null);
                serverReady = false;
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess() ? "Локальное LLM-ядро остановлено." : result.usefulError());
            }
            case "update" -> {
                setBusy(false, null);
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess()
                                ? "Pi обновлён.\n" + tail(clean(result.stdout), 5)
                                : "Обновление Pi не завершилось.\n" + result.usefulError());
            }
            case "newsession" -> {
                setBusy(false, null);
                if (result.isSuccess()) {
                    prefs.setHasSession(false);
                    append(ConsoleEntry.Channel.SYSTEM,
                            "Открыта новая ветка. Предыдущая сессия перемещена в ~/.pideck/session-archive.");
                } else {
                    append(ConsoleEntry.Channel.ERROR, result.usefulError());
                }
            }
            case "abort" -> {
                setBusy(false, null);
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess()
                                ? "Сигнал остановки отправлен текущему Pi-процессу."
                                : result.usefulError());
            }
            case "agent" -> handleAgentResult(result);
            default -> {
                setBusy(false, null);
                if (!result.isSuccess()) append(ConsoleEntry.Channel.ERROR, result.usefulError());
            }
        }
        refreshUi();
        if (coreDialog != null && coreDialog.isShowing()) {
            coreDialog.dismiss();
            coreDialog = null;
        }
    }

    private void handleAgentResult(CommandResult result) {
        if (!result.isSuccess()
                && prefs.hasSession()
                && !retriedWithoutSession
                && (result.stderr.contains("session") || result.stdout.contains("session"))) {
            retriedWithoutSession = true;
            prefs.setHasSession(false);
            try {
                termux.run(
                        "agent",
                        TermuxBridge.PREFIX + "/bin/env",
                        RuntimeScripts.agentArguments(selectedModel, false, lastPrompt),
                        null,
                        TermuxBridge.WORKSPACE
                );
                armWatchdog("agent");
                return;
            } catch (RuntimeException ignored) {
            }
        }

        setBusy(false, null);
        if (result.isSuccess()) {
            prefs.setHasSession(true);
            PiJsonOutput.Parsed parsed = PiJsonOutput.parse(result.stdout);
            for (PiJsonOutput.Trace trace : parsed.traces) {
                append(
                        trace.error ? ConsoleEntry.Channel.ERROR : ConsoleEntry.Channel.TOOL,
                        trace.text
                );
            }
            String answer = parsed.answer;
            if (answer.isBlank() && !parsed.recognized) answer = clean(result.stdout).trim();
            if (answer.isBlank()) answer = clean(result.stderr).trim();
            if (answer.isBlank()) answer = "Задача завершена без текстового ответа.";
            append(ConsoleEntry.Channel.AGENT, answer);
        } else {
            append(ConsoleEntry.Channel.ERROR,
                    "Pi прервал задачу.\n" + clean(result.usefulError()));
        }
    }

    private void refreshUi() {
        boolean installed = termux.isInstalled();
        boolean permission = termux.hasRunPermission();
        boolean core = prefs.isCoreReady();
        ModelDownloadManager.State modelState = modelDownloads.state(selectedModel);
        boolean downloaded = modelState.phase == ModelDownloadManager.Phase.COMPLETE;
        boolean verified = prefs.isModelVerified(selectedModel);
        boolean linked = installed && permission && linkConfirmed;

        deck.setStatus(linked, core, downloaded ? selectedModel : null, serverReady, busy);
        deck.setEngineLine(deviceLine());

        if (android.os.Build.SUPPORTED_64_BIT_ABIS.length == 0) {
            deck.setBootState(
                    "BOOT HALT // ABI",
                    "НУЖЕН 64-БИТНЫЙ ТЕЛЕФОН",
                    "Текущий пакет llama.cpp в Termux выпускается для aarch64/x86_64. "
                            + "Интерфейс установится, но локальная GGUF-модель на этом 32-битном устройстве не запустится.",
                    null, null,
                    null, null
            );
            return;
        }
        if (!installed) {
            deck.setBootState(
                    "BOOT SEQUENCE // 01",
                    "УСТАНОВИТЕ TERMUX",
                    "Нужна версия из F-Droid. Она станет защищённым runtime-контуром для настоящего Pi, Python и shell.",
                    "OPEN F-DROID", termux::openTermuxPage,
                    null, null
            );
            return;
        }
        if (!permission) {
            deck.setBootState(
                    "BOOT SEQUENCE // 02",
                    "РАЗРЕШИТЕ КАНАЛ УПРАВЛЕНИЯ",
                    "PI//DECK просит только специальное разрешение Termux RUN_COMMAND. Оно позволяет запускать команды внутри Termux, не давая APK root-доступ.",
                    "GRANT LINK", () -> termux.requestRunPermission(this, REQUEST_RUN_COMMAND),
                    "APP SETTINGS", termux::openAppSettings
            );
            return;
        }
        if (!linkConfirmed) {
            deck.setBootState(
                    "BOOT SEQUENCE // 03",
                    "СВЯЖИТЕ TERMUX С ДЕКОЙ",
                    "Нажмите COPY + OPEN, вставьте строку в Termux и выполните её. Появится системный запрос доступа к файлам. Затем вернитесь и нажмите TEST LINK.",
                    "COPY + OPEN", this::copyHandshakeAndOpen,
                    "TEST LINK", this::probeTermux
            );
            return;
        }
        if (!core) {
            deck.setBootState(
                    "BOOT SEQUENCE // 04",
                    "РАЗВЕРНУТЬ PI CORE",
                    "Один раз установим Node.js, Python, llama.cpp, git и официальный Pi coding agent. Это займёт несколько минут и останется внутри Termux.",
                    "INSTALL CORE", this::installCore,
                    "TEST LINK", this::probeTermux
            );
            return;
        }
        if (!downloaded) {
            String body;
            String primaryLabel;
            Runnable primary;
            if (modelState.isActive()) {
                body = selectedModel.title + " · " + selectedModel.humanSize()
                        + "\nHugging Face download: " + modelState.percent() + "%"
                        + " (" + humanBytes(modelState.downloadedBytes) + " / "
                        + humanBytes(modelState.totalBytes) + ")";
                primaryLabel = "MODELS";
                primary = this::showModelsDialog;
            } else if (modelState.phase == ModelDownloadManager.Phase.FAILED) {
                body = "Загрузка остановилась: "
                        + ModelDownloadManager.failureLabel(modelState.reason)
                        + ". Неполный файл можно безопасно заменить.";
                primaryLabel = "RETRY";
                primary = () -> confirmDownload(selectedModel);
            } else {
                body = "Для этого телефона выбран " + selectedModel.title + " "
                        + selectedModel.humanSize() + ". " + selectedModel.note
                        + "\nWi‑Fi рекомендован; модель загружается напрямую с Hugging Face.";
                primaryLabel = "DOWNLOAD " + selectedModel.tier;
                primary = () -> confirmDownload(selectedModel);
            }
            deck.setBootState(
                    "BOOT SEQUENCE // 05",
                    "ЗАГРУЗИТЬ ЛОКАЛЬНЫЙ МОЗГ",
                    body,
                    primaryLabel, primary,
                    "CHOOSE", this::showModelsDialog
            );
            return;
        }
        if (!verified) {
            if (!verifying && verificationFault.isBlank()) verifyModel(selectedModel);
            String body = verificationFault.isBlank()
                    ? "Сверяем SHA‑256 большого GGUF-файла: " + verificationPercent
                    + "%. Это защищает от обрыва или подмены загрузки."
                    : "Проверка не пройдена: " + verificationFault
                    + "\nФайл можно безопасно загрузить заново.";
            deck.setBootState(
                    "BOOT SEQUENCE // 06",
                    "ПРОВЕРКА ЦЕЛОСТНОСТИ",
                    body,
                    verificationFault.isBlank() ? "VERIFYING…" : "RE-DOWNLOAD",
                    verificationFault.isBlank()
                            ? this::showModelsDialog
                            : () -> confirmDownload(selectedModel),
                    verificationFault.isBlank() ? null : "MODELS",
                    verificationFault.isBlank() ? null : this::showModelsDialog
            );
            return;
        }
        if (!serverReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 07",
                    "ЗАЖЕЧЬ ЛОКАЛЬНОЕ ЯДРО",
                    selectedModel.title + " проверена и готова. Запуск использует "
                            + Math.max(2, Math.min(8, cpuThreads - 1))
                            + " CPU-потоков и контекст 8192 токена.",
                    "IGNITE LLM", this::startServer,
                    "MODELS", this::showModelsDialog
            );
            return;
        }
        deck.hideBootPanel();
    }

    private long heartbeatDelay() {
        // Nothing but the health probe changes once the core is live, so back the polling off.
        return busy || verifying || !serverReady ? 1_250L : 3_500L;
    }

    /**
     * Termux can be killed by Android, or refuse the intent silently, and then no result ever
     * arrives. Without this the deck stays in its busy state forever with the input disabled.
     */
    private void armWatchdog(String kind) {
        cancelWatchdog();
        long timeout = watchdogTimeout(kind);
        if (timeout <= 0L) return;
        watchdogKind = kind;
        watchdog = () -> {
            watchdog = null;
            watchdogKind = "";
            if (!busy) return;
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR,
                    "Termux не вернул результат за " + (timeout / 1_000L) + " с. Откройте Termux "
                            + "кнопкой TERMUX, проверьте, что он запущен и что "
                            + "allow-external-apps=true, затем повторите шаг.");
        };
        main.postDelayed(watchdog, timeout);
    }

    private void cancelWatchdog() {
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        watchdogKind = "";
    }

    private static long watchdogTimeout(String kind) {
        return switch (kind) {
            case "probe", "stop", "newsession" -> 45_000L;
            case "start" -> 240_000L;
            case "update" -> 900_000L;
            case "install" -> 1_800_000L;
            case "agent" -> 2_700_000L;
            default -> 0L;
        };
    }

    private boolean canRunAgent() {
        return termux.isInstalled()
                && termux.hasRunPermission()
                && linkConfirmed
                && prefs.isCoreReady()
                && modelDownloads.isDownloaded(selectedModel)
                && prefs.isModelVerified(selectedModel)
                && serverReady;
    }

    private void probeTermux() {
        if (busy) return;
        setBusy(true, "TESTING TERMUX LINK");
        try {
            termux.runBash("probe", RuntimeScripts.probe());
            armWatchdog("probe");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void installCore() {
        if (busy) return;
        setBusy(true, "INSTALLING PI CORE");
        append(ConsoleEntry.Channel.SYSTEM,
                "Разворачиваю runtime внутри Termux. Не закрывайте Termux во время пакетной установки.");
        try {
            termux.runBash("install", RuntimeScripts.installCore());
            armWatchdog("install");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void startServer() {
        if (busy) return;
        if (!prefs.isModelVerified(selectedModel)) {
            toast("Сначала нужна проверка GGUF");
            return;
        }
        setBusy(true, "LOADING " + selectedModel.title);
        append(ConsoleEntry.Channel.SYSTEM,
                "Загружаю " + selectedModel.title + " в память. Первый запуск может быть медленным.");
        try {
            termux.runBash(
                    "start",
                    RuntimeScripts.startServer(selectedModel, Math.max(2, cpuThreads - 1))
            );
            armWatchdog("start");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void stopServer() {
        if (busy) return;
        setBusy(true, "STOPPING LLM CORE");
        try {
            termux.runBash("stop", RuntimeScripts.stopServer());
            armWatchdog("stop");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void updateAgent() {
        if (busy) return;
        setBusy(true, "UPDATING PI AGENT");
        try {
            termux.runBash("update", RuntimeScripts.updateAgent());
            armWatchdog("update");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void newSession() {
        if (busy) return;
        setBusy(true, "OPENING NEW SESSION");
        try {
            termux.runBash("newsession", RuntimeScripts.newSession());
            armWatchdog("newsession");
        } catch (RuntimeException error) {
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void abortAgent() {
        try {
            // Deliberately no watchdog: the outstanding agent command still owns the busy state.
            termux.runBash("abort", RuntimeScripts.abortAgent());
        } catch (RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void verifyModel(ModelSpec model) {
        if (verifying) return;
        if (modelDownloads.state(model).isActive()) return;
        verifying = true;
        verificationPercent = 0;
        verificationFault = "";
        modelDownloads.verifyAsync(model, new ModelDownloadManager.VerifyListener() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(() -> {
                    verificationPercent = percent;
                    refreshUi();
                });
            }

            @Override
            public void onComplete(boolean valid, String actualHash, String error) {
                runOnUiThread(() -> {
                    verifying = false;
                    prefs.setModelVerified(model, valid);
                    if (valid) {
                        verificationPercent = 100;
                        verificationFault = "";
                        append(ConsoleEntry.Channel.SYSTEM,
                                model.title + " · SHA‑256 verified. GGUF готова к запуску.");
                    } else {
                        verificationFault = error == null || error.isBlank()
                                ? "SHA‑256 не совпал (" + actualHash.substring(0, Math.min(12, actualHash.length())) + "…)"
                                : error;
                        append(ConsoleEntry.Channel.ERROR,
                                model.title + " не прошла проверку целостности: " + verificationFault);
                    }
                    refreshUi();
                    if (modelsDialog != null && modelsDialog.isShowing()) renderModelRows();
                });
            }
        });
    }

    private void checkServerHealth() {
        if (serverHealthInFlight || !prefs.isCoreReady() || !termux.isInstalled()) return;
        serverHealthInFlight = true;
        String expectedModelId = selectedModel.id;
        io.execute(() -> {
            boolean healthy = false;
            try {
                HttpURLConnection connection = (HttpURLConnection)
                        new URL("http://127.0.0.1:8080/health").openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(700);
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    String body = readSmall(connection.getInputStream());
                    healthy = !body.contains("\"status\":\"error\"");
                }
                connection.disconnect();
                if (healthy) {
                    HttpURLConnection models = (HttpURLConnection)
                            new URL("http://127.0.0.1:8080/v1/models").openConnection();
                    models.setConnectTimeout(500);
                    models.setReadTimeout(700);
                    models.setUseCaches(false);
                    int modelsCode = models.getResponseCode();
                    String body = modelsCode >= 200 && modelsCode < 300
                            ? readSmall(models.getInputStream())
                            : "";
                    healthy = body.contains(expectedModelId);
                    models.disconnect();
                }
            } catch (Exception ignored) {
            }
            boolean result = healthy;
            runOnUiThread(() -> {
                boolean stillExpected = selectedModel.id.equals(expectedModelId);
                boolean becameReady = !serverReady && result && stillExpected;
                serverReady = result && stillExpected;
                serverHealthInFlight = false;
                if (becameReady) {
                    append(ConsoleEntry.Channel.SYSTEM,
                            selectedModel.title + " online. Pi получает модель через локальный 127.0.0.1 — наружу промпты не уходят.");
                }
                refreshUi();
            });
        });
    }

    private void confirmDownload(ModelSpec model) {
        // The current target is dropped before the transfer starts, so its bytes count as free.
        long available = freeStorage + modelDownloads.reclaimableBytes(model);
        if (available < ModelCatalog.requiredStorage(model)) {
            append(ConsoleEntry.Channel.ERROR,
                    "Для " + model.title + " нужно минимум "
                            + humanBytes(ModelCatalog.requiredStorage(model))
                            + " свободного места. Сейчас доступно " + humanBytes(available) + ".");
            return;
        }
        String networkNote = isMetered()
                ? "\n\nСеть сейчас тарифицируемая."
                : "\n\nWi‑Fi/нетарифицируемая сеть обнаружена.";
        new AlertDialog.Builder(this)
                .setTitle("Загрузить " + model.title + "?")
                .setMessage(model.humanSize() + " · " + model.repo
                        + "\nФайл будет сохранён в Download/PiDeck/models."
                        + networkNote)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Загрузить", (dialog, which) -> {
                    selectedModel = model;
                    prefs.setSelectedModelId(model.id);
                    prefs.setModelVerified(model, false);
                    verificationFault = "";
                    try {
                        modelDownloads.start(model);
                        append(ConsoleEntry.Channel.SYSTEM,
                                "Hugging Face download запущен: " + model.title + " · " + model.humanSize());
                    } catch (RuntimeException error) {
                        append(ConsoleEntry.Channel.ERROR, "DownloadManager: " + readableException(error));
                    }
                    refreshUi();
                    if (modelsDialog != null && modelsDialog.isShowing()) renderModelRows();
                })
                .show();
    }

    private void chooseModel(ModelSpec model) {
        if (!modelDownloads.isDownloaded(model)) {
            confirmDownload(model);
            return;
        }
        selectedModel = model;
        prefs.setSelectedModelId(model.id);
        serverReady = false;
        verificationFault = "";
        if (!prefs.isModelVerified(model)) verifyModel(model);
        append(ConsoleEntry.Channel.SYSTEM,
                "Активный профиль → " + model.title + ". Перезапустите LLM-ядро.");
        if (modelsDialog != null) modelsDialog.dismiss();
        refreshUi();
    }

    private void confirmDeleteModel(ModelSpec model) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить " + model.title + "?")
                .setMessage("Будет удалён только Download/PiDeck/models/" + model.fileName
                        + ". Pi, Python и проекты останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    if (model.equals(selectedModel) && serverReady) stopServer();
                    prefs.setModelVerified(model, false);
                    boolean deleted = modelDownloads.delete(model);
                    if (deleted) {
                        append(ConsoleEntry.Channel.SYSTEM, model.title + " удалена с телефона.");
                    } else {
                        append(ConsoleEntry.Channel.ERROR,
                                "Android не разрешил удалить " + model.fileName + ".");
                    }
                    if (modelsDialog != null && modelsDialog.isShowing()) renderModelRows();
                    refreshUi();
                })
                .show();
    }

    private void showModelsDialog() {
        if (modelsDialog != null && modelsDialog.isShowing()) {
            renderModelRows();
            return;
        }
        DialogShell shell = dialogShell(
                "MODEL MATRIX",
                "GGUF // HUGGING FACE // SHA-256 PINNED"
        );
        modelsDialog = shell.dialog;
        modelRows = shell.body;
        renderModelRows();
        modelsDialog.setOnDismissListener(ignored -> {
            modelsDialog = null;
            modelRows = null;
        });
        modelsDialog.show();
        sizeDialog(modelsDialog, 0.95f, 0.88f);
    }

    private void renderModelRows() {
        if (modelRows == null) return;
        modelRows.removeAllViews();
        ModelSpec recommended = ModelCatalog.recommend(totalRam, freeStorage);
        for (ModelSpec model : ModelCatalog.all()) {
            ModelDownloadManager.State state = modelDownloads.state(model);
            boolean selected = model.equals(selectedModel);
            boolean verified = prefs.isModelVerified(model);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(deck.dp(12), deck.dp(11), deck.dp(12), deck.dp(11));
            card.setBackground(deck.panel(
                    selected ? palette.accentAlt : palette.accent,
                    palette.fill(0xE4), 1, 5
            ));

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = deck.text(model.title, 15, palette.text, Typeface.BOLD);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tier = deck.text(model.tier, 10, selected ? palette.accentAlt : palette.accent, Typeface.BOLD);
            tier.setLetterSpacing(0.12f);
            titleRow.addView(tier);
            card.addView(titleRow);

            String flags = model.humanSize() + " // ≥" + model.minimumRamGb + " GB RAM";
            if (model.equals(recommended)) flags += " // RECOMMENDED";
            if (selected) flags += " // ACTIVE";
            TextView meta = deck.text(flags, 9, palette.ok, Typeface.BOLD);
            meta.setPadding(0, deck.dp(4), 0, 0);
            card.addView(meta);

            TextView note = deck.text(model.note, 11, palette.muted, Typeface.NORMAL);
            note.setPadding(0, deck.dp(6), 0, deck.dp(7));
            card.addView(note);

            if (state.isActive()) {
                ProgressBar progress = new ProgressBar(
                        this, null, android.R.attr.progressBarStyleHorizontal
                );
                progress.setMax(100);
                progress.setProgress(state.percent());
                progress.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.accent));
                card.addView(progress, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, deck.dp(4)
                ));
                TextView progressText = deck.text(
                        state.percent() + "% // " + humanBytes(state.downloadedBytes)
                                + " / " + humanBytes(state.totalBytes),
                        9, palette.accent, Typeface.BOLD
                );
                progressText.setPadding(0, deck.dp(5), 0, 0);
                card.addView(progressText);
            } else if (state.phase == ModelDownloadManager.Phase.COMPLETE) {
                card.addView(deck.text(
                        verified ? "✓ DOWNLOADED // SHA-256 VERIFIED" : "◇ DOWNLOADED // VERIFY PENDING",
                        9, verified ? palette.ok : palette.warn, Typeface.BOLD
                ));
            } else if (state.phase == ModelDownloadManager.Phase.FAILED) {
                card.addView(deck.text(
                        "FAULT // " + ModelDownloadManager.failureLabel(state.reason),
                        9, palette.errorText, Typeface.BOLD
                ));
            }

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, deck.dp(9), 0, 0);
            if (state.isActive()) {
                actions.addView(deck.button("CANCEL", palette.errorText, () -> {
                    modelDownloads.cancel(model);
                    renderModelRows();
                    refreshUi();
                }));
            } else if (state.phase == ModelDownloadManager.Phase.COMPLETE) {
                if (!selected || !serverReady) {
                    actions.addView(deck.button(
                            selected ? "RESTART CORE" : "SELECT",
                            selected ? palette.accent : palette.accentAlt,
                            () -> {
                                chooseModel(model);
                                if (selected) startServer();
                            }
                    ));
                }
                TextView remove = deck.button("DELETE", palette.errorText, () -> confirmDeleteModel(model));
                LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                removeLp.leftMargin = deck.dp(7);
                actions.addView(remove, removeLp);
            } else {
                actions.addView(deck.button(
                        state.phase == ModelDownloadManager.Phase.FAILED ? "RETRY" : "DOWNLOAD",
                        palette.accent,
                        () -> confirmDownload(model)
                ));
            }
            card.addView(actions);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardLp.bottomMargin = deck.dp(9);
            modelRows.addView(card, cardLp);
        }
    }

    private void showCoreDialog() {
        if (coreDialog != null && coreDialog.isShowing()) return;
        DialogShell shell = dialogShell(
                "CORE CONTROL",
                "PI AGENT // TERMUX RUNTIME // LOCALHOST"
        );
        coreDialog = shell.dialog;
        LinearLayout body = shell.body;
        body.addView(infoLine("TERMUX", termux.isInstalled() ? "INSTALLED" : "ABSENT",
                termux.isInstalled() ? palette.ok : palette.errorText));
        body.addView(infoLine("TRANSPORT", linkConfirmed ? "LINKED" : "OFFLINE",
                linkConfirmed ? palette.accent : palette.warn));
        body.addView(infoLine("PI RUNTIME", prefs.isCoreReady() ? "READY" : "NOT INSTALLED",
                prefs.isCoreReady() ? palette.ok : palette.warn));
        body.addView(infoLine("LLM SERVER", serverReady ? "127.0.0.1:8080 LIVE" : "STOPPED",
                serverReady ? palette.accent : palette.muted));
        body.addView(infoLine("MODEL", selectedModel.title + " / " + selectedModel.tier, palette.accentAlt));
        body.addView(infoLine("WORKSPACE", "~/.pideck/workspace", palette.text));
        body.addView(infoLine("SCHEME", palette.label, palette.accent));

        TextView explanation = deck.text(
                "Pi работает как настоящий coding agent: read, write, edit, grep, find, ls и bash. "
                        + "Python и интернет доступны внутри Termux; общие файлы телефона — через ~/storage.",
                11, palette.muted, Typeface.NORMAL
        );
        explanation.setLineSpacing(0, 1.18f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        explanationLp.topMargin = deck.dp(12);
        body.addView(explanation, explanationLp);

        body.addView(dialogAction("UPDATE PI AGENT", "npm latest // keeps workspace", palette.accent, this::updateAgent));
        body.addView(dialogAction("RESTART LLM", "reload selected GGUF", palette.accentAlt, this::startServer));
        body.addView(dialogAction("NEW SESSION", "archives current Pi conversation", palette.ok, this::newSession));
        if (busy) {
            body.addView(dialogAction("ABORT CURRENT TASK", "send SIGINT to Pi process", palette.errorText, this::abortAgent));
        }
        Palette next = nextScheme();
        body.addView(dialogAction(
                "COLOR SCHEME → " + next.id.toUpperCase(Locale.ROOT),
                next.label.toLowerCase(Locale.ROOT),
                palette.accentAlt,
                this::switchColorScheme
        ));
        body.addView(dialogAction("COPY LINK COMMAND", "repair Termux handshake", palette.warn, this::copyHandshakeAndOpen));
        body.addView(dialogAction("STOP LOCAL CORE", "release model RAM and wake-lock", palette.errorText, this::stopServer));

        coreDialog.setOnDismissListener(ignored -> coreDialog = null);
        coreDialog.show();
        sizeDialog(coreDialog, 0.94f, 0.86f);
    }

    private View infoLine(String label, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, deck.dp(6), 0, deck.dp(6));
        TextView left = deck.text(label, 10, palette.muted, Typeface.BOLD);
        left.setLetterSpacing(0.1f);
        TextView right = deck.text(value, 10, color, Typeface.BOLD);
        right.setGravity(Gravity.END);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
        return row;
    }

    private View dialogAction(String title, String subtitle, int color, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(deck.dp(11), deck.dp(9), deck.dp(11), deck.dp(9));
        row.setBackground(deck.panel(color, palette.fill(color, 0.06f, 0xC7), 1, 4));
        row.setClickable(true);
        row.setOnClickListener(ignored -> {
            if (coreDialog != null) coreDialog.dismiss();
            action.run();
        });
        row.addView(deck.text(title, 11, color, Typeface.BOLD));
        row.addView(deck.text(subtitle, 9, palette.muted, Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = deck.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private DialogShell dialogShell(String title, String subtitle) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(deck.dp(14), deck.dp(14), deck.dp(14), deck.dp(14));
        root.setBackground(deck.panel(palette.accent, palette.fill(0xFB), 1, 7));

        TextView kicker = deck.text(subtitle, 9, palette.accentAlt, Typeface.BOLD);
        kicker.setLetterSpacing(0.11f);
        root.addView(kicker);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, deck.dp(4), 0, deck.dp(10));
        TextView titleView = deck.text(title, 20, palette.accent, Typeface.BOLD);
        heading.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = deck.button("×", palette.muted, dialog::dismiss);
        heading.addView(close, new LinearLayout.LayoutParams(deck.dp(42), deck.dp(38)));
        root.addView(heading);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        dialog.setContentView(root);
        return new DialogShell(dialog, body);
    }

    private void sizeDialog(Dialog dialog, float widthRatio, float heightRatio) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = (int) (getResources().getDisplayMetrics().widthPixels * widthRatio);
        params.height = (int) (getResources().getDisplayMetrics().heightPixels * heightRatio);
        params.gravity = Gravity.CENTER;
        window.setAttributes(params);
        window.setDimAmount(0.72f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    private Palette nextScheme() {
        return Palette.SCHEME_NORD.equals(palette.id)
                ? Palette.deck()
                : Palette.nord();
    }

    /**
     * Colours are baked into the views as they are built, so the activity is recreated instead of
     * walking the hierarchy. The transcript already lives in preferences, and a result that lands
     * during the restart is picked up from the pending slot in {@link #onResume()}.
     */
    private void switchColorScheme() {
        if (busy) {
            toast("Дождитесь завершения текущей команды");
            return;
        }
        prefs.setColorScheme(nextScheme().id);
        prefs.saveTranscript(deck.entries());
        recreate();
    }

    private void copyHandshakeAndOpen() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("PI//DECK Termux link", HANDSHAKE_COMMAND));
        toast("Команда скопирована — вставьте её в Termux");
        termux.openTermux();
    }

    private void setBusy(boolean value, String label) {
        busy = value;
        deck.setBusy(value, label);
        refreshUi();
    }

    private void append(ConsoleEntry.Channel channel, String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) return;
        deck.addEntry(new ConsoleEntry(channel, normalized));
        prefs.saveTranscript(deck.entries());
    }

    private void updateCapacity() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        totalRam = memory.totalMem;
        cpuThreads = Runtime.getRuntime().availableProcessors();
        try {
            StatFs stat = new StatFs(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            .getAbsolutePath()
            );
            freeStorage = stat.getAvailableBytes();
        } catch (RuntimeException ignored) {
            freeStorage = 0;
        }
    }

    private String deviceLine() {
        String network = isMetered() ? "METERED" : "NET OK";
        return humanBytes(totalRam) + " RAM // " + cpuThreads + " CPU // "
                + humanBytes(freeStorage) + " FREE // " + network
                + " // " + selectedModel.title;
    }

    private boolean isMetered() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        double gib = bytes / 1_073_741_824.0;
        if (gib >= 1.0) return String.format(Locale.US, "%.1f GB", gib);
        return String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0);
    }

    private String clean(String value) {
        if (value == null) return "";
        return ANSI.matcher(value)
                .replaceAll("")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\n{4,}", "\n\n\n");
    }

    private String tail(String value, int lines) {
        String[] all = value.trim().split("\\n");
        int start = Math.max(0, all.length - lines);
        StringBuilder result = new StringBuilder();
        for (int i = start; i < all.length; i++) {
            if (result.length() > 0) result.append('\n');
            result.append(all[i]);
        }
        return result.toString();
    }

    private String readableException(RuntimeException error) {
        if (error instanceof SecurityException) {
            return "Нет канала RUN_COMMAND. Выдайте дополнительное разрешение PI//DECK и включите allow-external-apps в Termux.";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String readSmall(InputStream stream) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && result.length() < 4_096) {
                result.append(line);
            }
            return result.toString();
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private static final class DialogShell {
        final Dialog dialog;
        final LinearLayout body;

        DialogShell(Dialog dialog, LinearLayout body) {
            this.dialog = dialog;
            this.body = body;
        }
    }
}
