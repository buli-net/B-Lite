package wallet.transaction;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small persistent index for RBF metadata that cannot be reconstructed safely
 * from a raw Bitcoin transaction alone (most importantly, the exact change
 * output selected by the wallet when the transaction was created).
 */
public final class RbfMetadata {
    private static final String PREFS = "rbf_metadata";
    private static final String PREFIX = "tx.";
    private static final String CHANGE_INDEX = ".change_index";
    private static final String REPLACED_BY = ".replaced_by";
    private static final String REPLACES = ".replaces";

    private RbfMetadata() {}

    public static void recordCreated(Context context, String txid, int changeIndex) {
        if (context == null || txid == null || changeIndex < 0) return;
        prefs(context).edit()
                .putInt(PREFIX + txid + CHANGE_INDEX, changeIndex)
                .apply();
    }

    public static int getChangeIndex(Context context, String txid) {
        if (context == null || txid == null) return -1;
        return prefs(context).getInt(PREFIX + txid + CHANGE_INDEX, -1);
    }

    public static void recordReplacement(Context context, String oldTxid, String newTxid,
                                         int newChangeIndex) {
        if (context == null || oldTxid == null || newTxid == null) return;
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(PREFIX + oldTxid + REPLACED_BY, newTxid)
                .putString(PREFIX + newTxid + REPLACES, oldTxid);
        if (newChangeIndex >= 0) {
            editor.putInt(PREFIX + newTxid + CHANGE_INDEX, newChangeIndex);
        }
        editor.apply();
    }

    public static String replacedBy(Context context, String txid) {
        if (context == null || txid == null) return null;
        return prefs(context).getString(PREFIX + txid + REPLACED_BY, null);
    }

    public static String replaces(Context context, String txid) {
        if (context == null || txid == null) return null;
        return prefs(context).getString(PREFIX + txid + REPLACES, null);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
