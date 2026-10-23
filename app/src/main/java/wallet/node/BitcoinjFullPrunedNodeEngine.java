/* Experimental full-pruned node integration. */
package wallet.node;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.listeners.BlockchainDownloadEventListener;
import org.bitcoinj.net.discovery.DnsDiscovery;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import wallet.network.NetworkConfig;

/**
 * Experimental full-block bitcoinj sync engine. Unlike WalletAppKit's SPV chain,
 * this uses FullPrunedBlockChain and a durable UTXO/undo store. It runs separately
 * from the wallet's existing SPV engine and never owns wallet keys or signs spends.
 */
public final class BitcoinjFullPrunedNodeEngine implements AutoCloseable {
    public interface Listener {
        void onProgress(int verifiedHeight, int blocksLeft, int peers);
        default void onSyncIssue(String message, boolean terminal) { }
    }

    private static final int UNDO_DEPTH = 2016;
    private final android.content.Context appContext;
    private final BitcoinNetwork network;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService healthExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "B-Lite-full-pruned-health");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScheduledFuture<?> healthTask;
    private volatile int lastObservedHeight = -1;
    private volatile long lastHeightProgressAt;
    private volatile int stallRecoveriesAtHeight;
    private volatile String lastRejectedBlockHash = "";
    private volatile int repeatedRejectionCount;
    private volatile boolean terminalStallReported;
    private volatile String lastSyncIssue = "";
    private volatile String lastRejectedFailure = "";

    private volatile SqliteFullPrunedBlockStore store;
    private volatile RecoverableFullPrunedBlockChain chain;
    private volatile PeerGroup peerGroup;
    private volatile int blocksLeft;

    public BitcoinjFullPrunedNodeEngine(android.content.Context context, BitcoinNetwork network, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.network = network;
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        NetworkParameters params = NetworkConfig.parameters(network);
        Context bitcoinjContext = new Context(params);
        Context.propagate(bitcoinjContext);

        File dataDir = dataDirectory();
        if (!dataDir.exists() && !dataDir.mkdirs() && !dataDir.isDirectory()) {
            throw new IllegalStateException("Cannot create node data directory");
        }
        SqliteFullPrunedBlockStore newStore = new SqliteFullPrunedBlockStore(
                new File(dataDir, "full-pruned.sqlite"), params, UNDO_DEPTH);
        PeerGroup newPeerGroup = null;
        try {
            RecoverableFullPrunedBlockChain newChain = new RecoverableFullPrunedBlockChain(params, newStore);
            newChain.setRejectionListener((height, blockHash, reason) -> onBlockRejected(height, blockHash, reason));
            newPeerGroup = new PeerGroup(network, newChain);
            newPeerGroup.setMaxConnections(4);
            // Do not call PeerGroup.setFastCatchupTime here: bitcoinj rejects that API for a chain
            // whose shouldVerifyTransactions() is true. The PeerGroup default is the genesis time,
            // and full-body download is retained by Peer for a fully verifying chain.
            newPeerGroup.setConnectTimeout(Duration.ofSeconds(20));
            newPeerGroup.setPeerDiscoveryTimeout(Duration.ofSeconds(10));
            newPeerGroup.setUserAgent("bitcoinj", "0.17.1", "full-pruned");
            newPeerGroup.addPeerDiscovery(new DnsDiscovery(network));
            this.store = newStore;
            this.chain = newChain;
            this.peerGroup = newPeerGroup;
            running.set(true);
            // Register the listener before starting peer discovery. Otherwise a very fast peer
            // handshake could finish before PeerGroup has a download listener and no download
            // peer would be selected until a later reconnection.
            newPeerGroup.startBlockChainDownload(new BlockchainDownloadEventListener() {
                @Override
                public void onChainDownloadStarted(Peer peer, int blocksRemaining) {
                    blocksLeft = Math.max(0, blocksRemaining);
                    reportProgress();
                }

                @Override
                public void onBlocksDownloaded(Peer peer, org.bitcoinj.core.Block block,
                                               org.bitcoinj.core.FilteredBlock filteredBlock,
                                               int blocksRemaining) {
                    blocksLeft = Math.max(0, blocksRemaining);
                    reportProgress();
                }
            });
            newPeerGroup.start();
            lastObservedHeight = newChain.getBestChainHeight();
            lastHeightProgressAt = System.currentTimeMillis();
            terminalStallReported = false;
            healthTask = healthExecutor.scheduleWithFixedDelay(this::checkForStalledSync, 20, 20, TimeUnit.SECONDS);
            reportProgress();
        } catch (Exception e) {
            running.set(false);
            this.peerGroup = null;
            this.chain = null;
            this.store = null;
            if (healthTask != null) healthTask.cancel(false);
            healthExecutor.shutdownNow();
            if (newPeerGroup != null) {
                try { newPeerGroup.stop(); } catch (Exception ignored) { }
            }
            try { newStore.close(); } catch (Exception ignored) { }
            throw e;
        }
    }


    private void onBlockRejected(int height, String blockHash, String reason) {
        if (!running.get() || terminalStallReported) return;
        healthExecutor.execute(() -> {
            if (!running.get() || terminalStallReported) return;
            if (blockHash != null && blockHash.equals(lastRejectedBlockHash)) repeatedRejectionCount++;
            else {
                lastRejectedBlockHash = blockHash == null ? "unknown" : blockHash;
                repeatedRejectionCount = 1;
            }
            lastRejectedFailure = appContext.getString(wallet.main.R.string.full_pruned_node_issue_block_rejected,
                    height + 1, lastRejectedBlockHash, reason);
            lastSyncIssue = lastRejectedFailure;
            reportIssue(lastRejectedFailure, false);
            if (repeatedRejectionCount >= 3) {
                terminalStallReported = true;
                reportIssue(appContext.getString(wallet.main.R.string.full_pruned_node_issue_repeated_rejection,
                        height, lastSyncIssue), true);
                return;
            }
            rotateDownloadPeer(appContext.getString(wallet.main.R.string.full_pruned_node_issue_rotating_rejected,
                    repeatedRejectionCount));
        });
    }

    private void checkForStalledSync() {
        if (!running.get() || terminalStallReported) return;
        int height = getVerifiedHeight();
        long now = System.currentTimeMillis();
        if (height > lastObservedHeight) {
            lastObservedHeight = height;
            lastHeightProgressAt = now;
            stallRecoveriesAtHeight = 0;
            repeatedRejectionCount = 0;
            lastRejectedBlockHash = "";
            lastRejectedFailure = "";
            lastSyncIssue = "";
            reportIssue("", false);
            reportProgress();
            return;
        }
        if (blocksLeft <= 0 || getConnectedPeers() <= 0) return;
        if (now - lastHeightProgressAt < TimeUnit.SECONDS.toMillis(120)) return;
        // Give each selected peer a bounded chance to resume full-block download. Never report
        // an unchanged chain as healthy forever just because sockets remain connected.
        lastHeightProgressAt = now;
        stallRecoveriesAtHeight++;
        if (stallRecoveriesAtHeight <= 2) {
            rotateDownloadPeer(appContext.getString(wallet.main.R.string.full_pruned_node_issue_no_progress_rotating,
                    height, stallRecoveriesAtHeight));
        } else {
            terminalStallReported = true;
            String detail = !lastRejectedFailure.isEmpty()
                    ? appContext.getString(wallet.main.R.string.full_pruned_node_issue_no_progress_after_recovery,
                        lastRejectedFailure)
                    : appContext.getString(wallet.main.R.string.full_pruned_node_issue_no_progress_terminal, height);
            reportIssue(detail, true);
        }
    }

    private void rotateDownloadPeer(String reason) {
        PeerGroup active = peerGroup;
        if (active == null || terminalStallReported) return;
        Peer downloadPeer = active.getDownloadPeer();
        lastSyncIssue = reason;
        reportIssue(reason, false);
        if (downloadPeer != null) {
            try { downloadPeer.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void reportIssue(String message, boolean terminal) {
        Listener target = listener;
        if (target != null) {
            try { target.onSyncIssue(message == null ? "" : message, terminal); }
            catch (RuntimeException ignored) { }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public BitcoinNetwork network() {
        return network;
    }

    public int getVerifiedHeight() {
        RecoverableFullPrunedBlockChain activeChain = chain;
        return activeChain == null ? 0 : activeChain.getBestChainHeight();
    }

    public int getBlocksLeft() {
        return blocksLeft;
    }

    public int getConnectedPeers() {
        PeerGroup activePeers = peerGroup;
        return activePeers == null ? 0 : activePeers.getConnectedPeers().size();
    }

    public long getDatabaseBytes() {
        SqliteFullPrunedBlockStore activeStore = store;
        if (activeStore == null) return 0L;
        File file = activeStore.getDatabaseFile();
        File wal = new File(file.getPath() + "-wal");
        File shm = new File(file.getPath() + "-shm");
        return (file.exists() ? file.length() : 0L)
                + (wal.exists() ? wal.length() : 0L)
                + (shm.exists() ? shm.length() : 0L);
    }

    public File dataDirectory() {
        return new File(new File(appContext.getFilesDir(), "full-pruned-node"),
                NetworkConfig.storageName(network));
    }

    private void reportProgress() {
        Listener target = listener;
        if (target != null) {
            target.onProgress(getVerifiedHeight(), blocksLeft, getConnectedPeers());
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        ScheduledFuture<?> task = healthTask;
        healthTask = null;
        if (task != null) task.cancel(false);
        PeerGroup activePeers = peerGroup;
        peerGroup = null;
        chain = null;
        SqliteFullPrunedBlockStore activeStore = store;
        store = null;
        if (activePeers != null) {
            try { activePeers.stop(); } catch (Exception ignored) { }
        }
        if (activeStore != null) {
            try { activeStore.close(); } catch (Exception ignored) { }
        }
        healthExecutor.shutdownNow();
    }
}
