/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */
package org.bitcoinj.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.content.ContentValues;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.StoredUndoableBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutputChanges;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.script.Script;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite-backed FullPrunedBlockStore. Block headers, the UTXO set, verified/unverified chain heads,
 * and undo information are committed to disk. All block connection changes are kept in one SQLite
 * transaction so a process death cannot leave a half-applied UTXO update.
 */
public final class SQLiteFullPrunedBlockStore implements FullPrunedBlockStore {
    private static final String META_CHAIN_HEAD = "chain_head";
    private static final String META_VERIFIED_HEAD = "verified_chain_head";
    private static final String META_GENESIS_HASH = "network_genesis_hash";
    // Bump when the canonical UTXO contents/rules change. Older stores included unspendable OP_RETURN outputs.
    private static final String META_UTXO_RULESET = "utxo_ruleset";
    // Bump whenever UTXO or undo serialization semantics change. V5 preserves raw script bytes
    // (including scripts the local interpreter cannot parse) and excludes only OP_RETURN outputs.
    private static final String UTXO_RULESET = "bitcoin-consensus-utxo-v5-raw-script-bytes";
    private static final int UNDO_VERSION = 2;
    private static final int DEFAULT_FULL_STORE_DEPTH = 2880;

    private final NetworkParameters params;
    private final int fullStoreDepth;
    private final File databaseFile;
    private final ReentrantLock databaseLock = new ReentrantLock(true);
    private SQLiteDatabase database;
    private boolean batchWrite;
    private Thread batchThread;
    // Set only when this constructor had to discard incompatible/corrupt chain metadata and restart at genesis.
    private boolean resetDuringOpen;

    public SQLiteFullPrunedBlockStore(NetworkParameters params, File databaseFile) throws BlockStoreException {
        this(params, databaseFile, DEFAULT_FULL_STORE_DEPTH);
    }

