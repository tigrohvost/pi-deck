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

import java.util.UUID;

@SuppressLint("SdCardPath")
public final class TermuxBridge {
    public static final String PACKAGE = "com.termux";
    public static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final String PREFIX = "/data/data/com.termux/files/usr";
    public static final String HOME = "/data/data/com.termux/files/home";
    public static final String WORKSPACE = HOME + "/.pideck/workspace";

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

    public boolean hasRunPermission() {
        return context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestRunPermission(Activity activity, int requestCode) {
        activity.requestPermissions(new String[]{RUN_PERMISSION}, requestCode);
    }

    public String run(
            String kind,
            String executable,
            String[] arguments,
            String stdin,
            String workdir
    ) {
        if (!isInstalled()) throw new IllegalStateException("Termux не установлен");
        if (!hasRunPermission()) throw new SecurityException("Нет разрешения RUN_COMMAND");

        String requestId = kind + ":" + UUID.randomUUID();
        Intent callback = new Intent(context, CommandResultReceiver.class)
                .setAction("dev.pideck.app.COMMAND_RESULT")
                .putExtra(CommandResultReceiver.EXTRA_REQUEST_ID, requestId);
        int requestCode = requestId.hashCode() & 0x7fffffff;
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
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "PI//DECK " + kind);
        if (stdin != null) command.putExtra("com.termux.RUN_COMMAND_STDIN", stdin);

        context.startService(command);
        return requestId;
    }

    public String runBash(String kind, String script) {
        return run(kind, PREFIX + "/bin/bash", new String[]{"-s"}, script, HOME);
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
