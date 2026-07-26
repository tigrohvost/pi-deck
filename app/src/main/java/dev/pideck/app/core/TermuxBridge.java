package dev.pideck.app.core;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

@SuppressLint("SdCardPath")
public final class TermuxBridge {
    public static final String PACKAGE = "com.termux";
    public static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final String PREFIX = "/data/data/com.termux/files/usr";
    public static final String HOME = "/data/data/com.termux/files/home";
    public static final String WORKSPACE = HOME + "/.pideck/workspace";
    public static final String TERMUX_EXEC = PREFIX + "/lib/libtermux-exec.so";

    private static final String ACTION_RUN = "com.termux.RUN_COMMAND";
    private static final String SERVICE = "com.termux.app.RunCommandService";

    private final Context context;

    public TermuxBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isInstalled() {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    public TermuxEnvironment inspectEnvironment() {
        return TermuxEnvironment.inspect(context);
    }

    public boolean hasRunPermission() {
        return context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestRunPermission(Activity activity, int requestCode) {
        activity.requestPermissions(new String[]{RUN_PERMISSION}, requestCode);
    }

    public void run(
            OperationId operationId,
            OperationKind kind,
            String executable,
            String[] arguments,
            String stdin,
            String workdir
    ) {
        TermuxEnvironment environment = inspectEnvironment();
        if (!environment.installed) throw new IllegalStateException("Termux не установлен");
        if (!environment.versionSupported) {
            throw new IllegalStateException(
                    "Версия Termux " + environment.version + " ниже минимальной 0.118.0"
            );
        }
        if (environment.source == TermuxEnvironment.Source.UNKNOWN) {
            throw new SecurityException("Подпись Termux отсутствует в compatibility allowlist");
        }
        if (!hasRunPermission()) throw new SecurityException("Нет разрешения RUN_COMMAND");

        Intent callback = new Intent(context, CommandResultReceiver.class)
                .setAction("dev.pideck.app.COMMAND_RESULT")
                .putExtra(CommandResultReceiver.EXTRA_OPERATION_ID, operationId.toString())
                .putExtra(CommandResultReceiver.EXTRA_OPERATION_KIND, kind.wireName());
        int requestCode = operationId.hashCode() & 0x7fffffff;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, callback, flags);

        Intent command = new Intent(ACTION_RUN)
                .setClassName(PACKAGE, SERVICE)
                .putExtra("com.termux.RUN_COMMAND_PATH", executable)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments == null ? new String[0] : arguments)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir == null ? HOME : workdir)
                .putExtra("com.termux.RUN_COMMAND_RUNNER", "app-shell")
                // RUN_COMMAND_RUNNER only exists since termux-shared 0.36.0. The F-Droid stable
                // build (0.118.x) reads this boolean instead and otherwise defaults to a terminal
                // session, which ignores RUN_COMMAND_STDIN and never returns stdout to the deck.
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "PI//DECK " + kind.wireName());
        if (stdin != null) command.putExtra("com.termux.RUN_COMMAND_STDIN", stdin);

        context.startService(command);
    }

    public void runBash(OperationId operationId, OperationKind kind, String script) {
        run(operationId, kind, PREFIX + "/bin/env", bashArguments(), script, HOME);
    }

    public void runRuntime(
            OperationId operationId,
            OperationKind kind,
            String command,
            String jsonInput
    ) {
        run(
                operationId,
                kind,
                PREFIX + "/bin/env",
                RuntimeScripts.runtimeArguments(command),
                jsonInput,
                WORKSPACE
        );
    }

    static String[] bashArguments() {
        // RUN_COMMAND bypasses Termux's login wrapper, which is normally responsible for loading
        // termux-exec. Without it, npm launchers with #!/usr/bin/env fail before Bash can run them.
        return new String[]{
                "LD_PRELOAD=" + TERMUX_EXEC,
                PREFIX + "/bin/bash",
                "-s"
        };
    }

    public void openTermux() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }

    public void openTermuxPage() {
        openUrl("https://f-droid.org/packages/com.termux/");
    }

    public void openAppSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public void openUrl(String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }
}
