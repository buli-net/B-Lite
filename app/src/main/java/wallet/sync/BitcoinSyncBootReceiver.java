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
                BitcoinSyncService.start(context);
            } catch (RuntimeException ignored) {
                // Continue restoring the explicitly enabled node even if the wallet's legacy service cannot start.
            }
            org.bitcoinj.base.BitcoinNetwork nodeNetwork =
                    wallet.node.FullPrunedNodeStatus.getSelectedNetwork(context);
            if (nodeNetwork != null && wallet.node.FullPrunedNodeStatus.isEnabled(context, nodeNetwork)) {
                wallet.node.FullPrunedNodeService.start(context, nodeNetwork);
            }
        }
    }
}
