package dev.pideck.app.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import dev.pideck.app.MainActivity;
import dev.pideck.app.R;

/**
 * Owns llama-server under the PI//DECK UID so Android does not place inference in Termux's
 * background cpuset while the deck is visible.
 */
public final class NativeLlamaService extends Service {
    public static final String ACTION_START = "dev.pideck.app.native_llama.START";
    public static final String ACTION_STOP = "dev.pideck.app.native_llama.STOP";
    public static final String ACTION_READY = "dev.pideck.app.native_llama.READY";
    public static final String ACTION_INFERENCE_ACTIVE =
            "dev.pideck.app.native_llama.INFERENCE_ACTIVE";
    public static final String ACTION_INFERENCE_IDLE =
            "dev.pideck.app.native_llama.INFERENCE_IDLE";
    public static final String ACTION_IDLE_REARM = "dev.pideck.app.native_llama.IDLE_REARM";
    public static final String ACTION_BACKGROUND_HINT =
            "dev.pideck.app.native_llama.BACKGROUND_HINT";
    public static final String EXTRA_BACKGROUND = "background";
    public static final String EXTRA_OPERATION_ID = "operation_id";
    public static final String EXTRA_GENERATION = "server_generation";
    public static final String EXTRA_TASK_ID = "task_operation_id";
    public static final String EXTRA_MODEL_ID = "model_id";
    public static final String EXTRA_MODEL_PATH = "model_path";
    public static final String EXTRA_SERVER_FLAVOR = "server_flavor";
    public static final String EXTRA_PROFILE = "profile";
    public static final String EXTRA_ARGUMENTS = "arguments";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_CONTEXT_TOKENS = "context_tokens";
    public static final String EXTRA_CONTEXT_WINDOW = "context_window";

    private static final String PREFS = "native_llama_service";
    private static final String CHANNEL_ID = "pideck-local-inference";
    private static final int NOTIFICATION_ID = 8201;
    private static final int MAX_NATIVE_LOG_BYTES = 4 * 1024 * 1024;
    private static final int RETAIN_NATIVE_LOG_BYTES = 2 * 1024 * 1024;
    private static final Object PROCESS_LOCK = new Object();
    private static final Object GENERATION_LOCK = new Object();
    private record Request(String generation, String operation, boolean stopped) {}
    private static final AtomicReference<Request> REQUEST = new AtomicReference<>(new Request("", "", true));
    private static final InferenceLease INFERENCE = new InferenceLease();
    private static volatile Process liveServerProcess;

    private volatile Process server;
    private volatile String activeOperationId = "";
    private volatile String activeGeneration = "";
    private String inferenceTask = "";
    private Thread monitor;
    private PowerManager.WakeLock inferenceWakeLock;
    private final android.os.Handler idleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleShutdown = this::onIdleTimeout;
    private volatile boolean backgroundHint;
    private volatile boolean pressureStopRequested;
    private volatile String lastPromotedText = "PI//DECK";
    private volatile long latestContextTokens = -1L;
    private volatile int latestContextWindow;

    /** The measured fact behind the suffix: a backgrounded deck decodes at ~1.5 tok/s. */
    static String notificationText(String base, boolean background, UiLanguage language) {
        return background ? base + language.pick(" · фон: медленно", " · background: slow") : base;
    }

    /** The service is not an Activity, so it reads the deck's language on each label. */
    private String t(String russian, String english) {
        return new DeckPreferences(this).uiLanguage().pick(russian, english);
    }

    static String notificationText(String base, boolean background) {
        return notificationText(base, background, UiLanguage.RUSSIAN);
    }

    public static final class Snapshot {
        public final String state;
        public final String persistedState;
        public final String operationId;
        public final String serverGeneration;
        public final boolean processAlive;
        public final String modelId;
        public final String profile;
        public final long pid;
        public final String error;

        private Snapshot(SharedPreferences value) {
            Process process = liveServerProcess;
            processAlive = process != null && process.isAlive();
            persistedState = value.getString("state", "STOPPED");
            state = StartupPolicy.effectiveNativeState(
                    persistedState,
                    processAlive
            );
            operationId = value.getString("operation_id", "");
            serverGeneration = value.getString("server_generation", "");
            modelId = value.getString("model_id", "");
            profile = value.getString("profile", "");
            pid = value.getLong("pid", -1L);
            error = value.getString("error", "");
        }

