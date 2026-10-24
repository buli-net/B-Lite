/*
 *
 * Persistent Android SQLite backend for bitcoinj's FullPrunedBlockChain.
 * This class implements only the storage contract; consensus validation is
 * performed by bitcoinj and must be audited before production use.
 */
package wallet.node;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.bitcoinj.base.Network;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.StoredUndoableBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutputChanges;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.FullPrunedBlockStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable FullPrunedBlockStore backed by SQLite. Mainnet and Signet must use
 * separate files; an on-disk network marker prevents accidental cross-use.
 *
 * The store retains every validated block header, the live UTXO set and only a
 * configurable recent window of undo records. It does not retain full historical
 * blocks indefinitely and it is not a txindex/history database.
 */
public final class SqliteFullPrunedBlockStore implements FullPrunedBlockStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_BLOB_BYTES = 128 * 1024 * 1024;
    private static final int MAX_ITEMS = 2_000_000;

    private final SQLiteDatabase db;
    private final NetworkParameters params;
    private final String networkId;
    private final int undoDepth;
    private final ReentrantLock accessLock = new ReentrantLock(true);
    private final ThreadLocal<Boolean> batchOpen = ThreadLocal.withInitial(() -> false);
    private volatile boolean closed;

    public SqliteFullPrunedBlockStore(File databaseFile, NetworkParameters params, int undoDepth)
            throws BlockStoreException {
        if (databaseFile == null || params == null) throw new IllegalArgumentException("databaseFile and params are required");
        if (undoDepth < 288) throw new IllegalArgumentException("undoDepth must be at least 288 blocks");
        this.params = params;
        this.networkId = params.network().id();
        this.undoDepth = undoDepth;
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new BlockStoreException("Cannot create full-pruned database directory: " + parent);
        }
        try {
            this.db = SQLiteDatabase.openOrCreateDatabase(databaseFile.getAbsolutePath(), null);
            db.enableWriteAheadLogging();
            db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("PRAGMA foreign_keys=ON");
            createSchema();
            ensureGenesisAndNetworkMarker();
        } catch (Exception e) {
            throw new BlockStoreException("Unable to open full-pruned SQLite store", e);
        }
    }

    private void createSchema() {
        int existingVersion = db.getVersion();
        if (existingVersion > SCHEMA_VERSION) {
            throw new SQLiteException("Full-pruned database schema is newer than this app supports");
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (k TEXT PRIMARY KEY NOT NULL, v BLOB NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS stored_blocks (hash TEXT PRIMARY KEY NOT NULL, height INTEGER NOT NULL, payload BLOB NOT NULL, was_undoable INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS stored_blocks_height_idx ON stored_blocks(height)");
        db.execSQL("CREATE TABLE IF NOT EXISTS undo_blocks (hash TEXT PRIMARY KEY NOT NULL, height INTEGER NOT NULL, payload BLOB NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS undo_blocks_height_idx ON undo_blocks(height)");
        db.execSQL("CREATE TABLE IF NOT EXISTS utxos (txid TEXT NOT NULL, output_index INTEGER NOT NULL, value_sat INTEGER NOT NULL, height INTEGER NOT NULL, coinbase INTEGER NOT NULL, script BLOB NOT NULL, address TEXT NOT NULL, PRIMARY KEY(txid, output_index))");
        db.execSQL("CREATE INDEX IF NOT EXISTS utxos_txid_idx ON utxos(txid)");
        db.execSQL("CREATE INDEX IF NOT EXISTS utxos_address_idx ON utxos(address)");
        db.execSQL("PRAGMA user_version=" + SCHEMA_VERSION);
    }

    private void ensureGenesisAndNetworkMarker() throws BlockStoreException {
        accessLock.lock();
        try {
            byte[] savedNetwork = getMetaBytes("network_id");
            if (savedNetwork != null) {
                String stored = new String(savedNetwork, StandardCharsets.UTF_8);
                if (!networkId.equals(stored)) {
                    throw new BlockStoreException("Network mismatch: database is " + stored + ", requested " + networkId);
                }
            } else {
                setMetaBytes("network_id", networkId.getBytes(StandardCharsets.UTF_8));
            }

            StoredBlock genesis = params.getGenesisBlock() == null ? null : new StoredBlock(
                    params.getGenesisBlock().cloneAsHeader(), params.getGenesisBlock().getWork(), 0);
            if (genesis == null) throw new BlockStoreException("Network parameters have no genesis block");
            byte[] savedChainHead = getMetaBytes("chain_head");
            byte[] savedVerifiedHead = getMetaBytes("verified_chain_head");
            if (savedChainHead == null && savedVerifiedHead == null) {
                db.beginTransaction();
                try {
                    putInternal(genesis, new StoredUndoableBlock(genesis.getHeader().getHash(), new ArrayList<>()));
                    setChainHeadInternal(genesis);
                    setVerifiedChainHeadInternal(genesis);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } else {
                if (savedChainHead == null || savedVerifiedHead == null) {
                    throw new BlockStoreException("Incomplete full-pruned chain head metadata");
                }
                StoredBlock chain = decodeStoredBlock(savedChainHead);
                StoredBlock verified = decodeStoredBlock(savedVerifiedHead);
                if (chain == null || verified == null || chain.getHeight() < verified.getHeight()) {
                    throw new BlockStoreException("Corrupt full-pruned chain head metadata");
                }
                StoredBlock savedGenesis = getStoredBlockByHash(genesis.getHeader().getHashAsString());
                if (savedGenesis == null || !params.getGenesisBlock().getHash().equals(savedGenesis.getHeader().getHash())) {
                    throw new BlockStoreException("Genesis block does not match selected network");
                }
            }
        } catch (SQLiteException e) {
            throw new BlockStoreException("Unable to initialize full-pruned database", e);
        } finally {
            accessLock.unlock();
        }
    }

    @Override
    public void put(StoredBlock block) throws BlockStoreException {
        withLock(() -> putInternal(block, null));
    }

    @Override
    public void put(StoredBlock block, StoredUndoableBlock undoableBlock) throws BlockStoreException {
        if (undoableBlock == null || !block.getHeader().getHash().equals(undoableBlock.getHash())) {
            throw new BlockStoreException("Undo record does not match stored block");
        }
        withLock(() -> putInternal(block, undoableBlock));
    }

    private void putInternal(StoredBlock block, StoredUndoableBlock undoable) throws BlockStoreException {
        requireOpen();
        ContentValues values = new ContentValues();
        String hash = block.getHeader().getHash().toString();
        values.put("hash", hash);
        values.put("height", block.getHeight());
        values.put("payload", encodeStoredBlock(block));
        int wasUndoable = undoable == null ? readWasUndoable(hash) : 1;
        values.put("was_undoable", wasUndoable);
        if (db.insertWithOnConflict("stored_blocks", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new BlockStoreException("Unable to persist stored block " + hash);
        }
        if (undoable != null) {
            ContentValues undoValues = new ContentValues();
            undoValues.put("hash", hash);
            undoValues.put("height", block.getHeight());
            undoValues.put("payload", encodeUndo(undoable));
            if (db.insertWithOnConflict("undo_blocks", null, undoValues, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                throw new BlockStoreException("Unable to persist undo record " + hash);
            }
        }
    }

    private int readWasUndoable(String hash) {
        try (Cursor cursor = db.rawQuery("SELECT was_undoable FROM stored_blocks WHERE hash=?", new String[]{hash})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    @Override
    public StoredBlock get(Sha256Hash hash) throws BlockStoreException {
        return withLockResult(() -> getStoredBlockByHash(hash.toString()));
    }

    private StoredBlock getStoredBlockByHash(String hash) throws BlockStoreException {
        requireOpen();
        try (Cursor cursor = db.rawQuery("SELECT payload FROM stored_blocks WHERE hash=?", new String[]{hash})) {
            if (!cursor.moveToFirst()) return null;
            StoredBlock block = decodeStoredBlock(cursor.getBlob(0));
            if (block == null || !hash.equals(block.getHeader().getHash().toString())) {
                throw new BlockStoreException("Stored block hash does not match its database key");
            }
            return block;
        }
    }

    @Override
    public StoredBlock getOnceUndoableStoredBlock(Sha256Hash hash) throws BlockStoreException {
        return withLockResult(() -> {
            requireOpen();
            try (Cursor cursor = db.rawQuery("SELECT payload FROM stored_blocks WHERE hash=? AND was_undoable=1", new String[]{hash.toString()})) {
                return cursor.moveToFirst() ? decodeStoredBlock(cursor.getBlob(0)) : null;
            }
        });
    }

    @Override
    public StoredUndoableBlock getUndoBlock(Sha256Hash hash) throws BlockStoreException {
        return withLockResult(() -> {
            requireOpen();
            try (Cursor cursor = db.rawQuery("SELECT payload FROM undo_blocks WHERE hash=?", new String[]{hash.toString()})) {
                return cursor.moveToFirst() ? decodeUndo(hash, cursor.getBlob(0)) : null;
            }
        });
    }

    @Override
    public StoredBlock getChainHead() throws BlockStoreException {
        return withLockResult(() -> decodeStoredBlock(getMetaBytes("chain_head")));
    }

    @Override
    public void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        withLock(() -> setChainHeadInternal(chainHead));
    }

    private void setChainHeadInternal(StoredBlock chainHead) throws BlockStoreException {
        putInternal(chainHead, null);
        setMetaBytes("chain_head", encodeStoredBlock(chainHead));
    }

    @Override
    public StoredBlock getVerifiedChainHead() throws BlockStoreException {
        return withLockResult(() -> decodeStoredBlock(getMetaBytes("verified_chain_head")));
    }

    @Override
    public void setVerifiedChainHead(StoredBlock chainHead) throws BlockStoreException {
        withLock(() -> setVerifiedChainHeadInternal(chainHead));
    }

    private void setVerifiedChainHeadInternal(StoredBlock chainHead) throws BlockStoreException {
        putInternal(chainHead, null);
        setMetaBytes("verified_chain_head", encodeStoredBlock(chainHead));
        StoredBlock current = decodeStoredBlock(getMetaBytes("chain_head"));
        if (current == null || current.getHeight() < chainHead.getHeight()) setChainHeadInternal(chainHead);
        int cutoff = chainHead.getHeight() - undoDepth;
        if (cutoff >= 0) {
            db.delete("undo_blocks", "height<=?", new String[]{Integer.toString(cutoff)});
        }
    }

    @Override
    public UTXO getTransactionOutput(Sha256Hash hash, long index) throws BlockStoreException {
        return withLockResult(() -> {
            requireOpen();
            try (Cursor cursor = db.rawQuery("SELECT value_sat,height,coinbase,script,address FROM utxos WHERE txid=? AND output_index=?",
                    new String[]{hash.toString(), Long.toString(index)})) {
                return cursor.moveToFirst() ? readUtxo(hash, index, cursor) : null;
            }
        });
    }

    private UTXO readUtxo(Sha256Hash hash, long index, Cursor cursor) throws BlockStoreException {
        long value = cursor.getLong(0);
        int height = cursor.getInt(1);
        boolean coinbase = cursor.getInt(2) != 0;
        byte[] scriptBytes = cursor.getBlob(3);
        String address = cursor.getString(4);
        try {
            Script script = Script.parse(scriptBytes);
            return new UTXO(hash, index, org.bitcoinj.base.Coin.valueOf(value), height, coinbase, script, address);
        } catch (Exception e) {
            throw new BlockStoreException("Invalid stored UTXO script", e);
        }
    }

    @Override
    public void addUnspentTransactionOutput(UTXO out) throws BlockStoreException {
        withLock(() -> {
            requireOpen();
            ContentValues values = new ContentValues();
            values.put("txid", out.getHash().toString());
            values.put("output_index", out.getIndex());
            values.put("value_sat", out.getValue().toSat());
            values.put("height", out.getHeight());
            values.put("coinbase", out.isCoinbase() ? 1 : 0);
            values.put("script", out.getScript() == null ? new byte[0] : out.getScript().getProgram());
            values.put("address", out.getAddress() == null ? "" : out.getAddress());
            if (db.insertWithOnConflict("utxos", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                throw new BlockStoreException("Unable to persist UTXO " + out.getHash() + ":" + out.getIndex());
            }
        });
    }

    @Override
    public void removeUnspentTransactionOutput(UTXO out) throws BlockStoreException {
        withLock(() -> {
            requireOpen();
            int removed = db.delete("utxos", "txid=? AND output_index=?",
                    new String[]{out.getHash().toString(), Long.toString(out.getIndex())});
            if (removed != 1) throw new BlockStoreException("Attempted to remove missing UTXO " + out.getHash() + ":" + out.getIndex());
        });
    }

    @Override
    public boolean hasUnspentOutputs(Sha256Hash hash, int numOutputs) throws BlockStoreException {
        return withLockResult(() -> {
            requireOpen();
            try (Cursor cursor = db.rawQuery("SELECT 1 FROM utxos WHERE txid=? AND output_index<? LIMIT 1",
                    new String[]{hash.toString(), Integer.toString(numOutputs)})) {
                return cursor.moveToFirst();
            }
        });
    }

    @Override
    public void beginDatabaseBatchWrite() throws BlockStoreException {
        if (Boolean.TRUE.equals(batchOpen.get())) return;
        accessLock.lock();
        try {
            requireOpen();
            db.beginTransaction();
            batchOpen.set(true);
        } catch (RuntimeException e) {
            accessLock.unlock();
            throw new BlockStoreException("Unable to begin full-pruned database batch", e);
        }
    }

    @Override
    public void commitDatabaseBatchWrite() throws BlockStoreException {
        if (!Boolean.TRUE.equals(batchOpen.get())) return;
        RuntimeException failure = null;
        try {
            db.setTransactionSuccessful();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            db.endTransaction();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        } finally {
            batchOpen.remove();
            accessLock.unlock();
        }
        if (failure != null) throw new BlockStoreException("Unable to commit full-pruned database batch", failure);
    }

    @Override
    public void abortDatabaseBatchWrite() throws BlockStoreException {
        if (!Boolean.TRUE.equals(batchOpen.get())) return;
        RuntimeException failure = null;
        try {
            db.endTransaction();
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            batchOpen.remove();
            accessLock.unlock();
        }
        if (failure != null) throw new BlockStoreException("Unable to abort full-pruned database batch", failure);
    }

    @Override
    public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
        Set<String> addresses = new LinkedHashSet<>();
        for (ECKey key : keys) {
            if (key == null) continue;
            try {
                addresses.add(key.toAddress(ScriptType.P2PKH, params.network()).toString());
                addresses.add(key.toAddress(ScriptType.P2WPKH, params.network()).toString());
                addresses.add(key.toAddress(ScriptType.P2TR, params.network()).toString());
            } catch (Exception ignored) {
                // A key may not support every script family; retain the addresses that did succeed.
            }
        }
        if (addresses.isEmpty()) return new ArrayList<>();
        List<UTXO> outputs = new ArrayList<>();
        List<String> list = new ArrayList<>(addresses);
        try {
            accessLock.lock();
            try {
                requireOpen();
                // Keep each IN clause below SQLite's bind-parameter limit.
                for (int offset = 0; offset < list.size(); offset += 400) {
                    int end = Math.min(list.size(), offset + 400);
                    StringBuilder sql = new StringBuilder("SELECT txid,output_index,value_sat,height,coinbase,script,address FROM utxos WHERE address IN (");
                    String[] args = new String[end - offset];
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) sql.append(',');
                        sql.append('?');
                        args[i] = list.get(offset + i);
                    }
                    sql.append(')');
                    try (Cursor cursor = db.rawQuery(sql.toString(), args)) {
                        while (cursor.moveToNext()) {
                            Sha256Hash hash = Sha256Hash.wrap(cursor.getString(0));
                            long index = cursor.getLong(1);
                            // Cursor's columns differ from the helper's expected zero-based schema;
                            // assemble with the fixed positions from this query.
                            try {
                                Script script = Script.parse(cursor.getBlob(5));
                                outputs.add(new UTXO(hash, index, org.bitcoinj.base.Coin.valueOf(cursor.getLong(2)),
                                        cursor.getInt(3), cursor.getInt(4) != 0, script, cursor.getString(6)));
                            } catch (Exception e) {
                                throw new UTXOProviderException("Invalid stored UTXO script", e);
                            }
                        }
                    }
                }
            } finally {
                accessLock.unlock();
            }
            return outputs;
        } catch (Exception e) {
            if (e instanceof UTXOProviderException) throw (UTXOProviderException) e;
            throw new UTXOProviderException(e);
        }
    }

    @Override
    public int getChainHeadHeight() throws UTXOProviderException {
        try {
            StoredBlock head = getVerifiedChainHead();
            return head == null ? 0 : head.getHeight();
        } catch (BlockStoreException e) {
            throw new UTXOProviderException(e);
        }
    }

    @Override
    public Network network() {
        return params.network();
    }

    @Override
    public void close() throws BlockStoreException {
        accessLock.lock();
        try {
            if (closed) return;
            if (Boolean.TRUE.equals(batchOpen.get())) {
                try { db.endTransaction(); } catch (Exception ignored) { }
                batchOpen.remove();
                accessLock.unlock(); // release the lock retained by beginDatabaseBatchWrite()
            }
            db.close();
            closed = true;
        } catch (SQLiteException e) {
            throw new BlockStoreException("Unable to close full-pruned database", e);
        } finally {
            accessLock.unlock();
        }
    }

    public File getDatabaseFile() {
        return new File(db.getPath());
    }

    private void setMetaBytes(String key, byte[] value) {
        ContentValues cv = new ContentValues();
        cv.put("k", key);
        cv.put("v", value);
        if (db.insertWithOnConflict("metadata", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new SQLiteException("Unable to persist metadata key " + key);
        }
    }

    private byte[] getMetaBytes(String key) {
        try (Cursor cursor = db.rawQuery("SELECT v FROM metadata WHERE k=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
        }
    }

    private byte[] encodeStoredBlock(StoredBlock block) {
        ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE_V2);
        block.serializeCompactV2(buffer);
        return buffer.array();
    }

    private StoredBlock decodeStoredBlock(byte[] bytes) throws BlockStoreException {
        if (bytes == null) return null;
        try {
            if (bytes.length != StoredBlock.COMPACT_SERIALIZED_SIZE_V2) throw new IOException("Invalid stored block length");
            return StoredBlock.deserializeCompactV2(ByteBuffer.wrap(bytes));
        } catch (Exception e) {
            throw new BlockStoreException("Unable to deserialize stored block", e);
        }
    }

    private byte[] encodeUndo(StoredUndoableBlock undoable) throws BlockStoreException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            TransactionOutputChanges changes = undoable.getTxOutChanges();
            if (changes != null) {
                out.writeByte(0);
                writeUtxos(out, changes.txOutsCreated);
                writeUtxos(out, changes.txOutsSpent);
            } else {
                out.writeByte(1);
                List<Transaction> transactions = undoable.getTransactions();
                out.writeInt(transactions == null ? 0 : transactions.size());
                if (transactions != null) {
                    for (Transaction tx : transactions) {
                        byte[] raw = tx.serialize();
                        out.writeInt(raw.length);
                        out.write(raw);
                    }
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new BlockStoreException("Unable to serialize undo record", e);
        }
    }

    private StoredUndoableBlock decodeUndo(Sha256Hash hash, byte[] payload) throws BlockStoreException {
        if (payload == null || payload.length > MAX_BLOB_BYTES) throw new BlockStoreException("Invalid undo payload size");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int mode = in.readUnsignedByte();
            if (mode == 0) {
                List<UTXO> created = readUtxos(in);
                List<UTXO> spent = readUtxos(in);
                if (in.available() != 0) throw new IOException("Trailing bytes in undo record");
                return new StoredUndoableBlock(hash, new TransactionOutputChanges(created, spent));
            }
            if (mode == 1) {
                int count = boundedCount(in.readInt());
                List<Transaction> txs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int length = boundedLength(in.readInt());
                    byte[] raw = new byte[length];
                    in.readFully(raw);
                    txs.add(Transaction.read(ByteBuffer.wrap(raw)));
                }
                if (in.available() != 0) throw new IOException("Trailing bytes in undo record");
                return new StoredUndoableBlock(hash, txs);
            }
            throw new IOException("Unknown undo payload mode");
        } catch (Exception e) {
            throw new BlockStoreException("Unable to deserialize undo record", e);
        }
    }

    private void writeUtxos(DataOutputStream out, List<UTXO> utxos) throws IOException {
        out.writeInt(utxos == null ? 0 : utxos.size());
        if (utxos == null) return;
        for (UTXO utxo : utxos) {
            out.writeUTF(utxo.getHash().toString());
            out.writeLong(utxo.getIndex());
            out.writeLong(utxo.getValue().toSat());
            out.writeInt(utxo.getHeight());
            out.writeBoolean(utxo.isCoinbase());
            byte[] script = utxo.getScript() == null ? new byte[0] : utxo.getScript().getProgram();
            out.writeInt(script.length);
            out.write(script);
            out.writeUTF(utxo.getAddress() == null ? "" : utxo.getAddress());
        }
    }

    private List<UTXO> readUtxos(DataInputStream in) throws IOException, BlockStoreException {
        int count = boundedCount(in.readInt());
        List<UTXO> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Sha256Hash hash = Sha256Hash.wrap(in.readUTF());
            long index = in.readLong();
            long satoshis = in.readLong();
            int height = in.readInt();
            boolean coinbase = in.readBoolean();
            int scriptLength = boundedLength(in.readInt());
            byte[] scriptBytes = new byte[scriptLength];
            in.readFully(scriptBytes);
            String address = in.readUTF();
            try {
                list.add(new UTXO(hash, index, org.bitcoinj.base.Coin.valueOf(satoshis), height, coinbase,
                        Script.parse(scriptBytes), address));
            } catch (Exception e) {
                throw new BlockStoreException("Invalid script in undo UTXO", e);
            }
        }
        return list;
    }

    private int boundedCount(int count) throws IOException {
        if (count < 0 || count > MAX_ITEMS) throw new IOException("Invalid item count in undo payload");
        return count;
    }

    private int boundedLength(int length) throws IOException {
        if (length < 0 || length > MAX_BLOB_BYTES) throw new IOException("Invalid item length in undo payload");
        return length;
    }

    private void requireOpen() throws BlockStoreException {
        if (closed || !db.isOpen()) throw new BlockStoreException("Full-pruned store is closed");
    }

    private interface StoreAction { void run() throws Exception; }
    private interface StoreSupplier<T> { T get() throws Exception; }

    private void withLock(StoreAction action) throws BlockStoreException {
        accessLock.lock();
        try {
            requireOpen();
            action.run();
        } catch (BlockStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new BlockStoreException("Full-pruned SQLite operation failed", e);
        } finally {
            accessLock.unlock();
        }
    }

    private <T> T withLockResult(StoreSupplier<T> supplier) throws BlockStoreException {
        accessLock.lock();
        try {
            requireOpen();
            return supplier.get();
        } catch (BlockStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new BlockStoreException("Full-pruned SQLite query failed", e);
        } finally {
            accessLock.unlock();
        }
    }
}
