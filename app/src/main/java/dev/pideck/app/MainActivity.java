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
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
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
import dev.pideck.app.core.AgentMode;
import dev.pideck.app.core.BridgeEvent;
import dev.pideck.app.core.BridgeFaultPolicy;
import dev.pideck.app.core.BridgeTokenStore;
import dev.pideck.app.core.CommandEvents;
import dev.pideck.app.core.CommandResult;
import dev.pideck.app.core.DeckPreferences;
import dev.pideck.app.core.GenerationSpeed;
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
import dev.pideck.app.core.PendingPromptDispatch;
import dev.pideck.app.core.OperationStore;
import dev.pideck.app.core.PiJsonOutput;
import dev.pideck.app.core.RpcBridgeClient;
import dev.pideck.app.core.RuntimeAssetBundle;
import dev.pideck.app.core.RuntimeScripts;
import dev.pideck.app.core.SessionContract;
import dev.pideck.app.core.SessionId;
import dev.pideck.app.core.SessionContextUsage;
import dev.pideck.app.core.StallWatchdog;
import dev.pideck.app.core.StartupPolicy;
import dev.pideck.app.core.SystemPromptSettings;
import dev.pideck.app.core.TermuxBridge;
import dev.pideck.app.core.TermuxEnvironment;
import dev.pideck.app.core.UiLanguage;
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
    /** Warming takes two steps, server then bridge. Beyond that a queued prompt is being lied to. */
    private static final int MAX_QUEUED_WARM_ATTEMPTS = 3;
    /** Ignore accidental one-frame edits while still hiding the 19-30 second model load. */
    private static final long COMPOSER_WARM_DELAY_MS = 900L;

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
    private AgentMode agentMode;
    private UiLanguage uiLanguage;
    private ModelSpec selectedModel;
    private long totalRam;
    private long availableRam;
    private long freeStorage;
    private int cpuThreads;
    private boolean lowMemory;
    private boolean modelSelectionRequired;
    private boolean activityStarted;
    private boolean composerHasText;
    private boolean composerWarmAttempted;

    private boolean linkConfirmed;
    private boolean serverReady;
    private boolean bridgeReady;
    private boolean bridgeConnected;
    private String bridgeFault = "";
    private boolean busy;
    private String busyPhase = "";
    private boolean verifying;
    private int verificationPercent;
    private String verificationFault = "";
    private float textScale;
    /** A prompt typed while a turn was running, or at a cold core; dispatched once the deck can. */
    private String queuedPrompt;
    private int queuedWarmAttempts;
    /** The heat warning is worth one line per turn, not one per event. */
    private boolean thermalWarned;
    /** Last listing of ~/.pideck/sessions, as the Termux runtime reported it. */
    private JSONArray sessions = new JSONArray();
    private int sessionCount;
    private long sessionBytes;
    private boolean sessionsRequested;
    private String sessionsFault = "";
    private Runnable watchdog;
    private StallWatchdog stallState;
    private int heartbeatTick;
    private boolean startupProbeAttempted;
    private AlertDialog approvalDialog;
    private String currentApprovalId;
    private String observedBridgeInstance;
    private String pendingModelDocumentId;
    private SessionContextUsage contextUsage;
    private boolean contextCompacting;
    private String smartCompactionAttemptSession;
    private long smartCompactionAttemptTokens = -1L;
    private boolean inferenceActive;
    private long turnStartedAtUptimeMs;
    private long firstOutputAtUptimeMs;
    private long streamedCharacters;
    private long lastRateUpdateUptimeMs;
    private String pendingPromptAfterCompaction;
    private String pendingPromptAfterNewSession;
    private final PendingPromptDispatch pendingRpcPrompt = new PendingPromptDispatch();
    private AlertDialog contextWarningDialog;

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

    private final Runnable composerWarmup = this::maybeWarmCoreForComposerIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new DeckPreferences(this);
        operationStore = new OperationStore(this);
        operations = new OperationCoordinator(operationStore);
        try {
            operations.failRuntimeInstallStartedBefore(
                    getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime
            );
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        palette = Palette.forId(prefs.colorScheme());
        accessProfile = prefs.accessProfile();
        agentMode = prefs.agentMode();
        uiLanguage = prefs.uiLanguage();
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
        modelDownloads.releaseStaleGrants(modelCatalog.all());
        updateCapacity();
        String savedModel = prefs.selectedModelId();
        selectedModel = modelCatalog.byId(savedModel).orElseGet(
                () -> modelCatalog.recommend(availableRam, lowMemory, freeStorage)
        );
        contextUsage = SessionContextUsage.unknown(selectedModel.recommendedContext);
        modelSelectionRequired = savedModel == null || modelCatalog.byId(savedModel).isEmpty();
        if (savedModel != null && modelSelectionRequired) prefs.clearSelectedModelId();
        linkConfirmed = prefs.isCoreReady();
        // The foreground service outlives the Activity, and its state is app-private: reading it
        // here means a surviving core is known before Termux has been asked anything at all.
        NativeLlamaService.Snapshot bootSnapshot = NativeLlamaService.snapshot(this);
        serverReady = "READY".equals(bootSnapshot.state)
                && selectedModel.id.equals(bootSnapshot.modelId);
        OperationRecord restored = operations.active();
        busy = restored != null && !restored.state.isTerminal();

        textScale = DeckStyle.normalizeScale(prefs.textScale());
        deck = new DeckView(this, this, palette, textScale, uiLanguage);
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
                } else if (active.kind == OperationKind.AGENT_TURN
                        || active.kind == OperationKind.COMPACT_SESSION) {
                    setInferenceActive(true, t(
                            "Задача продолжается…", "Task in progress…"
                    ));
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        CommandEvents.addListener(this);
        main.post(heartbeat);
        main.post(this::probeRuntimeOnLaunch);
        scheduleComposerWarmup();
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
        activityStarted = false;
        CommandEvents.removeListener(this);
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(composerWarmup);
        prefs.saveTranscript(deck.entries());
    }

    @Override
    protected void onDestroy() {
        if (approvalDialog != null) approvalDialog.dismiss();
        if (contextWarningDialog != null) contextWarningDialog.dismiss();
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
        if (!attached.attached() && !heldByAnotherModel(uri)) {
            // A refused pick must not leave a standing read capability on a file the deck will
            // never open.
            modelDownloads.releaseDocument(uri);
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
            toast(t("Промпт больше лимита 64 KiB", "Prompt exceeds the 64 KiB limit"));
            return;
        }
        if (!canRunAgent()) {
            // A prompt typed at a cold core is the clearest possible request to start it.
            if (StartupPolicy.queuesUntilReady(false, canWarmCore(), queuedPrompt != null)) {
                queuedPrompt = prompt;
                queuedWarmAttempts = 0;
                deck.acknowledgePrompt(prompt);
                deck.setQueueCount(1);
                append(ConsoleEntry.Channel.USER, prompt);
                append(ConsoleEntry.Channel.SYSTEM, t(
                        "Прогреваю ядро и отправлю запрос, как только Pi ответит.",
                        "Warming the core; the prompt goes out as soon as Pi answers."
                ));
                warmCore();
                refreshUi();
                return;
            }
            refreshUi();
            toast(canWarmCore()
                    ? t("В очереди уже есть промпт", "A prompt is already queued")
                    : t(
                            "Сначала завершите boot sequence",
                            "Complete the boot sequence first"
                    ));
            return;
        }
        if (busy) {
            // The field stays live during a turn, so a second prompt waits rather than bouncing.
            if (queuedPrompt != null) {
                toast(t(
                        "В очереди уже есть промпт",
                        "A prompt is already queued"
                ));
                return;
            }
            queuedPrompt = prompt;
            queuedWarmAttempts = 0;
            deck.acknowledgePrompt(prompt);
            deck.setQueueCount(1);
            append(ConsoleEntry.Channel.USER, prompt);
            append(ConsoleEntry.Channel.SYSTEM, t(
                    "Отправлю, как только текущая задача закончится.",
                    "I will send it as soon as the current task finishes."
            ));
            return;
        }

        if (needsLargeContextChoice()) {
            showLargeContextChoice(prompt);
            return;
        }
        sendPromptNow(prompt);
    }

    private void sendPromptNow(String prompt) {
        append(ConsoleEntry.Channel.USER, prompt);
        warnIfHot();
        dispatchRpcTurn(prompt);
    }

    private void showLargeContextChoice(String prompt) {
        if (contextWarningDialog != null) contextWarningDialog.dismiss();
        contextWarningDialog = new AlertDialog.Builder(this)
                .setTitle(t("Большая сессия · ", "Large session · ")
                        + contextUsage.percent + "%")
                .setMessage(t("Подготовка истории займёт ", "Preparing the history will take ")
                        + contextDelayHint()
                        + t(
                                ". Можно сначала сжать историю или начать чистую сессию. "
                                        + "Ваш текст останется в поле до подтверждения отправки.",
                                ". You can compact the history first or start a clean session. "
                                        + "Your text will remain in the field until you confirm sending."
                        ))
                .setPositiveButton(t(
                        "Сжать сначала", "Compact first"
                ), (dialog, which) -> {
                    pendingPromptAfterCompaction = prompt;
                    deck.setComposerDispatchPending(true);
                    compactSession();
                })
                .setNeutralButton(t("Новая сессия", "New session"), (dialog, which) -> {
                    pendingPromptAfterNewSession = prompt;
                    deck.setComposerDispatchPending(true);
                    newSession();
                })
                .setNegativeButton(t("Продолжить", "Continue"),
                        (dialog, which) -> sendPromptNow(prompt))
                .create();
        contextWarningDialog.setOnDismissListener(dialog -> contextWarningDialog = null);
        contextWarningDialog.show();
    }

    /**
     * A queued prompt is already visible in the transcript and no longer lives in the composer.
     * Keep it in the queue while the user chooses how much history to replay, then let the normal
     * completion path drain it after a compaction or a new session. Continuing consumes it exactly
     * once without appending a duplicate user entry.
     */
    private void showQueuedLargeContextChoice() {
        if (contextWarningDialog != null) return;
        String usageLabel = contextUsage != null && contextUsage.known()
                ? contextUsage.percent + "%"
                : t("размер неизвестен", "size unknown");
        contextWarningDialog = new AlertDialog.Builder(this)
                .setTitle(t("Большая сессия в очереди · ", "Large queued session · ")
                        + usageLabel)
                .setMessage(t(
                        "Ядро готово, но подготовка истории займёт ",
                        "The core is ready, but preparing the history will take "
                ) + contextDelayHint() + t(
                        ". Запрос уже сохранён в очереди: можно сначала сжать историю, "
                                + "начать чистую сессию или отправить его с полной историей.",
                        ". The prompt is already safe in the queue: you can compact the history, "
                                + "start a clean session, or send it with the full history."
                ))
                .setPositiveButton(t("Сжать сначала", "Compact first"), (dialog, which) -> {
                    compactSession();
                    retryQueuedPromptIfIdle();
                })
                .setNeutralButton(t(
                        "Новая сессия · быстрее всего", "New session · fastest"
                ), (dialog, which) -> {
                    newSession();
                    retryQueuedPromptIfIdle();
                })
                .setNegativeButton(t("Продолжить", "Continue"),
                        (dialog, which) -> dispatchQueuedPromptNow())
                // Cancelling would strand text that has already left the composer.
                .setCancelable(false)
                .create();
        contextWarningDialog.setOnDismissListener(dialog -> contextWarningDialog = null);
        contextWarningDialog.show();
    }

    private boolean needsLargeContextChoice() {
        return contextUsage != null && contextUsage.shouldCompactSoon();
    }

    private boolean needsQueuedContextChoice() {
        return StartupPolicy.asksQueuedContextChoice(prefs.hasSession(), contextUsage);
    }

    private void retryQueuedPromptIfIdle() {
        if (!busy) main.post(this::dispatchQueuedPrompt);
    }

    @Override
    public void onCompactSession() {
        compactSession();
    }

    @Override
    public void onNewSessionRequested() {
        newSession();
    }

    @Override
    public void onAgentModeChosen(AgentMode mode) {
        changeAgentMode(mode);
    }

    @Override
    public void onMaximumSpeedChanged(boolean enabled) {
        prefs.setMaximumSpeed(enabled);
        applyScreenSpeedPolicy();
        refreshUi();
    }

    @Override
    public void onAutostartCoreChanged(boolean enabled) {
        prefs.setAutostartCore(enabled);
        append(ConsoleEntry.Channel.SYSTEM, enabled
                ? t(
                        "Ядро будет грузиться при открытии деки.",
                        "The core will load when the deck opens."
                )
                : t(
                        "Ядро будет грузиться по первому запросу или по кнопке.",
                        "The core will load on the first prompt or on demand."
                ));
        // Turning it on while looking at a cold deck means it should be warm now, not next launch.
        if (enabled) warmCore();
        refreshUi();
    }

    @Override
    public void onSmartCompactionChanged(boolean enabled) {
        prefs.setSmartCompaction(enabled);
        append(ConsoleEntry.Channel.SYSTEM, enabled
                ? t(
                        "Умное сжатие будет создавать checkpoint в простое около 55% контекста.",
                        "Smart compaction will create an idle checkpoint near 55% context."
                )
                : t(
                        "Автоматическое сжатие отключено; ручная кнопка остаётся доступна.",
                        "Automatic compaction is off; the manual action remains available."
                ));
        if (enabled) main.post(this::maybeSmartCompactSession);
        refreshUi();
    }

    @Override
    public void onComposerIntentChanged(boolean hasText) {
        composerHasText = hasText;
        main.removeCallbacks(composerWarmup);
        if (!hasText) {
            composerWarmAttempted = false;
            return;
        }
        scheduleComposerWarmup();
    }

    @Override
    public void onLanguageChosen(UiLanguage language) {
        UiLanguage target = language == null ? UiLanguage.RUSSIAN : language;
        if (target == uiLanguage) return;
        if (busy) {
            toast(t(
                    "Дождитесь завершения текущей команды",
                    "Wait for the current command to finish"
            ));
            return;
        }
        prefs.saveTranscript(deck.entries());
        prefs.setUiLanguage(target);
        uiLanguage = target;
        recreate();
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
                ? t(
                        "Телефон сильно нагрелся — Android режет частоту, ответ будет заметно дольше.",
                        "The phone is very hot — Android is throttling it, so the answer will take noticeably longer."
                )
                : t(
                        "Телефон нагрелся — скорость упала примерно вдвое.",
                        "The phone is hot — generation speed has dropped by roughly half."
                ));
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
        toast(t("Путь скопирован — открываю Termux", "Path copied — opening Termux"));
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
            toast(t(
                    "Дождитесь завершения текущей команды",
                    "Wait for the current command to finish"
            ));
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
                ? t(
                        "Доступ выдан. Спрошу перед изменением файлов, которые агент не создавал сам.",
                        "Access granted. I will ask before changing files the agent did not create."
                )
                : t(
                        "Доступ выдан. Файлы в рабочей папке агент меняет без отдельного вопроса.",
                        "Access granted. The agent may change files in the workspace without another prompt."
                ));
        refreshUi();
    }

    @Override
    public void onAskBeforeOverwriteChanged(boolean askBeforeOverwrite) {
        prefs.setAskBeforeOverwrite(askBeforeOverwrite);
        refreshUi();
    }

    private void editSystemPrompt() {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }

        DeckStyle dialogStyle = new DeckStyle(this, palette, textScale);
        int padding = dialogStyle.dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, dialogStyle.dp(6), padding, 0);

        TextView explanation = dialogStyle.bodySecondary(
                "«Дополнить» сохраняет встроенные инструкции и инструменты Pi. "
                        + "«Заменить полностью» убирает встроенный системный промпт."
        );
        content.addView(explanation);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        RadioButton appendMode = new RadioButton(this);
        appendMode.setText("Дополнить · рекомендуется");
        appendMode.setTextColor(palette.text);
        RadioButton replaceMode = new RadioButton(this);
        replaceMode.setText("Заменить полностью · расширенный режим");
        replaceMode.setTextColor(palette.warn);
        modes.addView(appendMode);
        modes.addView(replaceMode);
        LinearLayout.LayoutParams modesLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        modesLayout.topMargin = dialogStyle.dp(12);
        content.addView(modes, modesLayout);

        SystemPromptSettings.Mode savedMode = prefs.systemPromptMode();
        (savedMode == SystemPromptSettings.Mode.REPLACE ? replaceMode : appendMode)
                .setChecked(true);

        EditText editor = new EditText(this);
        editor.setText(prefs.systemPrompt());
        editor.setSelection(editor.length());
        editor.setHint("Например: отвечай по-русски, сначала проверяй факты…");
        editor.setHintTextColor(palette.muted);
        editor.setTextColor(palette.text);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setSingleLine(false);
        editor.setMinLines(8);
        editor.setMaxLines(16);
        editor.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        editor.setPadding(
                dialogStyle.dp(12),
                dialogStyle.dp(11),
                dialogStyle.dp(12),
                dialogStyle.dp(11)
        );
        editor.setBackground(dialogStyle.outlined(
                palette.cardFill, palette.stroke, 7
        ));
        LinearLayout.LayoutParams editorLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dialogStyle.dp(220)
        );
        editorLayout.topMargin = dialogStyle.dp(12);
        content.addView(editor, editorLayout);

        TextView counter = dialogStyle.monoTrace("", palette.muted);
        counter.setGravity(Gravity.END);
        LinearLayout.LayoutParams counterLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        counterLayout.topMargin = dialogStyle.dp(6);
        content.addView(counter, counterLayout);
        updateSystemPromptCounter(counter, editor.getText().toString());
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateSystemPromptCounter(counter, value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Системный промпт агента")
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("По умолчанию", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                );
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                appendMode.setChecked(true);
                editor.setText("");
                editor.requestFocus();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String normalized;
                try {
                    normalized = SystemPromptSettings.normalize(editor.getText().toString());
                } catch (IllegalArgumentException invalid) {
                    editor.setError(invalid.getMessage());
                    return;
                }
                SystemPromptSettings.Mode mode = replaceMode.isChecked()
                        ? SystemPromptSettings.Mode.REPLACE
                        : SystemPromptSettings.Mode.APPEND;
                String previous = prefs.systemPrompt();
                SystemPromptSettings.Mode previousMode = prefs.systemPromptMode();
                if (previous.equals(normalized)
                        && (normalized.isEmpty() || previousMode == mode)) {
                    dialog.dismiss();
                    toast("Системный промпт не изменился");
                    return;
                }
                prefs.setSystemPrompt(mode, normalized);
                dialog.dismiss();
                bridgeReady = false;
                bridgeConnected = false;
                bridgeFault = "";
                append(
                        ConsoleEntry.Channel.SYSTEM,
                        normalized.isEmpty()
                                ? "Восстановлен встроенный системный промпт Pi."
                                : mode == SystemPromptSettings.Mode.APPEND
                                ? "Дополнение к системному промпту сохранено."
                                : "Включена полная замена системного промпта."
                );
                refreshUi();
                if (serverReady) main.post(MainActivity.this::startBridge);
            });
        });
        dialog.show();
    }

    private void updateSystemPromptCounter(TextView counter, String value) {
        int bytes = SystemPromptSettings.byteCount(value);
        counter.setText(bytes + " / " + SystemPromptSettings.MAX_BYTES + " байт");
        counter.setTextColor(bytes > SystemPromptSettings.MAX_BYTES
                ? palette.errorText
                : palette.muted);
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
                                t(
                                        "Pi runtime в Termux неполон. Нажмите INSTALL CORE; "
                                                + "загружать GGUF повторно не нужно.",
                                        "The Pi runtime in Termux is incomplete. Tap INSTALL CORE; "
                                                + "the GGUF does not need to be downloaded again."
                                ));
                    }
                    // The probe is the last thing that has to answer before the deck may start
                    // anything by itself, so the launch warm-up hangs off its success.
                    if (startup && runtimeFound) main.post(this::warmCoreOnLaunch);
                } else {
                    linkConfirmed = false;
                    if (!startup) {
                        append(ConsoleEntry.Channel.ERROR,
                                t(
                                        "Termux пока не принимает команды.\n",
                                        "Termux is not accepting commands yet.\n"
                                ) + result.usefulError()
                                        + t(
                                                "\n\nВыполните строку из шага LINK вручную в Termux.",
                                                "\n\nRun the command from the LINK step manually in Termux."
                                        ));
                    }
                }
                // warmCoreOnLaunch must enter the queue before a queued prompt retries warming;
                // otherwise both callbacks can start the same multi-step core transition.
                setBusy(false, null);
            }
            case INSTALL_RUNTIME -> {
                setBusy(false, null);
                if (result.isSuccess() && RuntimeScripts.isReadyProbeOutput(result.stdout)) {
                    prefs.setCoreReady(true);
                    linkConfirmed = true;
                    append(ConsoleEntry.Channel.SYSTEM, t(
                            "Pi runtime развёрнут и проверен.",
                            "The Pi runtime was deployed and verified."
                    ));
                } else {
                    prefs.setCoreReady(false);
                    append(ConsoleEntry.Channel.ERROR,
                            t(
                                    "Установка ядра не завершилась.\n",
                                    "Core installation did not finish.\n"
                            ) + result.usefulError()
                                    + t(
                                            "\n\nМожно повторить: пакетный менеджер Termux продолжит с места остановки.",
                                            "\n\nYou can retry; the Termux package manager will continue where it stopped."
                                    ));
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
                                    + t(
                                            " установлена в приватное хранилище PiDeck. "
                                                    + "Shared incoming-файл теперь можно удалить отдельно.",
                                            " was installed in PiDeck's private storage. "
                                                    + "The shared incoming file can now be deleted separately."
                                    ));
                } else {
                    if (operationModel != null) {
                        prefs.setPrivateModelInstalled(operationModel, false);
                    }
                    append(ConsoleEntry.Channel.ERROR,
                            t(
                                    "Приватная установка GGUF не завершилась.\n",
                                    "Private GGUF installation did not finish.\n"
                            ) + runtimeError(result));
                }
            }
            case START_SERVER -> {
                ModelSpec operationModel = modelForOperation(result.operationId);
                if (runtimeState(result, "READY")) {
                    serverReady = operationModel != null
                            && operationModel.id.equals(selectedModel.id);
                    append(ConsoleEntry.Channel.TOOL,
                            (operationModel == null ? "GGUF" : operationModel.title)
                                    + t(
                                            " работает под UID PiDeck и доступна на loopback.",
                                            " is running under the PiDeck UID and is available on loopback."
                                    ));
                    if (serverReady) main.post(this::startBridge);
                } else {
                    serverReady = false;
                    append(ConsoleEntry.Channel.ERROR,
                            t(
                                    "LLM-ядро не запустилось.\n",
                                    "The LLM core did not start.\n"
                            ) + runtimeError(result));
                }
                // The bridge continuation owns the next step. Queue draining is posted by
                // setBusy(false), so enqueue the continuation first to prevent a second ignite.
                setBusy(false, null);
            }
            case START_BRIDGE -> {
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
                            t(
                                    "Pi RPC bridge не запустился.\n",
                                    "The Pi RPC bridge did not start.\n"
                            ) + bridgeFault);
                }
                setBusy(false, null);
            }
            case STOP_BRIDGE -> {
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
                } else {
                    bridgeFault = "";
                    if (stopServerAfter) {
                        main.post(this::stopServerRuntime);
                    } else if (startNativeAfter) {
                        main.post(() -> stopServerRuntime(true));
                    } else if (startBridgeAfter) {
                        main.post(this::startBridge);
                    }
                }
                setBusy(false, null);
            }
            case STOP_SERVER -> {
                OperationRecord record = operationStore.load(result.operationId);
                boolean startNativeAfter = record != null
                        && record.request.optBoolean("startNativeAfter", false);
                serverReady = false;
                if (result.isSuccess()) bridgeFault = "";
                append(result.isSuccess() ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        result.isSuccess()
                                ? t(
                                        "Локальное LLM-ядро остановлено.",
                                        "The local LLM core stopped."
                                )
                                : result.usefulError());
                if (result.isSuccess() && startNativeAfter) {
                    main.post(this::launchNativeServer);
                }
                setBusy(false, null);
            }
            case UPDATE_RUNTIME -> {
                setBusy(false, null);
                boolean runtimeReady = runtimeState(result, "READY");
                prefs.setCoreReady(runtimeReady);
                if (runtimeReady) linkConfirmed = true;
                append(runtimeReady ? ConsoleEntry.Channel.SYSTEM : ConsoleEntry.Channel.ERROR,
                        runtimeReady
                                ? t(
                                        "Закреплённый Pi/runtime восстановлен и проверен.",
                                        "The pinned Pi/runtime was restored and verified."
                                )
                                : t(
                                        "Обновление Pi не завершилось.\n",
                                        "The Pi update did not finish.\n"
                                ) + runtimeError(result));
                if (runtimeReady && serverReady) main.post(this::restartBridge);
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

        DeckView.CoreStatus status = coreStatus();
        deck.setCoreStatus(
                status,
                status == DeckView.CoreStatus.READY
                        ? t("Готово отвечать · ", "Ready · ")
                        + agentMode.label(uiLanguage)
                        : status == DeckView.CoreStatus.BUSY ? busyPhase : null
        );
        deck.setQueueCount(queuedPrompt == null ? 0 : 1);
        deck.setContextUsage(
                contextUsage,
                bridgeReady && !busy && prefs.hasSession(),
                contextCompacting
        );
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
                    t("НУЖЕН ARM64-ТЕЛЕФОН", "ARM64 PHONE REQUIRED"),
                    t(
                            "Встроенный llama.cpp b10092 содержит проверенные Arm CPU-варианты. "
                                    + "На устройстве без arm64-v8a локальная GGUF-модель не запускается.",
                            "The bundled llama.cpp b10092 contains verified Arm CPU variants. "
                                    + "A local GGUF model cannot run without arm64-v8a."
                    ),
                    null, null,
                    null, null
            );
            return;
        }
        if (!installed) {
            deck.setBootState(
                    "BOOT SEQUENCE // 01",
                    t("УСТАНОВИТЕ TERMUX", "INSTALL TERMUX"),
                    t(
                            "Нужна версия из F-Droid. Она станет защищённым runtime-контуром для настоящего Pi, Python и shell.",
                            "Install the F-Droid build. It provides the runtime environment "
                                    + "for real Pi, Python, and shell commands."
                    ),
                    "OPEN F-DROID", termux::openTermuxPage,
                    null, null
            );
            return;
        }
        if (!termuxEnvironment.versionSupported) {
            deck.setBootState(
                    "BOOT HALT // TERMUX VERSION",
                    t("ОБНОВИТЕ TERMUX", "UPDATE TERMUX"),
                    t("Обнаружена версия ", "Detected version ")
                            + termuxEnvironment.version
                            + t(
                                    "; требуется 0.118.0 или новее из F-Droid.",
                                    "; version 0.118.0 or newer from F-Droid is required."
                            ),
                    "OPEN F-DROID", termux::openTermuxPage,
                    null, null
            );
            return;
        }
        if (termuxEnvironment.source == TermuxEnvironment.Source.UNKNOWN) {
            String signer = termuxEnvironment.signerSha256;
            String prefix = signer.isBlank()
                    ? t("не читается", "unavailable")
                    : signer.substring(0, 12) + "…";
            deck.setBootState(
                    "BOOT HALT // TERMUX SIGNER",
                    t("НЕИЗВЕСТНАЯ ПОДПИСЬ", "UNKNOWN SIGNATURE"),
                    t(
                            "PI//DECK не передаст RUN_COMMAND неизвестной сборке Termux. ",
                            "PI//DECK will not send RUN_COMMAND to an unknown Termux build. "
                    ) + "SHA-256 signer: " + prefix
                            + t(
                                    ". Установите совместимую F-Droid-сборку.",
                                    ". Install a compatible F-Droid build."
                            ),
                    "OPEN F-DROID", termux::openTermuxPage,
                    null, null
            );
            return;
        }
        if (!permission) {
            deck.setBootState(
                    "BOOT SEQUENCE // 02",
                    t("РАЗРЕШИТЕ КАНАЛ УПРАВЛЕНИЯ", "ALLOW CONTROL CHANNEL"),
                    t(
                            "PI//DECK просит только специальное разрешение Termux RUN_COMMAND. Оно позволяет запускать команды внутри Termux, не давая APK root-доступ.",
                            "PI//DECK requests only Termux's dedicated RUN_COMMAND permission. "
                                    + "It can run commands inside Termux without granting root to the APK."
                    ),
                    "GRANT LINK", () -> termux.requestRunPermission(this, REQUEST_RUN_COMMAND),
                    "APP SETTINGS", termux::openAppSettings
            );
            return;
        }
        if (!linkConfirmed) {
            deck.setBootState(
                    "BOOT SEQUENCE // 03",
                    t("СВЯЖИТЕ TERMUX С ДЕКОЙ", "LINK TERMUX TO THE DECK"),
                    t(
                            "Нажмите COPY + OPEN, вставьте строку в Termux и выполните её. Появится системный запрос доступа к файлам. Затем вернитесь и нажмите TEST LINK.",
                            "Tap COPY + OPEN, paste the command into Termux, and run it. "
                                    + "Accept the system storage request, return here, and tap TEST LINK."
                    ),
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
                    t("РАЗВЕРНУТЬ PI CORE", "DEPLOY PI CORE"),
                    t(
                            "Один раз установим Node.js, Python, git и официальный Pi coding agent. Встроенный llama.cpp уже находится внутри APK.",
                            "This one-time step installs Node.js, Python, git, and the official "
                                    + "Pi coding agent. The bundled llama.cpp is already inside the APK."
                    ),
                    "INSTALL CORE", this::installCore,
                    "TEST LINK", this::probeTermux
            );
            return;
        }
        if (modelSelectionRequired) {
            deck.setBootState(
                    "BOOT SEQUENCE // 05",
                    t("ВЫБЕРИТЕ ПРОФИЛЬ МОДЕЛИ", "SELECT A MODEL PROFILE"),
                    t(
                            "Сохранённая модель отсутствует в проверенном каталоге. Рекомендация по доступной памяти: ",
                            "The saved model is not in the verified catalog. Recommendation for available memory: "
                    ) + selectedModel.title
                            + t(
                                    ". Выбор не применяется скрыто.",
                                    ". The selection will not be applied silently."
                            ),
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
                body = t("Загрузка остановилась: ", "Download stopped: ")
                        + downloadFailureLabel(modelState.reason)
                        + t(
                                ". Неполный файл можно безопасно заменить.",
                                ". The incomplete file can be replaced safely."
                        );
                primaryLabel = "RETRY";
                primary = () -> confirmDownload(selectedModel);
            } else {
                body = t("Для этого телефона выбран ", "Selected for this phone: ")
                        + selectedModel.title + " " + selectedModel.humanSize() + ". "
                        + modelNote(selectedModel)
                        + t(
                                "\nWi‑Fi рекомендован; модель загружается напрямую с Hugging Face.",
                                "\nWi-Fi is recommended; the model downloads directly from Hugging Face."
                        );
                primaryLabel = "DOWNLOAD " + selectedModel.tier;
                primary = () -> confirmDownload(selectedModel);
            }
            deck.setBootState(
                    "BOOT SEQUENCE // 05",
                    t("ЗАГРУЗИТЬ ЛОКАЛЬНЫЙ МОЗГ", "DOWNLOAD LOCAL MODEL"),
                    body,
                    primaryLabel, primary,
                    "CHOOSE", this::openCoreRoot
            );
            return;
        }
        if (!privateReady && !verified) {
            if (!verifying && verificationFault.isBlank()) verifyModel(selectedModel);
            String body = verificationFault.isBlank()
                    ? t(
                            "Сверяем SHA‑256 большого GGUF-файла: ",
                            "Checking the large GGUF file's SHA-256: "
                    ) + verificationPercent
                    + t(
                            "%. Это защищает от обрыва или подмены загрузки.",
                            "%. This detects interrupted or substituted downloads."
                    )
                    : t("Проверка не пройдена: ", "Verification failed: ")
                    + verificationFault
                    + t(
                            "\nФайл можно безопасно загрузить заново.",
                            "\nThe file can be downloaded again safely."
                    );
            deck.setBootState(
                    "BOOT SEQUENCE // 06",
                    t("ПРОВЕРКА ЦЕЛОСТНОСТИ", "INTEGRITY CHECK"),
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
                    t("УСТАНОВИТЬ ПРИВАТНУЮ GGUF", "INSTALL PRIVATE GGUF"),
                    t(
                            "Android SHA-256 пройден. PiDeck повторно проверит hash во время копирования, "
                                    + "выполнит fsync и atomic rename в приватный model store.",
                            "Android SHA-256 passed. PiDeck will verify the hash again while copying, "
                                    + "then fsync and atomically rename it into the private model store."
                    ),
                    busy ? "INSTALLING…" : "INSTALL PRIVATE",
                    busy ? this::openCoreRoot : () -> installPrivateModel(selectedModel),
                    "MODELS", this::openCoreRoot
            );
            return;
        }
        if (!serverReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 08",
                    t("ЗАЖЕЧЬ ЛОКАЛЬНОЕ ЯДРО", "IGNITE LOCAL CORE"),
                    selectedModel.title + t(
                            " находится в приватном read-only store. Запуск использует ",
                            " is in the private read-only store. Startup uses "
                    )
                            + dev.pideck.app.core.CpuProfile.detect()
                            + t(" и контекст ", " and a ")
                            + selectedModel.recommendedContext
                            + t(" токенов. Ожидаемый peak: ", "-token context. Expected peak: ")
                            + humanBytes(selectedModel.estimatedPeakBytes())
                            + t("; доступно ", "; available ")
                            + humanBytes(availableRam) + ".",
                    "IGNITE LLM", this::startServer,
                    "MODELS", this::openCoreRoot
            );
            return;
        }
        if (!bridgeReady) {
            deck.setBootState(
                    "BOOT SEQUENCE // 09",
                    t("ПОДКЛЮЧИТЬ PI RPC", "CONNECT PI RPC"),
                    t(
                            "Локальный bridge использует 256-bit token и слушает только 127.0.0.1. ",
                            "The local bridge uses a 256-bit token and listens only on 127.0.0.1. "
                    )
                            + (bridgeFault.isBlank()
                            ? accessProfile.description(uiLanguage)
                            : bridgeFault),
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
        stallState = new StallWatchdog(operationId, kind, System.currentTimeMillis());
        scheduleWatchdogCheck();
    }

    private void armRestoredWatchdog(OperationRecord operation) {
        stallState = new StallWatchdog(
                operation.operationId,
                operation.kind,
                operation.createdAtMs,
                System.currentTimeMillis()
        );
        scheduleWatchdogCheck();
    }

    private void scheduleWatchdogCheck() {
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        if (stallState == null) return;
        StallWatchdog armed = stallState;
        watchdog = () -> {
            watchdog = null;
            if (stallState != armed) return;
            OperationId operationId = armed.operationId();
            if (!busy || !operationId.equals(operations.activeOperationId())) {
                if (stallState == armed) stallState = null;
                return;
            }
            long now = System.currentTimeMillis();
            StallWatchdog.Verdict verdict = armed.verdict(now);
            if (verdict == StallWatchdog.Verdict.WAIT) {
                scheduleWatchdogCheck();
                return;
            }
            long silent = armed.silentForMs(now);
            stallState = null;
            operations.timeout(operationId);
            setBusy(true, "Ответа нет");
            reportWatchdog(operationId, armed.kind(), silent, verdict);
            if (armed.kind() == OperationKind.AGENT_TURN
                    || armed.kind() == OperationKind.NEW_SESSION
                    || armed.kind() == OperationKind.COMPACT_SESSION) {
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
        // Verdicts are timestamped with System.currentTimeMillis(), but postDelayed runs on the
        // uptime clock, so a process freeze can make this fire early or late relative to the
        // deadline. WAIT reschedules against the wall clock; a spurious early fire just re-checks
        // the verdict, so drift only ever reconciles fail-closed.
        main.postDelayed(
                watchdog,
                Math.max(1_000L, armed.nextCheckDelayMs(System.currentTimeMillis()))
        );
    }

    /**
     * A silent Termux is the deck's most common failure, and the honest report is that the result
     * is unknown — so the card offers both readings: wait longer, or stop and take the loss.
     */
    private void reportWatchdog(
            OperationId operationId,
            OperationKind kind,
            long waited,
            StallWatchdog.Verdict verdict
    ) {
        String description = verdict == StallWatchdog.Verdict.EXPIRED
                ? "Операция идёт дольше общего предела " + (kind.timeoutMs() / 60_000L) + " мин. "
                        + "Так бывает, когда Android выгружает Termux ради экономии батареи. Часть "
                        + "изменений могла быть уже применена — проверьте рабочую папку перед повтором."
                : "Событий не было " + Math.max(1L, waited / 60_000L) + " мин. Так бывает, когда Android "
                        + "выгружает его ради экономии батареи. Часть изменений могла быть уже "
                        + "применена — проверьте рабочую папку перед повтором.";
        FailureCardView.Failure failure = new FailureCardView.Failure(
                "Связь потеряна",
                "Команда идёт слишком долго",
                description,
                false
        );
        failure.recovered(
                "вывод, который уже пришёл, сохранён",
                "сессия и весь диалог сохранены",
                "запрос не повторялся автоматически"
        );
        failure.primary("Ждать ещё", () -> {
            append(ConsoleEntry.Channel.SYSTEM, "Жду ещё; операция " + operationId + ".");
            armWatchdog(operationId, kind);
        });
        if (kind == OperationKind.AGENT_TURN) {
            failure.secondary("Прервать задачу", this::abortAgent);
        }
        deck.addFailure(failure);
        prefs.saveTranscript(deck.entries());
    }

    private void cancelWatchdog(OperationId completedOperationId) {
        if (completedOperationId != null
                && stallState != null
                && !completedOperationId.equals(stallState.operationId())) {
            return;
        }
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        stallState = null;
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
        // The last known link state holds until the probe actually fails. Clearing it here made
        // BOOT SEQUENCE // 03 flash on every launch of an already linked deck.
        dispatchOperation(
                OperationKind.PROBE_RUNTIME,
                json("startup", true),
                "CHECKING PI RUNTIME",
                operationId -> termux.runBash(
                        operationId, OperationKind.PROBE_RUNTIME, RuntimeScripts.probe()
                )
        );
    }

    /**
     * Everything the deck needs before it may load a model on its own: a Termux it is allowed to
     * drive, a runtime that answered, an access decision the user made, and a private GGUF. None of
     * it can be inferred, so all of it is checked before anything is started without a tap.
     */
    private boolean canWarmCore() {
        return termuxEnvironment.canRunCommands()
                && termux.hasRunPermission()
                && linkConfirmed
                && prefs.isCoreReady()
                && prefs.consentGranted()
                && !modelSelectionRequired
                && nativeModels.isInstalled(selectedModel);
    }

    /** The ignite ladder without the tap: load the model if it is down, otherwise raise the bridge. */
    private void warmCore() {
        if (busy || !canWarmCore()) return;
        if (!serverReady) {
            startServer();
        } else if (!bridgeReady) {
            startBridge();
        }
    }

    private void warmCoreOnLaunch() {
        if (!StartupPolicy.warmsOnLaunch(
                prefs.autostartCore(),
                canWarmCore(),
                serverReady,
                bridgeReady,
                busy,
                lowMemory
        )) {
            return;
        }
        append(ConsoleEntry.Channel.SYSTEM, serverReady
                ? t("Сервер уже работает; поднимаю Pi RPC bridge.",
                        "The server is already running; raising the Pi RPC bridge.")
                : t("Автозапуск: гружу " + selectedModel.title + ".",
                        "Autostart: loading " + selectedModel.title + "."));
        warmCore();
    }

    private void scheduleComposerWarmup() {
        main.removeCallbacks(composerWarmup);
        if (!activityStarted || !composerHasText || composerWarmAttempted) return;
        main.postDelayed(composerWarmup, COMPOSER_WARM_DELAY_MS);
    }

    private void maybeWarmCoreForComposerIntent() {
        if (!activityStarted || composerWarmAttempted) return;
        if (!StartupPolicy.warmsOnComposerIntent(
                composerHasText,
                canWarmCore(),
                serverReady,
                bridgeReady,
                busy,
                lowMemory
        )) {
            return;
        }
        composerWarmAttempted = true;
        warmCore();
    }

    private void installCore() {
        if (busy) return;
        append(ConsoleEntry.Channel.SYSTEM,
                t(
                        "Разворачиваю runtime внутри Termux. Не закрывайте Termux во время пакетной установки.",
                        "Deploying the runtime inside Termux. Keep Termux open during package installation."
                ));
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
            toast(t(
                    "Сначала установите приватную GGUF",
                    "Install the private GGUF first"
            ));
            return;
        }
        long expectedPeak = selectedModel.estimatedPeakBytes();
        if (StartupPolicy.asksOomRisk(
                lowMemory,
                availableRam,
                selectedModel.minimumAvailableMiB * 1_048_576L,
                expectedPeak,
                prefs.oomRiskAcknowledged(selectedModel.id)
        )) {
            new AlertDialog.Builder(this)
                    .setTitle(t("Высокий риск OOM", "High OOM risk"))
                    .setMessage(t("Ожидаемый peak: ", "Expected peak: ")
                            + humanBytes(expectedPeak)
                            + t("\nДоступно сейчас: ", "\nCurrently available: ")
                            + humanBytes(availableRam)
                            + t("\nКонтекст: ", "\nContext: ")
                            + selectedModel.recommendedContext
                            + t(
                                    "\n\nAndroid может завершить foreground inference при дефиците RAM. "
                                            + "Модель не будет заменена скрыто.",
                                    "\n\nAndroid may terminate foreground inference when RAM is low. "
                                            + "The model will not be replaced silently."
                            ))
                    .setNegativeButton(t("Отмена", "Cancel"), null)
                    .setPositiveButton(t("Запустить", "Start"), (dialog, which) -> {
                        prefs.setOomRiskAcknowledged(selectedModel.id);
                        startServerConfirmed();
                    })
                    .show();
            return;
        }
        startServerConfirmed();
    }

    private void startServerConfirmed() {
        append(ConsoleEntry.Channel.SYSTEM,
                t(
                        "Переношу inference под UID PiDeck и загружаю ",
                        "Moving inference under the PiDeck UID and loading "
                ) + selectedModel.title
                        + t(
                                " через оптимизированный Arm backend.",
                                " through the optimized Arm backend."
                        ));
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
        // server-stop exists to retire a managed or legacy llama-server. With nothing claimed
        // anywhere, that Termux round trip only delays the model load.
        if (StartupPolicy.skipsRuntimeStop(
                NativeLlamaService.snapshot(this).state,
                serverReady,
                bridgeReady || bridgeConnected
        )) {
            launchNativeServer();
            return;
        }
        stopServerRuntime(true);
    }

    private void launchNativeServer() {
        if (busy) return;
        bridgeFault = "";
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

    private void compactSession() {
        compactSession(false);
    }

    private void compactSession(boolean automatic) {
        if (busy || !bridgeReady) {
            if (pendingPromptAfterCompaction != null) {
                pendingPromptAfterCompaction = null;
                deck.setComposerDispatchPending(false);
            }
            toast(t(
                    "Дождитесь завершения текущей операции",
                    "Wait for the current operation to finish"
            ));
            return;
        }
        OperationRecord operation;
        try {
            JSONObject request = json("sessionId", prefs.ensureSessionId());
            put(request, "automatic", automatic);
            operation = operations.begin(
                    OperationKind.COMPACT_SESSION,
                    request
            );
            operations.dispatched(operation.operationId);
            contextCompacting = true;
            setBusy(true, t("Сжимаю историю", "Compacting history"));
            setInferenceActive(true, t(
                    "Сжимаю историю сессии…", "Compacting session history…"
            ));
            armWatchdog(operation.operationId, OperationKind.COMPACT_SESSION);
        } catch (RuntimeException error) {
            contextCompacting = false;
            pendingPromptAfterCompaction = null;
            deck.setComposerDispatchPending(false);
            append(ConsoleEntry.Channel.ERROR, readableException(error));
            return;
        }
        io.execute(() -> {
            try {
                rpc.command(
                        operation.operationId,
                        "COMPACT",
                        json(
                                "customInstructions",
                                "Создай точный checkpoint для продолжения работы. Сохрани: цель и "
                                        + "ограничения пользователя; подтверждённые факты отдельно от "
                                        + "гипотез; принятые решения; точные относительные пути прочитанных "
                                        + "и изменённых файлов; выполненные команды и результаты тестов "
                                        + "pass/fail; ошибки, незавершённые шаги и следующий конкретный шаг. "
                                        + "Не выдумывай выполненные действия и не теряй отрицательные "
                                        + "результаты. Удали только повторения и подробности, не нужные для "
                                        + "продолжения."
                        )
                );
            } catch (Exception error) {
                runOnUiThread(() -> failRpcDispatch(operation.operationId, error));
            }
        });
    }

    private void maybeSmartCompactSession() {
        if (!prefs.smartCompaction()
                || busy
                || !bridgeReady
                || contextCompacting
                || !prefs.hasSession()
                || contextUsage == null
                || !contextUsage.shouldSmartCompact()) {
            return;
        }
        String session = prefs.ensureSessionId();
        if (session.equals(smartCompactionAttemptSession)
                && contextUsage.tokens < smartCompactionAttemptTokens + 512L) {
            return;
        }
        smartCompactionAttemptSession = session;
        smartCompactionAttemptTokens = contextUsage.tokens;
        append(ConsoleEntry.Channel.SYSTEM, t(
                "Контекст достиг " + contextUsage.percent
                        + "%. Создаю idle checkpoint до следующего запроса.",
                "Context reached " + contextUsage.percent
                        + "%. Creating an idle checkpoint before the next prompt."
        ));
        compactSession(true);
    }

    private void abortAgent() {
        OperationRecord active = operations.active();
        if (active == null || active.kind != OperationKind.AGENT_TURN) {
            toast(t("Нет активного Pi turn", "There is no active Pi turn"));
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

    private String contextDelayHint() {
        if (contextUsage == null || !contextUsage.known()) {
            return t("неизвестное время", "an unknown amount of time");
        }
        if (contextUsage.tokens < 2_000) {
            return t("обычно 15–30 секунд", "usually 15–30 seconds");
        }
        if (contextUsage.tokens < 4_000) {
            return t("примерно 30–60 секунд", "about 30–60 seconds");
        }
        if (contextUsage.tokens < 7_000) {
            return t("около минуты", "about a minute");
        }
        return t(
                "примерно 2–4 минуты на малой модели; на средней дольше",
                "about 2–4 minutes on a small model; longer on a medium model"
        );
    }

    private String contextPhaseLabel(String phase) {
        if (contextUsage == null || !contextUsage.known()) return phase;
        return phase + t(" · контекст ", " · context ") + contextUsage.percent + "%";
    }

    private void updateStreamingRate() {
        long now = SystemClock.uptimeMillis();
        if (firstOutputAtUptimeMs <= 0L
                || now - firstOutputAtUptimeMs < 1_000L
                || now - lastRateUpdateUptimeMs < 750L) {
            return;
        }
        lastRateUpdateUptimeMs = now;
        GenerationSpeed speed = GenerationSpeed.fromStreaming(
                streamedCharacters,
                now - firstOutputAtUptimeMs
        );
        if (speed == null) return;
        deck.setGenerationSpeed(speed);
        setBusy(true, t("Печатает · ", "Writing · ")
                + speed.label(uiLanguage.locale, uiLanguage));
    }

    private void setInferenceActive(boolean active, String phase) {
        inferenceActive = active;
        applyScreenSpeedPolicy();
        NativeLlamaService.Snapshot nativeState = NativeLlamaService.snapshot(this);
        if (!nativeState.isStartingOrReady()) return;
        if (active) NativeLlamaService.beginInference(this, phase);
        else NativeLlamaService.endInference(this);
    }

    private void applyScreenSpeedPolicy() {
        if (inferenceActive && prefs.maximumSpeed()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

    private boolean heldByAnotherModel(Uri uri) {
        String value = uri == null ? null : uri.toString();
        if (value == null) return false;
        for (ModelSpec candidate : modelCatalog.all()) {
            if (value.equals(modelDownloads.externalDocumentUri(candidate))) return true;
        }
        return false;
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
            if (!ModelCatalog.isRecommendable(candidate)) continue;
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
        contextUsage = SessionContextUsage.unknown(model.recommendedContext);
        contextCompacting = false;
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
        state.agentMode = agentMode;
        state.maximumSpeed = prefs.maximumSpeed();
        state.autostartCore = prefs.autostartCore();
        state.smartCompaction = prefs.smartCompaction();
        state.language = uiLanguage;

        for (ModelSpec model : modelCatalog.all()) state.models.add(modelRow(model));

        String systemPrompt = prefs.systemPrompt();
        SystemPromptSettings.Mode systemPromptMode = prefs.systemPromptMode();
        state.systemPrompt = new CoreRootView.ActionRow(
                systemPrompt.isEmpty()
                        ? t("Встроенный промпт Pi", "Built-in Pi prompt")
                        : systemPromptMode == SystemPromptSettings.Mode.APPEND
                        ? t("Дополнение к промпту", "Prompt addition")
                        : t("Полная замена промпта", "Full prompt replacement"),
                systemPrompt.isEmpty()
                        ? t(
                                "без пользовательских инструкций · нажмите, чтобы изменить",
                                "no custom instructions · tap to edit"
                        )
                        : SystemPromptSettings.byteCount(systemPrompt)
                        + t(" байт · нажмите, чтобы редактировать",
                        " bytes · tap to edit"),
                systemPromptMode == SystemPromptSettings.Mode.REPLACE
                        && !systemPrompt.isEmpty()
                        ? palette.warn
                        : palette.accent,
                this::editSystemPrompt
        );

        state.accessProfileLabel = accessProfile.label;
        state.accessProfileNote = accessProfile.description(uiLanguage);
        for (AccessProfile profile : AccessProfile.values()) {
            if (profile == accessProfile) continue;
            state.accessProfiles.add(new CoreRootView.ActionRow(
                    t("Доступ → ", "Access → ") + profile.label,
                    profile == AccessProfile.AUTONOMOUS
                            ? t(
                                    "явное согласие на высокий риск",
                                    "explicit consent to high risk"
                            )
                            : profile.description(uiLanguage),
                    profile == AccessProfile.AUTONOMOUS ? palette.warn : palette.accent,
                    () -> changeAccessProfile(profile)
            ));
        }

        state.maintenance.add(new CoreRootView.ActionRow(
                t("Обновить Pi", "Update Pi"),
                t(
                        "восстановить закреплённую сборку и проверить целостность",
                        "restore the pinned build and verify integrity"
                ),
                palette.text, this::updateAgent
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                t("Перезапустить LLM", "Restart LLM"),
                t("перечитать выбранный GGUF", "reload the selected GGUF"),
                palette.text, this::startServer
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                t("Новая сессия", "New session"),
                t(
                        "прошлая уезжает в ~/.pideck/session-archive",
                        "the previous one moves to ~/.pideck/session-archive"
                ),
                palette.text, this::newSession
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                t("Сжать историю", "Compact history"),
                contextUsage != null && contextUsage.known()
                        ? t("сейчас ", "currently ") + contextUsage.percent
                        + t(
                                "% · сохранить решения и освободить контекст",
                                "% · preserve decisions and free context"
                        )
                        : t(
                                "сохранить решения и сократить историю диалога",
                                "preserve decisions and shorten the conversation history"
                        ),
                contextUsage != null && contextUsage.shouldWarn() ? palette.warn : palette.text,
                this::compactSession
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                t("Открыть Termux", "Open Termux"),
                t("runtime-контур деки", "deck runtime environment"),
                palette.text, this::openTermux
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                t("Скопировать команду связи", "Copy link command"),
                t("починить handshake Termux", "repair the Termux handshake"),
                palette.text, this::copyHandshakeAndOpen
        ));
        state.maintenance.add(new CoreRootView.ActionRow(
                t("Очистить консоль", "Clear console"),
                t("сессия Pi при этом сохраняется", "the Pi session is preserved"),
                palette.text, this::clearConsole
        ));
        if (busy) {
            state.maintenance.add(new CoreRootView.ActionRow(
                    t("Прервать задачу", "Abort task"),
                    t("структурный RPC abort", "structured RPC abort"),
                    palette.errorText, this::abortAgent
            ));
        }

        state.info.add(new CoreRootView.InfoRow(
                t("рабочая папка", "workspace"), workspaceLabel(), palette.text
        ));
        state.info.add(new CoreRootView.InfoRow(
                "termux", termuxEnvironment.installed
                ? termuxEnvironment.version + " / " + termuxEnvironment.sourceLabel()
                : t("не установлен", "not installed"),
                termuxEnvironment.signerTrusted() ? palette.ok : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                "termux:api",
                termuxEnvironment.apiCompatible
                        ? termuxEnvironment.apiVersion + " / wake-lock"
                        : termuxEnvironment.apiInstalled
                        ? termuxEnvironment.apiVersion + t(" / несовместим", " / incompatible")
                        : t("не установлен", "not installed"),
                termuxEnvironment.apiCompatible ? palette.ok : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                t("канал управления", "control channel"),
                linkConfirmed ? t("связан", "linked") : t("нет связи", "not linked"),
                linkConfirmed ? palette.accent : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                "pi runtime",
                prefs.isCoreReady() ? t("готов", "ready") : t("не установлен", "not installed"),
                prefs.isCoreReady() ? palette.ok : palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                t("llm сервер", "llm server"),
                serverReady ? "127.0.0.1:8080" : t("остановлен", "stopped"),
                serverReady ? palette.accent : palette.muted
        ));
        NativeLlamaService.Snapshot nativeState = NativeLlamaService.snapshot(this);
        state.info.add(new CoreRootView.InfoRow(
                "inference",
                nativeState.isStartingOrReady()
                        ? "PiDeck foreground · " + nativeState.profile
                        : serverReady
                        ? t("legacy Termux · нужен restart", "legacy Termux · restart required")
                        : t("остановлен", "stopped"),
                nativeState.isStartingOrReady() ? palette.ok : serverReady ? palette.warn : palette.muted
        ));
        state.info.add(new CoreRootView.InfoRow(
                "rpc bridge",
                bridgeReady ? "authenticated / 127.0.0.1" : t("остановлен", "stopped"),
                bridgeReady ? palette.ok : palette.muted
        ));
        state.info.add(new CoreRootView.InfoRow(
                t("контекст", "context"),
                contextUsage != null && contextUsage.known()
                        ? (contextUsage.estimated ? "≈" : "")
                        + contextUsage.tokens + " / " + contextUsage.contextWindow
                        + " · " + contextUsage.percent + "%"
                        : t("будет измерен после запроса", "measured after the next request"),
                contextUsage != null && contextUsage.shouldWarn() ? palette.warn : palette.muted
        ));
        state.info.add(new CoreRootView.InfoRow(
                t("сеть инструментов", "tool network"),
                agentMode == AgentMode.CHAT
                        ? t("отключена в режиме Чат", "disabled in Chat mode")
                        : accessProfile.toolNetworkPossible
                        ? t("возможна", "possible")
                        : t("нет shell", "no shell"),
                agentMode == AgentMode.CHAT
                        ? palette.ok
                        : accessProfile.toolNetworkPossible ? palette.warn : palette.ok
        ));
        state.info.add(new CoreRootView.InfoRow(
                t("изоляция ОС", "OS isolation"),
                t("не реализована", "not implemented"),
                palette.warn
        ));
        state.info.add(new CoreRootView.InfoRow(
                t("телефон", "phone"),
                humanBytes(totalRam) + " RAM · " + cpuThreads + " CPU · "
                        + humanBytes(freeStorage) + t(" свободно · ", " free · ")
                        + (isMetered()
                        ? t("сеть тарифицируется", "metered network")
                        : t("сеть без лимита", "unmetered network")),
                palette.muted
        ));

        if (serverReady) {
            state.stopCoreLabel = t("Остановить ядро · освободить ", "Stop core · free ")
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

        String meta = speedProfile(model) + " · " + model.humanSize()
                + t(" · контекст ", " · context ") + model.recommendedContext;
        if (model.equals(modelCatalog.recommend(availableRam, lowMemory, freeStorage))) {
            meta += t(" · рекомендуем", " · recommended");
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
            state = t("не хватит RAM (", "not enough RAM (") + humanBytes(totalRam) + ")";
        } else if (privateReady && selected && serverReady) {
            state = t("активна", "active");
            stateColor = palette.ok;
        } else if (privateReady) {
            state = t("загружена, готова к запуску", "downloaded, ready to start");
            stateColor = palette.ok;
            actionLabel = selected
                    ? t("Перезапустить", "Restart")
                    : t("Выбрать", "Select");
            action = () -> {
                chooseModel(model);
                if (selected) startServer();
            };
        } else if (download.isActive()) {
            state = t("скачивается · ", "downloading · ")
                    + humanBytes(download.downloadedBytes)
                    + t(" из ", " of ") + humanBytes(download.totalBytes);
            stateColor = palette.accent;
            percent = download.percent();
            actionLabel = t("Отменить", "Cancel");
            action = () -> {
                modelDownloads.cancel(model);
                refreshUi();
            };
        } else if (download.phase == ModelDownloadManager.Phase.FAILED) {
            state = t("сбой загрузки: ", "download failed: ")
                    + ModelDownloadManager.failureLabel(download.reason).toLowerCase(Locale.ROOT);
            stateColor = palette.errorText;
            actionLabel = t("Повторить", "Retry");
            action = () -> confirmDownload(model);
            canAttach = true;
        } else if (incoming && verified) {
            state = t(
                    "проверена, ждёт приватной установки",
                    "verified, waiting for private installation"
            );
            stateColor = palette.warn;
            actionLabel = t("Установить", "Install");
            action = () -> installPrivateModel(model);
        } else if (incoming) {
            state = t("ждёт проверки SHA-256", "waiting for SHA-256 verification");
            stateColor = palette.warn;
            actionLabel = t("Проверить", "Verify");
            action = () -> verifyModel(model);
        } else {
            state = t("не скачана", "not downloaded");
            actionLabel = t("Скачать", "Download");
            action = () -> confirmDownload(model);
            canAttach = true;
        }

        String secondaryLabel = null;
        Runnable secondary = null;
        if (incoming) {
            secondaryLabel = t("Удалить исходник", "Delete source");
            secondary = () -> confirmDeleteModel(model);
        } else if (canAttach && fits) {
            secondaryLabel = t("Подключить файл", "Attach file");
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

    private String speedProfile(ModelSpec model) {
        return switch (model.id) {
            case "qwen3.5-0.8b" -> t("Быстро · ≈52 ток/с", "Fast · ≈52 tok/s");
            case "qwen3.5-2b" -> t(
                    "FAST · без скрытого рассуждения · ≈19 ток/с",
                    "FAST · direct · ≈19 tok/s"
            );
            case "qwen3.5-4b" -> t(
                    "DEEP · рассуждение ≤1024 токенов · ≈7 ток/с",
                    "DEEP · reasoning up to 1024 tokens · ≈7 tok/s"
            );
            case "qwen3.5-9b" -> t("Эксперимент · медленно", "Experimental · slow");
            case "bonsai-27b" -> t("Не для диалога · ≈1 ток/с", "Not for chat · ≈1 tok/s");
            default -> t(
                    "Скорость зависит от телефона",
                    "Speed depends on the phone"
            );
        };
    }

    private String modelNote(ModelSpec model) {
        if (uiLanguage != UiLanguage.ENGLISH) return model.note;
        return switch (model.id) {
            case "qwen3.5-0.8b" ->
                    "The fastest profile for phones with limited available memory.";
            case "qwen3.5-2b" ->
                    "FAST: direct answers without hidden reasoning for everyday edits.";
            case "qwen3.5-4b" ->
                    "DEEP: bounded reasoning for harder coding tasks; slower than FAST.";
            case "qwen3.5-9b" ->
                    "A multi-step profile with high OOM risk; it needs a separate device benchmark.";
            case "bonsai-27b" ->
                    "27B in a 1-bit packing: it fits a flagship's memory but decodes at about "
                            + "1.2 tok/s. Hand-picked only.";
            default -> model.note;
        };
    }

    private String downloadFailureLabel(int reason) {
        if (uiLanguage != UiLanguage.ENGLISH) {
            return ModelDownloadManager.failureLabel(reason);
        }
        return switch (reason) {
            case android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE ->
                    "not enough storage";
            case android.app.DownloadManager.ERROR_CANNOT_RESUME ->
                    "the server refused to resume";
            case android.app.DownloadManager.ERROR_DEVICE_NOT_FOUND ->
                    "storage is unavailable";
            case android.app.DownloadManager.ERROR_HTTP_DATA_ERROR ->
                    "HTTP error";
            case android.app.DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
                    "too many redirects";
            case android.app.DownloadManager.ERROR_FILE_ALREADY_EXISTS ->
                    "the file already exists";
            default -> String.format(Locale.US, "code %d", reason);
        };
    }

    /** The listing is a Termux round trip, so it is asked for once per visit unless forced. */
    private void listSessions(boolean force) {
        if (busy || !prefs.isCoreReady() || !termuxEnvironment.canRunCommands()) return;
        if (sessionsRequested && !force) return;
        sessionsRequested = true;
        dispatchOperation(
                OperationKind.LIST_SESSIONS,
                new JSONObject(),
                t("Читаю сессии", "Loading sessions"),
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
                t("Архивирую сессии", "Archiving sessions"),
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
        SessionsRootView.Group today = new SessionsRootView.Group(t("Сегодня", "Today"));
        SessionsRootView.Group earlier = new SessionsRootView.Group(t("Раньше", "Earlier"));
        for (int index = 0; index < sessions.length(); index++) {
            JSONObject value = sessions.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("id", "");
            long updated = value.optLong("updatedAtEpochMs", 0L);
            long age = now - updated;
            boolean current = !id.isEmpty() && id.equals(activeSession);
            String title = value.optString("title", "");
            if (title.isBlank()) title = t("Сессия ", "Session ") + shortId(id);

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
                    ? t(
                            "Установленный Pi runtime нужно обновить. Откройте Ядро → "
                                    + "Обновить Pi, затем вернитесь в Сессии.",
                            "The installed Pi runtime needs an update. Open Core → "
                                    + "Update Pi, then return to Sessions."
                    )
                    : t(
                            "Список сессий прочитать не удалось: ",
                            "Could not read the session list: "
                    ) + sessionsFault;
        } else if (state.groups.isEmpty()) {
            state.emptyNote = sessionsRequested
                    ? t(
                            "В ~/.pideck/sessions пока пусто — первая сессия появится после "
                                    + "первого разговора.",
                            "~/.pideck/sessions is empty — the first session will appear "
                                    + "after your first conversation."
                    )
                    : t(
                            "Список читается из Termux при открытии этого экрана.",
                            "The list is loaded from Termux when this screen opens."
                    );
        } else {
            state.emptyNote = t(
                    "Тап по сессии переключает на неё Pi. Локальный транскрипт при "
                            + "этом не подменяется: дека не переписывает то, что вы уже видели.",
                    "Tap a session to switch Pi to it. The local transcript is not replaced: "
                            + "the deck does not rewrite what you have already seen."
            );
        }

        state.footer = sessionCount + " " + sessionsLabel(sessionCount)
                + " · " + humanBytes(sessionBytes);
        if (sessionCount > 0) {
            state.archiveLabel = t("Архивировать старые", "Archive old sessions");
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
            toast(t(
                    "Дождитесь завершения текущей операции",
                    "Wait for the current operation to finish"
            ));
            return;
        }
        try {
            prefs.setSessionId(id, true);
        } catch (RuntimeException error) {
            toast(t(
                    "Эту сессию нельзя продолжить: ",
                    "This session cannot be resumed: "
            ) + readableException(error));
            return;
        }
        onTabSelected(TabBarView.TAB_CONSOLE);
        append(ConsoleEntry.Channel.SYSTEM,
                t("Продолжаю сессию ", "Resuming session ") + shortId(id)
                        + t(
                                ". Прошлые сообщения остались в Pi; "
                                        + "в консоли они не воспроизводятся.",
                                ". Earlier messages remain in Pi and are not replayed "
                                        + "in the console."
                        ));
        if (serverReady) main.post(this::restartBridge);
    }

    private String shortId(String id) {
        if (id == null || id.isEmpty()) return t("без имени", "unnamed");
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String messagesLabel(int count) {
        if (uiLanguage == UiLanguage.ENGLISH) {
            return count + (count == 1 ? " message" : " messages");
        }
        String noun = plural(count, "сообщение", "сообщения", "сообщений");
        return count + " " + noun;
    }

    private String sessionsLabel(int count) {
        if (uiLanguage == UiLanguage.ENGLISH) {
            return count == 1 ? "session" : "sessions";
        }
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
        return new java.text.SimpleDateFormat("HH:mm", uiLanguage.locale)
                .format(new java.util.Date(epochMs));
    }

    private String calendarDate(long epochMs) {
        return new java.text.SimpleDateFormat("d MMM", uiLanguage.locale)
                .format(new java.util.Date(epochMs));
    }

    /**
     * Colours are baked into the views as they are built, so the activity is recreated instead of
     * walking the hierarchy. The transcript already lives in preferences, and a result that lands
     * during the restart is recovered from the durable per-operation store in {@link #onResume()}.
     */
    private void switchColorScheme(String schemeId) {
        if (busy) {
            toast(t(
                    "Дождитесь завершения текущей команды",
                    "Wait for the current command to finish"
            ));
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
        toast(t(
                "Команда скопирована — вставьте её в Termux",
                "Command copied — paste it into Termux"
        ));
        termux.openTermux();
    }

    private void setBusy(boolean value, String label) {
        busy = value;
        busyPhase = value && label != null ? label : "";
        deck.setBusy(value, label);
        refreshUi();
        if (!value
                && pendingPromptAfterCompaction == null
                && pendingPromptAfterNewSession == null) {
            main.post(this::dispatchQueuedPrompt);
            scheduleComposerWarmup();
        }
    }

    private void dispatchQueuedPrompt() {
        // A state/event callback may arrive while the choice dialog is open. The dialog owns the
        // queue until one of its buttons resolves it, even if bridge telemetry changes underneath.
        if (busy || queuedPrompt == null || contextWarningDialog != null) return;
        if (!canRunAgent()) {
            // A cold core is not a dead one. The server hands off to the bridge through a posted
            // continuation, so a prompt that arrives between those two steps waits rather than
            // being thrown away; the attempt counter is what keeps a failing warm-up from looping.
            if (canWarmCore() && queuedWarmAttempts < MAX_QUEUED_WARM_ATTEMPTS) {
                queuedWarmAttempts++;
                warmCore();
                return;
            }
            queuedPrompt = null;
            queuedWarmAttempts = 0;
            deck.setQueueCount(0);
            append(ConsoleEntry.Channel.ERROR,
                    t(
                            "Промпт из очереди не отправлен: ядро больше не готово принимать задачи.",
                            "The queued prompt was not sent because the core is no longer ready."
                    ));
            refreshUi();
            return;
        }
        if (needsQueuedContextChoice()) {
            showQueuedLargeContextChoice();
            return;
        }
        dispatchQueuedPromptNow();
    }

    private void dispatchQueuedPromptNow() {
        if (busy || queuedPrompt == null) return;
        if (!canRunAgent()) {
            main.post(this::dispatchQueuedPrompt);
            return;
        }
        String prompt = queuedPrompt;
        queuedPrompt = null;
        queuedWarmAttempts = 0;
        deck.setQueueCount(0);
        warnIfHot();
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
                            .put("agentMode", agentMode.wireName())
            );
            operations.dispatched(operation.operationId);
            pendingRpcPrompt.begin(operation.operationId, prompt);
            deck.setComposerDispatchPending(true);
            turnStartedAtUptimeMs = SystemClock.uptimeMillis();
            firstOutputAtUptimeMs = 0L;
            streamedCharacters = 0L;
            lastRateUpdateUptimeMs = turnStartedAtUptimeMs;
            deck.setGenerationSpeed(null);
            setBusy(true, contextPhaseLabel(t("Готовлю контекст", "Preparing context")));
            setInferenceActive(true, contextPhaseLabel(t(
                    "Готовлю контекст", "Preparing context"
            )));
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
                runOnUiThread(() -> acknowledgeRpcPrompt(operation.operationId));
            } catch (Exception error) {
                runOnUiThread(() -> failRpcDispatch(operation.operationId, error));
            }
        });
    }

    private void startBridge() {
        if (busy || !serverReady || !nativeModels.isInstalled(selectedModel)) return;
        bridgeFault = "";
        String sessionId = prefs.ensureSessionId();
        String systemPrompt = prefs.systemPrompt();
        SystemPromptSettings.Mode systemPromptMode = prefs.systemPromptMode();
        String effectivePromptMode = SystemPromptSettings.effectiveWireMode(
                systemPromptMode, systemPrompt
        );
        String systemPromptSha256 = SystemPromptSettings.sha256Hex(systemPrompt);
        JSONObject request = requestMetadata(selectedModel.id, prefs.hasSession());
        put(request, "accessProfile", accessProfile.wireName());
        put(request, "agentMode", agentMode.wireName());
        put(request, "sessionId", sessionId);
        put(request, "systemPromptMode", effectivePromptMode);
        put(request, "systemPromptSha256", systemPromptSha256);
        put(request, "systemPromptBytes", SystemPromptSettings.byteCount(systemPrompt));
        dispatchOperation(
                OperationKind.START_BRIDGE,
                request,
                "STARTING PI RPC BRIDGE",
                operationId -> {
                    JSONObject input = runtimeRequest(operationId);
                    put(input, "token", bridgeTokenStore.getOrCreate());
                    put(input, "modelId", selectedModel.id);
                    put(input, "accessProfile", accessProfile.wireName());
                    put(input, "agentMode", agentMode.wireName());
                    put(input, "sessionId", sessionId);
                    put(input, "port", RpcBridgeClient.DEFAULT_PORT);
                    put(input, "systemPromptMode", systemPromptMode.wireName());
                    put(input, "systemPrompt", systemPrompt);
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
                            bridgeFault = BridgeFaultPolicy.afterDisconnect(
                                    bridgeConnected,
                                    bridgeReady,
                                    bridgeFault,
                                    reason
                            );
                            bridgeConnected = false;
                            bridgeReady = false;
                            OperationRecord active = operations.active();
                            if (active != null
                                    && (active.kind == OperationKind.AGENT_TURN
                                    || active.kind == OperationKind.NEW_SESSION
                                    || active.kind == OperationKind.COMPACT_SESSION)
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
        JSONObject sessionStats = state.optJSONObject("sessionStats");
        contextUsage = SessionContextUsage.parse(
                sessionStats == null ? null : sessionStats.optJSONObject("contextUsage"),
                selectedModel.recommendedContext
        );
        contextCompacting = state.optBoolean("compacting", false);
        boolean sameModel = selectedModel.id.equals(state.optString("modelId"));
        boolean sameProfile = accessProfile.wireName().equals(state.optString("accessProfile"));
        boolean sameAgentMode = agentMode.wireName().equals(state.optString("agentMode", "agent"));
        String prompt = prefs.systemPrompt();
        String expectedPromptMode = SystemPromptSettings.effectiveWireMode(
                prefs.systemPromptMode(), prompt
        );
        boolean sameSystemPrompt = expectedPromptMode.equals(
                state.optString("systemPromptMode")
        ) && SystemPromptSettings.sha256Hex(prompt).equals(
                state.optString("systemPromptSha256")
        ) && SystemPromptSettings.byteCount(prompt) == state.optInt(
                "systemPromptBytes", -1
        );
        bridgeReady = sameModel && sameProfile && sameAgentMode && sameSystemPrompt;
        if (bridgeReady) {
            bridgeFault = "";
        } else if (!sameModel || !sameProfile || !sameAgentMode) {
            bridgeFault = t(
                    "Режим, профиль или модель bridge изменились; требуется restart.",
                    "The bridge mode, profile, or model changed; restart required."
            );
        } else {
            bridgeFault = t(
                    "Системный промпт bridge устарел; требуется restart.",
                    "The bridge system prompt is stale; restart required."
            );
        }

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
                    t(
                            "Session cursor восстановлен из авторитетного состояния bridge.",
                            "The session cursor was restored from authoritative bridge state."
                    ));
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
                if (stallState == null
                        && System.currentTimeMillis() - active.createdAtMs < active.kind.timeoutMs()) {
                    armRestoredWatchdog(active);
                }
                if (active.kind == OperationKind.AGENT_TURN) {
                    acknowledgeRpcPrompt(active.operationId);
                }
                busy = true;
                String phase = active.kind == OperationKind.COMPACT_SESSION
                        ? t("Сжимаю историю", "Compacting history")
                        : contextCompacting
                        ? t("Сжимаю историю", "Compacting history")
                        : t("Задача продолжается", "Task in progress");
                deck.setBusy(true, phase);
                if (active.kind == OperationKind.AGENT_TURN
                        || active.kind == OperationKind.COMPACT_SESSION) {
                    setInferenceActive(true, phase);
                }
            } else if ((active.kind == OperationKind.AGENT_TURN
                    || active.kind == OperationKind.NEW_SESSION
                    || active.kind == OperationKind.COMPACT_SESSION)
                    && active.state == OperationState.UNKNOWN) {
                long bridgeLastSequence = state.optLong("lastSequence", -1L);
                if (SessionContract.mayDeclareTerminalEventMissing(
                        bridgeLastSequence,
                        prefs.bridgeSequence()
                )) {
                    operations.reconcileTerminalMissing(
                            active.operationId,
                            "Bridge has no active turn and no terminal event is available"
                    );
                    cancelWatchdog(active.operationId);
                    deck.discardStreaming();
                    releaseRpcPrompt(active.operationId);
                    setBusy(false, null);
                    append(ConsoleEntry.Channel.ERROR,
                            t(
                                    "Bridge подтвердил отсутствие активного turn, но terminal event утрачен. "
                                            + "Операция завершена как FAILED и не повторялась автоматически.",
                                    "The bridge confirmed there is no active turn, but its terminal "
                                            + "event was lost. The operation was marked FAILED and "
                                            + "was not retried automatically."
                            ));
                } else {
                    setBusy(true, "OPERATION STATE // JOURNAL CATCH-UP");
                }
            } else if ((active.kind == OperationKind.AGENT_TURN
                    || active.kind == OperationKind.NEW_SESSION
                    || active.kind == OperationKind.COMPACT_SESSION)
                    && !active.state.isTerminal()
                    && active.state != OperationState.UNKNOWN) {
                operations.timeout(active.operationId);
                setBusy(true, "OPERATION STATE // UNKNOWN");
            }
        } else if (!contextCompacting && inferenceActive) {
            setInferenceActive(false, "");
        }
        refreshUi();
        main.postDelayed(this::maybeSmartCompactSession, 750L);
    }

    private void handleBridgeGap(String instanceId, long earliestReceived) {
        // A gap invalidates the speculative preview. The terminal event remains authoritative and
        // can reconstruct the answer without promoting an incomplete row into the transcript.
        deck.discardStreaming();
        OperationRecord active = operations.active();
        if (active != null && !active.state.isTerminal()) {
            operations.timeout(active.operationId);
            setBusy(true, "EVENT GAP // RECONCILE");
        }
        append(ConsoleEntry.Channel.ERROR,
                t(
                        "Bridge сообщил EVENT_GAP; выполнена полная сверка состояния. "
                                + "Промпт автоматически не повторялся.",
                        "The bridge reported EVENT_GAP; a full state reconciliation ran. "
                                + "The prompt was not retried automatically."
                ));
        prefs.saveTranscript(deck.entries());
        prefs.setBridgeCursor(instanceId, Math.max(0L, earliestReceived - 1L));
    }

    private void handleBridgeEvent(BridgeEvent event) {
        observedBridgeInstance = event.bridgeInstanceId;
        if (event.operationId != null
                && stallState != null
                && stallState.progress(event.operationId, System.currentTimeMillis())) {
            scheduleWatchdogCheck();
        }
        boolean refreshState = switch (event.type) {
            case MODEL_OUTPUT_DELTA, MODEL_OUTPUT_REJECTED, MODEL_THINKING_STARTED,
                    TOOL_CALL_REQUESTED,
                    TOOL_CALL_STARTED, TOOL_CALL_COMPLETED, SESSION_STATS_CHANGED,
                    CONTEXT_COMPACTION_STARTED, CONTEXT_COMPACTION_FINISHED,
                    SERVER_STATE_CHANGED, DIAGNOSTIC -> false;
            default -> true;
        };
        switch (event.type) {
            case BRIDGE_READY -> {
                bridgeConnected = true;
                bridgeReady = true;
                bridgeFault = "";
                main.post(this::dispatchQueuedPrompt);
            }
            case BRIDGE_ERROR -> append(
                    ConsoleEntry.Channel.ERROR,
                    "RPC protocol: " + event.payload.optString(
                            "message", t("неизвестная ошибка", "unknown error")
                    )
            );
            case PI_STARTED -> {
                bridgeReady = true;
                main.post(this::dispatchQueuedPrompt);
            }
            case PI_EXITED -> {
                setInferenceActive(false, "");
                if (!event.payload.optBoolean("expected", false)) {
                    append(ConsoleEntry.Channel.ERROR,
                            t(
                                    "Pi RPC child завершился; активный turn не будет повторён.",
                                    "The Pi RPC child exited; the active turn will not be retried."
                            ));
                }
            }
            case TURN_ACCEPTED -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    acknowledgeRpcPrompt(event.operationId);
                    setBusy(true, contextPhaseLabel(t(
                            "Готовлю контекст", "Preparing context"
                    )));
                    setInferenceActive(true, contextPhaseLabel(t(
                            "Готовлю контекст", "Preparing context"
                    )));
                }
            }
            case TURN_STARTED, MODEL_THINKING_STARTED -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    setBusy(true, t("Модель думает", "Model is thinking"));
                    setInferenceActive(true, t("Модель думает…", "Model is thinking…"));
                }
            }
            case MODEL_OUTPUT_DELTA -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    String delta = event.payload.optString("delta");
                    if (firstOutputAtUptimeMs == 0L) {
                        firstOutputAtUptimeMs = SystemClock.uptimeMillis();
                        setBusy(true, t("Печатает ответ", "Writing answer"));
                        setInferenceActive(true, t("Печатает ответ…", "Writing answer…"));
                    }
                    streamedCharacters += delta.length();
                    updateStreamingRate();
                    deck.appendStreaming(delta);
                }
            }
            case MODEL_OUTPUT_REJECTED -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    deck.discardStreaming();
                    prefs.saveTranscript(deck.entries());
                    firstOutputAtUptimeMs = 0L;
                    streamedCharacters = 0L;
                    lastRateUpdateUptimeMs = SystemClock.uptimeMillis();
                    deck.setGenerationSpeed(null);
                    if (event.payload.optBoolean("willRetry", false)) {
                        String retryState = "live_tool_required".equals(
                                event.payload.optString("reason"))
                                ? "Получаю актуальные данные"
                                : t("Задача продолжается", "Task in progress");
                        if ("live_tool_required".equals(event.payload.optString("reason"))) {
                            retryState = t(
                                    "Получаю актуальные данные",
                                    "Fetching current data"
                            );
                        }
                        setBusy(true, retryState);
                        setInferenceActive(true, retryState + "…");
                    } else {
                        setBusy(true, t("Ответ отклонён", "Answer rejected"));
                        setInferenceActive(true, t("Ответ отклонён", "Answer rejected"));
                    }
                }
            }
            case TOOL_CALL_STARTED -> {
                if (event.operationId != null
                        && event.operationId.equals(operations.activeOperationId())) {
                    firstOutputAtUptimeMs = 0L;
                    streamedCharacters = 0L;
                    lastRateUpdateUptimeMs = SystemClock.uptimeMillis();
                    deck.setGenerationSpeed(null);
                    deck.flushStreaming();
                    String verb = traceVerb(event.payload.optString("toolName", "tool"));
                    String argument = traceArgument(event.payload.optString("args", ""));
                    deck.addTrace(verb, argument, "");
                    prefs.saveTranscript(deck.entries());
                    // The row is what makes a long turn legible, so it moves on every event.
                    deck.setExecutionLabel(verb + " " + argument);
                    setInferenceActive(true, verb + " " + argument);
                }
            }
            case TOOL_CALL_COMPLETED -> {
                boolean failed = event.payload.optBoolean("isError", false);
                deck.completeTrace(failed
                        ? t("ошибка", "error")
                        : t("готово", "done"));
                if (failed) {
                    append(ConsoleEntry.Channel.ERROR,
                            t("Инструмент ", "Tool ")
                                    + event.payload.optString("toolName", "tool")
                                    + t(" вернул ошибку.\n", " returned an error.\n")
                                    + event.payload.optString("resultPreview"));
                }
            }
            case SESSION_STATS_CHANGED -> {
                contextUsage = SessionContextUsage.parse(
                        event.payload.optJSONObject("contextUsage"),
                        selectedModel.recommendedContext
                );
                refreshUi();
                main.post(this::maybeSmartCompactSession);
            }
            case CONTEXT_COMPACTION_STARTED -> {
                contextCompacting = true;
                setBusy(true, t("Сжимаю историю", "Compacting history"));
                setInferenceActive(true, t(
                        "Сжимаю историю сессии…", "Compacting session history…"
                ));
            }
            case CONTEXT_COMPACTION_FINISHED -> {
                contextCompacting = false;
                JSONObject estimatedUsage = new JSONObject();
                if (event.payload.has("estimatedTokensAfter")) {
                    put(estimatedUsage, "tokens", event.payload.optLong("estimatedTokensAfter"));
                    put(estimatedUsage, "contextWindow", selectedModel.recommendedContext);
                    put(estimatedUsage, "estimated", true);
                    contextUsage = SessionContextUsage.parse(
                            estimatedUsage, selectedModel.recommendedContext
                    );
                }
                if (!event.payload.optString("error").isBlank()) {
                    append(
                            ConsoleEntry.Channel.ERROR,
                            t(
                                    "Сжатие истории не завершилось: ",
                                    "History compaction failed: "
                            )
                                    + event.payload.optString("error")
                    );
                } else if (operations.active() != null
                        && operations.active().kind == OperationKind.AGENT_TURN) {
                    setBusy(true, t(
                            "Продолжаю после сжатия", "Continuing after compaction"
                    ));
                }
                refreshUi();
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
                                t(
                                        "Решение больше не ждёт ответа: bridge закрыл запрос по ",
                                        "The decision no longer needs a response: the bridge "
                                                + "closed it due to "
                                )
                                        + event.payload.optString(
                                        "source", t("таймауту", "timeout")
                                ) + ".");
                    }
                }
            }
            case TURN_COMPLETED, TURN_FAILED, TURN_ABORTED, SESSION_CREATED,
                    SESSION_COMPACTED, SESSION_COMPACTION_FAILED ->
                    completeRpcOperation(event);
            default -> {
            }
        }
        if (refreshState) refreshUi();
        // Persist acknowledgement only after the event's UI mutation. A process death may replay
        // an event, but it cannot durably acknowledge a rejection before its preview was removed.
        prefs.setBridgeCursor(event.bridgeInstanceId, event.sequence);
    }

    private void completeRpcOperation(BridgeEvent event) {
        if (event.operationId == null) return;
        OperationRecord record = operationStore.load(event.operationId);
        if (record == null) return;
        boolean success = event.type == BridgeEvent.Type.TURN_COMPLETED
                || event.type == BridgeEvent.Type.SESSION_CREATED
                || event.type == BridgeEvent.Type.SESSION_COMPACTED;
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
        if (record.kind == OperationKind.AGENT_TURN) {
            acknowledgeRpcPrompt(event.operationId);
        }
        if (record.kind == OperationKind.AGENT_TURN
                || record.kind == OperationKind.COMPACT_SESSION) {
            setInferenceActive(false, "");
        }
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
                        t(
                                "Открыта новая Pi RPC session без скрытого replay.",
                                "A new Pi RPC session opened without hidden replay."
                        ));
                contextUsage = SessionContextUsage.empty(selectedModel.recommendedContext);
                smartCompactionAttemptSession = null;
                smartCompactionAttemptTokens = -1L;
                deck.setGenerationSpeed(null);
                String pending = pendingPromptAfterNewSession;
                pendingPromptAfterNewSession = null;
                if (pending != null) main.post(() -> sendPromptNow(pending));
            } else {
                pendingPromptAfterNewSession = null;
                deck.setComposerDispatchPending(false);
                append(ConsoleEntry.Channel.ERROR, error);
            }
        } else if (record.kind == OperationKind.AGENT_TURN) {
            GenerationSpeed exactSpeed = GenerationSpeed.fromTerminal(event.payload);
            if (event.type == BridgeEvent.Type.TURN_COMPLETED) {
                prefs.setHasSession(true);
                deck.finishStreaming(answer, exactSpeed);
                prefs.saveTranscript(deck.entries());
            } else if (event.type == BridgeEvent.Type.TURN_ABORTED) {
                deck.discardStreaming();
                append(ConsoleEntry.Channel.SYSTEM, t(
                        "Pi turn подтверждённо остановлен.",
                        "The Pi turn was confirmed stopped."
                ));
            } else {
                prefs.setHasSession(true);
                if (!answer.isBlank()) deck.finishStreaming(answer, exactSpeed);
                else deck.discardStreaming();
                append(ConsoleEntry.Channel.ERROR,
                        t(
                                "Pi turn завершился ошибкой; запрос не повторялся.\n",
                                "The Pi turn failed; the request was not retried.\n"
                        ) + error);
            }
            if (exactSpeed != null) {
                deck.setGenerationSpeed(exactSpeed);
            } else if (event.type != BridgeEvent.Type.TURN_COMPLETED) {
                deck.setGenerationSpeed(null);
            }
            turnStartedAtUptimeMs = 0L;
            firstOutputAtUptimeMs = 0L;
            streamedCharacters = 0L;
        } else if (record.kind == OperationKind.COMPACT_SESSION) {
            contextCompacting = false;
            if (success) {
                long before = event.payload.optLong("tokensBefore", -1L);
                long after = event.payload.optLong("estimatedTokensAfter", -1L);
                if (after >= 0L) {
                    JSONObject estimated = new JSONObject();
                    put(estimated, "tokens", after);
                    put(estimated, "contextWindow", selectedModel.recommendedContext);
                    put(estimated, "estimated", true);
                    contextUsage = SessionContextUsage.parse(
                            estimated, selectedModel.recommendedContext
                    );
                }
                append(
                        ConsoleEntry.Channel.SYSTEM,
                        before >= 0L && after >= 0L
                                ? t("История сжата: ", "History compacted: ")
                                + before + " → ≈" + after
                                + t(
                                        " токенов. Следующие ответы начнутся быстрее.",
                                        " tokens. Subsequent answers will start faster."
                                )
                                : t(
                                        "История сжата. Следующие ответы начнутся быстрее.",
                                        "History compacted. Subsequent answers will start faster."
                                )
                );
                String pending = pendingPromptAfterCompaction;
                pendingPromptAfterCompaction = null;
                if (pending != null) main.post(() -> sendPromptNow(pending));
                else deck.setComposerDispatchPending(false);
            } else {
                pendingPromptAfterCompaction = null;
                deck.setComposerDispatchPending(false);
                append(ConsoleEntry.Channel.ERROR, t(
                        "Историю сжать не удалось.\n",
                        "History compaction failed.\n"
                ) + error);
            }
        }
        operations.markConsumed(event.operationId);
        if (record.kind == OperationKind.AGENT_TURN) {
            main.postDelayed(this::maybeSmartCompactSession, 750L);
        }
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
                .setTitle(event.payload.optString(
                        "title", t("Разрешить изменение?", "Allow this change?")
                ))
                .setMessage(event.payload.optString("message")
                        + "\n\nOperation: " + event.operationId
                        + "\nSession: "
                        + (event.sessionId == null ? "none" : event.sessionId)
                        + t(
                                "\n\nОдноразовое разрешение истекает через 30 секунд.",
                                "\n\nThis one-time permission expires in 30 seconds."
                        ))
                .setNegativeButton(t("Запретить", "Deny"), (dialog, which) -> {
                    if (responded.compareAndSet(false, true)) sendApproval(event, false);
                })
                .setPositiveButton(t(
                        "Разрешить один раз", "Allow once"
                ), (dialog, which) -> {
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
                    ? t("Разрешил перезаписать ", "Allowed overwrite of ")
                    + model.fileName() + "."
                    : t("Оставил ", "Left ") + model.fileName()
                    + t(" без изменений.", " unchanged."));
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
                        t(
                                "Approval не доставлен; bridge применит deny по timeout.",
                                "The approval was not delivered; the bridge will deny it on timeout."
                        )
                ));
            }
        });
    }

    private void failRpcDispatch(OperationId operationId, Exception error) {
        OperationRecord record = operationStore.load(operationId);
        boolean definitive = RpcBridgeClient.isDefinitiveCommandRejection(error);
        boolean ownsUi;
        try {
            ownsUi = definitive
                    ? operations.dispatchFailedIfActive(operationId, safeException(error))
                    : operations.dispatchUnknownIfActive(operationId);
        } catch (RuntimeException ignored) {
            ownsUi = false;
        }
        if (!ownsUi) return;
        if (!definitive) {
            setBusy(true, "OPERATION STATE // UNKNOWN");
            append(ConsoleEntry.Channel.SYSTEM,
                    t(
                            "Bridge мог принять RPC-команду, но подтверждение потеряно. "
                                    + "Сверяю журнал и состояние; запрос не повторяется.",
                            "The bridge may have accepted the RPC command, but its acknowledgement "
                                    + "was lost. Reconciling the journal and state; the command "
                                    + "will not be retried."
                    ));
            return;
        }

        cancelWatchdog(operationId);
        releaseRpcPrompt(operationId);
        if (record != null && (record.kind == OperationKind.AGENT_TURN
                || record.kind == OperationKind.COMPACT_SESSION)) {
            setInferenceActive(false, "");
        }
        if (record != null && record.kind == OperationKind.COMPACT_SESSION) {
            contextCompacting = false;
            pendingPromptAfterCompaction = null;
        }
        if (record != null && record.kind == OperationKind.NEW_SESSION) {
            pendingPromptAfterNewSession = null;
        }
        setBusy(false, null);
        append(ConsoleEntry.Channel.ERROR,
                t(
                        "RPC-команда не принята; автоматического повтора не было.\n",
                        "The RPC command was not accepted; it was not retried automatically.\n"
                )
                        + safeException(error));
    }

    private void acknowledgeRpcPrompt(OperationId operationId) {
        String prompt = pendingRpcPrompt.acknowledge(operationId);
        if (prompt != null) deck.acknowledgePrompt(prompt);
    }

    private void releaseRpcPrompt(OperationId operationId) {
        if (pendingRpcPrompt.release(operationId)) {
            deck.setComposerDispatchPending(false);
        }
    }

    private void changeAccessProfile(AccessProfile target) {
        if (busy) {
            toast(t(
                    "Дождитесь завершения текущей операции",
                    "Wait for the current operation to finish"
            ));
            return;
        }
        if (target == accessProfile) {
            toast(t("Профиль уже активен", "This profile is already active"));
            return;
        }
        if (target == AccessProfile.AUTONOMOUS) {
            new AlertDialog.Builder(this)
                    .setTitle(t("Включить AUTONOMOUS?", "Enable AUTONOMOUS?"))
                    .setMessage(t(
                            "Агент может выполнять shell-команды и изменять любые файлы, "
                                    + "доступные пользователю Termux. Workspace-ограничение не является "
                                    + "системной песочницей.",
                            "The agent may run shell commands and modify any files available "
                                    + "to the Termux user. The workspace boundary is not an OS sandbox."
                    ))
                    .setNegativeButton(t("Отмена", "Cancel"), null)
                    .setPositiveButton(t(
                            "Понимаю риск", "I understand the risk"
                    ), (dialog, which) ->
                            applyAccessProfile(target))
                    .show();
            return;
        }
        applyAccessProfile(target);
    }

    private void changeAgentMode(AgentMode target) {
        if (busy) {
            toast(t(
                    "Дождитесь завершения текущей операции",
                    "Wait for the current operation to finish"
            ));
            return;
        }
        if (target == agentMode) return;
        agentMode = target;
        prefs.setAgentMode(target);
        bridgeReady = false;
        bridgeConnected = false;
        bridgeFault = "";
        append(
                ConsoleEntry.Channel.SYSTEM,
                target == AgentMode.CHAT
                        ? t(
                                "Режим → Чат. Инструменты отключены, ответы начнутся быстрее.",
                                "Mode → Chat. Tools are disabled, so answers start faster."
                        )
                        : t(
                                "Режим → Агент. Инструменты доступны по выбранному профилю.",
                                "Mode → Agent. Tools are available under the selected profile."
                        )
        );
        if (serverReady) main.post(this::startBridge);
        else refreshUi();
    }

    private void applyAccessProfile(AccessProfile target) {
        accessProfile = target;
        prefs.setAccessProfile(target);
        bridgeReady = false;
        bridgeConnected = false;
        bridgeFault = "";
        append(ConsoleEntry.Channel.SYSTEM,
                t("Профиль доступа → ", "Access profile → ") + target.label + ".");
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
            value.put("agentMode", agentMode.wireName());
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

    private String t(String russian, String english) {
        return uiLanguage.pick(russian, english);
    }

}
