/*
 * Copyright 2012 Matt Corallo.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.params.BitcoinNetworkParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.Script.VerifyFlag;
import org.bitcoinj.script.ScriptPattern;
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.FullPrunedBlockStore;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.bitcoinj.base.internal.Preconditions.checkState;

/**
 * <p>A FullPrunedBlockChain works in conjunction with a {@link FullPrunedBlockStore} to verify all the rules of the
 * Bitcoin system, with the downside being a large cost in system resources. Fully verifying means all unspent
 * transaction outputs are stored. Once a transaction output is spent and that spend is buried deep enough, the data
 * related to it is deleted to ensure disk space usage doesn't grow forever. For this reason a pruning node cannot
 * serve the full block chain to other clients, but it nevertheless provides the same security guarantees as Bitcoin
 * Core does.</p>
 */
public class FullPrunedBlockChain extends AbstractBlockChain {
    private static final Logger log = LoggerFactory.getLogger(FullPrunedBlockChain.class);

    /**
     * Keeps a map of block hashes to StoredBlocks.
     */
    protected final FullPrunedBlockStore blockStore;

    // Whether or not to execute scriptPubKeys before accepting a transaction (i.e. check signatures).
    private boolean runScripts = true;

    /**
     * Constructs a block chain connected to the given wallet and store. To obtain a {@link Wallet} you can construct
     * one from scratch, or you can deserialize a saved wallet from disk using
     * {@link Wallet#loadFromFile(File, WalletExtension...)}
     */
    public FullPrunedBlockChain(NetworkParameters params, Wallet wallet, FullPrunedBlockStore blockStore) throws BlockStoreException {
        this(params, Collections.singletonList(wallet), blockStore);
    }

    /**
     * Constructs a block chain connected to the given store.
     */
    public FullPrunedBlockChain(NetworkParameters params, FullPrunedBlockStore blockStore) throws BlockStoreException {
        this(params, Collections.emptyList(), blockStore);
    }

    /**
     * Constructs a block chain connected to the given list of wallets and a store.
     */
    public FullPrunedBlockChain(NetworkParameters params, List<Wallet> listeners,
                                FullPrunedBlockStore blockStore) throws BlockStoreException {
        super(params, listeners, blockStore);
        this.blockStore = blockStore;
        // Ignore upgrading for now
        this.chainHead = blockStore.getVerifiedChainHead();
    }

    @Override
    protected StoredBlock addToBlockStore(StoredBlock storedPrev, Block header, TransactionOutputChanges txOutChanges)
            throws BlockStoreException, VerificationException {
        StoredBlock newBlock = storedPrev.build(header);
        blockStore.put(newBlock, new StoredUndoableBlock(newBlock.getHeader().getHash(), txOutChanges));
        return newBlock;
    }

    @Override
    protected StoredBlock addToBlockStore(StoredBlock storedPrev, Block block)
            throws BlockStoreException, VerificationException {
        StoredBlock newBlock = storedPrev.build(block);
        blockStore.put(newBlock, new StoredUndoableBlock(newBlock.getHeader().getHash(), block.getTransactions()));
        return newBlock;
    }

    /**
     * Roll back the verified UTXO chain to a previously persisted wallet checkpoint.
     * This is used after an unclean process stop when the SQLite chainstate advanced
     * further than the wallet file. It reuses undo records and never clears the store.
     * The caller must first confirm that the target block is an ancestor of the current
     * verified chain head. If required undo data has already been pruned, the operation
     * fails atomically and leaves the current chainstate intact.
     */
    public void rollbackToHeight(int height) throws BlockStoreException {
        rollbackBlockStore(height);
    }

