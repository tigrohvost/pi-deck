package dev.pideck.app.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class CommandResultReceiver extends BroadcastReceiver {
    public static final String EXTRA_OPERATION_ID = "dev.pideck.app.OPERATION_ID";
    public static final String EXTRA_OPERATION_KIND = "dev.pideck.app.OPERATION_KIND";

    @Override
    public void onReceive(Context context, Intent intent) {
        OperationId operationId;
        OperationKind kind;
        try {
            operationId = OperationId.parse(intent.getStringExtra(EXTRA_OPERATION_ID));
            kind = OperationKind.fromWireName(intent.getStringExtra(EXTRA_OPERATION_KIND));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        Bundle result = intent.getBundleExtra("result");
        CommandResult commandResult;
        if (result == null) {
            commandResult = new CommandResult(
                    operationId, kind, "", "", -1, 1,
                    "Termux не вернул результат. Проверьте разрешение RUN_COMMAND."
            );
        } else {
            commandResult = new CommandResult(
                    operationId,
                    kind,
                    result.getString("stdout", ""),
                    result.getString("stderr", ""),
                    result.getInt("exitCode", -1),
                    result.getInt("err", 0),
                    result.getString("errmsg", "")
            );
        }

        new OperationStore(context).recordResult(commandResult);
        CommandEvents.publish(commandResult);
    }
}
