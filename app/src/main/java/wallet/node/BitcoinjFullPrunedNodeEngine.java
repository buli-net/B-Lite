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

import wallet.network.NetworkConfig;

/**
 * Experimental full-block bitcoinj sync engine. Unlike WalletAppKit's SPV chain,
 * this uses FullPrunedBlockChain and a durable UTXO/undo store. It runs separately
 * from the wallet's existing SPV engine and never owns wallet keys or signs spends.
 */
public final class BitcoinjFullPrunedNodeEngine implements AutoCloseable {
    public interface Listener {
        void onProgress(int verifiedHeight, int blocksLeft, int peers);
    }

    private static final int UNDO_DEPTH = 2016;
    private final android.content.Context appContext;
    private final BitcoinNetwork network;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

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
            newPeerGroup = new PeerGroup(network, newChain);
            newPeerGroup.setMaxConnections(4);
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
            reportProgress();
        } catch (Exception e) {
            running.set(false);
            this.peerGroup = null;
            this.chain = null;
            this.store = null;
            if (newPeerGroup != null) {
                try { newPeerGroup.stop(); } catch (Exception ignored) { }
            }
            try { newStore.close(); } catch (Exception ignored) { }
            throw e;
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
    }
}
