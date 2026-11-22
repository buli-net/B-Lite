package wallet.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.bitcoinj.base.BitcoinNetwork;

import wallet.network.NetworkConfig;

import java.util.ArrayList;
import java.util.List;

/** Persistent local history for one selected network, shared by the sync service and its UI. */
public final class SyncChartHistoryStore extends SQLiteOpenHelper {
    public static final long RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "samples";

    public SyncChartHistoryStore(Context context, BitcoinNetwork network) {
        super(context.getApplicationContext(), databaseName(network), null, DATABASE_VERSION);
    }

    private static String databaseName(BitcoinNetwork network) {
        BitcoinNetwork selected = network == null ? BitcoinNetwork.MAINNET : network;
        return "sync_chart_history_" + NetworkConfig.storageName(selected) + ".db";
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (timestamp INTEGER PRIMARY KEY, best REAL, network REAL, progress REAL, age REAL, rate REAL, connected REAL, pending REAL, ping REAL, behind REAL, rx REAL, tx REAL, refresh_total REAL, refresh_processing REAL)");
        db.execSQL("CREATE INDEX idx_sync_samples_timestamp ON " + TABLE + "(timestamp)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public synchronized void save(Sample sample) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("timestamp", sample.timestampMillis);
        putFinite(values, "best", sample.bestChain); putFinite(values, "network", sample.networkChain);
        putFinite(values, "progress", sample.progressPercent); putFinite(values, "age", sample.blockAgeSeconds);
        putFinite(values, "rate", sample.blocksPerSecond); putFinite(values, "connected", sample.connectedPeers);
        putFinite(values, "pending", sample.pendingPeers); putFinite(values, "ping", sample.averagePeerPingMs);
        putFinite(values, "behind", sample.blocksBehind); putFinite(values, "rx", sample.receivedKbPerSecond);
        putFinite(values, "tx", sample.sentKbPerSecond); putFinite(values, "refresh_total", sample.refreshTotalMs);
        putFinite(values, "refresh_processing", sample.refreshProcessingMs);
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.delete(TABLE, "timestamp < ?", new String[]{String.valueOf(sample.timestampMillis - RETENTION_MILLIS)});
    }

    private void putFinite(ContentValues values, String key, float value) {
        if (Float.isFinite(value)) values.put(key, value); else values.putNull(key);
    }

    private float readFloat(Cursor cursor, int column) {
        return cursor.isNull(column) ? Float.NaN : cursor.getFloat(column);
    }

    public synchronized List<Sample> loadSince(long since) {
        List<Sample> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE, null, "timestamp >= ?", new String[]{String.valueOf(since)}, null, null, "timestamp ASC")) {
            while (cursor.moveToNext()) {
                result.add(new Sample(cursor.getLong(0), readFloat(cursor, 1), readFloat(cursor, 2),
                        readFloat(cursor, 3), readFloat(cursor, 4), readFloat(cursor, 5), readFloat(cursor, 6),
                        readFloat(cursor, 7), readFloat(cursor, 8), readFloat(cursor, 9), readFloat(cursor, 10),
                        readFloat(cursor, 11), readFloat(cursor, 12), readFloat(cursor, 13)));
            }
        }
        return result;
    }

    public static final class Sample {
        public final long timestampMillis;
        public final float bestChain, networkChain, progressPercent, blockAgeSeconds, blocksPerSecond;
        public final float connectedPeers, pendingPeers, averagePeerPingMs, blocksBehind;
        public final float receivedKbPerSecond, sentKbPerSecond, refreshTotalMs, refreshProcessingMs;

        public Sample(long timestampMillis, float bestChain, float networkChain, float progressPercent,
                      float blockAgeSeconds, float blocksPerSecond, float connectedPeers, float pendingPeers,
                      float averagePeerPingMs, float blocksBehind, float receivedKbPerSecond,
                      float sentKbPerSecond, float refreshTotalMs, float refreshProcessingMs) {
            this.timestampMillis = timestampMillis; this.bestChain = bestChain; this.networkChain = networkChain;
            this.progressPercent = progressPercent; this.blockAgeSeconds = blockAgeSeconds;
            this.blocksPerSecond = blocksPerSecond; this.connectedPeers = connectedPeers;
            this.pendingPeers = pendingPeers; this.averagePeerPingMs = averagePeerPingMs;
            this.blocksBehind = blocksBehind; this.receivedKbPerSecond = receivedKbPerSecond;
            this.sentKbPerSecond = sentKbPerSecond; this.refreshTotalMs = refreshTotalMs;
            this.refreshProcessingMs = refreshProcessingMs;
        }
    }
}
