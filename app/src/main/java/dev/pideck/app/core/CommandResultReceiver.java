package dev.pideck.app.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class CommandResultReceiver extends BroadcastReceiver {
    public static final String EXTRA_REQUEST_ID = "dev.pideck.app.REQUEST_ID";

    @Override
    public void onReceive(Context context, Intent intent) {
        String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        Bundle result = intent.getBundleExtra("result");
        CommandResult commandResult;
        if (result == null) {
            commandResult = new CommandResult(
                    requestId, "", "", -1, 1,
                    "Termux не вернул результат. Проверьте разрешение RUN_COMMAND."
            );
        } else {
            commandResult = new CommandResult(
                    requestId,
                    result.getString("stdout", ""),
                    result.getString("stderr", ""),
                    result.getInt("exitCode", -1),
                    result.getInt("err", 0),
                    result.getString("errmsg", "")
            );
        }

        new DeckPreferences(context).savePendingResult(commandResult);
        CommandEvents.publish(commandResult);
    }
}
