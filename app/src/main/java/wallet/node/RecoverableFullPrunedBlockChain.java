/*
 */
package wallet.node;

import org.bitcoinj.core.FullPrunedBlockChain;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PrunedException;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.FullPrunedBlockStore;

/**
 * FullPrunedBlockChain variant that supports the rollback bitcoinj normally uses
 * when a saved wallet is behind the persistent chain store. It can only roll
 * back while undo data still exists; otherwise it fails closed with a clear
 * storage error rather than fabricating UTXO state.
 */
public final class RecoverableFullPrunedBlockChain extends FullPrunedBlockChain {
    public RecoverableFullPrunedBlockChain(NetworkParameters params, FullPrunedBlockStore store)
            throws BlockStoreException {
        super(params, store);
    }

    @Override
    protected void rollbackBlockStore(int height) throws BlockStoreException {
        lock.lock();
        try {
            int currentHeight = getBestChainHeight();
            if (height < 0 || height > currentHeight) {
                throw new BlockStoreException("Invalid full-pruned rollback height: " + height);
            }
            while (getBestChainHeight() > height) {
                StoredBlock oldHead = getChainHead();
                StoredBlock previous = oldHead.getPrev(blockStore);
                if (previous == null) {
                    throw new BlockStoreException("Unable to locate previous block while rolling back full-pruned store");
                }
                try {
                    disconnectTransactions(oldHead);
                } catch (PrunedException e) {
                    throw new BlockStoreException(
                            "Cannot roll back to height " + height + ": required undo data has already been pruned", e);
                }
                // FullPrunedBlockChain.doSetChainHead writes verified head and commits the current DB batch.
                // Explicitly move the non-verified head first because it may currently be ahead after recovery.
                blockStore.setChainHead(previous);
                setChainHead(previous);
            }
        } finally {
            lock.unlock();
        }
    }
}
