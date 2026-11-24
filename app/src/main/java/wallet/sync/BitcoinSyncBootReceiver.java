package wallet.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the wallet foreground sync service after a normal device/app restart. */
public final class BitcoinSyncBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            try {
                // specialUse is not among the FGS types prohibited from
                // BOOT_COMPLETED on Android 15+. The system can still reject
                // a background start for other policy or device-state reasons.
                BitcoinSyncService.start(context);
            } catch (IllegalStateException | SecurityException ignored) {
                // Never let a denied background start crash the receiver.
                // The next foreground app launch starts the sync service.
            }
        }
    }
}
