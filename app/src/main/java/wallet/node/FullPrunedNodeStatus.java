/* Experimental full-pruned node integration. */
package wallet.node;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;

/** Small per-network status and restart preference store for the experimental node. */
public final class FullPrunedNodeStatus {
    private static final String PREFS = "bitcoinj_full_pruned_node";
    private static final String PREFIX = "network_";
    private static final String KEY_SELECTED_NETWORK = "selected_network";
    private FullPrunedNodeStatus() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(BitcoinNetwork network, String suffix) {
        return PREFIX + network.name().toLowerCase(java.util.Locale.ROOT) + "_" + suffix;
    }

    public static boolean isEnabled(Context context, BitcoinNetwork network) {
        return prefs(context).getBoolean(key(network, "enabled"), false);
    }

    public static void setEnabled(Context context, BitcoinNetwork network, boolean enabled) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit().putBoolean(key(network, "enabled"), enabled);
        if (enabled) {
            BitcoinNetwork other = network == BitcoinNetwork.MAINNET
                    ? BitcoinNetwork.SIGNET : BitcoinNetwork.MAINNET;
            editor.putBoolean(key(other, "enabled"), false)
                    .putString(KEY_SELECTED_NETWORK, network.name());
        } else if (network.name().equals(p.getString(KEY_SELECTED_NETWORK, ""))) {
            editor.remove(KEY_SELECTED_NETWORK);
        }
        editor.apply();
    }

    /** Network selected for the single node service, independent from the wallet UI selection. */
    public static BitcoinNetwork getSelectedNetwork(Context context) {
        String value = prefs(context).getString(KEY_SELECTED_NETWORK, "");
        try {
            BitcoinNetwork network = BitcoinNetwork.valueOf(value);
            return network == BitcoinNetwork.MAINNET || network == BitcoinNetwork.SIGNET ? network : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void write(Context context, BitcoinNetwork network, String state,
                             int height, int blocksLeft, int peers, long databaseBytes, String error) {
        prefs(context).edit()
                .putString(key(network, "state"), state == null ? "stopped" : state)
                .putInt(key(network, "height"), Math.max(0, height))
                .putInt(key(network, "blocks_left"), Math.max(0, blocksLeft))
                .putInt(key(network, "peers"), Math.max(0, peers))
                .putLong(key(network, "database_bytes"), Math.max(0L, databaseBytes))
                .putString(key(network, "error"), error == null ? "" : error)
                .putLong(key(network, "updated_at"), System.currentTimeMillis())
                .apply();
    }

    public static Snapshot read(Context context, BitcoinNetwork network) {
        SharedPreferences p = prefs(context);
        return new Snapshot(
                p.getString(key(network, "state"), "stopped"),
                p.getInt(key(network, "height"), 0),
                p.getInt(key(network, "blocks_left"), 0),
                p.getInt(key(network, "peers"), 0),
                p.getLong(key(network, "database_bytes"), 0L),
                p.getString(key(network, "error"), ""),
                p.getLong(key(network, "updated_at"), 0L),
                p.getBoolean(key(network, "enabled"), false));
    }

    public static final class Snapshot {
        public final String state;
        public final int height;
        public final int blocksLeft;
        public final int peers;
        public final long databaseBytes;
        public final String error;
        public final long updatedAt;
        public final boolean enabled;

        Snapshot(String state, int height, int blocksLeft, int peers,
                 long databaseBytes, String error, long updatedAt, boolean enabled) {
            this.state = state;
            this.height = height;
            this.blocksLeft = blocksLeft;
            this.peers = peers;
            this.databaseBytes = databaseBytes;
            this.error = error;
            this.updatedAt = updatedAt;
            this.enabled = enabled;
        }
    }
}