    @Override
    protected void rollbackBlockStore(int height) throws BlockStoreException {
        lock.lock();
        boolean batchStarted = false;
        try {
            StoredBlock current = blockStore.getVerifiedChainHead();
            int currentHeight = current.getHeight();
            if (height < 0 || height > currentHeight)
                throw new BlockStoreException("Invalid FullPruned rollback height: " + height);
            if (height == currentHeight) return;

            blockStore.beginDatabaseBatchWrite();
            batchStarted = true;
            while (current.getHeight() > height) {
                StoredUndoableBlock undo = blockStore.getUndoBlock(current.getHeader().getHash());
                if (undo == null || undo.getTxOutChanges() == null)
                    throw new BlockStoreException("Cannot safely roll back FullPruned block at height " +
                            current.getHeight() + ": undo data was pruned or incomplete");
                TransactionOutputChanges changes = undo.getTxOutChanges();
                // Restore spent outputs first, then remove all outputs created by the block. This ordering
                // also handles an output created and spent again inside the same block.
                for (UTXO output : changes.txOutsSpent)
                    blockStore.addUnspentTransactionOutput(output);
                for (UTXO output : changes.txOutsCreated)
                    blockStore.removeUnspentTransactionOutput(output);
                current = current.getPrev(blockStore);
                if (current == null)
                    throw new BlockStoreException("FullPruned rollback could not reach the requested height");
            }
            blockStore.setChainHead(current);
            // setChainHead commits the UTXO rollback and updates the verified chain head atomically.
            setChainHead(current);
            batchStarted = false;
        } catch (BlockStoreException | RuntimeException e) {
            if (batchStarted) {
                try {
                    blockStore.abortDatabaseBatchWrite();
                } catch (BlockStoreException abortError) {
                    e.addSuppressed(abortError);
                }
            }
            if (e instanceof BlockStoreException) throw (BlockStoreException) e;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected boolean shouldVerifyTransactions() {
        return true;
    }

    /**
     * Whether or not to run scripts whilst accepting blocks (i.e. checking signatures, for most transactions).
     * If you're accepting data from an untrusted node, such as one found via the P2P network, this should be set
     * to true (which is the default). If you're downloading a chain from a node you control, script execution
     * is redundant because you know the connected node won't relay bad data to you. In that case it's safe to set
     * this to false and obtain a significant speedup.
     */
    public void setRunScripts(boolean value) {
        this.runScripts = value;
    }

    // TODO: Remove lots of duplicated code in the two connectTransactions

    // TODO: execute in order of largest transaction (by input count) first
    private volatile ExecutorService scriptVerificationExecutor = newScriptVerificationExecutor();

    private static ExecutorService newScriptVerificationExecutor() {
        return Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                new ContextPropagatingThreadFactory("Script verification"));
    }

    /** Stop CPU workers when the wallet switches networks or synchronization modes. */
    public void shutdownScriptVerificationExecutor() {
        ExecutorService executor = scriptVerificationExecutor;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A job submitted to the executor which verifies signatures.
     */
    private static class Verifier implements Callable<VerificationException> {
        final Transaction tx;
        final List<Script> prevOutScripts;
        final List<Coin> prevOutValues;
        final Set<VerifyFlag> verifyFlags;

        public Verifier(final Transaction tx, final List<Script> prevOutScripts,
                        final List<Coin> prevOutValues, final Set<VerifyFlag> verifyFlags) {
            this.tx = tx;
            this.prevOutScripts = prevOutScripts;
            this.prevOutValues = prevOutValues;
            this.verifyFlags = verifyFlags;
        }

        private static Script findNestedWitnessProgram(TransactionInput input) {
            if (input == null || input.getScriptSig() == null) return null;
            List<org.bitcoinj.script.ScriptChunk> chunks = input.getScriptSig().chunks();
            // Inspect the final push even if the scriptSig is malformed. Otherwise a nested
            // witness program with extra scriptSig items could fall back to legacy evaluation and
            // skip witness verification.
            if (chunks.isEmpty() || chunks.get(chunks.size() - 1).data == null) return null;
            try {
                Script redeemScript = Script.parse(chunks.get(chunks.size() - 1).data);
                return ScriptPattern.isP2WPKH(redeemScript) || ScriptPattern.isP2WSH(redeemScript)
                        ? redeemScript : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        @Nullable
        @Override
        public VerificationException call() throws Exception {
            ListIterator<Script> prevOutIt = prevOutScripts.listIterator();
            ListIterator<Coin> prevOutValueIt = prevOutValues.listIterator();
            for (int index = 0; index < tx.getInputs().size(); index++) {
                TransactionInput input = tx.getInput(index);
                Script previousScript = prevOutIt.next();
                Coin previousValue = prevOutValueIt.next();
                try {
                    if (previousScript == null)
                        throw new VerificationException("Previous output script is not parseable");
                    // The embedded Script engine only implements a P2WPKH fast path; falling back
                    // to legacy stack evaluation for P2WSH/Taproot would accept spends without
                    // checking their witness signatures. Fail closed instead of falsely accepting
                    // a block as fully verified.
                    if (ScriptPattern.isP2WSH(previousScript) || ScriptPattern.isP2TR(previousScript))
                        throw new VerificationException("Unsupported consensus spend type: " + previousScript.getScriptType()
                                + " (complete BIP141/BIP341/BIP342 verification is not implemented)");
                    if (ScriptPattern.isP2WPKH(previousScript)
                            && input.getScriptSig().program().length != 0)
                        throw new VerificationException("Native P2WPKH spend must have an empty scriptSig");
                    if (ScriptPattern.isP2SH(previousScript)) {
                        Script nestedWitnessProgram = findNestedWitnessProgram(input);
                        if (nestedWitnessProgram != null) {
                            if (input.getScriptSig().chunks().size() != 1)
                                throw new VerificationException("Nested SegWit scriptSig must contain only the witness program push");
                            if (ScriptPattern.isP2WSH(nestedWitnessProgram))
                                throw new VerificationException("Unsupported nested P2WSH consensus spend; refusing unchecked script");
                            // First verify the P2SH hash commitment and push-only scriptSig; then
                            // verify the inner native P2WPKH witness with BIP143.
                            input.getScriptSig().correctlySpends(tx, index, previousScript, verifyFlags);
                            nestedWitnessProgram.correctlySpends(tx, index, input.getWitness(),
                                    previousValue, nestedWitnessProgram, verifyFlags);
                            continue;
                        }
                    }
                    input.getScriptSig().correctlySpends(tx, index, input.getWitness(),
                            previousValue, previousScript, verifyFlags);
                } catch (VerificationException e) {
                    String scriptType = "unknown";
                    if (previousScript != null && previousScript.getScriptType() != null)
                        scriptType = previousScript.getScriptType().toString();
                    String txId = tx.getTxId().toString();
                    if (txId.length() > 12) txId = txId.substring(0, 12) + "…";
                    String prevTxId = input.getOutpoint().hash().toString();
                    if (prevTxId.length() > 12) prevTxId = prevTxId.substring(0, 12) + "…";
                    return new VerificationException(
                            "FullPruned script verification failed: tx " + txId
                                    + ", input " + index + ", prevout " + prevTxId
                                    + ":" + input.getOutpoint().index()
                                    + ", " + scriptType + ": " + e.getMessage(), e);
                }
            }
            return null;
        }
    }

    /**
     * Parse a locking script without turning malformed bytes into an empty (anyone-can-spend)
     * script. A malformed scriptPubKey is unspendable under Bitcoin script rules; representing
     * it as an empty script would poison the UTXO set and could make an invalid spend appear valid.
     */
    @Nullable
    private Script getScript(byte[] scriptBytes) {
        if (scriptBytes == null || scriptBytes.length > 10_000) return null;
        try {
            return Script.parse(scriptBytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Match Bitcoin Core's chainstate rule: only outputs whose scriptPubKey starts with
     * OP_RETURN are excluded. A malformed/oversized script may be impossible to execute but its
     * raw output still belongs in chainstate for exact UTXO and BIP30/undo semantics.
     */
    private boolean isUnspendableOutputScript(byte[] scriptBytes) {
        return scriptBytes != null && scriptBytes.length > 0
                && (scriptBytes[0] & 0xff) == ScriptOpCodes.OP_RETURN;
    }

    /**
     * Get the address from the {@link Script} if it exists otherwise return empty string "".
     *
     * @param script The script.
     * @return The address.
     */
    private String getScriptAddress(@Nullable Script script) {
        String address = "";
        try {
            if (script != null) {
                address = script.getToAddress(params.network(), true).toString();
            }
        } catch (Exception e) {
        }
        return address;
    }

    @Override
    protected TransactionOutputChanges connectTransactions(int height, Block block)
            throws VerificationException, BlockStoreException {
        checkState(lock.isHeldByCurrentThread());
        if (block.getTransactions() == null)
            throw new RuntimeException("connectTransactions called with Block that didn't have transactions!");
        if (!params.passesCheckpoint(height, block.getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + height);

        blockStore.beginDatabaseBatchWrite();

        LinkedList<UTXO> txOutsSpent = new LinkedList<>();
        LinkedList<UTXO> txOutsCreated = new LinkedList<>();
        long sigOps = 0;

        if (scriptVerificationExecutor.isShutdown())
            scriptVerificationExecutor = newScriptVerificationExecutor();

        List<Future<VerificationException>> listScriptVerificationResults = new ArrayList<>(block.getTransactions().size());
        try {
            if (!params.isCheckpoint(height)) {
                // BIP30 violator blocks are ones that contain a duplicated transaction. They are all in the
                // checkpoints list and we therefore only check non-checkpoints for duplicated transactions here. See the
                // BIP30 document for more details on this: https://github.com/bitcoin/bips/blob/master/bip-0030.mediawiki
                for (Transaction tx : block.getTransactions()) {
                    final Set<VerifyFlag> verifyFlags = params.getTransactionVerificationFlags(block, tx, getVersionTally(), height);
                    Sha256Hash hash = tx.getTxId();
                    // If we already have unspent outputs for this hash, we saw the tx already. Either the block is
                    // being added twice (bug) or the block is a BIP30 violator.
                    if (blockStore.hasUnspentOutputs(hash, tx.getOutputs().size()))
                        throw new VerificationException("Block failed BIP30 test!");
                    if (verifyFlags.contains(VerifyFlag.P2SH)) // We already check non-BIP16 sigops in Block.verifyTransactions(true)
                        sigOps += tx.getSigOpCount();
                }
            }
            Coin totalFees = Coin.ZERO;
            Coin coinbaseValue = null;
            for (final Transaction tx : block.getTransactions()) {
                boolean isCoinBase = tx.isCoinBase();
                Coin valueIn = Coin.ZERO;
                Coin valueOut = Coin.ZERO;
                final List<Script> prevOutScripts = new LinkedList<>();
                final List<Coin> prevOutValues = new LinkedList<>();
                final Set<VerifyFlag> verifyFlags = params.getTransactionVerificationFlags(block, tx, getVersionTally(), height);
                if (!isCoinBase) {
                    // For each input of the transaction remove the corresponding output from the set of unspent
                    // outputs.
                    for (int index = 0; index < tx.getInputs().size(); index++) {
                        TransactionInput in = tx.getInput(index);
                        UTXO prevOut = blockStore.getTransactionOutput(in.getOutpoint().hash(),
                                in.getOutpoint().index());
                        if (prevOut == null)
                            throw new VerificationException("Attempted to spend a non-existent or already spent output!");
                        if (prevOut.getScript() == null)
                            throw new VerificationException("Cannot validate spend of an output with an unsupported/malformed scriptPubKey");
                        // Coinbases can't be spent until they mature, to avoid re-orgs destroying entire transaction
                        // chains. The assumption is there will ~never be re-orgs deeper than the spendable coinbase
                        // chain depth.
                        if (prevOut.isCoinbase()) {
                            if (height - prevOut.getHeight() < params.getSpendableCoinbaseDepth()) {
                                throw new VerificationException("Tried to spend coinbase at depth " + (height - prevOut.getHeight()));
                            }
                        }
                        // TODO: Check we're not spending the genesis transaction here. Bitcoin Core won't allow it.
                        valueIn = valueIn.add(prevOut.getValue());
                        if (verifyFlags.contains(VerifyFlag.P2SH)) {
                            if (ScriptPattern.isP2SH(prevOut.getScript()))
                                sigOps += Script.getP2SHSigOpCount(in.getScriptBytes());
                            if (sigOps > Block.MAX_BLOCK_SIGOPS)
                                throw new VerificationException("Too many P2SH SigOps in block");
                        }

                        prevOutScripts.add(prevOut.getScript());
                        prevOutValues.add(prevOut.getValue());
                        blockStore.removeUnspentTransactionOutput(prevOut);
                        txOutsSpent.add(prevOut);
                    }
                }
                Sha256Hash hash = tx.getTxId();
                for (TransactionOutput out : tx.getOutputs()) {
                    valueOut = valueOut.add(out.getValue());
                    // For each output, add it to the set of unspent outputs so it can be consumed in future.
                    byte[] scriptBytes = out.getScriptBytes();
                    // OP_RETURN values remain part of valueOut/fee validation, but are excluded
                    // from chainstate. Keep every other script's exact bytes even if parsing fails.
                    if (isUnspendableOutputScript(scriptBytes)) continue;
                    Script script = getScript(scriptBytes);
                    UTXO newOut = UTXO.fromScriptBytes(hash, out.getIndex(), out.getValue(),
                            height, isCoinBase, scriptBytes, getScriptAddress(script));
                    blockStore.addUnspentTransactionOutput(newOut);
                    txOutsCreated.add(newOut);
                }
                // All values were already checked for being non-negative (as it is verified in Transaction.verify())
                // but we check again here just for defence in depth. Transactions with zero output value are OK.
                if (valueOut.signum() < 0 || params.network().exceedsMaxMoney(valueOut))
                    throw new VerificationException("Transaction output value out of range");
                if (isCoinBase) {
                    coinbaseValue = valueOut;
                } else {
                    if (valueIn.compareTo(valueOut) < 0 || params.network().exceedsMaxMoney(valueIn))
                        throw new VerificationException("Transaction input value out of range");
                    totalFees = totalFees.add(valueIn.subtract(valueOut));
                }

                if (!isCoinBase && runScripts) {
                    // Because correctlySpends modifies transactions, this must come after we are done with tx
                    FutureTask<VerificationException> future = new FutureTask<>(new Verifier(tx, prevOutScripts, prevOutValues, verifyFlags));
                    scriptVerificationExecutor.execute(future);
                    listScriptVerificationResults.add(future);
                }
            }
            if (params.network().exceedsMaxMoney(totalFees) || getBlockInflation(height).add(totalFees).compareTo(coinbaseValue) < 0)
                throw new VerificationException("Transaction fees out of range");
            for (Future<VerificationException> future : listScriptVerificationResults) {
                VerificationException e;
                try {
                    e = future.get();
                } catch (InterruptedException thrownE) {
                    throw new RuntimeException(thrownE); // Shouldn't happen
                } catch (ExecutionException thrownE) {
                    log.error("Script.correctlySpends threw a non-normal exception: " + thrownE.getCause());
                    throw new VerificationException("Bug in Script.correctlySpends, likely script malformed in some new and interesting way.", thrownE);
                }
                if (e != null)
                    throw e;
            }
        } catch (BlockStoreException | RuntimeException e) {
            scriptVerificationExecutor.shutdownNow();
            try {
                blockStore.abortDatabaseBatchWrite();
            } catch (BlockStoreException abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        }
        return new TransactionOutputChanges(txOutsCreated, txOutsSpent);
    }

    /**
     * Used during reorgs to connect a block previously on a fork
     */
    @Override
    protected synchronized TransactionOutputChanges connectTransactions(StoredBlock newBlock)
            throws VerificationException, BlockStoreException, PrunedException {
        checkState(lock.isHeldByCurrentThread());
        if (!params.passesCheckpoint(newBlock.getHeight(), newBlock.getHeader().getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + newBlock.getHeight());

        blockStore.beginDatabaseBatchWrite();
        StoredUndoableBlock block;
        try {
            block = blockStore.getUndoBlock(newBlock.getHeader().getHash());
            if (block == null) {
                // We're trying to re-org too deep and the data needed has been deleted.
                throw new PrunedException(newBlock.getHeader().getHash());
            }
        } catch (PrunedException | BlockStoreException | RuntimeException e) {
            try {
                blockStore.abortDatabaseBatchWrite();
            } catch (BlockStoreException abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        }
        TransactionOutputChanges txOutChanges;
        try {
            List<Transaction> transactions = block.getTransactions();
            if (transactions != null) {
                LinkedList<UTXO> txOutsSpent = new LinkedList<>();
                LinkedList<UTXO> txOutsCreated = new LinkedList<>();
                long sigOps = 0;

                if (!params.isCheckpoint(newBlock.getHeight())) {
                    for (Transaction tx : transactions) {
                        Sha256Hash hash = tx.getTxId();
                        if (blockStore.hasUnspentOutputs(hash, tx.getOutputs().size()))
                            throw new VerificationException("Block failed BIP30 test!");
                    }
                }
                Coin totalFees = Coin.ZERO;
                Coin coinbaseValue = null;

                if (scriptVerificationExecutor.isShutdown())
                    scriptVerificationExecutor = newScriptVerificationExecutor();
                List<Future<VerificationException>> listScriptVerificationResults = new ArrayList<>(transactions.size());
                for (final Transaction tx : transactions) {
                    final Set<VerifyFlag> verifyFlags =
                        params.getTransactionVerificationFlags(newBlock.getHeader(), tx, getVersionTally(), newBlock.getHeight());
                    boolean isCoinBase = tx.isCoinBase();
                    Coin valueIn = Coin.ZERO;
                    Coin valueOut = Coin.ZERO;
                    final List<Script> prevOutScripts = new LinkedList<>();
                    final List<Coin> prevOutValues = new LinkedList<>();

                    if (!isCoinBase) {
                        for (int index = 0; index < tx.getInputs().size(); index++) {
                            final TransactionInput in = tx.getInput(index);
                            final UTXO prevOut = blockStore.getTransactionOutput(in.getOutpoint().hash(),
                                    in.getOutpoint().index());
                            if (prevOut == null)
                                throw new VerificationException("Attempted spend of a non-existent or already spent output!");
                            if (prevOut.getScript() == null)
                                throw new VerificationException("Cannot validate spend of an output with an unsupported/malformed scriptPubKey");
                            if (prevOut.isCoinbase() && newBlock.getHeight() - prevOut.getHeight() < params.getSpendableCoinbaseDepth())
                                throw new VerificationException("Tried to spend coinbase at depth " + (newBlock.getHeight() - prevOut.getHeight()));
                            valueIn = valueIn.add(prevOut.getValue());
                            if (verifyFlags.contains(VerifyFlag.P2SH)) {
                                if (ScriptPattern.isP2SH(prevOut.getScript()))
                                    sigOps += Script.getP2SHSigOpCount(in.getScriptBytes());
                                if (sigOps > Block.MAX_BLOCK_SIGOPS)
                                    throw new VerificationException("Too many P2SH SigOps in block");
                            }

                            // TODO: Enforce DER signature format

                            prevOutScripts.add(prevOut.getScript());
                            prevOutValues.add(prevOut.getValue());

                            blockStore.removeUnspentTransactionOutput(prevOut);
                            txOutsSpent.add(prevOut);
                        }
                    }
                    Sha256Hash hash = tx.getTxId();
                    for (TransactionOutput out : tx.getOutputs()) {
                        valueOut = valueOut.add(out.getValue());
                        byte[] scriptBytes = out.getScriptBytes();
                        // Keep reorgs identical to the main-chain path: only OP_RETURN outputs are
                        // excluded; malformed scripts are retained as raw, non-spendable UTXOs.
                        if (isUnspendableOutputScript(scriptBytes)) continue;
                        Script script = getScript(scriptBytes);
                        UTXO newOut = UTXO.fromScriptBytes(hash, out.getIndex(), out.getValue(),
                                newBlock.getHeight(), isCoinBase, scriptBytes, getScriptAddress(script));
                        blockStore.addUnspentTransactionOutput(newOut);
                        txOutsCreated.add(newOut);
                    }
                    // All values were already checked for being non-negative (as it is verified in Transaction.verify())
                    // but we check again here just for defence in depth. Transactions with zero output value are OK.
                    if (valueOut.signum() < 0 || params.network().exceedsMaxMoney(valueOut))
                        throw new VerificationException("Transaction output value out of range");
                    if (isCoinBase) {
                        coinbaseValue = valueOut;
                    } else {
                        if (valueIn.compareTo(valueOut) < 0 || params.network().exceedsMaxMoney(valueIn))
                            throw new VerificationException("Transaction input value out of range");
                        totalFees = totalFees.add(valueIn.subtract(valueOut));
                    }

                    if (!isCoinBase) {
                        // Because correctlySpends modifies transactions, this must come after we are done with tx
                        FutureTask<VerificationException> future = new FutureTask<>(new Verifier(tx, prevOutScripts, prevOutValues, verifyFlags));
                        scriptVerificationExecutor.execute(future);
                        listScriptVerificationResults.add(future);
                    }
                }
                if (params.network().exceedsMaxMoney(totalFees) || getBlockInflation(newBlock.getHeight()).add(totalFees).compareTo(coinbaseValue) < 0)
                    throw new VerificationException("Transaction fees out of range");
                txOutChanges = new TransactionOutputChanges(txOutsCreated, txOutsSpent);
                for (Future<VerificationException> future : listScriptVerificationResults) {
                    VerificationException e;
                    try {
                        e = future.get();
                    } catch (InterruptedException thrownE) {
                        throw new RuntimeException(thrownE); // Shouldn't happen
                    } catch (ExecutionException thrownE) {
                        log.error("Script.correctlySpends threw a non-normal exception: " + thrownE.getCause());
                        throw new VerificationException("Bug in Script.correctlySpends, likely script malformed in some new and interesting way.", thrownE);
                    }
                    if (e != null)
                        throw e;
                }
            } else {
                txOutChanges = block.getTxOutChanges();
                if (!params.isCheckpoint(newBlock.getHeight()))
                    for (UTXO out : txOutChanges.txOutsCreated) {
                        Sha256Hash hash = out.getHash();
                        if (blockStore.getTransactionOutput(hash, out.getIndex()) != null)
                            throw new VerificationException("Block failed BIP30 test!");
                    }
                for (UTXO out : txOutChanges.txOutsCreated)
                    blockStore.addUnspentTransactionOutput(out);
                for (UTXO out : txOutChanges.txOutsSpent)
                    blockStore.removeUnspentTransactionOutput(out);
            }
        } catch (BlockStoreException | RuntimeException e) {
            scriptVerificationExecutor.shutdownNow();
            try {
                blockStore.abortDatabaseBatchWrite();
            } catch (BlockStoreException abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        }
        return txOutChanges;
    }

    /**
     * This is broken for blocks that do not pass BIP30, so all BIP30-failing blocks which are allowed to fail BIP30
     * must be checkpointed.
     */
    @Override
    protected void disconnectTransactions(StoredBlock oldBlock) throws PrunedException, BlockStoreException {
        checkState(lock.isHeldByCurrentThread());
        blockStore.beginDatabaseBatchWrite();
        try {
            StoredUndoableBlock undoBlock = blockStore.getUndoBlock(oldBlock.getHeader().getHash());
            if (undoBlock == null) throw new PrunedException(oldBlock.getHeader().getHash());
            TransactionOutputChanges txOutChanges = undoBlock.getTxOutChanges();
            for (UTXO out : txOutChanges.txOutsSpent)
                blockStore.addUnspentTransactionOutput(out);
            for (UTXO out : txOutChanges.txOutsCreated)
                blockStore.removeUnspentTransactionOutput(out);
        } catch (PrunedException | BlockStoreException | RuntimeException e) {
            try {
                blockStore.abortDatabaseBatchWrite();
            } catch (BlockStoreException abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        }
    }

    @Override
    protected void doSetChainHead(StoredBlock chainHead) throws BlockStoreException {
        checkState(lock.isHeldByCurrentThread());
        blockStore.setVerifiedChainHead(chainHead);
        blockStore.commitDatabaseBatchWrite();
    }

    @Override
    protected void notSettingChainHead() throws BlockStoreException {
        blockStore.abortDatabaseBatchWrite();
    }

    @Override
    protected StoredBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException {
        checkState(lock.isHeldByCurrentThread());
        return blockStore.getOnceUndoableStoredBlock(hash);
    }

    private Coin getBlockInflation(int height) {
        return ((BitcoinNetworkParams) params).getBlockInflation(height);
    }
}
