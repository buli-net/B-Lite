package wallet.transaction;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutputChanges;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.UTXO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import wallet.network.NetworkConfig;

/**
 * Compact, wallet-scoped input metadata captured from blocks already validated by FullPrunedBlockChain.
 * This is not a second blockchain index: only prevouts belonging to transactions retained by the wallet
 * are kept, so transaction/forwarding details can show verified input values and source scripts even
 * when the previous transaction itself is not part of the wallet.
 */
public final class FullPrunedInputHistoryStore {
    private static final String DATABASE_NAME = "b_lite_fullpruned_input_history.db";
    private static final String TABLE = "wallet_tx_inputs";
    private static SQLiteDatabase database;

    private FullPrunedInputHistoryStore() { }

    public static final class InputDetails {
        public final long valueSats;
        public final String address;
        public final String scriptType;
        public final byte[] scriptBytes;

        private InputDetails(long valueSats, String address, String scriptType, byte[] scriptBytes) {
            this.valueSats = valueSats;
            this.address = address == null ? "" : address;
            this.scriptType = scriptType == null ? "" : scriptType;
            this.scriptBytes = scriptBytes;
        }
    }

    private static synchronized SQLiteDatabase database(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) return null;
        try {
            if (database == null || !database.isOpen()) {
                database = app.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null);
                database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "network TEXT NOT NULL, txid TEXT NOT NULL, input_index INTEGER NOT NULL, "
                        + "prev_txid TEXT NOT NULL, prev_output_index INTEGER NOT NULL, value_sat INTEGER NOT NULL, "
                        + "address TEXT NOT NULL DEFAULT '', script_type TEXT NOT NULL DEFAULT '', script BLOB, "
                        + "PRIMARY KEY(network, txid, input_index))");
                database.execSQL("CREATE INDEX IF NOT EXISTS wallet_tx_inputs_prevout_idx ON "
                        + TABLE + "(network, prev_txid, prev_output_index)");
            }
            return database;
        } catch (SQLiteException | IllegalStateException error) {
            return null;
        }
    }

    private static String outpointKey(Sha256Hash hash, long index) {
        return hash == null ? "" : hash.toString() + ":" + index;
    }

    /** Called after a full-pruned block has committed and bitcoinj has delivered the wallet-relevant tx. */
    public static void recordTransactionInputs(Context context, BitcoinNetwork network,
                                               Transaction transaction, TransactionOutputChanges changes) {
        if (context == null || network == null || transaction == null || changes == null
                || transaction.isCoinBase() || transaction.getInputs().isEmpty()) return;
        Map<String, UTXO> spentByOutpoint = new HashMap<>();
        for (UTXO spent : changes.txOutsSpent) {
            if (spent != null) spentByOutpoint.put(outpointKey(spent.getHash(), spent.getIndex()), spent);
        }
        List<ContentValues> rows = new ArrayList<>(transaction.getInputs().size());
        for (int i = 0; i < transaction.getInputs().size(); i++) {
            TransactionInput input = transaction.getInput(i);
            TransactionOutPoint outpoint = input.getOutpoint();
            if (outpoint == null || outpoint.hash() == null) return;
            UTXO previous = spentByOutpoint.get(outpointKey(outpoint.hash(), outpoint.index()));
            if (previous == null || previous.getValue() == null) return;
            ContentValues values = new ContentValues();
            values.put("network", NetworkConfig.storageName(network));
            values.put("txid", transaction.getTxId().toString());
            values.put("input_index", i);
            values.put("prev_txid", outpoint.hash().toString());
            values.put("prev_output_index", outpoint.index());
            values.put("value_sat", previous.getValue().value);
            values.put("address", previous.getAddress() == null ? "" : previous.getAddress());
            String scriptType = "";
            byte[] scriptBytes = null;
            if (previous.getScript() != null) {
                Object type = previous.getScript().getScriptType();
                scriptType = type == null ? "" : type.toString();
                scriptBytes = previous.getScript().getProgram();
            }
            values.put("script_type", scriptType);
            if (scriptBytes != null) values.put("script", scriptBytes);
            rows.add(values);
        }
        // Require all inputs to match the verified block's spent-output set; never persist a partial
        // fee/source summary that could look authoritative while omitting one of the inputs.
        if (rows.size() != transaction.getInputs().size()) return;
        SQLiteDatabase db = database(context);
        if (db == null) return;
        synchronized (FullPrunedInputHistoryStore.class) {
            try {
                db.beginTransaction();
                for (ContentValues row : rows) {
                    db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } catch (SQLiteException | IllegalStateException ignored) {
                // Detail indexing is secondary: a metadata-storage failure must never reject a valid block.
            } finally {
                if (db.inTransaction()) {
                    try { db.endTransaction(); } catch (SQLiteException ignored) { }
                }
            }
        }
    }

    public static InputDetails getInput(Context context, BitcoinNetwork network, String txid, int inputIndex) {
        if (context == null || network == null || txid == null || txid.isEmpty() || inputIndex < 0) return null;
        SQLiteDatabase db = database(context);
        if (db == null) return null;
        synchronized (FullPrunedInputHistoryStore.class) {
            try (Cursor cursor = db.query(TABLE,
                    new String[]{"value_sat", "address", "script_type", "script"},
                    "network=? AND txid=? AND input_index=?",
                    new String[]{NetworkConfig.storageName(network), txid, String.valueOf(inputIndex)},
                    null, null, null, "1")) {
                if (!cursor.moveToFirst()) return null;
                return new InputDetails(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.isNull(3) ? null : cursor.getBlob(3));
            } catch (SQLiteException | IllegalStateException ignored) {
                return null;
            }
        }
    }

    /** Clear only one network's cached prevout metadata before its one-time verified full-chain rebuild. */
    public static void clearNetwork(Context context, BitcoinNetwork network) {
        if (context == null || network == null) return;
        SQLiteDatabase db = database(context);
        if (db == null) return;
        synchronized (FullPrunedInputHistoryStore.class) {
            try {
                db.delete(TABLE, "network=?", new String[]{NetworkConfig.storageName(network)});
            } catch (SQLiteException | IllegalStateException ignored) {
                // Missing optional detail metadata must not prevent wallet startup.
            }
        }
    }

}
