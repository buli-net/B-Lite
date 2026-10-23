package wallet.node;

/** Immutable view of the subset of Bitcoin Core status needed by B-Lite. */
public final class BitcoinCoreStatus {
    public final String chain;
    public final String bestBlockHash;
    public final int blocks;
    public final int headers;
    public final int connections;
    public final int mempoolTransactions;
    public final double verificationProgress;
    public final boolean initialBlockDownload;
    public final boolean pruned;
    public final int pruneHeight;
    public final long blockUndoSizeBytes;
    public final long pruneTargetBytes;

    public BitcoinCoreStatus(
            String chain,
            String bestBlockHash,
            int blocks,
            int headers,
            int connections,
            int mempoolTransactions,
            double verificationProgress,
            boolean initialBlockDownload,
            boolean pruned,
            int pruneHeight,
            long blockUndoSizeBytes,
            long pruneTargetBytes) {
        this.chain = chain;
        this.bestBlockHash = bestBlockHash;
        this.blocks = blocks;
        this.headers = headers;
        this.connections = connections;
        this.mempoolTransactions = mempoolTransactions;
        this.verificationProgress = verificationProgress;
        this.initialBlockDownload = initialBlockDownload;
        this.pruned = pruned;
        this.pruneHeight = pruneHeight;
        this.blockUndoSizeBytes = blockUndoSizeBytes;
        this.pruneTargetBytes = pruneTargetBytes;
    }

    public int verificationPercent() {
        return (int) Math.round(Math.max(0.0d, Math.min(1.0d, verificationProgress)) * 100.0d);
    }
}
