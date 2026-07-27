package dev.pideck.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONArray;
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
import dev.pideck.app.core.NativeLlamaController;
import dev.pideck.app.core.NativeLlamaService;
import dev.pideck.app.core.NativeModelStore;
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
import dev.pideck.app.core.SessionContract;
import dev.pideck.app.core.SessionId;
import dev.pideck.app.core.TermuxBridge;
import dev.pideck.app.core.TermuxEnvironment;
import dev.pideck.app.ui.ConsoleEntry;
import dev.pideck.app.ui.CoreRootView;
import dev.pideck.app.ui.DeckStyle;
import dev.pideck.app.ui.DeckView;
import dev.pideck.app.ui.DecisionCardView;
import dev.pideck.app.ui.FailureCardView;
import dev.pideck.app.ui.Palette;
import dev.pideck.app.ui.SessionsRootView;
import dev.pideck.app.ui.TabBarView;

public final class MainActivity extends Activity implements DeckView.Listener, CommandEvents.Listener {
    private static final int REQUEST_RUN_COMMAND = 41;
    private static final int REQUEST_MODEL_DOCUMENT = 42;
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
    private NativeModelStore nativeModels;
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
    private float textScale;
    /** A prompt typed while a turn was running; dispatched when the deck frees up. */
    private String queuedPrompt;
    /** The heat warning is worth one line per turn, not one per event. */
    private boolean thermalWarned;
    /** Last listing of ~/.pideck/sessions, as the Termux runtime reported it. */
    private JSONArray sessions = new JSONArray();
    private int sessionCount;
    private long sessionBytes;
    private boolean sessionsRequested;
    private String sessionsFault = "";
    private Runnable watchdog;
    private OperationId watchdogOperationId;
    private int heartbeatTick;
    private boolean startupProbeAttempted;
    private AlertDialog approvalDialog;
    private String currentApprovalId;
    private String observedBridgeInstance;
    private String pendingModelDocumentId;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            // Capacity probing hits StatFs and ActivityManager, so it does not need every tick.
            if (heartbeatTick % 4 == 0) updateCapacity();
            heartbeatTick++;
            refreshUi();
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
        nativeModels = new NativeModelStore(this, prefs);
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

        textScale = DeckStyle.normalizeScale(prefs.textScale());
        deck = new DeckView(this, this, palette, textScale);
        setContentView(deck);
        deck.setEntries(prefs.loadTranscript());
        deck.setWorkspacePath(workspaceLabel());
        deck.setActiveTab(prefs.activeTab());
        refreshUi();
        if (restored != null && !restored.state.isTerminal()) {
            OperationRecord active = operations.active();
            if (active != null) {
                main.post(() -> armRestoredWatchdog(active));
                NativeLlamaService.Snapshot nativeState = NativeLlamaService.snapshot(this);
                if (active.kind == OperationKind.START_SERVER
                        && active.operationId.toString().equals(nativeState.operationId)
                        && nativeState.isStartingOrReady()) {
                    main.post(() -> NativeLlamaController.resume(
                            this,
                            active.operationId,
                            selectedModel
                    ));
                }
            }
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
            FailureCardView.Failure failure = new FailureCardView.Failure(
                    "Разрешение отозвано",
                    "Termux больше не пускает",
                    "Android не выдал RUN_COMMAND, без которого дека не может запустить ни одной "
                            + "команды в Termux. Разрешение выдаётся в сведениях о приложении, "
                            + "в разделе дополнительных разрешений.",
                    true
            );
            failure.recovered("сессия и весь диалог сохранены", "файлы в рабочей папке не тронуты");
            failure.primary(
                    "Повторить настройку",
                    () -> termux.requestRunPermission(this, REQUEST_RUN_COMMAND)
            );
            failure.secondary("Открыть настройки приложения", termux::openAppSettings);
            deck.addFailure(failure);
            prefs.saveTranscript(deck.entries());
            refreshUi();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MODEL_DOCUMENT) return;

