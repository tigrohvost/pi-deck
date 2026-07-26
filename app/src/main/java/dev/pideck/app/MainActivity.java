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

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import dev.pideck.app.core.AccessProfile;
import dev.pideck.app.core.BridgeEvent;
import dev.pideck.app.core.BridgeTokenStore;
import dev.pideck.app.core.CommandEvents;
import dev.pideck.app.core.CommandResult;
import dev.pideck.app.core.DeckPreferences;
import dev.pideck.app.core.ModelCatalog;
import dev.pideck.app.core.ModelDownloadManager;
import dev.pideck.app.core.ModelSpec;
import dev.pideck.app.core.OperationCoordinator;
import dev.pideck.app.core.OperationId;
import dev.pideck.app.core.OperationKind;
import dev.pideck.app.core.OperationRecord;
import dev.pideck.app.core.OperationState;
import dev.pideck.app.core.OperationStore;
import dev.pideck.app.core.PiJsonOutput;
import dev.pideck.app.core.RpcBridgeClient;
import dev.pideck.app.core.RuntimeAssetBundle;
import dev.pideck.app.core.RuntimeScripts;
import dev.pideck.app.core.TermuxBridge;
import dev.pideck.app.core.TermuxEnvironment;
import dev.pideck.app.ui.ConsoleEntry;
import dev.pideck.app.ui.DeckView;
import dev.pideck.app.ui.Palette;

public final class MainActivity extends Activity implements DeckView.Listener, CommandEvents.Listener {
    private static final int REQUEST_RUN_COMMAND = 41;
    private static final String HANDSHAKE_COMMAND =
            "mkdir -p ~/.termux && " +
            "(grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || " +
            "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties) && " +
            "termux-reload-settings && " +
            "([ -d \"$HOME/storage/downloads\" ] || termux-setup-storage)";
    private static final Pattern ANSI = Pattern.compile(
            "(?:\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))|(?:\\u001B\\[[0-?]*[ -/]*[@-~])"
    );

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DeckView deck;
    private Palette palette;
    private DeckPreferences prefs;
    private OperationStore operationStore;
    private OperationCoordinator operations;
    private TermuxBridge termux;
    private TermuxEnvironment termuxEnvironment;
    private ModelDownloadManager modelDownloads;
    private ModelCatalog modelCatalog;
    private BridgeTokenStore bridgeTokenStore;
    private RpcBridgeClient rpc;
    private AccessProfile accessProfile;
    private ModelSpec selectedModel;
    private long totalRam;
    private long availableRam;
    private long freeStorage;
    private int cpuThreads;
    private boolean lowMemory;
    private boolean modelSelectionRequired;

    private boolean linkConfirmed;
    private boolean serverReady;
    private boolean bridgeReady;
    private boolean bridgeConnected;
    private String bridgeFault = "";
    private boolean busy;
    private boolean verifying;
    private int verificationPercent;
    private String verificationFault = "";
    private Dialog modelsDialog;
    private LinearLayout modelRows;
    private Dialog coreDialog;
    private Runnable watchdog;
    private OperationId watchdogOperationId;
    private int heartbeatTick;
    private boolean startupProbeAttempted;
    private AlertDialog approvalDialog;
    private String currentApprovalId;
    private String observedBridgeInstance;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            // Capacity probing hits StatFs and ActivityManager, so it does not need every tick.
            if (heartbeatTick % 4 == 0) updateCapacity();
            heartbeatTick++;
            refreshUi();
            if (modelsDialog != null && modelsDialog.isShowing()) renderModelRows();
            main.postDelayed(this, heartbeatDelay());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new DeckPreferences(this);
        operationStore = new OperationStore(this);
        operations = new OperationCoordinator(operationStore);
        palette = Palette.forId(prefs.colorScheme());
        accessProfile = prefs.accessProfile();
        bridgeTokenStore = new BridgeTokenStore(this);
        rpc = new RpcBridgeClient(bridgeTokenStore.getOrCreate());
        observedBridgeInstance = prefs.bridgeInstanceId();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        termux = new TermuxBridge(this);
        termuxEnvironment = termux.inspectEnvironment();
        modelDownloads = new ModelDownloadManager(this, prefs);
        modelCatalog = ModelCatalog.initialize(this);
        updateCapacity();
        String savedModel = prefs.selectedModelId();
        selectedModel = modelCatalog.byId(savedModel).orElseGet(
                () -> modelCatalog.recommend(availableRam, lowMemory, freeStorage)
        );
        modelSelectionRequired = savedModel == null || modelCatalog.byId(savedModel).isEmpty();
        if (savedModel != null && modelSelectionRequired) prefs.clearSelectedModelId();
        linkConfirmed = prefs.isCoreReady();
        OperationRecord restored = operations.active();
        busy = restored != null && !restored.state.isTerminal();

        deck = new DeckView(this, this, palette);
        setContentView(deck);
        deck.setEntries(prefs.loadTranscript());
        deck.setEngineLine(deviceLine());
        updatePrivacyIndicator();
        refreshUi();
        if (restored != null && !restored.state.isTerminal()) {
            OperationRecord active = operations.active();
            if (active != null) main.post(() -> armRestoredWatchdog(active));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        CommandEvents.addListener(this);
        main.post(heartbeat);
        main.post(this::probeRuntimeOnLaunch);
        startRpcPolling();
    }

    @Override
    protected void onResume() {
        super.onResume();
        termuxEnvironment = termux.inspectEnvironment();
        for (OperationRecord record : operations.unconsumedResults()) {
            if (record.result != null) handleCommandResult(record.result, true);
        }
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
        if (approvalDialog != null) approvalDialog.dismiss();
        cancelWatchdog(null);
        if (rpc != null) rpc.close();
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
        if (prompt.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            toast("Промпт больше лимита 64 KiB");
            return;
        }
        if (busy) {
            toast("Pi уже выполняет задачу");
            return;
        }
        if (!canRunAgent()) {
            refreshUi();
            toast("Сначала завершите boot sequence");
            return;
        }

        append(ConsoleEntry.Channel.USER, prompt);
        dispatchRpcTurn(prompt);
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
        runOnUiThread(() -> handleCommandResult(result, false));
    }

