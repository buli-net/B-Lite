package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import wallet.model.TransactionItem;

/**
 * Small, network-scoped cache of already-rendered home-screen data.
 *
 * This is presentation-only data: it never participates in wallet accounting,
 * signing, transaction selection, or synchronization. It lets the home screen
 * display the last local snapshot while bitcoinj opens the wallet/SPV chain and
 * reconnects to peers in the background.
 */
final class WalletHomeCache {
    private static final String PREFS_PREFIX = "wallet_home_cache_";
    private static final String KEY_SNAPSHOT = "snapshot_json";
    private static final int MAX_CACHED_TRANSACTIONS = 120;

    private WalletHomeCache() { }

    private static SharedPreferences prefs(Context context, BitcoinNetwork network) {
        String networkName = NetworkConfig.storageName(
                network == null ? BitcoinNetwork.MAINNET : network);
        return context.getApplicationContext().getSharedPreferences(
                PREFS_PREFIX + networkName, Context.MODE_PRIVATE);
    }

    static void save(Context context,
                     BitcoinNetwork network,
                     String balance,
                     String available,
                     String pending,
                     String address,
                     String walletType,
                     List<TransactionItem> transactions) {
        if (context == null || balance == null || address == null || address.isEmpty()) {
            return;
        }
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("balance", safe(balance));
            snapshot.put("available", safe(available));
            snapshot.put("pending", safe(pending));
            snapshot.put("address", safe(address));
            snapshot.put("walletType", safe(walletType));
            snapshot.put("savedAt", System.currentTimeMillis());

            JSONArray items = new JSONArray();
            if (transactions != null) {
                int start = Math.max(0, transactions.size() - MAX_CACHED_TRANSACTIONS);
                for (int i = start; i < transactions.size(); i++) {
                    TransactionItem item = transactions.get(i);
                    if (item == null) {
                        continue;
                    }
                    JSONObject encoded = new JSONObject();
                    encoded.put("type", safe(item.type));
                    encoded.put("amount", safe(item.amount));
                    encoded.put("time", safe(item.time));
                    encoded.put("confirmations", safe(item.confirmations));
                    encoded.put("txid", safe(item.txid));
                    encoded.put("counterparty", safe(item.counterparty));
                    encoded.put("peers", safe(item.peers));
                    encoded.put("state", safe(item.state));
                    items.put(encoded);
                }
            }
            snapshot.put("transactions", items);
            prefs(context, network).edit().putString(KEY_SNAPSHOT, snapshot.toString()).apply();
        } catch (Exception ignored) {
            // A cache failure must never block wallet refresh or affect wallet data.
        }
    }

    /**
     * Returns true only when a usable display snapshot exists for this exact network.
     * This lets startup decide whether it needs to read the wallet file for a first-run
     * preview before SPVBlockStore has finished opening.
     */
    static boolean hasSnapshot(Context context, BitcoinNetwork network) {
        if (context == null) {
            return false;
        }
        String encoded = prefs(context, network).getString(KEY_SNAPSHOT, null);
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        try {
            JSONObject snapshot = new JSONObject(encoded);
            return !snapshot.optString("balance", "").isEmpty()
                    && !snapshot.optString("address", "").isEmpty();
        } catch (Exception ignored) {
            prefs(context, network).edit().remove(KEY_SNAPSHOT).apply();
            return false;
        }
    }

    /** Renders an existing snapshot synchronously before startup/network sync finishes. */
    static boolean restore(MainActivity activity, BitcoinNetwork network) {
        if (activity == null) {
            return false;
        }
        String encoded = prefs(activity, network).getString(KEY_SNAPSHOT, null);
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        try {
            JSONObject snapshot = new JSONObject(encoded);
            String balance = snapshot.optString("balance", "");
            String address = snapshot.optString("address", "");
            if (balance.isEmpty() || address.isEmpty()) {
                return false;
            }

            activity.displayWalletType(snapshot.optString("walletType", ""));
            activity.displayMyBalance(balance);
            // Tell the user these are last-saved values until the live wallet refreshes.
            String note = activity.getString(R.string.wallet_cached_snapshot_note);
            activity.displayBalanceState(
                    snapshot.optString("available", "") + note,
                    snapshot.optString("pending", "") + note);
            activity.displayMyAddress(address);

            JSONArray items = snapshot.optJSONArray("transactions");
            List<TransactionItem> transactions = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    transactions.add(new TransactionItem(
                            item.optString("type", ""),
                            item.optString("amount", ""),
                            item.optString("time", ""),
                            item.optString("confirmations", ""),
                            item.optString("txid", ""),
                            item.optString("counterparty", ""),
                            item.optString("peers", ""),
                            item.optString("state", "")));
                }
            }
            activity.displayTransactions(transactions);
            return true;
        } catch (Exception ignored) {
            // Ignore malformed/outdated presentation data and allow live startup to render.
            prefs(activity, network).edit().remove(KEY_SNAPSHOT).apply();
            return false;
        }
    }

    static void clear(Context context, BitcoinNetwork network) {
        if (context != null) {
            prefs(context, network).edit().remove(KEY_SNAPSHOT).apply();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