        String modelId = pendingModelDocumentId;
        pendingModelDocumentId = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            toast("Выбор GGUF отменён");
            return;
        }
        ModelSpec model = modelCatalog.byId(modelId).orElse(selectedModel);
        Uri uri = data.getData();
        ModelDownloadManager.AttachResult attached;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
            attached = modelDownloads.attachExternalDocument(model, uri);
        } catch (RuntimeException error) {
            reportModelAccessFailure(model, safeException(error));
            return;
        }
        switch (attached.failure) {
            case NONE -> {
                append(ConsoleEntry.Channel.SYSTEM,
                        model.title + " · доступ к существующему GGUF восстановлен. "
                                + "Проверяю размер и SHA‑256.");
                verifyModel(model);
            }
            case SIZE_MISMATCH -> reportModelSizeMismatch(model, attached.actualBytes);
            case UNREADABLE -> reportModelAccessFailure(
                    model, "Android не сообщил размер выбранного файла"
            );
            case NOT_A_DOCUMENT -> reportModelAccessFailure(
                    model, "Android не выдал document URI"
            );
        }
    }

    @Override
    public void onSend(String prompt) {
        if (prompt.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            toast("Промпт больше лимита 64 KiB");
            return;
        }
        if (!canRunAgent()) {
            refreshUi();
            toast("Сначала завершите boot sequence");
            return;
        }
        if (busy) {
            // The field stays live during a turn, so a second prompt waits rather than bouncing.
            if (queuedPrompt != null) {
                toast("В очереди уже есть промпт");
                return;
            }
            queuedPrompt = prompt;
            append(ConsoleEntry.Channel.USER, prompt);
            append(ConsoleEntry.Channel.SYSTEM, "Отправлю, как только текущая задача закончится.");
            return;
        }

        append(ConsoleEntry.Channel.USER, prompt);
        warnIfHot();
        dispatchRpcTurn(prompt);
    }

    /**
     * A hot phone halves the token rate, and the drop is otherwise indistinguishable from the
     * deck hanging. Said once per turn, and never made sticky.
     */
    private void warnIfHot() {
        if (thermalWarned || android.os.Build.VERSION.SDK_INT < 29) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        int status = power.getCurrentThermalStatus();
        if (status < PowerManager.THERMAL_STATUS_MODERATE) return;
        thermalWarned = true;
        deck.addThermalNotice(status >= PowerManager.THERMAL_STATUS_SEVERE
                ? "Телефон сильно нагрелся — Android режет частоту, ответ будет заметно дольше."
                : "Телефон нагрелся — скорость упала примерно вдвое.");
        prefs.saveTranscript(deck.entries());
    }

    @Override
    public void onStopTurn() {
        abortAgent();
    }

    /**
     * The deck has no file viewer, and the file lives inside Termux's private tree, so the honest
     * hand-off is the path on the clipboard and Termux in the foreground.
     */
    @Override
    public void onOpenFile(String path) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("PI//DECK path", path));
        toast("Путь скопирован — открываю Termux");
        openTermux();
    }

    @Override
    public void onTabSelected(int tab) {
        deck.setActiveTab(tab);
        prefs.setActiveTab(tab);
        if (tab == TabBarView.TAB_SESSIONS) listSessions(false);
        refreshUi();
    }

    @Override
    public void onSchemeChosen(String schemeId) {
        switchColorScheme(schemeId);
    }

    @Override
    public void onTextScaleChosen(float scale) {
        if (busy) {
            toast("Дождитесь завершения текущей команды");
            return;
        }
        prefs.setTextScale(DeckStyle.normalizeScale(scale));
        prefs.saveTranscript(deck.entries());
        recreate();
    }

    @Override
    public void onConsentGranted(boolean askBeforeOverwrite) {
        prefs.setConsentGranted(true);
        prefs.setAskBeforeOverwrite(askBeforeOverwrite);
        append(ConsoleEntry.Channel.SYSTEM, askBeforeOverwrite
                ? "Доступ выдан. Спрошу перед изменением файлов, которые агент не создавал сам."
                : "Доступ выдан. Файлы в рабочей папке агент меняет без отдельного вопроса.");
        refreshUi();
    }

    @Override
    public void onAskBeforeOverwriteChanged(boolean askBeforeOverwrite) {
        prefs.setAskBeforeOverwrite(askBeforeOverwrite);
        refreshUi();
    }

    private void openTermux() {
        if (termux.isInstalled()) termux.openTermux();
        else termux.openTermuxPage();
    }

    private void clearConsole() {
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
                        append(ConsoleEntry.Channel.TOOL, "Termux bridge online.");
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
                    append(ConsoleEntry.Channel.SYSTEM, "Pi runtime развёрнут и проверен.");
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
                                    + " установлена в приватное хранилище PiDeck. "
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
                                    + " работает под UID PiDeck и доступна на loopback.");
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
                boolean startNativeAfter = record != null
                        && record.request.optBoolean("startNativeAfter", false);
                if (!result.isSuccess()) {
                    append(ConsoleEntry.Channel.ERROR, runtimeError(result));
                } else if (stopServerAfter) {
                    main.post(this::stopServerRuntime);
                } else if (startNativeAfter) {
                    main.post(() -> stopServerRuntime(true));
                } else if (startBridgeAfter) {
                    main.post(this::startBridge);
                }
            }
            case STOP_SERVER -> {
                OperationRecord record = operationStore.load(result.operationId);
                boolean startNativeAfter = record != null
                        && record.request.optBoolean("startNativeAfter", false);
                setBusy(false, null);
                serverReady = false;
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess() ? "Локальное LLM-ядро остановлено." : result.usefulError());
                if (result.isSuccess() && startNativeAfter) {
                    main.post(this::launchNativeServer);
                }
            }
            case UPDATE_RUNTIME -> {
                setBusy(false, null);
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess()
                                ? "Закреплённый Pi/runtime восстановлен и проверен."
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
            case LIST_SESSIONS -> {
                setBusy(false, null);
                JSONObject value = RuntimeScripts.finalJsonObject(result.stdout);
                if (runtimeState(result, "READY") && value != null) {
                    JSONArray listed = value.optJSONArray("sessions");
                    sessions = listed == null ? new JSONArray() : listed;
                    sessionCount = value.optInt("count", sessions.length());
                    sessionBytes = value.optLong("totalBytes", 0L);
                    sessionsFault = "";
                } else {
                    sessionsFault = runtimeError(result);
                }
            }
            case ARCHIVE_SESSIONS -> {
                setBusy(false, null);
                JSONObject value = RuntimeScripts.finalJsonObject(result.stdout);
                if (runtimeState(result, "READY") && value != null) {
                    int moved = value.optInt("archivedEntries", 0);
                    append(ConsoleEntry.Channel.SYSTEM, moved == 0
                            ? "Архивировать нечего: ~/.pideck/sessions пуст."
                            : "Перенесено в ~/.pideck/session-archive: "
                            + moved + " " + sessionsLabel(moved) + ".");
                    main.post(() -> listSessions(true));
                } else {
                    append(ConsoleEntry.Channel.ERROR,
                            "Архивация сессий не завершилась.\n" + runtimeError(result));
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
                if (trace.verb.isEmpty()) {
                    append(
                            trace.error ? ConsoleEntry.Channel.ERROR : ConsoleEntry.Channel.TOOL,
                            trace.text
                    );
                    continue;
                }
                deck.addTrace(
                        traceVerb(trace.verb),
                        traceArgument(trace.argument),
                        trace.error ? "ошибка" : ""
                );
            }
            prefs.saveTranscript(deck.entries());
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

    /**
     * The single fact the header carries. Order matters: a turn in flight outranks a healthy
     * core, and a question to the user outranks the turn that asked it.
     */
    private DeckView.CoreStatus coreStatus() {
        if (currentApprovalId != null) return DeckView.CoreStatus.AWAITING_USER;
        if (busy) return DeckView.CoreStatus.BUSY;
        if (serverReady && bridgeReady && bridgeConnected) return DeckView.CoreStatus.READY;
        if (verifying || modelDownloads.state(selectedModel).isActive()) {
            return DeckView.CoreStatus.STARTING;
        }
        if (!bridgeFault.isBlank() || !verificationFault.isBlank()) {
            return DeckView.CoreStatus.FAILED;
        }
        return DeckView.CoreStatus.SLEEPING;
    }

    private void refreshUi() {
        boolean installed = termuxEnvironment.installed;
        boolean permission = termux.hasRunPermission();
        boolean core = prefs.isCoreReady();
        ModelDownloadManager.State modelState = modelDownloads.state(selectedModel);
        boolean incomingAvailable = modelDownloads.isDownloaded(selectedModel);
        boolean verified = prefs.isModelVerified(selectedModel);
        boolean privateReady = nativeModels.isInstalled(selectedModel);

        deck.setCoreStatus(coreStatus(), "Ядро · " + selectedModel.tier);
        if (deck.activeTab() == TabBarView.TAB_CORE) renderCoreRoot();
        if (deck.activeTab() == TabBarView.TAB_SESSIONS) renderSessionsRoot();

        // Consent sits between a working Termux link and the first run, and it never covers a
        // boot step the user still has to fix — if the link breaks, the boot panel wins.
        deck.setConsentVisible(
                termuxEnvironment.canRunCommands()
                        && permission
                        && linkConfirmed
                        && !prefs.consentGranted()
        );

        if (!supportsArm64()) {
            deck.setBootState(
                    "BOOT HALT // ABI",
                    "НУЖЕН ARM64-ТЕЛЕФОН",
                    "Встроенный llama.cpp b10092 содержит проверенные Arm CPU-варианты. "
                            + "На устройстве без arm64-v8a локальная GGUF-модель не запускается.",
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
        // Boot does not continue past the access decision.
        if (!prefs.consentGranted()) return;
        if (!core) {
            deck.setBootState(
                    "BOOT SEQUENCE // 04",
                    "РАЗВЕРНУТЬ PI CORE",
                    "Один раз установим Node.js, Python, git и официальный Pi coding agent. Встроенный llama.cpp уже находится внутри APK.",
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
                    "CHOOSE MODEL", this::openCoreRoot,
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
                primary = this::openCoreRoot;
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
                    "CHOOSE", this::openCoreRoot
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
                            ? this::openCoreRoot
                            : () -> confirmDownload(selectedModel),
                    verificationFault.isBlank() ? null : "MODELS",
                    verificationFault.isBlank() ? null : this::openCoreRoot
            );
            return;
        }
        if (!privateReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 07",
                    "УСТАНОВИТЬ ПРИВАТНУЮ GGUF",
                    "Android SHA-256 пройден. PiDeck повторно проверит hash во время копирования, "
                            + "выполнит fsync и atomic rename в приватный model store.",
                    busy ? "INSTALLING…" : "INSTALL PRIVATE",
                    busy ? this::openCoreRoot : () -> installPrivateModel(selectedModel),
                    "MODELS", this::openCoreRoot
            );
            return;
        }
        if (!serverReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 08",
                    "ЗАЖЕЧЬ ЛОКАЛЬНОЕ ЯДРО",
                    selectedModel.title + " находится в приватном read-only store. Запуск использует "
                            + dev.pideck.app.core.CpuProfile.detect()
                            + " и контекст " + selectedModel.recommendedContext
                            + " токенов. Ожидаемый peak: "
                            + humanBytes(selectedModel.estimatedPeakBytes())
                            + "; доступно " + humanBytes(availableRam) + ".",
                    "IGNITE LLM", this::startServer,
                    "MODELS", this::openCoreRoot
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
                    "ACCESS", this::openCoreRoot
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
            setBusy(true, "Ответа нет");
            reportWatchdog(operationId, kind, timeout);
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

    /**
     * A silent Termux is the deck's most common failure, and the honest report is that the result
     * is unknown — so the card offers both readings: wait longer, or stop and take the loss.
     */
    private void reportWatchdog(OperationId operationId, OperationKind kind, long waited) {
        FailureCardView.Failure failure = new FailureCardView.Failure(
                "Связь потеряна",
                "Команда идёт слишком долго",
                "Termux не ответил за " + (waited / 60_000L) + " мин. Так бывает, когда Android "
                        + "выгружает его ради экономии батареи. Часть изменений могла быть уже "
                        + "применена — проверьте рабочую папку перед повтором.",
                false
        );
        failure.recovered(
                "вывод, который уже пришёл, сохранён",
                "сессия и весь диалог сохранены",
                "запрос не повторялся автоматически"
        );
        failure.primary("Ждать ещё", () -> {
            append(ConsoleEntry.Channel.SYSTEM, "Жду ещё; операция " + operationId + ".");
            armWatchdog(operationId, kind, kind.timeoutMs());
        });
        if (kind == OperationKind.AGENT_TURN) {
            failure.secondary("Прервать задачу", this::abortAgent);
        }
        deck.addFailure(failure);
        prefs.saveTranscript(deck.entries());
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
                && nativeModels.isInstalled(selectedModel)
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
        if (!nativeModels.isInstalled(selectedModel)) {
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
                            + "\n\nAndroid может завершить foreground inference при дефиците RAM. "
                            + "Модель не будет заменена скрыто.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Запустить", (dialog, which) -> startServerConfirmed())
                    .show();
            return;
        }
        startServerConfirmed();
    }

    private void startServerConfirmed() {
        append(ConsoleEntry.Channel.SYSTEM,
                "Переношу inference под UID PiDeck и загружаю " + selectedModel.title
                        + " через оптимизированный Arm backend.");
        if (bridgeReady || bridgeConnected) {
            dispatchOperation(
                    OperationKind.STOP_BRIDGE,
                    json("startNativeAfter", true),
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
        stopServerRuntime(true);
    }

    private void launchNativeServer() {
        if (busy) return;
        dispatchOperation(
                OperationKind.START_SERVER,
                requestMetadata(selectedModel.id, false),
                "LOADING " + selectedModel.title,
                operationId -> NativeLlamaController.start(
                        this,
                        operationId,
                        selectedModel,
                        nativeModels
                )
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
        stopServerRuntime(false);
    }

    private void stopServerRuntime(boolean startNativeAfter) {
        if (busy) return;
        JSONObject request = requestMetadata(selectedModel.id, false);
        put(request, "startNativeAfter", startNativeAfter);
        dispatchOperation(
                OperationKind.STOP_SERVER,
                request,
                "STOPPING LLM CORE",
                operationId -> NativeLlamaController.stopThen(
                        this,
                        () -> termux.runRuntime(
                                operationId,
                                OperationKind.STOP_SERVER,
                                "server-stop",
                                "{}"
                        )
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
        String newSessionId = SessionId.create().toString();
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
        long available = Math.min(freeStorage, nativeModels.usableSpace());
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
                operationId -> nativeModels.installAsync(
                        model,
                        modelDownloads,
                        new NativeModelStore.Listener() {
                            @Override
                            public void onProgress(int percent) {
                                if (percent % 10 == 0) runOnUiThread(MainActivity.this::refreshUi);
                            }

                            @Override
                            public void onComplete(boolean valid, String error) {
                                JSONObject output = new JSONObject();
                                put(output, "schemaVersion", 1);
                                put(output, "ok", valid);
                                if (valid) {
                                    put(output, "state", "READY");
                                    put(output, "modelId", model.id);
                                    put(output, "sha256", model.sha256);
                                } else {
                                    JSONObject failure = new JSONObject();
                                    put(failure, "code", "NATIVE_MODEL_INSTALL_FAILED");
                                    put(failure, "message", error);
                                    put(output, "error", failure);
                                }
                                CommandResult result = new CommandResult(
                                        operationId,
                                        OperationKind.INSTALL_MODEL,
                                        output.toString(),
                                        "",
                                        valid ? 0 : 2,
                                        0,
                                        valid ? "" : error
                                );
                                runOnUiThread(() -> handleCommandResult(result, false));
                            }
                        }
                )
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
            public void onComplete(ModelDownloadManager.VerifyResult result) {
                runOnUiThread(() -> {
                    verifying = false;
                    prefs.setModelVerified(model, result.valid);
                    if (result.valid) {
                        verificationPercent = 100;
                        verificationFault = "";
                        append(ConsoleEntry.Channel.SYSTEM,
                                model.title + " · Android SHA‑256 verified. "
                                        + "Перед запуском PiDeck создаст приватную копию.");
                        main.post(() -> installPrivateModel(model));
                    } else if (result.failure
                            == ModelDownloadManager.VerificationFailure.ACCESS_DENIED
                            || result.failure == ModelDownloadManager.VerificationFailure.MISSING
                            || result.failure == ModelDownloadManager.VerificationFailure.IO) {
                        verificationFault = result.error.isBlank()
                                ? "Android не открыл источник модели"
                                : result.error;
                        reportModelAccessFailure(model, verificationFault);
                    } else {
                        verificationFault = result.error.isBlank()
                                ? "SHA‑256 не совпал ("
                                        + result.actualHash.substring(
                                                0,
                                                Math.min(12, result.actualHash.length())
                                        )
                                        + "…)"
                                : result.error;
                        boolean externalDocument = modelDownloads.hasExternalDocument(model);
                        boolean removed = modelDownloads.delete(model);
                        FailureCardView.Failure failure = new FailureCardView.Failure(
                                "Файл повреждён",
                                "Файл модели повреждён",
                                model.title + " не сошлась с закреплённым SHA-256: "
                                        + verificationFault
                                        + " Так бывает при обрыве загрузки или подмене зеркала.",
                                true
                        );
                        failure.recovered(
                                externalDocument
                                        ? "выбранный через проводник файл не удалён"
                                        : removed
                                        ? "битый файл удалён, место освобождено"
                                        : "битый файл помечен непроверенным",
                                "модели и сессии на диске не тронуты"
                        );
                        failure.primary("Скачать заново", () -> confirmDownload(model));
                        deck.addFailure(failure);
                        prefs.saveTranscript(deck.entries());
                    }
                    refreshUi();
                });
            }
        });
    }

    /**
     * Recovery lands where the app puts its own transfers; attaching lands one level up, because a
     * file the user already has is as likely to sit in PiDeck/models as in PiDeck/incoming.
     */
    private static final String INCOMING_FOLDER = "primary%3ADownload%2FPiDeck%2Fincoming";
    private static final String PIDECK_FOLDER = "primary%3ADownload%2FPiDeck";

    private void requestModelDocument(ModelSpec model, String initialFolder) {
        pendingModelDocumentId = model.id;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(
                        Intent.EXTRA_TITLE,
                        model.id + "-" + model.sha256.substring(0, 12) + ".gguf"
                )
                .putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse(
                                "content://com.android.externalstorage.documents/document/"
                                        + initialFolder
                        )
                );
        try {
            startActivityForResult(picker, REQUEST_MODEL_DOCUMENT);
        } catch (RuntimeException error) {
            pendingModelDocumentId = null;
            reportModelAccessFailure(model, "системный проводник недоступен");
        }
    }

    /**
     * A file of the wrong length is a wrong pick, not a permission problem. Saying so keeps the
     * user from hunting for an Android setting that was never in the way.
     */
    private void reportModelSizeMismatch(ModelSpec model, long actualBytes) {
        FailureCardView.Failure failure = new FailureCardView.Failure(
                "ФАЙЛ НЕ ПОДХОДИТ",
                "Это не " + model.title,
                "Ожидается " + model.humanSize()
                        + ", выбрано " + ModelSpec.humanBytes(actualBytes)
                        + ". Размер закреплён в манифесте, поэтому файл не принят до SHA-256.",
                true
        );
        failure.recovered(
                "ничего не скачано и не удалено",
                "выбранный файл оставлен без изменений"
        );
        failure.primary(
                "Выбрать другой файл",
                () -> requestModelDocument(model, PIDECK_FOLDER)
        );
        failure.secondary("Скачать", () -> confirmDownload(model));
        deck.addFailure(failure);
        prefs.saveTranscript(deck.entries());
        refreshUi();
    }

    private void reportModelAccessFailure(ModelSpec model, String detail) {
        String explanation = detail == null || detail.isBlank()
                ? "Android не выдал приложению доступ к общей копии GGUF."
                : detail;
        FailureCardView.Failure failure = new FailureCardView.Failure(
                "НУЖЕН ДОСТУП",
                "Модель видна, но закрыта Android",
                explanation + " Это бывает после переустановки: пакет тот же, "
                        + "но Linux UID приложения уже другой. Файл не повреждён и не удалён.",
                true
        );
        failure.recovered(
                "общая GGUF оставлена без изменений",
                "приватные модели, Pi и сессии не тронуты"
        );
        failure.primary(
                "Выбрать существующий GGUF",
                () -> requestModelDocument(model, INCOMING_FOLDER)
        );
        failure.secondary("Скачать новую копию", () -> confirmDownload(model));
        deck.addFailure(failure);
        prefs.saveTranscript(deck.entries());
        refreshUi();
    }

    private void confirmDownload(ModelSpec model) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        // The current target is dropped before the transfer starts, so its bytes count as free.
        long available = freeStorage + modelDownloads.reclaimableBytes(model);
        if (available < ModelCatalog.requiredStorageForFreshInstall(model)) {
            reportNoRoomFor(model, available);
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
                        + "затем проверен и скопирован в приватный PiDeck store."
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
                })
                .show();
    }

    /**
     * Running out of space is a choice, not a breakage: the deck offers the largest profile that
     * still fits rather than telling the user to go and delete things.
     */
    private void reportNoRoomFor(ModelSpec model, long available) {
        FailureCardView.Failure failure = new FailureCardView.Failure(
                "Не хватает места",
                "Не хватит места на " + model.tier,
                model.title + " просит " + humanBytes(
                        ModelCatalog.requiredStorageForFreshInstall(model)
                ) + " вместе с приватной копией; свободно " + humanBytes(available) + ".",
                false
        );
        failure.recovered("загрузка не начата", "уже скачанные модели не тронуты");
        ModelSpec fallback = largestModelThatFits(available, model);
        if (fallback == null) {
            failure.primary("Проверить место снова", () -> {
                updateCapacity();
                refreshUi();
            });
        } else {
            failure.primary(
                    "Взять " + fallback.tier + " · " + fallback.humanSize(),
                    () -> confirmDownload(fallback)
            );
        }
        deck.addFailure(failure);
        prefs.saveTranscript(deck.entries());
    }

    private ModelSpec largestModelThatFits(long available, ModelSpec rejected) {
        ModelSpec best = null;
        for (ModelSpec candidate : modelCatalog.all()) {
            if (candidate.equals(rejected)) continue;
            if (available < ModelCatalog.requiredStorageForFreshInstall(candidate)) continue;
            if (totalRam < candidate.minimumAvailableMiB * 1_048_576L) continue;
            if (best == null || candidate.bytes > best.bytes) best = candidate;
        }
        return best;
    }

    private void chooseModel(ModelSpec model) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        if (!nativeModels.isInstalled(model) && !modelDownloads.isDownloaded(model)) {
            confirmDownload(model);
            return;
        }
        selectedModel = model;
        modelSelectionRequired = false;
        prefs.setSelectedModelId(model.id);
        serverReady = false;
        verificationFault = "";
        if (!nativeModels.isInstalled(model) && !prefs.isModelVerified(model)) {
            verifyModel(model);
        }
        append(ConsoleEntry.Channel.SYSTEM,
                "Активный профиль → " + model.title + ". Перезапустите LLM-ядро.");
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
                    refreshUi();
                })
                .show();
    }

    /** ЯДРО now owns the model matrix and the core controls; boot steps deep-link into it. */
    private void openCoreRoot() {
        onTabSelected(TabBarView.TAB_CORE);
    }

    private void renderCoreRoot() {
        CoreRootView.State state = new CoreRootView.State();
        state.schemeId = palette.id;
        state.textScale = textScale;
        state.askBeforeOverwrite = prefs.askBeforeOverwrite();

        for (ModelSpec model : modelCatalog.all()) state.models.add(modelRow(model));

        state.accessProfileLabel = accessProfile.label;
        state.accessProfileNote = accessProfile.description;
        for (AccessProfile profile : AccessProfile.values()) {
            if (profile == accessProfile) continue;
            state.accessProfiles.add(new CoreRootView.ActionRow(
                    "Доступ → " + profile.label,
                    profile == AccessProfile.AUTONOMOUS
                            ? "явное согласие на высокий риск"
                            : profile.description,
                    profile == AccessProfile.AUTONOMOUS ? palette.warn : palette.accent,
                    () -> changeAccessProfile(profile)
            ));
        }

        state.maintenance.add(new CoreRootView.ActionRow(
                "Обновить Pi", "восстановить закреплённую сборку и проверить целостность",
                palette.text, this::updateAgent
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                "Перезапустить LLM", "перечитать выбранный GGUF", palette.text, this::startServer
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                "Новая сессия", "прошлая уезжает в ~/.pideck/session-archive",
                palette.text, this::newSession
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                "Открыть Termux", "runtime-контур деки", palette.text, this::openTermux
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                "Скопировать команду связи", "починить handshake Termux",
                palette.text, this::copyHandshakeAndOpen
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                "Очистить консоль", "сессия Pi при этом сохраняется",
                palette.text, this::clearConsole
        ));
        if (busy) {
            state.maintenance.add(new CoreRootView.ActionRow(
                    "Прервать задачу", "структурный RPC abort",
                    palette.errorText, this::abortAgent
            ));
        }

        state.info.add(new CoreRootView.InfoRow("рабочая папка", workspaceLabel(), palette.text));
        state.info.add(new CoreRootView.InfoRow(
                "termux", termuxEnvironment.installed
                ? termuxEnvironment.version + " / " + termuxEnvironment.sourceLabel()
                : "не установлен",
                termuxEnvironment.signerTrusted() ? palette.ok : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                "termux:api",
                termuxEnvironment.apiCompatible
                        ? termuxEnvironment.apiVersion + " / wake-lock"
                        : termuxEnvironment.apiInstalled
                        ? termuxEnvironment.apiVersion + " / несовместим"
                        : "не установлен",
                termuxEnvironment.apiCompatible ? palette.ok : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                "канал управления", linkConfirmed ? "связан" : "нет связи",
                linkConfirmed ? palette.accent : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                "pi runtime", prefs.isCoreReady() ? "готов" : "не установлен",
                prefs.isCoreReady() ? palette.ok : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                "llm сервер", serverReady ? "127.0.0.1:8080" : "остановлен",
                serverReady ? palette.accent : palette.muted
        ));
        NativeLlamaService.Snapshot nativeState = NativeLlamaService.snapshot(this);
        state.info.add(new CoreRootView.InfoRow(
                "inference",
                nativeState.isStartingOrReady()
                        ? "PiDeck foreground · " + nativeState.profile
                        : serverReady ? "legacy Termux · нужен restart" : "остановлен",
                nativeState.isStartingOrReady() ? palette.ok : serverReady ? palette.warn : palette.muted
        ));
        state.info.add(new CoreRootView.InfoRow(
                "rpc bridge", bridgeReady ? "authenticated / 127.0.0.1" : "остановлен",
                bridgeReady ? palette.ok : palette.muted
        ));
        state.info.add(new CoreRootView.InfoRow(
                "сеть инструментов",
                accessProfile.toolNetworkPossible ? "возможна" : "нет shell",
                accessProfile.toolNetworkPossible ? palette.warn : palette.ok
        ));
        state.info.add(new CoreRootView.InfoRow("изоляция ОС", "не реализована", palette.warn));
        state.info.add(new CoreRootView.InfoRow(
                "телефон",
                humanBytes(totalRam) + " RAM · " + cpuThreads + " CPU · "
                        + humanBytes(freeStorage) + " свободно · "
                        + (isMetered() ? "сеть тарифицируется" : "сеть без лимита"),
                palette.muted
        ));

        if (serverReady) {
            state.stopCoreLabel = "Остановить ядро · освободить "
                    + humanBytes(selectedModel.estimatedPeakBytes());
            state.onStopCore = this::stopServer;
        }
        deck.renderCore(state);
    }

    private CoreRootView.ModelRow modelRow(ModelSpec model) {
        ModelDownloadManager.State download = modelDownloads.state(model);
        boolean privateReady = nativeModels.isInstalled(model);
        boolean incoming = modelDownloads.isDownloaded(model);
        boolean verified = prefs.isModelVerified(model);
        boolean selected = model.equals(selectedModel);
        boolean fits = totalRam >= model.minimumAvailableMiB * 1_048_576L;

        String meta = model.humanSize() + " · контекст " + model.recommendedContext;
        if (model.equals(modelCatalog.recommend(availableRam, lowMemory, freeStorage))) {
            meta += " · рекомендуем";
        }

        String state;
        int stateColor = palette.muted;
        int percent = -1;
        String actionLabel = null;
        Runnable action = null;
        // Offered exactly where the deck has no bytes of its own: someone holding the pinned
        // artifact already should not pay for it twice.
        boolean canAttach = false;

        if (!fits) {
            // Nothing else about the row matters if the phone cannot hold the weights.
            state = "не хватит RAM (" + humanBytes(totalRam) + ")";
        } else if (privateReady && selected && serverReady) {
            state = "активна";
            stateColor = palette.ok;
        } else if (privateReady) {
            state = "загружена, готова к запуску";
            stateColor = palette.ok;
            actionLabel = selected ? "Перезапустить" : "Выбрать";
            action = () -> {
                chooseModel(model);
                if (selected) startServer();
            };
        } else if (download.isActive()) {
            state = "скачивается · " + humanBytes(download.downloadedBytes)
                    + " из " + humanBytes(download.totalBytes);
            stateColor = palette.accent;
            percent = download.percent();
            actionLabel = "Отменить";
            action = () -> {
                modelDownloads.cancel(model);
                refreshUi();
            };
        } else if (download.phase == ModelDownloadManager.Phase.FAILED) {
            state = "сбой загрузки: "
                    + ModelDownloadManager.failureLabel(download.reason).toLowerCase(Locale.ROOT);
            stateColor = palette.errorText;
            actionLabel = "Повторить";
            action = () -> confirmDownload(model);
            canAttach = true;
        } else if (incoming && verified) {
            state = "проверена, ждёт приватной установки";
            stateColor = palette.warn;
            actionLabel = "Установить";
            action = () -> installPrivateModel(model);
        } else if (incoming) {
            state = "ждёт проверки SHA-256";
            stateColor = palette.warn;
            actionLabel = "Проверить";
            action = () -> verifyModel(model);
        } else {
            state = "не скачана";
            actionLabel = "Скачать";
            action = () -> confirmDownload(model);
            canAttach = true;
        }

        String secondaryLabel = null;
        Runnable secondary = null;
        if (incoming) {
            secondaryLabel = "Удалить исходник";
            secondary = () -> confirmDeleteModel(model);
        } else if (canAttach && fits) {
            secondaryLabel = "Подключить файл";
            secondary = () -> requestModelDocument(model, PIDECK_FOLDER);
        }

        return new CoreRootView.ModelRow(
                model.title,
                meta,
                state,
                stateColor,
                selected,
                fits,
                percent,
                actionLabel,
                action,
                secondaryLabel,
                secondary
        );
    }

    /** The listing is a Termux round trip, so it is asked for once per visit unless forced. */
    private void listSessions(boolean force) {
        if (busy || !prefs.isCoreReady() || !termuxEnvironment.canRunCommands()) return;
        if (sessionsRequested && !force) return;
        sessionsRequested = true;
        dispatchOperation(
                OperationKind.LIST_SESSIONS,
                new JSONObject(),
                "Читаю сессии",
                operationId -> termux.runRuntime(
                        operationId, OperationKind.LIST_SESSIONS, "list-sessions", "{}"
                )
        );
    }

    private void archiveSessions() {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        dispatchOperation(
                OperationKind.ARCHIVE_SESSIONS,
                new JSONObject(),
                "Архивирую сессии",
                operationId -> termux.runRuntime(
                        operationId, OperationKind.ARCHIVE_SESSIONS, "archive-sessions", "{}"
                )
        );
    }

    private void renderSessionsRoot() {
        SessionsRootView.State state = new SessionsRootView.State();
        state.onNewSession = bridgeReady && !busy ? this::newSession : null;

        String activeSession = prefs.sessionId();
        long now = System.currentTimeMillis();
        SessionsRootView.Group today = new SessionsRootView.Group("Сегодня");
        SessionsRootView.Group earlier = new SessionsRootView.Group("Раньше");
        for (int index = 0; index < sessions.length(); index++) {
            JSONObject value = sessions.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("id", "");
            long updated = value.optLong("updatedAtEpochMs", 0L);
            long age = now - updated;
            boolean current = !id.isEmpty() && id.equals(activeSession);
            String title = value.optString("title", "");
            if (title.isBlank()) title = "Сессия " + shortId(id);

            String meta = messagesLabel(value.optInt("messages", 0))
                    + " · " + humanBytes(value.optLong("bytes", 0L))
                    + " · " + (age < 86_400_000L ? clockTime(updated) : calendarDate(updated));

            SessionsRootView.SessionRow row = new SessionsRootView.SessionRow(
                    title,
                    meta,
                    current,
                    age > 7L * 86_400_000L,
                    current || !isResumable(id) ? null : () -> resumeSession(id)
            );
            (age < 86_400_000L ? today : earlier).rows.add(row);
        }
        if (!today.rows.isEmpty()) state.groups.add(today);
        if (!earlier.rows.isEmpty()) state.groups.add(earlier);

        if (!sessionsFault.isBlank()) {
            state.emptyNote = sessionsFault.contains("UNKNOWN_COMMAND")
                    && sessionsFault.contains("list-sessions")
                    ? "Установленный Pi runtime нужно обновить. Откройте Ядро → "
                    + "Обновить Pi, затем вернитесь в Сессии."
                    : "Список сессий прочитать не удалось: " + sessionsFault;
        } else if (state.groups.isEmpty()) {
            state.emptyNote = sessionsRequested
                    ? "В ~/.pideck/sessions пока пусто — первая сессия появится после "
                    + "первого разговора."
                    : "Список читается из Termux при открытии этого экрана.";
        } else {
            state.emptyNote = "Тап по сессии переключает на неё Pi. Локальный транскрипт при "
                    + "этом не подменяется: дека не переписывает то, что вы уже видели.";
        }

        state.footer = sessionCount + " " + sessionsLabel(sessionCount)
                + " · " + humanBytes(sessionBytes);
        if (sessionCount > 0) {
            state.archiveLabel = "Архивировать старые";
            state.onArchive = this::archiveSessions;
        }
        deck.renderSessions(state);
    }

    /**
     * The bridge keys a session by the UUID the deck handed it, so a listing entry can only be
     * resumed when its name is still one of those.
     */
    private boolean isResumable(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            SessionId.parse(id);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void resumeSession(String id) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        try {
            prefs.setSessionId(id, true);
        } catch (RuntimeException error) {
            toast("Эту сессию нельзя продолжить: " + readableException(error));
            return;
        }
        onTabSelected(TabBarView.TAB_CONSOLE);
        append(ConsoleEntry.Channel.SYSTEM,
                "Продолжаю сессию " + shortId(id) + ". Прошлые сообщения остались в Pi; "
                        + "в консоли они не воспроизводятся.");
        if (serverReady) main.post(this::restartBridge);
    }

    private String shortId(String id) {
        if (id == null || id.isEmpty()) return "без имени";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String messagesLabel(int count) {
        String noun = plural(count, "сообщение", "сообщения", "сообщений");
        return count + " " + noun;
    }

    private String sessionsLabel(int count) {
        return plural(count, "сессия", "сессии", "сессий");
    }

    private String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        if (mod100 >= 11 && mod100 <= 14) return many;
        return switch (count % 10) {
            case 1 -> one;
            case 2, 3, 4 -> few;
            default -> many;
        };
    }

    private String clockTime(long epochMs) {
        return new java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new java.util.Date(epochMs));
    }

    private String calendarDate(long epochMs) {
        return new java.text.SimpleDateFormat("d MMM", Locale.getDefault())
                .format(new java.util.Date(epochMs));
    }

    /**
     * Colours are baked into the views as they are built, so the activity is recreated instead of
     * walking the hierarchy. The transcript already lives in preferences, and a result that lands
     * during the restart is recovered from the durable per-operation store in {@link #onResume()}.
     */
    private void switchColorScheme(String schemeId) {
        if (busy) {
            toast("Дождитесь завершения текущей команды");
            return;
        }
        if (palette.id.equals(schemeId)) return;
        prefs.setColorScheme(Palette.forId(schemeId).id);
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
        if (!value) main.post(this::dispatchQueuedPrompt);
    }

    private void dispatchQueuedPrompt() {
        if (busy || queuedPrompt == null) return;
        String prompt = queuedPrompt;
        queuedPrompt = null;
        if (!canRunAgent()) {
            append(ConsoleEntry.Channel.ERROR,
                    "Промпт из очереди не отправлен: ядро больше не готово принимать задачи.");
            return;
        }
        dispatchRpcTurn(prompt);
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
        if (busy || !serverReady || !nativeModels.isInstalled(selectedModel)) return;
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
                            deck.dismissDecision();
                            currentApprovalId = null;
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
        boolean sessionTransitionPending = active != null
                && active.kind == OperationKind.NEW_SESSION
                && !active.state.isTerminal();
        String remoteSession = SessionContract.authoritativeRemoteSession(
                prefs.sessionId(),
                state.optString("sessionId", null),
                sessionTransitionPending
        );
        if (remoteSession != null) {
            prefs.setSessionId(remoteSession, false);
            append(ConsoleEntry.Channel.SYSTEM,
                    "Session cursor восстановлен из авторитетного состояния bridge.");
        }
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
                    String verb = traceVerb(event.payload.optString("toolName", "tool"));
                    String argument = traceArgument(event.payload.optString("args", ""));
                    deck.addTrace(verb, argument, "");
                    prefs.saveTranscript(deck.entries());
                    // The row is what makes a long turn legible, so it moves on every event.
                    deck.setExecutionLabel(verb + " " + argument);
                }
            }
            case TOOL_CALL_COMPLETED -> {
                boolean failed = event.payload.optBoolean("isError", false);
                deck.completeTrace(failed ? "ошибка" : "готово");
                if (failed) {
                    append(ConsoleEntry.Channel.ERROR,
                            "Инструмент " + event.payload.optString("toolName", "tool")
                                    + " вернул ошибку.\n"
                                    + event.payload.optString("resultPreview"));
                }
            }
            case APPROVAL_REQUESTED -> showApproval(event);
            case APPROVAL_RESOLVED -> {
                String approvalId = event.payload.optString("approvalId");
                if (approvalId.equals(currentApprovalId)) {
                    // Resolved elsewhere, or expired: the pending affordance goes with it.
                    if (approvalDialog != null) approvalDialog.dismiss();
                    if (deck.hasPendingDecision()) {
                        deck.dismissDecision();
                        currentApprovalId = null;
                        append(ConsoleEntry.Channel.SYSTEM,
                                "Решение больше не ждёт ответа: bridge закрыл запрос по "
                                        + event.payload.optString("source", "таймауту") + ".");
                    }
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
        if (!ownsUi) {
            boolean recoverableSessionResult = record.kind == OperationKind.NEW_SESSION
                    && SessionContract.mayApplyRecoveredSessionResult(
                            record.uiConsumed,
                            operations.activeOperationId()
                    );
            if (!recoverableSessionResult) {
                operations.markConsumed(event.operationId);
                return;
            }
        }
        cancelWatchdog(event.operationId);
        setBusy(false, null);
        thermalWarned = false;
        // A turn that ended can no longer be waiting on a decision.
        deck.dismissDecision();
        currentApprovalId = null;
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

        JSONObject decision = event.payload.optJSONObject("decision");
        if (decision != null && "overwrite".equals(decision.optString("kind"))) {
            showOverwriteDecision(event, approvalId, decision);
            return;
        }
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

    /**
     * A pending overwrite is a decision, not an interruption: it is drawn where the agent stopped
     * rather than thrown over the transcript as a dialog, and the turn simply waits.
     *
     * <p>Turning off «спрашивать» in ЯДРО only ever silences files the agent created in this same
     * session — the gate still asks about everything else, and about every shell command.
     */
    private void showOverwriteDecision(
            BridgeEvent event, String approvalId, JSONObject decision
    ) {
        boolean selfCreated = decision.optBoolean("selfCreated", false);
        if (selfCreated && !prefs.askBeforeOverwrite()) {
            sendApproval(event, true);
            return;
        }
        currentApprovalId = approvalId;
        DecisionCardView.Decision model = new DecisionCardView.Decision(
                approvalId,
                decision.optString("path", ""),
                decision.optString("reason", ""),
                decision.optInt("addedLines", 0),
                decision.optInt("removedLines", 0),
                selfCreated
        );
        JSONArray preview = decision.optJSONArray("preview");
        for (int index = 0; preview != null && index < preview.length(); index++) {
            String line = preview.optString(index, "");
            if (!line.isBlank()) model.preview.add(line);
        }
        AtomicBoolean responded = new AtomicBoolean(false);
        deck.addDecision(model, (id, confirmed) -> {
            if (!responded.compareAndSet(false, true)) return;
            currentApprovalId = null;
            sendApproval(event, confirmed);
            append(ConsoleEntry.Channel.SYSTEM, confirmed
                    ? "Разрешил перезаписать " + model.fileName() + "."
                    : "Оставил " + model.fileName() + " без изменений.");
            refreshUi();
        });
        prefs.saveTranscript(deck.entries());
        refreshUi();
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
        append(ConsoleEntry.Channel.SYSTEM, "Access profile → " + target.label + ".");
        if (serverReady) main.post(this::startBridge);
        else refreshUi();
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

    /**
     * The workspace is fixed by the Termux-side runtime scripts, so the deck shows where the
     * agent is confined rather than offering to move it.
     */
    private String workspaceLabel() {
        return TermuxBridge.WORKSPACE.replace(TermuxBridge.HOME, "~");
    }

    /** The gate renames the mutating built-ins; the trace shows the verb the user recognises. */
    private String traceVerb(String toolName) {
        String verb = toolName == null ? "tool" : toolName.trim();
        if (verb.startsWith("pideck_")) verb = verb.substring("pideck_".length());
        return verb.isEmpty() ? "tool" : verb;
    }

    /**
     * The trace shows one argument, not the whole call: whichever field names what was touched,
     * falling back to the raw payload when the tool is one the deck does not know.
     */
    private String traceArgument(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) return "";
        String value = rawArgs.trim();
        try {
            JSONObject parsed = new JSONObject(value);
            for (String key : new String[]{"path", "file", "filePath", "command", "pattern", "query"}) {
                String candidate = parsed.optString(key, "");
                if (!candidate.isBlank()) {
                    value = candidate;
                    break;
                }
            }
        } catch (JSONException ignored) {
            // Not an object; the bounded text the bridge sent is the best we have.
        }
        value = clean(value).replace('\n', ' ').trim();
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
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

    private boolean supportsArm64() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
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

}