    private void handleCommandResult(CommandResult result, boolean recovered) {
        boolean ownsUi = operations.onResult(result);
        OperationId activeId = operations.activeOperationId();
        boolean control = result.kind == OperationKind.ABORT_AGENT
                || result.kind == OperationKind.RECONCILE;
        if (!ownsUi && !control && !(recovered && activeId == null)) {
            operations.markConsumed(result.operationId);
            return;
        }
        cancelWatchdog(result.operationId);
        switch (result.kind) {
            case PROBE_RUNTIME -> {
                OperationRecord record = operationStore.load(result.operationId);
                boolean startup = record != null && record.request.optBoolean("startup", false);
                setBusy(false, null);
                if (result.isSuccess() && RuntimeScripts.isLinkProbeOutput(result.stdout)) {
                    linkConfirmed = true;
                    boolean wasCoreReady = prefs.isCoreReady();
                    boolean runtimeFound = RuntimeScripts.isReadyProbeOutput(result.stdout);
                    prefs.setCoreReady(runtimeFound);
                    JSONObject probe = RuntimeScripts.finalJsonObject(result.stdout);
                    if (probe != null) {
                        JSONObject server = probe.optJSONObject("server");
                        serverReady = server != null && "READY".equals(server.optString("state"));
                    }
                    if (!startup) {
                        append(ConsoleEntry.Channel.TOOL,
                                "Termux bridge online.\n" + clean(result.stdout).trim());
                    } else if (wasCoreReady && !runtimeFound) {
                        append(ConsoleEntry.Channel.ERROR,
                                "Pi runtime в Termux неполон. Нажмите INSTALL CORE; "
                                        + "загружать GGUF повторно не нужно.");
                    }
                } else {
                    linkConfirmed = false;
                    if (!startup) {
                        append(ConsoleEntry.Channel.ERROR,
                                "Termux пока не принимает команды.\n" + result.usefulError()
                                        + "\n\nВыполните строку из шага LINK вручную в Termux.");
                    }
                }
            }
            case INSTALL_RUNTIME -> {
                setBusy(false, null);
                if (result.isSuccess() && RuntimeScripts.isReadyProbeOutput(result.stdout)) {
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
            case INSTALL_MODEL -> {
                setBusy(false, null);
                ModelSpec operationModel = modelForOperation(result.operationId);
                if (runtimeState(result, "READY")) {
                    if (operationModel != null) {
                        prefs.setPrivateModelInstalled(operationModel, true);
                    }
                    append(ConsoleEntry.Channel.SYSTEM,
                            (operationModel == null ? "GGUF" : operationModel.title)
                                    + " установлена в приватное хранилище Termux. "
                                    + "Shared incoming-файл теперь можно удалить отдельно.");
                } else {
                    if (operationModel != null) {
                        prefs.setPrivateModelInstalled(operationModel, false);
                    }
                    append(ConsoleEntry.Channel.ERROR,
                            "Приватная установка GGUF не завершилась.\n" + runtimeError(result));
                }
            }
            case START_SERVER -> {
                setBusy(false, null);
                ModelSpec operationModel = modelForOperation(result.operationId);
                if (runtimeState(result, "READY")) {
                    serverReady = operationModel != null
                            && operationModel.id.equals(selectedModel.id);
                    append(ConsoleEntry.Channel.TOOL,
                            (operationModel == null ? "GGUF" : operationModel.title)
                                    + " прошла полный SHA-256 и доступна на loopback.");
                    if (serverReady) main.post(this::startBridge);
                } else {
                    serverReady = false;
                    append(ConsoleEntry.Channel.ERROR,
                            "LLM-ядро не запустилось.\n" + runtimeError(result));
                }
            }
            case START_BRIDGE -> {
                setBusy(false, null);
                if (runtimeState(result, "READY")) {
                    bridgeReady = true;
                    bridgeConnected = true;
                    bridgeFault = "";
                    append(ConsoleEntry.Channel.SYSTEM,
                            "Pi RPC bridge online // " + accessProfile.label
                                    + " // authenticated localhost.");
                } else {
                    bridgeReady = false;
                    bridgeConnected = false;
                    bridgeFault = runtimeError(result);
                    append(ConsoleEntry.Channel.ERROR,
                            "Pi RPC bridge не запустился.\n" + bridgeFault);
                }
            }
            case STOP_BRIDGE -> {
                setBusy(false, null);
                bridgeReady = false;
                bridgeConnected = false;
                OperationRecord record = operationStore.load(result.operationId);
                boolean stopServerAfter = record != null
                        && record.request.optBoolean("stopServerAfter", false);
                boolean startBridgeAfter = record != null
                        && record.request.optBoolean("startBridgeAfter", false);
                if (!result.isSuccess()) {
                    append(ConsoleEntry.Channel.ERROR, runtimeError(result));
                } else if (stopServerAfter) {
                    main.post(this::stopServerRuntime);
                } else if (startBridgeAfter) {
                    main.post(this::startBridge);
                }
            }
            case STOP_SERVER -> {
                setBusy(false, null);
                serverReady = false;
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess() ? "Локальное LLM-ядро остановлено." : result.usefulError());
            }
            case UPDATE_RUNTIME -> {
                setBusy(false, null);
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess()
                                ? "Закреплённый Pi/runtime восстановлен.\n"
                                + tail(clean(result.stdout), 5)
                                : "Обновление Pi не завершилось.\n" + runtimeError(result));
                if (result.isSuccess() && serverReady) main.post(this::restartBridge);
            }
            case NEW_SESSION -> {
                setBusy(false, null);
                if (result.isSuccess()) {
                    prefs.setHasSession(false);
                    append(ConsoleEntry.Channel.SYSTEM,
                            "Открыта новая ветка. Предыдущая сессия перемещена в ~/.pideck/session-archive.");
                } else {
                    append(ConsoleEntry.Channel.ERROR, result.usefulError());
                }
            }
            case ABORT_AGENT -> {
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess()
                                ? "Структурированная команда abort принята RPC bridge."
                                : runtimeError(result));
            }
            case AGENT_TURN -> handleAgentResult(result);
            default -> {
                setBusy(false, null);
                if (!result.isSuccess()) append(ConsoleEntry.Channel.ERROR, result.usefulError());
            }
        }
        operations.markConsumed(result.operationId);
        refreshUi();
        if (coreDialog != null && coreDialog.isShowing()) {
            coreDialog.dismiss();
            coreDialog = null;
        }
    }

    private void handleAgentResult(CommandResult result) {
        if (!result.isSuccess() && missingPiExecutable(result)) {
            setBusy(false, null);
            prefs.setCoreReady(false);
            linkConfirmed = true;
            serverReady = false;
            append(ConsoleEntry.Channel.ERROR,
                    "Pi CLI отсутствует в Termux. Нажмите INSTALL CORE; "
                            + "модель уже загружена и повторно скачиваться не будет.");
            return;
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
        boolean installed = termuxEnvironment.installed;
        boolean permission = termux.hasRunPermission();
        boolean core = prefs.isCoreReady();
        ModelDownloadManager.State modelState = modelDownloads.state(selectedModel);
        boolean incomingAvailable = modelDownloads.isDownloaded(selectedModel);
        boolean verified = prefs.isModelVerified(selectedModel);
        boolean privateReady = prefs.isPrivateModelInstalled(selectedModel);
        boolean linked = installed && permission && linkConfirmed;

        deck.setStatus(linked, core, privateReady ? selectedModel : null, serverReady && bridgeReady, busy);
        deck.setEngineLine(deviceLine());
        updatePrivacyIndicator();

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
        if (!termuxEnvironment.versionSupported) {
            deck.setBootState(
                    "BOOT HALT // TERMUX VERSION",
                    "ОБНОВИТЕ TERMUX",
                    "Обнаружена версия " + termuxEnvironment.version
                            + "; требуется 0.118.0 или новее из F-Droid.",
                    "OPEN F-DROID", termux::openTermuxPage,
                    null, null
            );
            return;
        }
        if (termuxEnvironment.source == TermuxEnvironment.Source.UNKNOWN) {
            String signer = termuxEnvironment.signerSha256;
            String prefix = signer.isBlank() ? "не читается" : signer.substring(0, 12) + "…";
            deck.setBootState(
                    "BOOT HALT // TERMUX SIGNER",
                    "НЕИЗВЕСТНАЯ ПОДПИСЬ",
                    "PI//DECK не передаст RUN_COMMAND неизвестной сборке Termux. "
                            + "SHA-256 signer: " + prefix + ". Установите совместимую F-Droid-сборку.",
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
        if (modelSelectionRequired) {
            deck.setBootState(
                    "BOOT SEQUENCE // 05",
                    "ВЫБЕРИТЕ ПРОФИЛЬ МОДЕЛИ",
                    "Сохранённая модель отсутствует в проверенном каталоге. Рекомендация по "
                            + "доступной памяти: " + selectedModel.title + ". Выбор не применяется скрыто.",
                    "CHOOSE MODEL", this::showModelsDialog,
                    null, null
            );
            return;
        }
        if (!privateReady && !incomingAvailable) {
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
        if (!privateReady && !verified) {
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
        if (!privateReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 07",
                    "УСТАНОВИТЬ ПРИВАТНУЮ GGUF",
                    "Android SHA-256 пройден. Termux повторно проверит hash во время копирования, "
                            + "выполнит fsync и atomic rename в ~/.pideck/models.",
                    busy ? "INSTALLING…" : "INSTALL PRIVATE",
                    busy ? this::showModelsDialog : () -> installPrivateModel(selectedModel),
                    "MODELS", this::showModelsDialog
            );
            return;
        }
        if (!serverReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 08",
                    "ЗАЖЕЧЬ ЛОКАЛЬНОЕ ЯДРО",
                    selectedModel.title + " находится в приватном read-only store. Запуск использует "
                            + Math.max(2, Math.min(8, cpuThreads - 1))
                            + " CPU-потоков и контекст " + selectedModel.recommendedContext
                            + " токенов. Ожидаемый peak: "
                            + humanBytes(selectedModel.estimatedPeakBytes())
                            + "; доступно " + humanBytes(availableRam) + ".",
                    "IGNITE LLM", this::startServer,
                    "MODELS", this::showModelsDialog
            );
            return;
        }
        if (!bridgeReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 09",
                    "ПОДКЛЮЧИТЬ PI RPC",
                    "Локальный bridge использует 256-bit token и слушает только 127.0.0.1. "
                            + (bridgeFault.isBlank() ? accessProfile.description : bridgeFault),
                    "START BRIDGE", this::startBridge,
                    "ACCESS", this::showCoreDialog
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
    private void armWatchdog(OperationId operationId, OperationKind kind) {
        armWatchdog(operationId, kind, kind.timeoutMs());
    }

    private void armRestoredWatchdog(OperationRecord operation) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - operation.createdAtMs);
        long remaining = Math.max(1_000L, operation.kind.timeoutMs() - elapsed);
        armWatchdog(operation.operationId, operation.kind, remaining);
    }

    private void armWatchdog(
            OperationId operationId,
            OperationKind kind,
            long timeout
    ) {
        cancelWatchdog(null);
        if (timeout <= 0L) return;
        watchdogOperationId = operationId;
        watchdog = () -> {
            watchdog = null;
            OperationId active = operations.activeOperationId();
            if (!busy || !operationId.equals(active)) return;
            operations.timeout(operationId);
            watchdogOperationId = null;
            setBusy(true, "OPERATION STATE // UNKNOWN");
            append(ConsoleEntry.Channel.ERROR,
                    "Результат операции неизвестен; часть изменений могла быть применена. "
                            + "Проверьте состояние workspace перед повтором. Operation ID: "
                            + operationId);
            if (kind == OperationKind.AGENT_TURN || kind == OperationKind.NEW_SESSION) {
                io.execute(() -> {
                    try {
                        JSONObject state = rpc.state();
                        runOnUiThread(() -> handleBridgeState(state));
                    } catch (Exception error) {
                        runOnUiThread(() -> bridgeFault = safeException(error));
                    }
                });
            }
        };
        main.postDelayed(watchdog, timeout);
    }

    private void cancelWatchdog(OperationId completedOperationId) {
        if (completedOperationId != null
                && watchdogOperationId != null
                && !completedOperationId.equals(watchdogOperationId)) {
            return;
        }
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        watchdogOperationId = null;
    }

    private boolean canRunAgent() {
        return termuxEnvironment.canRunCommands()
                && termux.hasRunPermission()
                && linkConfirmed
                && prefs.isCoreReady()
                && prefs.isPrivateModelInstalled(selectedModel)
                && serverReady
                && bridgeReady
                && bridgeConnected;
    }

    private void probeTermux() {
        if (busy) return;
        dispatchOperation(
                OperationKind.PROBE_RUNTIME,
                new JSONObject(),
                "TESTING TERMUX LINK",
                operationId -> termux.runBash(
                        operationId, OperationKind.PROBE_RUNTIME, RuntimeScripts.probe()
                )
        );
    }

    private void probeRuntimeOnLaunch() {
        if (startupProbeAttempted
                || busy
                || !prefs.isCoreReady()
                || !termux.isInstalled()
                || !termux.hasRunPermission()) {
            return;
        }
        startupProbeAttempted = true;
        linkConfirmed = false;
        dispatchOperation(
                OperationKind.PROBE_RUNTIME,
                json("startup", true),
                "CHECKING PI RUNTIME",
                operationId -> termux.runBash(
                        operationId, OperationKind.PROBE_RUNTIME, RuntimeScripts.probe()
                )
        );
    }

    private void installCore() {
        if (busy) return;
        append(ConsoleEntry.Channel.SYSTEM,
                "Разворачиваю runtime внутри Termux. Не закрывайте Termux во время пакетной установки.");
        String installScript;
        try {
            installScript = RuntimeAssetBundle.installCore(this);
        } catch (RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, readableException(error));
            return;
        }
        dispatchOperation(
                OperationKind.INSTALL_RUNTIME,
                new JSONObject(),
                "INSTALLING PI CORE",
                operationId -> termux.runBash(
                        operationId, OperationKind.INSTALL_RUNTIME, installScript
                )
        );
    }

    private void startServer() {
        if (busy) return;
        if (!prefs.isPrivateModelInstalled(selectedModel)) {
            toast("Сначала установите приватную GGUF");
            return;
        }
        long expectedPeak = selectedModel.estimatedPeakBytes();
        if (lowMemory || availableRam < Math.max(
                selectedModel.minimumAvailableMiB * 1_048_576L,
                expectedPeak
        )) {
            new AlertDialog.Builder(this)
                    .setTitle("Высокий риск OOM")
                    .setMessage("Ожидаемый peak: " + humanBytes(expectedPeak)
                            + "\nДоступно сейчас: " + humanBytes(availableRam)
                            + "\nКонтекст: " + selectedModel.recommendedContext
                            + "\n\nAndroid может завершить Termux. Модель не будет заменена скрыто.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Запустить", (dialog, which) -> startServerConfirmed())
                    .show();
            return;
        }
        startServerConfirmed();
    }

    private void startServerConfirmed() {
        append(ConsoleEntry.Channel.SYSTEM,
                "Проверяю приватный SHA-256 и загружаю " + selectedModel.title
                        + " в память. Первый запуск может быть медленным.");
        dispatchOperation(
                OperationKind.START_SERVER,
                requestMetadata(selectedModel.id, false),
                "LOADING " + selectedModel.title,
                operationId -> {
                    JSONObject input = runtimeRequest(operationId);
                    put(input, "modelId", selectedModel.id);
                    put(input, "threads", Math.max(2, cpuThreads - 1));
                    put(input, "port", 8080);
                    termux.runRuntime(
                            operationId,
                            OperationKind.START_SERVER,
                            "server-start",
                            input.toString()
                    );
                }
        );
    }

    private void stopServer() {
        if (busy) return;
        if (bridgeReady || bridgeConnected) {
            dispatchOperation(
                    OperationKind.STOP_BRIDGE,
                    json("stopServerAfter", true),
                    "STOPPING PI BRIDGE",
                    operationId -> termux.runRuntime(
                            operationId,
                            OperationKind.STOP_BRIDGE,
                            "bridge-stop",
                            "{}"
                    )
            );
            return;
        }
        stopServerRuntime();
    }

    private void stopServerRuntime() {
        if (busy) return;
        dispatchOperation(
                OperationKind.STOP_SERVER,
                requestMetadata(selectedModel.id, false),
                "STOPPING LLM CORE",
                operationId -> termux.runRuntime(
                        operationId, OperationKind.STOP_SERVER, "server-stop", "{}"
                )
        );
    }

    private void updateAgent() {
        if (busy) return;
        String updateScript;
        try {
            updateScript = RuntimeAssetBundle.updateRuntime(this);
        } catch (RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, readableException(error));
            return;
        }
        dispatchOperation(
                OperationKind.UPDATE_RUNTIME,
                new JSONObject(),
                "UPDATING PI AGENT",
                operationId -> termux.runBash(
                        operationId, OperationKind.UPDATE_RUNTIME, updateScript
                )
        );
    }

    private void newSession() {
        if (busy || !bridgeReady) return;
        OperationRecord operation;
        String newSessionId = java.util.UUID.randomUUID().toString();
        try {
            operation = operations.begin(
                    OperationKind.NEW_SESSION,
                    json("sessionId", newSessionId)
            );
            operations.dispatched(operation.operationId);
            setBusy(true, "OPENING NEW SESSION");
            armWatchdog(operation.operationId, OperationKind.NEW_SESSION);
        } catch (RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, readableException(error));
            return;
        }
        io.execute(() -> {
            try {
                rpc.command(
                        operation.operationId,
                        "NEW_SESSION",
                        json("sessionId", newSessionId)
                );
            } catch (Exception error) {
                runOnUiThread(() -> failRpcDispatch(operation.operationId, error));
            }
        });
    }

    private void abortAgent() {
        OperationRecord active = operations.active();
        if (active == null || active.kind != OperationKind.AGENT_TURN) {
            toast("Нет активного Pi turn");
            return;
        }
        try {
            operations.requestAbort(active.operationId);
            OperationRecord control = operations.beginControl(
                    OperationKind.ABORT_AGENT,
                    json("targetOperationId", active.operationId.toString())
            );
            operations.dispatched(control.operationId);
            setBusy(true, "PI AGENT // ABORTING");
            io.execute(() -> {
                try {
                    rpc.command(
                            control.operationId,
                            "ABORT",
                            json("targetOperationId", active.operationId.toString())
                    );
                    CommandResult accepted = new CommandResult(
                            control.operationId,
                            OperationKind.ABORT_AGENT,
                            "{\"accepted\":true}",
                            "",
                            0,
                            0,
                            ""
                    );
                    runOnUiThread(() -> handleCommandResult(accepted, false));
                } catch (Exception error) {
                    CommandResult failed = new CommandResult(
                            control.operationId,
                            OperationKind.ABORT_AGENT,
                            "",
                            "",
                            1,
                            1,
                            safeException(error)
                    );
                    runOnUiThread(() -> handleCommandResult(failed, false));
                }
            });
        } catch (RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private void installPrivateModel(ModelSpec model) {
        if (busy) return;
        if (!prefs.isModelVerified(model) || !modelDownloads.isDownloaded(model)) {
            toast("Сначала нужна Android SHA-256 проверка incoming-файла");
            return;
        }
        long available = freeStorage;
        long required = ModelCatalog.requiredStorageForPrivateInstall(model);
        if (available < required) {
            append(ConsoleEntry.Channel.ERROR,
                    "Для приватной копии нужно ещё " + humanBytes(required)
                            + "; доступно " + humanBytes(available) + ".");
            return;
        }
        dispatchOperation(
                OperationKind.INSTALL_MODEL,
                requestMetadata(model.id, false),
                "INSTALLING PRIVATE GGUF",
                operationId -> {
                    JSONObject input = runtimeRequest(operationId);
                    put(input, "modelId", model.id);
                    put(input, "sourcePath", modelDownloads.sourcePath(model));
                    termux.runRuntime(
                            operationId,
                            OperationKind.INSTALL_MODEL,
                            "install-model",
                            input.toString()
                    );
                }
        );
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
                                model.title + " · Android SHA‑256 verified. "
                                        + "Перед запуском Termux создаст приватную копию.");
                        main.post(() -> installPrivateModel(model));
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

    private void confirmDownload(ModelSpec model) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        // The current target is dropped before the transfer starts, so its bytes count as free.
        long available = freeStorage + modelDownloads.reclaimableBytes(model);
        if (available < ModelCatalog.requiredStorageForFreshInstall(model)) {
            append(ConsoleEntry.Channel.ERROR,
                    "Для " + model.title + " нужно минимум "
                            + humanBytes(ModelCatalog.requiredStorageForFreshInstall(model))
                            + " свободного места. Сейчас доступно " + humanBytes(available) + ".");
            return;
        }
        String networkNote = isMetered()
                ? "\n\nСеть сейчас тарифицируемая. Размер: " + model.humanSize()
                + ". Продолжение требует отдельного согласия."
                : "\n\nWi‑Fi/нетарифицируемая сеть обнаружена.";
        boolean allowMetered = isMetered();
        new AlertDialog.Builder(this)
                .setTitle("Загрузить " + model.title + "?")
                .setMessage(model.humanSize() + " · " + model.repo
                        + "\nIncoming будет сохранён в Download/PiDeck/incoming, "
                        + "затем проверен и скопирован в приватный Termux store."
                        + networkNote)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(
                        allowMetered ? "По мобильной сети" : "Загрузить",
                        (dialog, which) -> {
                    selectedModel = model;
                    modelSelectionRequired = false;
                    prefs.setSelectedModelId(model.id);
                    prefs.setModelVerified(model, false);
                    prefs.setPrivateModelInstalled(model, false);
                    verificationFault = "";
                    try {
                        modelDownloads.start(model, allowMetered);
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
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        if (!prefs.isPrivateModelInstalled(model) && !modelDownloads.isDownloaded(model)) {
            confirmDownload(model);
            return;
        }
        selectedModel = model;
        modelSelectionRequired = false;
        prefs.setSelectedModelId(model.id);
        serverReady = false;
        verificationFault = "";
        if (!prefs.isPrivateModelInstalled(model) && !prefs.isModelVerified(model)) {
            verifyModel(model);
        }
        append(ConsoleEntry.Channel.SYSTEM,
                "Активный профиль → " + model.title + ". Перезапустите LLM-ядро.");
        if (modelsDialog != null) modelsDialog.dismiss();
        refreshUi();
    }

    private void confirmDeleteModel(ModelSpec model) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Удалить incoming source?")
                .setMessage("Будет удалена только общая копия "
                        + modelDownloads.fileFor(model).getAbsolutePath()
                        + ". Приватная read-only GGUF, Pi и проекты останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    prefs.setModelVerified(model, false);
                    boolean deleted = modelDownloads.delete(model);
                    if (deleted) {
                        append(ConsoleEntry.Channel.SYSTEM,
                                "Shared incoming source для " + model.title + " удалён.");
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
        ModelSpec recommended = modelCatalog.recommend(availableRam, lowMemory, freeStorage);
        for (ModelSpec model : modelCatalog.all()) {
            ModelDownloadManager.State state = modelDownloads.state(model);
            boolean selected = model.equals(selectedModel);
            boolean verified = prefs.isModelVerified(model);
            boolean privateReady = prefs.isPrivateModelInstalled(model);
            boolean incomingReady = modelDownloads.isDownloaded(model);

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

            String flags = model.humanSize() + " // ≥"
                    + model.minimumAvailableMiB + " MiB AVAILABLE";
            if (model.equals(recommended)) flags += " // RECOMMENDED";
            if (selected) flags += " // ACTIVE";
            TextView meta = deck.text(flags, 9, palette.ok, Typeface.BOLD);
            meta.setPadding(0, deck.dp(4), 0, 0);
            card.addView(meta);

            TextView note = deck.text(model.note, 11, palette.muted, Typeface.NORMAL);
            note.setPadding(0, deck.dp(6), 0, deck.dp(7));
            card.addView(note);

            if (privateReady) {
                card.addView(deck.text(
                        "✓ PRIVATE READY // FULL SHA BEFORE EACH START",
                        9, palette.ok, Typeface.BOLD
                ));
            } else if (state.isActive()) {
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
            } else if (incomingReady) {
                card.addView(deck.text(
                        verified ? "✓ INCOMING // ANDROID SHA-256 VERIFIED"
                                : "◇ INCOMING // VERIFY PENDING",
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
            if (privateReady) {
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
                if (incomingReady) {
                    TextView removeSource = deck.button(
                            "DELETE SOURCE", palette.muted, () -> confirmDeleteModel(model)
                    );
                    LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    sourceLp.leftMargin = deck.dp(7);
                    actions.addView(removeSource, sourceLp);
                }
            } else if (state.isActive()) {
                actions.addView(deck.button("CANCEL", palette.errorText, () -> {
                    modelDownloads.cancel(model);
                    renderModelRows();
                    refreshUi();
                }));
            } else if (incomingReady) {
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
        body.addView(infoLine(
                "TERMUX BUILD",
                termuxEnvironment.version + " / " + termuxEnvironment.sourceLabel(),
                termuxEnvironment.signerTrusted() ? palette.ok : palette.warn
        ));
        body.addView(infoLine(
                "TERMUX:API",
                termuxEnvironment.apiCompatible
                        ? termuxEnvironment.apiVersion + " / WAKE-LOCK READY"
                        : termuxEnvironment.apiInstalled
                        ? termuxEnvironment.apiVersion + " / INCOMPATIBLE"
                        : "OPTIONAL / ABSENT",
                termuxEnvironment.apiCompatible ? palette.ok : palette.warn
        ));
        body.addView(infoLine("TRANSPORT", linkConfirmed ? "LINKED" : "OFFLINE",
                linkConfirmed ? palette.accent : palette.warn));
        body.addView(infoLine("PI RUNTIME", prefs.isCoreReady() ? "READY" : "NOT INSTALLED",
                prefs.isCoreReady() ? palette.ok : palette.warn));
        body.addView(infoLine("LLM SERVER", serverReady ? "127.0.0.1:8080 LIVE" : "STOPPED",
                serverReady ? palette.accent : palette.muted));
        body.addView(infoLine("RPC BRIDGE", bridgeReady ? "AUTHENTICATED / 127.0.0.1" : "STOPPED",
                bridgeReady ? palette.ok : palette.muted));
        body.addView(infoLine("ACCESS", accessProfile.label,
                accessProfile == AccessProfile.AUTONOMOUS ? palette.warn : palette.accent));
        body.addView(infoLine("MODEL", selectedModel.title + " / " + selectedModel.tier, palette.accentAlt));
        body.addView(infoLine("WORKSPACE", "~/.pideck/workspace", palette.text));
        body.addView(infoLine("LOCAL INFERENCE", "YES", palette.ok));
        body.addView(infoLine("TOOL NETWORK", accessProfile.toolNetworkPossible ? "POSSIBLE" : "NO SHELL",
                accessProfile.toolNetworkPossible ? palette.warn : palette.ok));
        body.addView(infoLine("OS ISOLATION", "NOT IMPLEMENTED", palette.warn));
        body.addView(infoLine("SCHEME", palette.label, palette.accent));

        TextView explanation = deck.text(
                accessProfile.description + " Local inference означает выполнение модели на телефоне, "
                        + "но не является сетевой песочницей. Loopback защищён token-ом bridge."
                        + (termuxEnvironment.source
                        == TermuxEnvironment.Source.GITHUB_SHARED_TEST_KEY
                        ? " У этой Termux-сборки публичный shared test key; доверять можно только APK "
                        + "из официального GitHub release."
                        : ""),
                11, palette.muted, Typeface.NORMAL
        );
        explanation.setLineSpacing(0, 1.18f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        explanationLp.topMargin = deck.dp(12);
        body.addView(explanation, explanationLp);

        body.addView(dialogAction("ACCESS → READ ONLY", "no mutating tools", palette.ok,
                () -> changeAccessProfile(AccessProfile.READ_ONLY)));
        body.addView(dialogAction("ACCESS → CONFIRM", "one-time Android approval", palette.accent,
                () -> changeAccessProfile(AccessProfile.CONFIRM_CHANGES)));
        body.addView(dialogAction("ACCESS → AUTONOMOUS", "explicit high-risk opt-in", palette.warn,
                () -> changeAccessProfile(AccessProfile.AUTONOMOUS)));
        body.addView(dialogAction("RESTORE PINNED PI", "0.82.1 + integrity check", palette.accent,
                this::updateAgent));
        body.addView(dialogAction("RESTART LLM", "reload selected GGUF", palette.accentAlt, this::startServer));
        body.addView(dialogAction("NEW SESSION", "start a separate Pi conversation", palette.ok, this::newSession));
        if (busy) {
            body.addView(dialogAction(
                    "ABORT CURRENT TASK",
                    "structured RPC; exact-process fallback",
                    palette.errorText,
                    this::abortAgent
            ));
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
     * during the restart is recovered from the durable per-operation store in {@link #onResume()}.
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

    private void dispatchRpcTurn(String prompt) {
        String sessionId = prefs.ensureSessionId();
        OperationRecord operation;
        try {
            operation = operations.begin(
                    OperationKind.AGENT_TURN,
                    requestMetadata(selectedModel.id, prefs.hasSession())
                            .put("sessionId", sessionId)
                            .put("accessProfile", accessProfile.wireName())
            );
            operations.dispatched(operation.operationId);
            setBusy(true, "PI AGENT // RPC");
            armWatchdog(operation.operationId, OperationKind.AGENT_TURN);
        } catch (JSONException | RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, safeException(error));
            return;
        }
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("message", prompt);
                payload.put("sessionId", sessionId);
                rpc.command(operation.operationId, "PROMPT", payload);
            } catch (Exception error) {
                runOnUiThread(() -> failRpcDispatch(operation.operationId, error));
            }
        });
    }

    private void startBridge() {
        if (busy || !serverReady || !prefs.isPrivateModelInstalled(selectedModel)) return;
        String sessionId = prefs.ensureSessionId();
        JSONObject request = requestMetadata(selectedModel.id, prefs.hasSession());
        put(request, "accessProfile", accessProfile.wireName());
        put(request, "sessionId", sessionId);
        dispatchOperation(
                OperationKind.START_BRIDGE,
                request,
                "STARTING PI RPC BRIDGE",
                operationId -> {
                    JSONObject input = runtimeRequest(operationId);
                    put(input, "token", bridgeTokenStore.getOrCreate());
                    put(input, "modelId", selectedModel.id);
                    put(input, "accessProfile", accessProfile.wireName());
                    put(input, "sessionId", sessionId);
                    put(input, "port", RpcBridgeClient.DEFAULT_PORT);
                    termux.runRuntime(
                            operationId,
                            OperationKind.START_BRIDGE,
                            "bridge-start",
                            input.toString()
                    );
                }
        );
    }

    private void restartBridge() {
        if (busy || !serverReady) return;
        dispatchOperation(
                OperationKind.STOP_BRIDGE,
                json("startBridgeAfter", true),
                "RESTARTING PI BRIDGE",
                operationId -> termux.runRuntime(
                        operationId,
                        OperationKind.STOP_BRIDGE,
                        "bridge-stop",
                        "{}"
                )
        );
    }

    private void startRpcPolling() {
        rpc.startPolling(
                prefs.bridgeInstanceId(),
                prefs.bridgeSequence(),
                new RpcBridgeClient.Listener() {
                    @Override
                    public void onConnected(JSONObject state) {
                        runOnUiThread(() -> handleBridgeState(state));
                    }

                    @Override
                    public void onEvent(BridgeEvent event) {
                        runOnUiThread(() -> handleBridgeEvent(event));
                    }

                    @Override
                    public void onEventGap(String bridgeInstanceId, long earliestReceived) {
                        runOnUiThread(() -> handleBridgeGap(bridgeInstanceId, earliestReceived));
                    }

                    @Override
                    public void onDisconnected(String reason) {
                        runOnUiThread(() -> {
                            bridgeConnected = false;
                            bridgeReady = false;
                            bridgeFault = reason;
                            OperationRecord active = operations.active();
                            if (active != null
                                    && (active.kind == OperationKind.AGENT_TURN
                                    || active.kind == OperationKind.NEW_SESSION)
                                    && !active.state.isTerminal()
                                    && active.state != OperationState.UNKNOWN) {
                                operations.timeout(active.operationId);
                                busy = true;
                                deck.setBusy(true, "OPERATION STATE // UNKNOWN");
                            }
                            if (approvalDialog != null) approvalDialog.dismiss();
                            refreshUi();
                        });
                    }
                }
        );
    }

    private void handleBridgeState(JSONObject state) {
        String instanceId = state.optString("bridgeInstanceId", "");
        if (instanceId.isBlank()) return;
        if (!instanceId.equals(observedBridgeInstance)) {
            observedBridgeInstance = instanceId;
            prefs.setBridgeCursor(instanceId, 0L);
        }
        bridgeConnected = true;
        boolean sameModel = selectedModel.id.equals(state.optString("modelId"));
        boolean sameProfile = accessProfile.wireName().equals(state.optString("accessProfile"));
        bridgeReady = sameModel && sameProfile;
        bridgeFault = bridgeReady ? "" : "Bridge profile/model отличается; требуется restart.";

        JSONObject server = state.optJSONObject("server");
        serverReady = server != null
                && "READY".equals(server.optString("state"))
                && selectedModel.id.equals(server.optString("modelId"));

        OperationRecord active = operations.active();
        if (active != null) {
            String remoteField = active.kind == OperationKind.NEW_SESSION
                    ? "pendingNewSessionOperationId"
                    : "activeOperationId";
            String remote = state.isNull(remoteField)
                    ? null
                    : state.optString(remoteField, null);
            if (active.operationId.toString().equals(remote)) {
                operations.reconcileRunning(active.operationId);
                busy = true;
                deck.setBusy(true, "PI AGENT // RECONNECTED");
            } else if ((active.kind == OperationKind.AGENT_TURN
                    || active.kind == OperationKind.NEW_SESSION)
                    && active.state == OperationState.UNKNOWN) {
                operations.reconcileTerminalMissing(
                        active.operationId,
                        "Bridge has no active turn and no terminal event is available"
                );
                cancelWatchdog(active.operationId);
                deck.discardStreaming();
                setBusy(false, null);
                append(ConsoleEntry.Channel.ERROR,
                        "Bridge подтвердил отсутствие активного turn, но terminal event утрачен. "
                                + "Операция завершена как FAILED и не повторялась автоматически.");
            } else if ((active.kind == OperationKind.AGENT_TURN
                    || active.kind == OperationKind.NEW_SESSION)
                    && !active.state.isTerminal()
                    && active.state != OperationState.UNKNOWN) {
                operations.timeout(active.operationId);
                setBusy(true, "OPERATION STATE // UNKNOWN");
            }
        }
        refreshUi();
    }

    private void handleBridgeGap(String instanceId, long earliestReceived) {
        prefs.setBridgeCursor(instanceId, Math.max(0L, earliestReceived - 1L));
        OperationRecord active = operations.active();
        if (active != null && !active.state.isTerminal()) {
            operations.timeout(active.operationId);
            setBusy(true, "EVENT GAP // RECONCILE");
        }
        append(ConsoleEntry.Channel.ERROR,
                "Bridge сообщил EVENT_GAP; выполнена полная сверка состояния. "
                        + "Промпт автоматически не повторялся.");
    }

    private void handleBridgeEvent(BridgeEvent event) {
        observedBridgeInstance = event.bridgeInstanceId;
        prefs.setBridgeCursor(event.bridgeInstanceId, event.sequence);
        switch (event.type) {
            case BRIDGE_READY -> {
                bridgeConnected = true;
                bridgeReady = true;
                bridgeFault = "";
            }
            case BRIDGE_ERROR -> append(
                    ConsoleEntry.Channel.ERROR,
                    "RPC protocol: " + event.payload.optString("message", "неизвестная ошибка")
            );
            case PI_STARTED -> bridgeReady = true;
            case PI_EXITED -> {
                if (!event.payload.optBoolean("expected", false)) {
                    append(ConsoleEntry.Channel.ERROR,
                            "Pi RPC child завершился; активный turn не будет повторён.");
                }
            }
            case TURN_ACCEPTED, TURN_STARTED -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    setBusy(true, "PI AGENT // STREAMING");
                    if (event.type == BridgeEvent.Type.TURN_STARTED) deck.beginStreaming();
                }
            }
            case MODEL_OUTPUT_DELTA -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    deck.appendStreaming(event.payload.optString("delta"));
                }
            }
            case TOOL_CALL_STARTED -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    append(ConsoleEntry.Channel.TOOL,
                            "› " + event.payload.optString("toolName", "tool")
                                    + "\n" + event.payload.optString("args"));
                }
            }
            case TOOL_CALL_COMPLETED -> {
                if (event.payload.optBoolean("isError", false)) {
                    append(ConsoleEntry.Channel.ERROR,
                            "× " + event.payload.optString("toolName", "tool")
                                    + "\n" + event.payload.optString("resultPreview"));
                }
            }
            case APPROVAL_REQUESTED -> showApproval(event);
            case APPROVAL_RESOLVED -> {
                String approvalId = event.payload.optString("approvalId");
                if (approvalId.equals(currentApprovalId) && approvalDialog != null) {
                    approvalDialog.dismiss();
                }
            }
            case TURN_COMPLETED, TURN_FAILED, TURN_ABORTED, SESSION_CREATED ->
                    completeRpcOperation(event);
            default -> {
            }
        }
        refreshUi();
    }

    private void completeRpcOperation(BridgeEvent event) {
        if (event.operationId == null) return;
        OperationRecord record = operationStore.load(event.operationId);
        if (record == null) return;
        boolean success = event.type == BridgeEvent.Type.TURN_COMPLETED
                || event.type == BridgeEvent.Type.SESSION_CREATED;
        String answer = event.payload.optString("answer");
        String error = event.payload.optString(
                "error",
                event.type == BridgeEvent.Type.TURN_ABORTED ? "Turn aborted" : "RPC turn failed"
        );
        CommandResult result = new CommandResult(
                event.operationId,
                record.kind,
                answer,
                "",
                success ? 0 : 1,
                success ? 0 : 1,
                success ? "" : error,
                event.type == BridgeEvent.Type.TURN_ABORTED
                        ? OperationState.ABORTED
                        : success ? OperationState.COMPLETED : OperationState.FAILED
        );
        boolean ownsUi;
        try {
            ownsUi = operations.onResult(result);
        } catch (RuntimeException ignored) {
            return;
        }
        if (!ownsUi && record.kind != OperationKind.NEW_SESSION) {
            operations.markConsumed(event.operationId);
            return;
        }
        cancelWatchdog(event.operationId);
        setBusy(false, null);
        if (record.kind == OperationKind.NEW_SESSION) {
            if (success) {
                String actualSession = event.payload.optString("sessionId", event.sessionId);
                try {
                    prefs.setSessionId(actualSession, false);
                } catch (RuntimeException ignored) {
                    prefs.startNewSession();
                }
                append(ConsoleEntry.Channel.SYSTEM,
                        "Открыта новая Pi RPC session без скрытого replay.");
            } else {
                append(ConsoleEntry.Channel.ERROR, error);
            }
        } else if (record.kind == OperationKind.AGENT_TURN) {
            if (event.type == BridgeEvent.Type.TURN_COMPLETED) {
                prefs.setHasSession(true);
                deck.finishStreaming(answer);
                prefs.saveTranscript(deck.entries());
            } else if (event.type == BridgeEvent.Type.TURN_ABORTED) {
                deck.discardStreaming();
                append(ConsoleEntry.Channel.SYSTEM, "Pi turn подтверждённо остановлен.");
            } else {
                prefs.setHasSession(true);
                if (!answer.isBlank()) deck.finishStreaming(answer);
                else deck.discardStreaming();
                append(ConsoleEntry.Channel.ERROR,
                        "Pi turn завершился ошибкой; запрос не повторялся.\n" + error);
            }
        }
        operations.markConsumed(event.operationId);
    }

    private void showApproval(BridgeEvent event) {
        if (event.operationId == null
                || !event.operationId.equals(operations.activeOperationId())
                || accessProfile != AccessProfile.CONFIRM_CHANGES) {
            sendApproval(event, false);
            return;
        }
        if (approvalDialog != null) approvalDialog.dismiss();
        String approvalId = event.payload.optString("approvalId");
        if (approvalId.isBlank()) return;
        currentApprovalId = approvalId;
        AtomicBoolean responded = new AtomicBoolean(false);
        approvalDialog = new AlertDialog.Builder(this)
                .setTitle(event.payload.optString("title", "Разрешить изменение?"))
                .setMessage(event.payload.optString("message")
                        + "\n\nOperation: " + event.operationId
                        + "\nSession: "
                        + (event.sessionId == null ? "none" : event.sessionId)
                        + "\n\nОдноразовое разрешение истекает через 30 секунд.")
                .setNegativeButton("Запретить", (dialog, which) -> {
                    if (responded.compareAndSet(false, true)) sendApproval(event, false);
                })
                .setPositiveButton("Разрешить один раз", (dialog, which) -> {
                    if (responded.compareAndSet(false, true)) sendApproval(event, true);
                })
                .setOnCancelListener(dialog -> {
                    if (responded.compareAndSet(false, true)) sendApproval(event, false);
                })
                .create();
        approvalDialog.setOnDismissListener(dialog -> {
            approvalDialog = null;
            currentApprovalId = null;
        });
        approvalDialog.show();
    }

    private void sendApproval(BridgeEvent event, boolean confirmed) {
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("approvalId", event.payload.getString("approvalId"));
                payload.put("confirmed", confirmed);
                rpc.command(event.operationId, "APPROVAL_DECISION", payload);
            } catch (Exception error) {
                runOnUiThread(() -> append(
                        ConsoleEntry.Channel.ERROR,
                        "Approval не доставлен; bridge применит deny по timeout."
                ));
            }
        });
    }

    private void failRpcDispatch(OperationId operationId, Exception error) {
        cancelWatchdog(operationId);
        try {
            operations.dispatchFailed(operationId, safeException(error));
        } catch (RuntimeException ignored) {
        }
        setBusy(false, null);
        append(ConsoleEntry.Channel.ERROR,
                "RPC-команда не принята; автоматического повтора не было.\n"
                        + safeException(error));
    }

    private void changeAccessProfile(AccessProfile target) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        if (target == accessProfile) {
            toast("Профиль уже активен");
            return;
        }
        if (target == AccessProfile.AUTONOMOUS) {
            new AlertDialog.Builder(this)
                    .setTitle("Включить AUTONOMOUS?")
                    .setMessage("Агент может выполнять shell-команды и изменять любые файлы, "
                            + "доступные пользователю Termux. Workspace-ограничение не является "
                            + "системной песочницей.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Понимаю риск", (dialog, which) ->
                            applyAccessProfile(target))
                    .show();
            return;
        }
        applyAccessProfile(target);
    }

    private void applyAccessProfile(AccessProfile target) {
        accessProfile = target;
        prefs.setAccessProfile(target);
        bridgeReady = false;
        bridgeConnected = false;
        updatePrivacyIndicator();
        append(ConsoleEntry.Channel.SYSTEM, "Access profile → " + target.label + ".");
        if (serverReady) main.post(this::startBridge);
        else refreshUi();
    }

    private void updatePrivacyIndicator() {
        if (deck == null) return;
        deck.setPrivacyLine(accessProfile.toolNetworkPossible
                ? "TOOLS: NETWORK POSSIBLE"
                : "TOOLS: NO SHELL");
    }

    @FunctionalInterface
    private interface OperationDispatch {
        void run(OperationId operationId);
    }

    private void dispatchOperation(
            OperationKind kind,
            JSONObject request,
            String busyLabel,
            OperationDispatch dispatch
    ) {
        OperationRecord operation;
        try {
            operation = operations.begin(kind, request);
        } catch (RuntimeException error) {
            append(ConsoleEntry.Channel.ERROR, readableException(error));
            return;
        }
        setBusy(true, busyLabel);
        try {
            operations.dispatched(operation.operationId);
            dispatch.run(operation.operationId);
            armWatchdog(operation.operationId, kind);
        } catch (RuntimeException error) {
            operations.dispatchFailed(operation.operationId, readableException(error));
            setBusy(false, null);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
        }
    }

    private JSONObject requestMetadata(String modelId, boolean continuingSession) {
        JSONObject value = new JSONObject();
        try {
            value.put("modelId", modelId == null ? JSONObject.NULL : modelId);
            String sessionId = continuingSession ? prefs.sessionId() : null;
            value.put("sessionId", sessionId == null ? JSONObject.NULL : sessionId);
        } catch (JSONException ignored) {
        }
        return value;
    }

    private ModelSpec modelForOperation(OperationId operationId) {
        OperationRecord record = operationStore.load(operationId);
        if (record == null) return null;
        String modelId = record.request.optString("modelId", "");
        return modelCatalog.byId(modelId).orElse(null);
    }

    private JSONObject runtimeRequest(OperationId operationId) {
        JSONObject value = new JSONObject();
        put(value, "schemaVersion", 1);
        put(value, "operationId", operationId.toString());
        return value;
    }

    private JSONObject json(String key, Object value) {
        JSONObject result = new JSONObject();
        put(result, key, value);
        return result;
    }

    private void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not build bounded command JSON", error);
        }
    }

    private boolean runtimeState(CommandResult result, String expectedState) {
        if (!result.isSuccess()) return false;
        JSONObject value = RuntimeScripts.finalJsonObject(result.stdout);
        return value != null
                && value.optInt("schemaVersion", -1) == 1
                && value.optBoolean("ok", false)
                && expectedState.equals(value.optString("state"));
    }

    private String runtimeError(CommandResult result) {
        JSONObject value = RuntimeScripts.finalJsonObject(result.stdout);
        if (value != null) {
            JSONObject error = value.optJSONObject("error");
            if (error != null) {
                String code = error.optString("code", "RUNTIME_ERROR");
                String message = error.optString("message", "Termux runtime error");
                return code + ": " + message;
            }
        }
        return clean(result.usefulError());
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
        availableRam = memory.availMem;
        lowMemory = memory.lowMemory;
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
                + " // TERMUX " + (termuxEnvironment.version.isBlank()
                ? "ABSENT" : termuxEnvironment.version)
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

    private boolean missingPiExecutable(CommandResult result) {
        String details = clean(
                result.stdout + "\n" + result.stderr + "\n" + result.errorMessage
        ).toLowerCase(Locale.ROOT);
        boolean namesPi = details.contains("/bin/pi")
                || details.contains("env: ‘pi’")
                || details.contains("env: 'pi'")
                || details.contains("env: \"pi\"");
        return namesPi && (details.contains("no such file or directory")
                || details.contains("not found"));
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

    private String safeException(Throwable error) {
        if (error instanceof SecurityException security) return readableException(security);
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        String sanitized = ANSI.matcher(message).replaceAll("").replace('\0', ' ');
        return sanitized.length() > 1024 ? sanitized.substring(0, 1024) : sanitized;
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
