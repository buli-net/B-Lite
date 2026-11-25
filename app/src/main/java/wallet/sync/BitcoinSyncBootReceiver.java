package wallet.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts sync after an app update; device boot requires the user to reopen the wallet. */
public final class BitcoinSyncBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            try {
                BitcoinSyncService.start(context);
            } catch (IllegalStateException | SecurityException ignored) {
                // Android may reject a background foreground-service start.
                // The wallet resumes sync when the user next opens the app.
            }
        }
    }
}