    public SQLiteFullPrunedBlockStore(NetworkParameters params, File databaseFile, int fullStoreDepth)
            throws BlockStoreException {
        if (params == null || databaseFile == null) throw new IllegalArgumentException("Network and database file are required");
        this.params = params;
        this.databaseFile = databaseFile;
        this.fullStoreDepth = Math.max(1, fullStoreDepth);
        try {
            File parent = databaseFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory())
                throw new IOException("Could not create block-store directory: " + parent);
            database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
            // Several SQLite PRAGMAs return a row even when they change a setting, so run them
            // through rawQuery rather than execSQL (which is only for statements without results).
            try (Cursor timeout = database.rawQuery("PRAGMA busy_timeout=10000", null)) {
                if (!timeout.moveToFirst() || timeout.getInt(0) < 10000)
                    throw new IOException("SQLite could not set the block-store busy timeout");
            }
            if (!database.enableWriteAheadLogging())
                throw new IOException("SQLite could not enable WAL journaling");
            try (Cursor journalMode = database.rawQuery("PRAGMA journal_mode", null)) {
                if (!journalMode.moveToFirst() || !"wal".equalsIgnoreCase(journalMode.getString(0)))
                    throw new IOException("SQLite WAL journaling is not active");
            }
            database.execSQL("PRAGMA synchronous=FULL");
            try (Cursor synchronous = database.rawQuery("PRAGMA synchronous", null)) {
                if (!synchronous.moveToFirst() || synchronous.getInt(0) < 2)
                    throw new IOException("SQLite could not enable FULL synchronous durability");
            }
            createSchema();
            byte[] expectedGenesis = params.getGenesisBlock().getHashAsString().getBytes(StandardCharsets.UTF_8);
            byte[] storedGenesis = readMeta(META_GENESIS_HASH);
            if (storedGenesis != null && !java.util.Arrays.equals(storedGenesis, expectedGenesis)) {
                throw new IOException("FullPruned database belongs to a different Bitcoin network");
            }
            byte[] storedRuleset = readMeta(META_UTXO_RULESET);
            byte[] expectedRuleset = UTXO_RULESET.getBytes(StandardCharsets.UTF_8);
            if (storedGenesis == null || readMeta(META_VERIFIED_HEAD) == null
                    || readMeta(META_CHAIN_HEAD) == null
                    || storedRuleset == null || !java.util.Arrays.equals(storedRuleset, expectedRuleset)) {
                // The rule-set version also forces a one-time replay when upgrading from older
                // builds with different OP_RETURN/malformed-script handling. Replaying is required
                // so the UTXO set and undo deltas match the current chainstate rules exactly.
                resetToGenesisLocked();
                resetDuringOpen = true;
            }
        } catch (Exception e) {
            closeQuietly();
            throw new BlockStoreException("Could not open persistent FullPruned block store: " + databaseFile, e);
        }
    }

    private void createSchema() {
        database.execSQL("CREATE TABLE IF NOT EXISTS blocks (hash TEXT PRIMARY KEY NOT NULL, height INTEGER NOT NULL, compact BLOB NOT NULL)");
        database.execSQL("CREATE INDEX IF NOT EXISTS blocks_height_idx ON blocks(height)");
        database.execSQL("CREATE TABLE IF NOT EXISTS undo_blocks (hash TEXT PRIMARY KEY NOT NULL, height INTEGER NOT NULL, payload BLOB NOT NULL)");
        database.execSQL("CREATE INDEX IF NOT EXISTS undo_height_idx ON undo_blocks(height)");
        database.execSQL("CREATE TABLE IF NOT EXISTS utxos (tx_hash TEXT NOT NULL, output_index INTEGER NOT NULL, value_sat INTEGER NOT NULL, height INTEGER NOT NULL, coinbase INTEGER NOT NULL, script BLOB, address TEXT NOT NULL DEFAULT '', PRIMARY KEY(tx_hash, output_index))");
        database.execSQL("CREATE INDEX IF NOT EXISTS utxos_tx_hash_idx ON utxos(tx_hash)");
        database.execSQL("CREATE INDEX IF NOT EXISTS utxos_address_idx ON utxos(address)");
        database.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL)");
    }

    /** Reset chain metadata and UTXOs after a wallet restore. */
    public void clear() throws BlockStoreException {
        withLock(() -> resetToGenesisLocked());
    }

    private void resetToGenesisLocked() throws BlockStoreException {
        boolean ownTransaction = !database.inTransaction();
        if (ownTransaction) database.beginTransaction();
        try {
            database.delete("blocks", null, null);
            database.delete("undo_blocks", null, null);
            database.delete("utxos", null, null);
            database.delete("metadata", null, null);
            Block genesis = params.getGenesisBlock();
            StoredBlock storedGenesis = new StoredBlock(genesis.cloneAsHeader(), genesis.getWork(), 0);
            putLocked(storedGenesis);
            putUndoLocked(storedGenesis, new StoredUndoableBlock(genesis.getHash(), Collections.emptyList()));
            writeMeta(META_CHAIN_HEAD, serializeStoredBlock(storedGenesis));
            writeMeta(META_VERIFIED_HEAD, serializeStoredBlock(storedGenesis));
            writeMeta(META_GENESIS_HASH, params.getGenesisBlock().getHashAsString().getBytes(StandardCharsets.UTF_8));
            writeMeta(META_UTXO_RULESET, UTXO_RULESET.getBytes(StandardCharsets.UTF_8));
            if (ownTransaction) {
                database.setTransactionSuccessful();
                database.endTransaction();
            }
        } catch (Exception e) {
            if (ownTransaction && database.inTransaction()) database.endTransaction();
            throw wrap("Could not initialize FullPruned store at genesis", e);
        }
    }

    private interface StoreAction { void run() throws Exception; }

    private void withLock(StoreAction action) throws BlockStoreException {
        databaseLock.lock();
        try {
            ensureOpen();
            action.run();
        } catch (BlockStoreException e) {
            throw e;
        } catch (Exception e) {
            throw wrap("SQLite FullPruned block-store operation failed", e);
        } finally {
            databaseLock.unlock();
        }
    }

    private void ensureOpen() throws BlockStoreException {
        if (database == null || !database.isOpen()) throw new BlockStoreException("FullPruned block store is closed");
    }

    private BlockStoreException wrap(String message, Exception e) {
        return e instanceof BlockStoreException ? (BlockStoreException) e : new BlockStoreException(message, e);
    }

    private String hashKey(Sha256Hash hash) { return hash.toString(); }

    private byte[] serializeStoredBlock(StoredBlock storedBlock) {
        ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE_V2);
        storedBlock.serializeCompactV2(buffer);
        return buffer.array();
    }

    private StoredBlock deserializeStoredBlock(byte[] bytes) throws ProtocolException {
        return StoredBlock.deserializeCompactV2(ByteBuffer.wrap(bytes));
    }

    private void putLocked(StoredBlock block) {
        ContentValues values = new ContentValues();
        values.put("hash", hashKey(block.getHeader().getHash()));
        values.put("height", block.getHeight());
        values.put("compact", serializeStoredBlock(block));
        long rowId = database.insertWithOnConflict("blocks", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (rowId == -1) throw new SQLiteException("Could not persist block header " + block.getHeader().getHash());
    }

    private void putUndoLocked(StoredBlock block, StoredUndoableBlock undoableBlock) throws IOException {
        ContentValues values = new ContentValues();
        values.put("hash", hashKey(block.getHeader().getHash()));
        values.put("height", block.getHeight());
        values.put("payload", serializeUndo(undoableBlock));
        long rowId = database.insertWithOnConflict("undo_blocks", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (rowId == -1) throw new SQLiteException("Could not persist undo data for block " + block.getHeader().getHash());
    }

    @Override
    public void put(StoredBlock block) throws BlockStoreException {
        withLock(() -> putLocked(block));
    }

    @Override
    public void put(StoredBlock block, StoredUndoableBlock undoableBlock) throws BlockStoreException {
        withLock(() -> {
            // Side-chain blocks can be stored outside the main connect-transactions batch. Keep
            // their header and undo payload atomic too; when called inside a block batch, reuse it.
            boolean ownTransaction = !database.inTransaction();
            if (ownTransaction) database.beginTransaction();
            try {
                putLocked(block);
                putUndoLocked(block, undoableBlock);
                if (ownTransaction) {
                    database.setTransactionSuccessful();
                    database.endTransaction();
                }
            } catch (Exception e) {
                if (ownTransaction && database.inTransaction()) {
                    try { database.endTransaction(); } catch (Exception rollbackError) { e.addSuppressed(rollbackError); }
                }
                throw e;
            }
        });
    }

    @Override
    public StoredBlock get(Sha256Hash hash) throws BlockStoreException {
        final StoredBlock[] result = new StoredBlock[1];
        withLock(() -> {
            try (Cursor cursor = database.query("blocks", new String[]{"compact"}, "hash=?",
                    new String[]{hashKey(hash)}, null, null, null, "1")) {
                if (cursor.moveToFirst()) result[0] = deserializeStoredBlock(cursor.getBlob(0));
            }
        });
        return result[0];
    }

    @Override
    public StoredBlock getOnceUndoableStoredBlock(Sha256Hash hash) throws BlockStoreException {
        final StoredBlock[] result = new StoredBlock[1];
        withLock(() -> {
            try (Cursor cursor = database.rawQuery(
                    "SELECT b.compact FROM blocks b INNER JOIN undo_blocks u ON b.hash=u.hash WHERE b.hash=? LIMIT 1",
                    new String[]{hashKey(hash)})) {
                if (cursor.moveToFirst()) result[0] = deserializeStoredBlock(cursor.getBlob(0));
            }
        });
        return result[0];
    }

    @Override
    public StoredUndoableBlock getUndoBlock(Sha256Hash hash) throws BlockStoreException {
        final StoredUndoableBlock[] result = new StoredUndoableBlock[1];
        withLock(() -> {
            try (Cursor cursor = database.query("undo_blocks", new String[]{"payload"}, "hash=?",
                    new String[]{hashKey(hash)}, null, null, null, "1")) {
                if (cursor.moveToFirst()) result[0] = deserializeUndo(hash, cursor.getBlob(0));
            }
        });
        return result[0];
    }

    @Override
    public StoredBlock getChainHead() throws BlockStoreException {
        return readStoredBlockMeta(META_CHAIN_HEAD);
    }

    @Override
    public void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        withLock(() -> writeMeta(META_CHAIN_HEAD, serializeStoredBlock(chainHead)));
    }

    @Override
    public StoredBlock getVerifiedChainHead() throws BlockStoreException {
        return readStoredBlockMeta(META_VERIFIED_HEAD);
    }

    private StoredBlock readStoredBlockMeta(String key) throws BlockStoreException {
        final StoredBlock[] result = new StoredBlock[1];
        withLock(() -> {
            byte[] data = readMeta(key);
            if (data == null) throw new BlockStoreException("Missing FullPruned chain metadata: " + key);
            result[0] = deserializeStoredBlock(data);
        });
        return result[0];
    }

    @Override
    public void setVerifiedChainHead(StoredBlock chainHead) throws BlockStoreException {
        withLock(() -> {
            writeMeta(META_VERIFIED_HEAD, serializeStoredBlock(chainHead));
            StoredBlock ordinaryHead = readStoredBlockMetaLocked(META_CHAIN_HEAD);
            if (ordinaryHead.getHeight() < chainHead.getHeight()) {
                writeMeta(META_CHAIN_HEAD, serializeStoredBlock(chainHead));
            }
            int pruneThroughHeight = chainHead.getHeight() - fullStoreDepth;
            if (pruneThroughHeight >= 0) {
                database.delete("undo_blocks", "height<=?", new String[]{Integer.toString(pruneThroughHeight)});
            }
        });
    }

    private StoredBlock readStoredBlockMetaLocked(String key) throws Exception {
        byte[] data = readMeta(key);
        if (data == null) throw new BlockStoreException("Missing FullPruned chain metadata: " + key);
        return deserializeStoredBlock(data);
    }

    private byte[] readMeta(String key) {
        try (Cursor cursor = database.query("metadata", new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
        }
    }

    private void writeMeta(String key, byte[] value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        long rowId = database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (rowId == -1) throw new SQLiteException("Could not persist FullPruned metadata: " + key);
    }

    @Override
    public UTXO getTransactionOutput(Sha256Hash hash, long index) throws BlockStoreException {
        final UTXO[] result = new UTXO[1];
        withLock(() -> {
            try (Cursor cursor = database.query("utxos",
                    new String[]{"value_sat", "height", "coinbase", "script", "address"},
                    "tx_hash=? AND output_index=?", new String[]{hashKey(hash), Long.toString(index)},
                    null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    byte[] scriptBytes = cursor.isNull(3) ? null : cursor.getBlob(3);
                    result[0] = UTXO.fromScriptBytes(hash, index, Coin.valueOf(cursor.getLong(0)), cursor.getInt(1),
                            cursor.getInt(2) != 0, scriptBytes, cursor.getString(4));
                }
            }
        });
        return result[0];
    }

    @Override
    public void addUnspentTransactionOutput(UTXO out) throws BlockStoreException {
        withLock(() -> {
            ContentValues values = new ContentValues();
            values.put("tx_hash", hashKey(out.getHash()));
            values.put("output_index", out.getIndex());
            values.put("value_sat", out.getValue().value);
            values.put("height", out.getHeight());
            values.put("coinbase", out.isCoinbase() ? 1 : 0);
            byte[] scriptBytes = out.getScriptBytes();
            if (scriptBytes == null) values.putNull("script");
            else values.put("script", scriptBytes);
            values.put("address", out.getAddress() == null ? "" : out.getAddress());
            long inserted = database.insertWithOnConflict("utxos", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            if (inserted == -1) throw new BlockStoreException("Could not persist UTXO " + out);
        });
    }

    @Override
    public void removeUnspentTransactionOutput(UTXO out) throws BlockStoreException {
        withLock(() -> {
            int removed = database.delete("utxos", "tx_hash=? AND output_index=?",
                    new String[]{hashKey(out.getHash()), Long.toString(out.getIndex())});
            if (removed == 0) throw new BlockStoreException("Attempted to remove missing UTXO " + out);
        });
    }

    @Override
    public boolean hasUnspentOutputs(Sha256Hash hash, int numOutputs) throws BlockStoreException {
        final boolean[] found = new boolean[1];
        withLock(() -> {
            try (Cursor cursor = database.rawQuery("SELECT 1 FROM utxos WHERE tx_hash=? AND output_index < ? LIMIT 1",
                    new String[]{hashKey(hash), Integer.toString(Math.max(0, numOutputs))})) {
                found[0] = cursor.moveToFirst();
            }
        });
        return found[0];
    }

    @Override
    public void beginDatabaseBatchWrite() throws BlockStoreException {
        databaseLock.lock();
        if (batchWrite && batchThread == Thread.currentThread()) {
            databaseLock.unlock();
            return;
        }
        try {
            ensureOpen();
            database.beginTransaction();
            batchWrite = true;
            batchThread = Thread.currentThread();
        } catch (Exception e) {
            databaseLock.unlock();
            throw wrap("Could not begin atomic FullPruned block write", e);
        }
    }

    @Override
    public void commitDatabaseBatchWrite() throws BlockStoreException {
        databaseLock.lock();
        if (!batchWrite || batchThread != Thread.currentThread()) {
            databaseLock.unlock();
            throw new BlockStoreException("No FullPruned database batch owned by current thread");
        }
        try {
            database.setTransactionSuccessful();
            database.endTransaction();
            batchWrite = false;
            batchThread = null;
        } catch (Exception e) {
            if (database.inTransaction()) {
                try { database.endTransaction(); } catch (Exception rollbackError) { e.addSuppressed(rollbackError); }
            }
            batchWrite = false;
            batchThread = null;
            throw wrap("Could not commit atomic FullPruned block write", e);
        } finally {
            databaseLock.unlock();
            databaseLock.unlock(); // release the lock held from beginDatabaseBatchWrite()
        }
    }

    @Override
    public void abortDatabaseBatchWrite() throws BlockStoreException {
        databaseLock.lock();
        // AbstractBlockChain may invoke notSettingChainHead() after connectTransactions()
        // has already aborted its failed batch. Match bitcoinj's idempotent store contract.
        // A different thread cannot observe an active batch here because the batch owner
        // retains the same reentrant lock until commit/abort.
        if (!batchWrite || batchThread != Thread.currentThread()) {
            databaseLock.unlock();
            return;
        }
        try {
            database.endTransaction();
            batchWrite = false;
            batchThread = null;
        } catch (Exception e) {
            if (database.inTransaction()) {
                try { database.endTransaction(); } catch (Exception retryError) { e.addSuppressed(retryError); }
            }
            batchWrite = false;
            batchThread = null;
            throw wrap("Could not roll back atomic FullPruned block write", e);
        } finally {
            databaseLock.unlock();
            databaseLock.unlock();
        }
    }

    /** True when opening this instance initialized/reset persisted chain data to genesis. */
    public boolean wasResetDuringOpen() {
        return resetDuringOpen;
    }

    @Override
    public Network network() { return params.network(); }

    @Override
    public int getChainHeadHeight() throws UTXOProviderException {
        try {
            StoredBlock head = getVerifiedChainHead();
            return head.getHeight();
        } catch (BlockStoreException e) {
            throw new UTXOProviderException(e);
        }
    }

    @Override
    public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
        if (keys == null || keys.isEmpty()) return Collections.emptyList();
        Set<String> addresses = new HashSet<>();
        for (ECKey key : keys) {
            for (ScriptType type : ScriptType.values()) {
                try {
                    Address address = key.toAddress(type, params.network());
                    addresses.add(address.toString());
                } catch (Exception ignored) {
                    // Some keys/script combinations are not addressable on every network.
                }
            }
        }
        return queryOpenTransactionOutputsByAddress(addresses);
    }

    @Override
    public List<UTXO> getOpenTransactionOutputsForScripts(List<Script> scripts) throws UTXOProviderException {
        if (scripts == null || scripts.isEmpty()) return Collections.emptyList();
        Set<String> addresses = new HashSet<>();
        for (Script script : scripts) {
            if (script == null) continue;
            try {
                addresses.add(script.getToAddress(params.network(), true).toString());
            } catch (Exception ignored) {
                // Non-address scripts (for example arbitrary OP_RETURN watches) cannot match this address index.
            }
        }
        List<UTXO> addressMatches = queryOpenTransactionOutputsByAddress(addresses);
        Set<String> wantedScripts = new HashSet<>();
        for (Script script : scripts) {
            if (script != null) wantedScripts.add(java.util.Arrays.toString(script.getProgram()));
        }
        List<UTXO> exactMatches = new ArrayList<>();
        for (UTXO output : addressMatches) {
            if (output.getScript() != null
                    && wantedScripts.contains(java.util.Arrays.toString(output.getScript().getProgram()))) {
                exactMatches.add(output);
            }
        }
        return exactMatches;
    }

    private List<UTXO> queryOpenTransactionOutputsByAddress(Set<String> addresses)
            throws UTXOProviderException {
        if (addresses == null || addresses.isEmpty()) return Collections.emptyList();
        final List<UTXO> result = new ArrayList<>();
        final List<String> addressList = new ArrayList<>(addresses);
        try {
            withLock(() -> {
                // Query by the indexed address column instead of scanning potentially millions of UTXOs.
                // Keep each IN clause below SQLite's conservative bind-parameter limit.
                final int chunkSize = 400;
                for (int offset = 0; offset < addressList.size(); offset += chunkSize) {
                    int end = Math.min(addressList.size(), offset + chunkSize);
                    StringBuilder selection = new StringBuilder("address IN (");
                    String[] selectionArgs = new String[end - offset];
                    for (int i = offset; i < end; i++) {
                        if (i > offset) selection.append(',');
                        selection.append('?');
                        selectionArgs[i - offset] = addressList.get(i);
                    }
                    selection.append(')');
                    try (Cursor cursor = database.query("utxos",
                            new String[]{"tx_hash", "output_index", "value_sat", "height", "coinbase", "script", "address"},
                            selection.toString(), selectionArgs, null, null, null)) {
                        while (cursor.moveToNext()) {
                            byte[] scriptBytes = cursor.isNull(5) ? null : cursor.getBlob(5);
                            result.add(UTXO.fromScriptBytes(Sha256Hash.wrap(cursor.getString(0)), cursor.getLong(1),
                                    Coin.valueOf(cursor.getLong(2)), cursor.getInt(3), cursor.getInt(4) != 0,
                                    scriptBytes, cursor.getString(6)));
                        }
                    }
                }
            });
        } catch (BlockStoreException e) {
            throw new UTXOProviderException(e);
        }
        return result;
    }

    @Override
    public void close() throws BlockStoreException {
        databaseLock.lock();
        boolean releaseBatchLock = batchWrite && batchThread == Thread.currentThread();
        try {
            if (database != null) {
                if (releaseBatchLock) {
                    // close() must not strand the lock retained by beginDatabaseBatchWrite().
                    database.endTransaction();
                    batchWrite = false;
                    batchThread = null;
                }
                database.close();
                database = null;
            }
        } catch (SQLiteException e) {
            throw new BlockStoreException("Could not close persistent FullPruned block store", e);
        } finally {
            databaseLock.unlock();
            if (releaseBatchLock) databaseLock.unlock();
        }
    }

    private void closeQuietly() {
        try { if (database != null) database.close(); } catch (Exception ignored) { }
        database = null;
    }

    private byte[] serializeUndo(StoredUndoableBlock undo) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(UNDO_VERSION);
            TransactionOutputChanges changes = undo.getTxOutChanges();
            if (changes != null) {
                out.writeByte(0);
                writeUtxos(out, changes.txOutsCreated);
                writeUtxos(out, changes.txOutsSpent);
            } else {
                out.writeByte(1);
                List<Transaction> transactions = undo.getTransactions();
                out.writeInt(transactions == null ? 0 : transactions.size());
                if (transactions != null) {
                    for (Transaction tx : transactions) {
                        byte[] serialized = tx.serialize();
                        out.writeInt(serialized.length);
                        out.write(serialized);
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    private StoredUndoableBlock deserializeUndo(Sha256Hash hash, byte[] payload) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readInt();
            if (version != UNDO_VERSION) throw new IOException("Unsupported undo payload version " + version);
            int kind = in.readUnsignedByte();
            if (kind == 0) {
                List<UTXO> created = readUtxos(in);
                List<UTXO> spent = readUtxos(in);
                return new StoredUndoableBlock(hash, new TransactionOutputChanges(created, spent));
            }
            if (kind == 1) {
                int count = checkedCount(in.readInt(), "transaction");
                List<Transaction> transactions = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int length = checkedCount(in.readInt(), "serialized transaction byte");
                    byte[] txBytes = new byte[length];
                    in.readFully(txBytes);
                    transactions.add(Transaction.read(ByteBuffer.wrap(txBytes)));
                }
                return new StoredUndoableBlock(hash, transactions);
            }
            throw new IOException("Unknown undo payload kind " + kind);
        }
    }

    private void writeUtxos(DataOutputStream out, List<UTXO> utxos) throws IOException {
        out.writeInt(utxos.size());
        for (UTXO utxo : utxos) {
            out.writeUTF(hashKey(utxo.getHash()));
            out.writeLong(utxo.getIndex());
            out.writeLong(utxo.getValue().value);
            out.writeInt(utxo.getHeight());
            out.writeBoolean(utxo.isCoinbase());
            byte[] script = utxo.getScriptBytes();
            out.writeInt(script == null ? -1 : script.length);
            if (script != null) out.write(script);
            out.writeUTF(utxo.getAddress() == null ? "" : utxo.getAddress());
        }
    }

    private List<UTXO> readUtxos(DataInputStream in) throws Exception {
        int count = checkedCount(in.readInt(), "UTXO");
        List<UTXO> utxos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Sha256Hash hash = Sha256Hash.wrap(in.readUTF());
            long index = in.readLong();
            Coin value = Coin.valueOf(in.readLong());
            int height = in.readInt();
            boolean coinbase = in.readBoolean();
            int scriptLength = in.readInt();
            byte[] scriptBytes = null;
            if (scriptLength >= 0) {
                if (scriptLength > 10_000_000) throw new IOException("Unreasonably large UTXO script");
                scriptBytes = new byte[scriptLength];
                in.readFully(scriptBytes);
            }
            String address = in.readUTF();
            utxos.add(UTXO.fromScriptBytes(hash, index, value, height, coinbase, scriptBytes, address));
        }
        return utxos;
    }

    private int checkedCount(int count, String item) throws IOException {
        if (count < 0 || count > 20_000_000) throw new IOException("Invalid " + item + " count: " + count);
        return count;
    }
}
