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
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    public static final String EXTRA_MODEL_ID = "model_id";
    public static final String EXTRA_MODEL_PATH = "model_path";
    public static final String EXTRA_SERVER_FLAVOR = "server_flavor";
    public static final String EXTRA_PROFILE = "profile";
    public static final String EXTRA_ARGUMENTS = "arguments";
    public static final String EXTRA_PHASE = "phase";

    private static final String PREFS = "native_llama_service";
    private static final String CHANNEL_ID = "pideck-local-inference";
    private static final int NOTIFICATION_ID = 8201;
    private static final int MAX_NATIVE_LOG_BYTES = 4 * 1024 * 1024;
    private static final int RETAIN_NATIVE_LOG_BYTES = 2 * 1024 * 1024;
    private static final Object PROCESS_LOCK = new Object();
    private static volatile Process liveServerProcess;

    private volatile Process server;
    private volatile String activeOperationId = "";
    private Thread monitor;
    private PowerManager.WakeLock inferenceWakeLock;
    private final android.os.Handler idleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleShutdown = this::onIdleTimeout;
    private volatile boolean backgroundHint;
    private volatile boolean pressureStopRequested;
    private volatile String lastPromotedText = "Локальное ядро";

    /** The measured fact behind the suffix: a backgrounded deck decodes at ~1.5 tok/s. */
    static String notificationText(String base, boolean background) {
        return background ? base + " · фон: медленно" : base;
    }

    public static final class Snapshot {
        public final String state;
        public final String persistedState;
        public final String operationId;
        public final String modelId;
        public final String profile;
        public final long pid;
        public final String error;

        private Snapshot(SharedPreferences value) {
            Process process = liveServerProcess;
            boolean processAlive = process != null && process.isAlive();
            persistedState = value.getString("state", "STOPPED");
            state = StartupPolicy.effectiveNativeState(
                    persistedState,
                    processAlive
            );
            operationId = value.getString("operation_id", "");
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
        Intent intent = new Intent(context, NativeLlamaService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_OPERATION_ID, operationId.toString())
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_MODEL_PATH, modelFile.getAbsolutePath())
                .putExtra(EXTRA_SERVER_FLAVOR, model.serverFlavor)
                .putExtra(EXTRA_PROFILE, profile.toString())
                .putStringArrayListExtra(EXTRA_ARGUMENTS, new ArrayList<>(arguments));
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, NativeLlamaService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void markReady(Context context, String operationId) {
        Snapshot current = snapshot(context);
        if (!operationId.equals(current.operationId) || !"STARTING".equals(current.state)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("state", "READY")
                .putString("error", "")
                .apply();
        context.startService(
                new Intent(context, NativeLlamaService.class).setAction(ACTION_READY)
        );
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
        context.startService(
                new Intent(context, NativeLlamaService.class)
                        .setAction(ACTION_INFERENCE_ACTIVE)
                        .putExtra(EXTRA_PHASE, phase)
        );
    }

    public static void endInference(Context context) {
        context.startService(
                new Intent(context, NativeLlamaService.class).setAction(ACTION_INFERENCE_IDLE)
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_READY.equals(action)) {
            promote("Локальная модель готова");
            armIdleTimer();
            return START_NOT_STICKY;
        }
        if (ACTION_INFERENCE_ACTIVE.equals(action)) {
            idleHandler.removeCallbacks(idleShutdown);
            acquireInferenceWakeLock();
            String phase = intent.getStringExtra(EXTRA_PHASE);
            promote(phase == null || phase.isBlank() ? "Модель отвечает…" : safeLabel(phase));
            return START_NOT_STICKY;
        }
        if (ACTION_INFERENCE_IDLE.equals(action)) {
            releaseInferenceWakeLock();
            promote("Локальная модель готова");
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
            idleHandler.removeCallbacks(idleShutdown);
            promote("Останавливаю локальное ядро…");
            new Thread(this::stopAndExit, "pideck-native-llama-stop").start();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        idleHandler.removeCallbacks(idleShutdown);
        String operationId = intent.getStringExtra(EXTRA_OPERATION_ID);
        String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
        String modelPath = intent.getStringExtra(EXTRA_MODEL_PATH);
        String serverFlavor = intent.getStringExtra(EXTRA_SERVER_FLAVOR);
        String profile = intent.getStringExtra(EXTRA_PROFILE);
        ArrayList<String> arguments = intent.getStringArrayListExtra(EXTRA_ARGUMENTS);
        promote("Загружаю " + safeLabel(modelId) + "…");
        writeState("STARTING", operationId, modelId, profile, -1L, "");
        new Thread(
                () -> launch(
                        operationId, modelId, modelPath, serverFlavor, profile, arguments
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
            promote("Мало памяти · текущая задача продолжается");
            return;
        }
        if (action != MemoryPressurePolicy.Action.STOP_IDLE_CORE || pressureStopRequested) return;
        pressureStopRequested = true;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean("memory_stop", true).apply();
        promote("Освобождаю память · ядро бездействует");
        new Thread(this::stopAndExit, "pideck-native-llama-memory-stop").start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void launch(
            String operationId,
            String modelId,
            String modelPath,
            String serverFlavor,
            String profile,
            ArrayList<String> arguments
    ) {
        if (operationId == null || modelId == null || modelPath == null
                || arguments == null || arguments.isEmpty()) {
            fail(operationId, modelId, profile, "Некорректная конфигурация native llama-server");
            return;
        }
        try {
            OperationId.parse(operationId);
            File model = new File(modelPath).getCanonicalFile();
            File allowed = new File(getFilesDir(), "models").getCanonicalFile();
            if (!model.getPath().startsWith(allowed.getPath() + File.separator)
                    || !model.isFile()) {
                throw new IOException("GGUF находится вне приватного model store");
            }
            File libraryDir = new File(getApplicationInfo().nativeLibraryDir).getCanonicalFile();
            File executable = new File(
                    libraryDir,
                    serverLibraryForFlavor(serverFlavor)
            ).getCanonicalFile();
            if (!executable.isFile() || !executable.canExecute()) {
                throw new IOException("Встроенный llama-server недоступен для запуска");
            }

            Process launched;
            synchronized (PROCESS_LOCK) {
                stopCurrentLocked();
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
            writeState("STARTING", operationId, modelId, profile, pid, "");
            pumpLog(launched.getInputStream());
            monitor = new Thread(
                    () -> monitorProcess(launched, operationId, modelId, profile),
                    "pideck-native-llama-monitor"
            );
            monitor.start();
        } catch (IOException | IllegalArgumentException error) {
            fail(operationId, modelId, profile, safeError(error));
        }
    }

    static String serverLibraryForFlavor(String flavor) {
        if ("stock".equals(flavor)) return "libpideck_llama_server.so";
        if ("nanbeige42".equals(flavor)) return "libpideck_nanbeige_server.so";
        throw new IllegalArgumentException("Неизвестный native runtime: " + safeLabel(flavor));
    }

    private void pumpLog(InputStream input) {
        File directory = new File(getFilesDir(), "logs");
        if (!directory.isDirectory()) directory.mkdirs();
        File log = new File(directory, "native-llama-server.log");
        Thread pump = new Thread(() -> {
            try {
                BoundedLogFile.copy(
                        input,
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
        if (!operationId.equals(activeOperationId)) return;
        if (liveServerProcess == process) liveServerProcess = null;
        server = null;
        activeOperationId = "";
        Snapshot snapshot = snapshot(this);
        if (!"STOPPING".equals(snapshot.persistedState)
                && !"STOPPED".equals(snapshot.persistedState)) {
            fail(
                    operationId,
                    modelId,
                    profile,
                    "llama-server завершился с кодом " + code + ": " + logTail()
            );
        }
    }

    /** Armed only in READY/idle states; any activity cancels before it can fire. */
    private void armIdleTimer() {
        idleHandler.removeCallbacks(idleShutdown);
        long minutes = new DeckPreferences(this).coreIdleTimeoutMinutes();
        if (!IdleShutdown.enabled(minutes)) return;
        idleHandler.postDelayed(idleShutdown, IdleShutdown.delayMs(minutes));
    }

    private void onIdleTimeout() {
        Snapshot current = snapshot(this);
        // Fail closed: a race with a starting or answering core means no stop.
        if (!"READY".equals(current.state)) return;
        long minutes = new DeckPreferences(this).coreIdleTimeoutMinutes();
        if (!IdleShutdown.enabled(minutes)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean("idle_stop", true).apply();
        promote("Останавливаю ядро: бездействие " + minutes + " мин");
        new Thread(this::stopAndExit, "pideck-native-llama-idle-stop").start();
    }

    private void stopAndExit() {
        Snapshot before = snapshot(this);
        writeState(
                "STOPPING",
                before.operationId,
                before.modelId,
                before.profile,
                before.pid,
                ""
        );
        synchronized (PROCESS_LOCK) {
            stopCurrentLocked();
        }
        writeState("STOPPED", "", before.modelId, before.profile, -1L, "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopCurrentLocked() {
        Process current = server;
        if (current == null || !current.isAlive()) {
            if (liveServerProcess == current) liveServerProcess = null;
            server = null;
            activeOperationId = "";
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                current.destroy();
                if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        if (liveServerProcess == current) liveServerProcess = null;
        server = null;
        activeOperationId = "";
    }

    private void fail(String operationId, String modelId, String profile, String error) {
        releaseInferenceWakeLock();
        writeState("FAILED", operationId, modelId, profile, -1L, error);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
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
                "Локальное AI-ядро",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Модель работает локально на CPU телефона");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void acquireInferenceWakeLock() {
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

    private void releaseInferenceWakeLock() {
        if (inferenceWakeLock == null || !inferenceWakeLock.isHeld()) return;
        inferenceWakeLock.release();
    }

    private void promote(String text) {
        lastPromotedText = text;
        String shown = notificationText(text, backgroundHint);
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
                .setContentTitle("PI//DECK · локальное ядро")
                .setContentText(shown)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null,
                        "Открыть деку",
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
        if (!log.isFile()) return "диагностический лог пуст";
        try {
            byte[] raw = FilesCompat.tail(log, 2048);
            String value = new String(raw, StandardCharsets.UTF_8)
                    .replace('\0', ' ')
                    .trim();
            return value.isEmpty() ? "диагностический лог пуст" : value;
        } catch (IOException error) {
            return "диагностический лог недоступен";
        }
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) return "локальную модель";
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