        public boolean isStartingOrReady() {
            return "STARTING".equals(state) || "READY".equals(state);
        }
    }

    public static Snapshot snapshot(Context context) {
        return new Snapshot(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public static void start(
            Context context,
            OperationId operationId,
            ModelSpec model,
            File modelFile,
            CpuProfile profile,
            List<String> arguments
    ) {
        start(context, operationId, UUID.randomUUID().toString(), model, modelFile, profile, arguments);
    }

    public static void start(Context context, OperationId operationId, String generation,
            ModelSpec model, File modelFile, CpuProfile profile, List<String> arguments) {
        synchronized (GENERATION_LOCK) {
            REQUEST.set(new Request(generation, operationId.toString(), false));
            INFERENCE.clear();
        }
        Intent intent = new Intent(context, NativeLlamaService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_OPERATION_ID, operationId.toString())
                .putExtra(EXTRA_GENERATION, generation)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_MODEL_PATH, modelFile.getAbsolutePath())
                .putExtra(EXTRA_SERVER_FLAVOR, model.serverFlavor)
                .putExtra(EXTRA_PROFILE, profile.toString())
                .putStringArrayListExtra(EXTRA_ARGUMENTS, new ArrayList<>(arguments));
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        stopIfGeneration(context, currentGeneration(context));
    }

    public static String currentGeneration(Context context) {
        String generation = REQUEST.get().generation();
        return generation.isBlank() ? snapshot(context).serverGeneration : generation;
    }

    public static boolean generationRunning(String generation) {
        Request request = REQUEST.get();
        return generation != null && generation.equals(request.generation()) && !request.stopped();
    }

    public static void stopIfGeneration(Context context, String generation) {
        synchronized (GENERATION_LOCK) {
            Request request = REQUEST.get();
            if (!request.generation().isBlank() && !generation.equals(request.generation())) return;
            REQUEST.set(new Request(generation, request.operation(), true));
        }
        context.startService(new Intent(context, NativeLlamaService.class).setAction(ACTION_STOP)
                .putExtra(EXTRA_GENERATION, generation));
    }

    /** The last cold-load duration of a model, so the next load can say what to expect. */
    public static void recordLoadMillis(Context context, String modelId, long elapsedMs) {
        if (modelId == null || modelId.isBlank() || elapsedMs <= 0L) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("load_ms_" + modelId, elapsedMs)
                .apply();
    }

    public static long lastLoadMillis(Context context, String modelId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("load_ms_" + modelId, 0L);
    }

    public static void markReady(Context context, String operationId) {
        markReady(context, operationId, snapshot(context).serverGeneration);
    }

    public static boolean markReady(Context context, String operationId, String generation) {
        synchronized (GENERATION_LOCK) {
            Snapshot current = snapshot(context);
            if (!generationRunning(generation) || !generation.equals(current.serverGeneration)
                    || !operationId.equals(current.operationId)
                    || !("STARTING".equals(current.state) || "READY".equals(current.state))) return false;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("state", "READY")
                    .putString("error", "")
                    .apply();
            context.startService(
                    new Intent(context, NativeLlamaService.class).setAction(ACTION_READY).putExtra(EXTRA_GENERATION, generation)
            );
            return true;
        }
    }

    /** The deck's visibility, so the notification can say honestly that background is slow. */
    public static void backgroundHint(Context context, boolean background) {
        // A stopped core has no notification to annotate; never start the service for a hint.
        if (!snapshot(context).isStartingOrReady()) return;
        try {
            context.startService(
                    new Intent(context, NativeLlamaService.class)
                            .setAction(ACTION_BACKGROUND_HINT)
                            .putExtra(EXTRA_BACKGROUND, background)
            );
        } catch (IllegalStateException error) {
            // Background start restrictions can refuse the intent; the hint is cosmetic.
        }
    }

    /** Re-reads the CORE idle-timeout setting; a no-op unless the core is READY. */
    public static void rearmIdleTimer(Context context) {
        context.startService(
                new Intent(context, NativeLlamaService.class).setAction(ACTION_IDLE_REARM)
        );
    }

    public static void beginInference(Context context, String phase) {
        beginInference(context, phase, "probe:" + snapshot(context).operationId);
    }

    public static void beginInference(Context context, String phase, String taskId) {
        String generation = currentGeneration(context);
        if (taskId == null || taskId.isBlank() || !generationRunning(generation)) return;
        INFERENCE.begin(generation, taskId);
        context.startService(
                new Intent(context, NativeLlamaService.class)
                        .setAction(ACTION_INFERENCE_ACTIVE)
                        .putExtra(EXTRA_PHASE, phase)
                        .putExtra(EXTRA_GENERATION, generation)
                        .putExtra(EXTRA_TASK_ID, taskId)
        );
    }

    public static void endInference(Context context, SessionContextUsage usage, String taskId) {
        String generation = currentGeneration(context);
        if (!INFERENCE.end(generation, taskId)) return;
        Intent intent = new Intent(context, NativeLlamaService.class)
                .setAction(ACTION_INFERENCE_IDLE)
                .putExtra(EXTRA_GENERATION, generation)
                .putExtra(EXTRA_TASK_ID, taskId);
        if (usage != null && usage.known()) {
            intent.putExtra(EXTRA_CONTEXT_TOKENS, usage.tokens);
            intent.putExtra(EXTRA_CONTEXT_WINDOW, usage.contextWindow);
        }
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        String generation = intent == null ? "" : intent.getStringExtra(EXTRA_GENERATION);
        if ((ACTION_READY.equals(action) || ACTION_INFERENCE_ACTIVE.equals(action) || ACTION_INFERENCE_IDLE.equals(action))
                && (!generationRunning(generation) || !generation.equals(activeGeneration))) return START_NOT_STICKY;
        if (ACTION_READY.equals(action)) {
            if (!inferenceTask.isBlank()) return START_NOT_STICKY;
            promote(t("Локальная модель готова", "Local model ready"));
            armIdleTimer();
            return START_NOT_STICKY;
        }
        if (ACTION_INFERENCE_ACTIVE.equals(action)) {
            String taskId = intent.getStringExtra(EXTRA_TASK_ID);
            if (taskId == null || taskId.isBlank() || !INFERENCE.accepts(generation, taskId, true)) return START_NOT_STICKY;
            inferenceTask = taskId;
            idleHandler.removeCallbacks(idleShutdown);
            acquireInferenceWakeLock();
            String phase = intent.getStringExtra(EXTRA_PHASE);
            promote(phase == null || phase.isBlank() ? t("Модель отвечает…", "Model is answering…") : safeLabel(phase));
            return START_NOT_STICKY;
        }
        if (ACTION_INFERENCE_IDLE.equals(action)) {
            if (!inferenceTask.equals(intent.getStringExtra(EXTRA_TASK_ID))
                    || !INFERENCE.accepts(generation, inferenceTask, false)) return START_NOT_STICKY;
            inferenceTask = "";
            latestContextTokens = intent.getLongExtra(EXTRA_CONTEXT_TOKENS, -1L);
            latestContextWindow = intent.getIntExtra(EXTRA_CONTEXT_WINDOW, 0);
            releaseInferenceWakeLock();
            promote(t("Локальная модель готова", "Local model ready"));
            armIdleTimer();
            return START_NOT_STICKY;
        }
        if (ACTION_IDLE_REARM.equals(action)) {
            if ("READY".equals(snapshot(this).state)) armIdleTimer();
            else idleHandler.removeCallbacks(idleShutdown);
            return START_NOT_STICKY;
        }
        if (ACTION_BACKGROUND_HINT.equals(action)) {
            backgroundHint = intent.getBooleanExtra(EXTRA_BACKGROUND, false);
            promote(lastPromotedText);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            if (generation == null || !generation.equals(REQUEST.get().generation()) || !REQUEST.get().stopped()) return START_NOT_STICKY;
            idleHandler.removeCallbacks(idleShutdown);
            promote(t("Останавливаю локальное ядро…", "Stopping the local core…"));
            new Thread(() -> stopAndExit(generation), "pideck-native-llama-stop").start();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (generation == null || !generationRunning(generation)) return START_NOT_STICKY;

        idleHandler.removeCallbacks(idleShutdown);
        latestContextTokens = -1L;
        latestContextWindow = 0;
        String operationId = intent.getStringExtra(EXTRA_OPERATION_ID);
        String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
        String modelPath = intent.getStringExtra(EXTRA_MODEL_PATH);
        String serverFlavor = intent.getStringExtra(EXTRA_SERVER_FLAVOR);
        String profile = intent.getStringExtra(EXTRA_PROFILE);
        ArrayList<String> arguments = intent.getStringArrayListExtra(EXTRA_ARGUMENTS);
        promote(t("Загружаю ", "Loading ") + safeLabel(modelId) + "…");
        synchronized (GENERATION_LOCK) {
            if (!generationRunning(generation)) return START_NOT_STICKY;
            activeGeneration = generation;
            inferenceTask = "";
            releaseInferenceWakeLock();
            writeState("STARTING", operationId, modelId, profile, -1L, "");
        }
        new Thread(
                () -> launch(
                        operationId, generation, modelId, modelPath, serverFlavor, profile, arguments
                ),
                "pideck-native-llama-launch"
        ).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        idleHandler.removeCallbacks(idleShutdown);
        releaseInferenceWakeLock();
        Process current = server;
        if (current != null && current.isAlive()) {
            current.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean active = inferenceWakeLock != null && inferenceWakeLock.isHeld();
        MemoryPressurePolicy.Action action = MemoryPressurePolicy.decide(
                level,
                "READY".equals(snapshot(this).state),
                active
        );
        if (action == MemoryPressurePolicy.Action.WARN_ACTIVE_TURN) {
            promote(t("Мало памяти · текущая задача продолжается", "Low memory · the current task continues"));
            return;
        }
        if (action != MemoryPressurePolicy.Action.STOP_IDLE_CORE || pressureStopRequested) return;
        pressureStopRequested = true;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean("memory_stop", true).apply();
        promote(t("Освобождаю память · ядро бездействует", "Freeing memory · the core is idle"));
        new Thread(this::stopAndExit, "pideck-native-llama-memory-stop").start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void launch(
            String operationId,
            String generation,
            String modelId,
            String modelPath,
            String serverFlavor,
            String profile,
            ArrayList<String> arguments
    ) {
        if (operationId == null || modelId == null || modelPath == null
                || arguments == null || arguments.isEmpty()) {
            fail(generation, operationId, modelId, profile, t("Некорректная конфигурация native llama-server", "Invalid native llama-server configuration"));
            return;
        }
        try {
            OperationId.parse(operationId);
            File model = new File(modelPath).getCanonicalFile();
            File allowed = new File(getFilesDir(), "models").getCanonicalFile();
            if (!model.getPath().startsWith(allowed.getPath() + File.separator)
                    || !model.isFile()) {
                throw new IOException(t("GGUF находится вне приватного model store", "The GGUF is outside the private model store"));
            }
            File libraryDir = new File(getApplicationInfo().nativeLibraryDir).getCanonicalFile();
            File executable = new File(
                    libraryDir,
                    serverLibraryForFlavor(serverFlavor)
            ).getCanonicalFile();
            if (!executable.isFile() || !executable.canExecute()) {
                throw new IOException(t("Встроенный llama-server недоступен для запуска", "The bundled llama-server cannot be started"));
            }

            Process launched;
            synchronized (PROCESS_LOCK) {
                if (!generationRunning(generation)) return;
                if (!stopCurrentLocked()) throw new IOException("Previous native process did not stop");
                if (!generationRunning(generation)) return;
                ArrayList<String> command = new ArrayList<>();
                command.add(executable.getAbsolutePath());
                command.addAll(arguments);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(libraryDir);
                builder.redirectErrorStream(true);
                builder.environment().put("LD_LIBRARY_PATH", libraryDir.getAbsolutePath());
                builder.environment().put("PIDECK_OPERATION_ID", operationId);
                builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                launched = builder.start();
                server = launched;
                liveServerProcess = launched;
                activeOperationId = operationId;
            }
            // android.jar exposes the legacy Process surface even though the host JDK has pid().
            // The service-owned Process object remains the authoritative identity.
            long pid = -1L;
            synchronized (GENERATION_LOCK) {
                if (!generationRunning(generation)) {
                    launched.destroy();
                    return;
                }
                writeState("STARTING", operationId, modelId, profile, pid, "");
            }
            pumpLog(launched.getInputStream());
            monitor = new Thread(
                    () -> monitorProcess(launched, generation, operationId, modelId, profile),
                    "pideck-native-llama-monitor"
            );
            monitor.start();
        } catch (IOException | IllegalArgumentException error) {
            fail(generation, operationId, modelId, profile, safeError(error));
        }
    }

    static String serverLibraryForFlavor(String flavor) {
        if ("stock".equals(flavor)) return "libpideck_llama_server.so";
        if ("nanbeige42".equals(flavor)) return "libpideck_nanbeige_server.so";
        if ("prism".equals(flavor)) return "libpideck_prism_server.so";
        if ("k2horizon".equals(flavor)) return CpuProfile.supportsI8mm()
                ? "libpideck_k2horizon_i8mm_server.so"
                : "libpideck_k2horizon_server.so";
        throw new IllegalArgumentException("Unknown native runtime: " + safeLabel(flavor));
    }

    private void pumpLog(InputStream input) {
        File directory = new File(getFilesDir(), "logs");
        if (!directory.isDirectory()) directory.mkdirs();
        File log = new File(directory, "native-llama-server.log");
        Thread pump = new Thread(() -> {
            try {
                BoundedLogFile.copy(
                        new ServerProgress.Tap(input, SystemClock::uptimeMillis),
                        log,
                        MAX_NATIVE_LOG_BYTES,
                        RETAIN_NATIVE_LOG_BYTES,
                        true
                );
            } catch (IOException ignored) {
            }
        }, "pideck-native-llama-log");
        pump.start();
    }

    private void monitorProcess(
            Process process,
            String generation,
            String operationId,
            String modelId,
            String profile
    ) {
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (PROCESS_LOCK) {
            if (!generationRunning(generation) || server != process || !operationId.equals(activeOperationId)) return;
            if (liveServerProcess == process) liveServerProcess = null;
            server = null;
            activeOperationId = "";
            Snapshot snapshot = snapshot(this);
            if (!"STOPPING".equals(snapshot.persistedState)
                    && !"STOPPED".equals(snapshot.persistedState)) {
                fail(
                        generation,
                        operationId,
                        modelId,
                        profile,
                        t("llama-server завершился с кодом ", "llama-server exited with code ") + code + ": " + logTail()
                );
            }
        }
    }

    /** Armed only in READY/idle states; any activity cancels before it can fire. */
    private void armIdleTimer() {
        idleHandler.removeCallbacks(idleShutdown);
        long setting = new DeckPreferences(this).coreIdleTimeoutMinutes();
        if (!IdleShutdown.enabled(setting)) return;
        long effectiveMinutes = IdleShutdown.effectiveMinutes(
                setting, latestContextTokens, latestContextWindow
        );
        idleHandler.postDelayed(idleShutdown, IdleShutdown.delayMs(effectiveMinutes));
    }

    private void onIdleTimeout() {
        Snapshot current = snapshot(this);
        // Fail closed: a race with a starting or answering core means no stop.
        if (!"READY".equals(current.state)) return;
        long setting = new DeckPreferences(this).coreIdleTimeoutMinutes();
        if (!IdleShutdown.enabled(setting)) return;
        long effectiveMinutes = IdleShutdown.effectiveMinutes(
                setting, latestContextTokens, latestContextWindow
        );
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean("idle_stop", true).apply();
        promote(t("Останавливаю ядро: бездействие ", "Stopping the core: idle for ") + effectiveMinutes + t(" мин", " min"));
        new Thread(this::stopAndExit, "pideck-native-llama-idle-stop").start();
    }

    private void stopAndExit() {
        stop(this);
    }

    private void stopAndExit(String generation) {
        if (!generation.equals(REQUEST.get().generation()) || !REQUEST.get().stopped()) return;
        Snapshot before = snapshot(this);
        synchronized (GENERATION_LOCK) {
            if (!generation.equals(REQUEST.get().generation()) || !REQUEST.get().stopped()) return;
            activeGeneration = generation;
            writeState(
                    "STOPPING",
                    before.operationId,
                    before.modelId,
                    before.profile,
                    before.pid,
                    ""
            );
        }
        boolean stopped;
        synchronized (PROCESS_LOCK) {
            if (!generation.equals(REQUEST.get().generation()) || !REQUEST.get().stopped()) return;
            stopped = stopCurrentLocked();
        }
        synchronized (GENERATION_LOCK) {
            if (!generation.equals(REQUEST.get().generation()) || !REQUEST.get().stopped()) return;
            releaseInferenceWakeLock();
            inferenceTask = "";
            writeState(stopped ? "STOPPED" : "FAILED", before.operationId, before.modelId, before.profile, -1L,
                    stopped ? "" : "Native process did not stop within deadline");
            if (!stopped) return;
            finishService(generation);
        }
    }

    private boolean stopCurrentLocked() {
        Process current = server;
        if (current == null || !current.isAlive()) {
            if (liveServerProcess == current) liveServerProcess = null;
            server = null;
            activeOperationId = "";
            return true;
        }
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                if (!current.waitFor(2, TimeUnit.SECONDS)) return false;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
            if (current.isAlive()) return false;
        }
        if (liveServerProcess == current) liveServerProcess = null;
        server = null;
        activeOperationId = "";
        return true;
    }

    private void fail(String generation, String operationId, String modelId, String profile, String error) {
        synchronized (GENERATION_LOCK) {
            if (!generationRunning(generation) || !generation.equals(activeGeneration)) return;
            releaseInferenceWakeLock();
            writeState("FAILED", operationId, modelId, profile, -1L, error);
            finishService(generation);
        }
    }

    private void finishService(String generation) {
        idleHandler.post(() -> {
            synchronized (GENERATION_LOCK) {
                if (!generation.equals(REQUEST.get().generation()) || !generation.equals(activeGeneration)) return;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private void writeState(
            String state,
            String operationId,
            String modelId,
            String profile,
            long pid,
            String error
    ) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("state", state == null ? "UNKNOWN" : state)
                .putString("operation_id", operationId == null ? "" : operationId)
                .putString("server_generation", activeGeneration)
                .putString("model_id", modelId == null ? "" : modelId)
                .putString("profile", profile == null ? "" : profile)
                .putLong("pid", pid)
                .putString("error", error == null ? "" : error)
                .apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                t("Локальное AI-ядро", "Local AI core"),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(t("Модель работает локально на CPU телефона", "The model runs locally on the phone CPU"));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private synchronized void acquireInferenceWakeLock() {
        if (inferenceWakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return;
            inferenceWakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "pideck:local-inference-turn"
            );
            inferenceWakeLock.setReferenceCounted(false);
        }
        if (!inferenceWakeLock.isHeld()) {
            // The normal RPC watchdog is 45 minutes. The timeout is a fail-safe if lifecycle
            // signals are lost; terminal events release the lock immediately.
            inferenceWakeLock.acquire(45L * 60L * 1000L);
        }
    }

    private synchronized void releaseInferenceWakeLock() {
        if (inferenceWakeLock == null || !inferenceWakeLock.isHeld()) return;
        inferenceWakeLock.release();
    }

    private void promote(String text) {
        lastPromotedText = text;
        String shown = notificationText(text, backgroundHint, new DeckPreferences(this).uiLanguage());
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(t("PI//DECK · локальное ядро", "PI//DECK · local core"))
                .setContentText(shown)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null,
                        t("Открыть деку", "Open the deck"),
                        content
                ).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private String logTail() {
        File log = new File(new File(getFilesDir(), "logs"), "native-llama-server.log");
        if (!log.isFile()) return t("диагностический лог пуст", "the diagnostic log is empty");
        try {
            byte[] raw = FilesCompat.tail(log, 2048);
            String value = new String(raw, StandardCharsets.UTF_8)
                    .replace('\0', ' ')
                    .trim();
            return value.isEmpty() ? t("диагностический лог пуст", "the diagnostic log is empty") : value;
        } catch (IOException error) {
            return t("диагностический лог недоступен", "the diagnostic log is unavailable");
        }
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) return "GGUF";
        return value.length() <= 80 ? value : value.substring(0, 80);
    }

    private static String safeError(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }

    /** Small tail reader kept here to avoid mapping an unbounded native log into the Java heap. */
    private static final class FilesCompat {
        private static byte[] tail(File file, int maximum) throws IOException {
            try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
                int size = (int) Math.min(maximum, input.length());
                byte[] value = new byte[size];
                input.seek(input.length() - size);
                input.readFully(value);
                return value;
            }
        }
    }
}
