package wallet.mempool;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.listeners.PeerConnectedEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.core.listeners.PreMessageReceivedEventListener;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DefaultRiskAnalysis;
import org.bitcoinj.wallet.RiskAnalysis;
import org.bitcoinj.wallet.Wallet;

import wallet.network.NetworkConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Observes transaction messages received by the wallet's existing PeerGroup. */
public final class MempoolMonitor {
    private static final String PREFERENCES = "mempool_monitor_preferences";
    private static final String KEY_ENABLED = "enabled";
    private static final int MAX_RECENT_TRANSACTIONS = 100;
    private static final int MAX_SEEN_TRANSACTION_IDS = 10000;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Entry> RECENT = new LinkedHashMap<>();
    private static final LinkedHashSet<String> SEEN_IDS = new LinkedHashSet<>();
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final Set<Peer> FILTERED_BLOCK_PEERS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final ExecutorService EVENT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wallet-mempool-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private static PeerGroup peerGroup;
    private static BitcoinNetwork network;
    private static org.bitcoinj.core.Context bitcoinContext;
    private static Wallet activeWallet;
    private static PreMessageReceivedEventListener messageListener;
    private static PeerConnectedEventListener connectedListener;
    private static PeerDisconnectedEventListener disconnectedListener;
    private static boolean enabled = true;
    private static long groupGeneration;
    private static long listenerGeneration;
    private static long totalTransactions;
    private static long totalBroadcastEvents;
    private static long duplicateEvents;
    private static long walletRelatedCount;
    private static long okCount;
    private static long nonFinalCount;
    private static long nonStandardCount;
    private static long errorCount;

    private MempoolMonitor() {
    }

    public interface Observer {
        void onMempoolChanged();
    }

    public enum RiskStatus {
        OK,
        NON_FINAL,
        NON_STANDARD,
        ERROR
    }

    public static final class Entry {
        public final String txId;
        public final RiskStatus riskStatus;
        public final long observedAtMillis;
        public final int inputCount;
        public final int outputCount;
        public final int sizeBytes;
        public final long outputValueSatoshis;
        public final boolean walletRelated;
        public final int observationCount;

        private Entry(String txId, RiskStatus riskStatus, long observedAtMillis,
                      int inputCount, int outputCount, int sizeBytes,
                      long outputValueSatoshis, boolean walletRelated, int observationCount) {
            this.txId = txId;
            this.riskStatus = riskStatus;
            this.observedAtMillis = observedAtMillis;
            this.inputCount = inputCount;
            this.outputCount = outputCount;
            this.sizeBytes = sizeBytes;
            this.outputValueSatoshis = outputValueSatoshis;
            this.walletRelated = walletRelated;
            this.observationCount = observationCount;
        }

        private Entry withObservationCount(int count) {
            return new Entry(txId, riskStatus, observedAtMillis, inputCount,
                    outputCount, sizeBytes, outputValueSatoshis, walletRelated, count);
        }
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final boolean attached;
        public final BitcoinNetwork network;
        public final int connectedPeers;
        public final long totalTransactions;
        public final long totalBroadcastEvents;
        public final long duplicateEvents;
        public final long walletRelatedCount;
        public final long okCount;
        public final long nonFinalCount;
        public final long nonStandardCount;
        public final long errorCount;
        public final List<Entry> recentTransactions;

        private Snapshot(boolean enabled, boolean attached, BitcoinNetwork network,
                         int connectedPeers, long totalTransactions,
                         long totalBroadcastEvents, long duplicateEvents,
                         long walletRelatedCount, long okCount, long nonFinalCount, long nonStandardCount,
                         long errorCount, List<Entry> recentTransactions) {
            this.enabled = enabled;
            this.attached = attached;
            this.network = network;
            this.connectedPeers = connectedPeers;
            this.totalTransactions = totalTransactions;
            this.totalBroadcastEvents = totalBroadcastEvents;
            this.duplicateEvents = duplicateEvents;
            this.walletRelatedCount = walletRelatedCount;
            this.okCount = okCount;
            this.nonFinalCount = nonFinalCount;
            this.nonStandardCount = nonStandardCount;
            this.errorCount = errorCount;
            this.recentTransactions = recentTransactions;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, true);
    }

    /** Connects monitoring to the currently active wallet PeerGroup; no additional peers are opened. */
    public static void attach(Context context, PeerGroup newPeerGroup, BitcoinNetwork newNetwork,
                              org.bitcoinj.core.Context newBitcoinContext, Wallet newWallet) {
        if (context == null || newPeerGroup == null || newNetwork == null || newBitcoinContext == null) {
            return;
        }

        boolean notify = false;
        synchronized (LOCK) {
            boolean requestedEnabled = isEnabled(context);
            if (peerGroup == newPeerGroup && network == newNetwork) {
                enabled = requestedEnabled;
                bitcoinContext = newBitcoinContext;
                activeWallet = newWallet;
                updateMessageListenerLocked();
                notify = true;
            } else {
                boolean networkChanged = network != null && network != newNetwork;
                detachLocked();
                if (networkChanged) {
                    clearDataLocked();
                }
                network = newNetwork;
                peerGroup = newPeerGroup;
                bitcoinContext = newBitcoinContext;
                activeWallet = newWallet;
                enabled = requestedEnabled;
                final long expectedGroupGeneration = ++groupGeneration;

                connectedListener = (peer, peerCount) -> {
                    if (isCurrentGroup(expectedGroupGeneration)) {
                        notifyObservers();
                    }
                };
                disconnectedListener = (peer, peerCount) -> {
                    if (isCurrentGroup(expectedGroupGeneration)) {
                        notifyObservers();
                    }
                };
                peerGroup.addConnectedEventListener(EVENT_EXECUTOR, connectedListener);
                peerGroup.addDisconnectedEventListener(EVENT_EXECUTOR, disconnectedListener);
                updateMessageListenerLocked();
                notify = true;
            }
        }
        if (notify) {
            notifyObservers();
        }
    }

    public static void setEnabled(Context context, boolean value) {
        preferences(context).edit().putBoolean(KEY_ENABLED, value).apply();
        synchronized (LOCK) {
            if (enabled != value || (value && peerGroup != null && messageListener == null)
                    || (!value && messageListener != null)) {
                enabled = value;
                updateMessageListenerLocked();
            }
        }
        notifyObservers();
    }

    private static void updateMessageListenerLocked() {
        listenerGeneration++;
        if (peerGroup != null && messageListener != null) {
            peerGroup.removePreMessageReceivedEventListener(messageListener);
            messageListener = null;
        }
        if (!enabled || peerGroup == null || bitcoinContext == null) {
            return;
        }

        final long expectedGroupGeneration = groupGeneration;
        final long expectedListenerGeneration = listenerGeneration;
        final org.bitcoinj.core.Context callbackContext = bitcoinContext;
        final BitcoinNetwork callbackNetwork = network;
        messageListener = (peer, message) -> {
            boolean filteredBlockTransaction = false;
            synchronized (LOCK) {
                if (message instanceof FilteredBlock) {
                    FILTERED_BLOCK_PEERS.add(peer);
                } else if (FILTERED_BLOCK_PEERS.contains(peer)) {
                    if (message instanceof Transaction) {
                        filteredBlockTransaction = true;
                    } else {
                        FILTERED_BLOCK_PEERS.remove(peer);
                    }
                }
            }

            if (message instanceof Transaction && !filteredBlockTransaction) {
                final Transaction transaction = (Transaction) message;
                try {
                    EVENT_EXECUTOR.execute(() -> {
                        org.bitcoinj.core.Context.propagate(callbackContext);
                        recordTransaction(expectedGroupGeneration, expectedListenerGeneration,
                                callbackNetwork, transaction);
                    });
                } catch (RuntimeException ignored) {
                    // Monitoring must never interrupt bitcoinj's peer message processing.
                }
            }
            return message;
        };
        // Peer.processTransaction can return before its broadcast listeners are notified
        // when a tx satisfies a getdata future or belongs to a filtered block. Observe
        // raw P2P tx messages first, while excluding tx messages inside FilteredBlock.
        // Queue analysis and return every message unchanged. bitcoinj requires SAME_THREAD.
        peerGroup.addPreMessageReceivedEventListener(Threading.SAME_THREAD, messageListener);
    }

    private static boolean isCurrentGroup(long expectedGeneration) {
        synchronized (LOCK) {
            return peerGroup != null && groupGeneration == expectedGeneration;
        }
    }

    private static void recordTransaction(long expectedGroupGeneration,
                                          long expectedListenerGeneration,
                                          BitcoinNetwork expectedNetwork,
                                          Transaction transaction) {
        if (transaction == null) {
            return;
        }

        String txId = transaction.getTxId().toString();
        boolean duplicate;
        Wallet currentWallet;
        synchronized (LOCK) {
            if (!enabled || peerGroup == null || groupGeneration != expectedGroupGeneration
                    || listenerGeneration != expectedListenerGeneration) {
                return;
            }
            totalBroadcastEvents++;
            currentWallet = activeWallet;
            duplicate = SEEN_IDS.contains(txId);
            if (duplicate) {
                duplicateEvents++;
                Entry previous = RECENT.get(txId);
                if (previous != null) {
                    RECENT.put(txId, previous.withObservationCount(previous.observationCount + 1));
                }
            } else {
                SEEN_IDS.add(txId);
                while (SEEN_IDS.size() > MAX_SEEN_TRANSACTION_IDS) {
                    String oldest = SEEN_IDS.iterator().next();
                    SEEN_IDS.remove(oldest);
                }
            }
        }
        if (duplicate) {
            notifyObservers();
            return;
        }

        RiskStatus riskStatus;
        try {
            Transaction.verify(NetworkConfig.parameters(expectedNetwork).network(), transaction);
            RiskAnalysis.Result result = DefaultRiskAnalysis.FACTORY
                    .create(currentWallet, transaction, Collections.emptyList()).analyze();
            if (result == RiskAnalysis.Result.NON_FINAL) {
                riskStatus = RiskStatus.NON_FINAL;
            } else if (result == RiskAnalysis.Result.NON_STANDARD) {
                riskStatus = RiskStatus.NON_STANDARD;
            } else {
                riskStatus = RiskStatus.OK;
            }
        } catch (VerificationException | RuntimeException failure) {
            riskStatus = RiskStatus.ERROR;
        }

        long outputValue = 0L;
        try {
            outputValue = transaction.getOutputSum().value;
        } catch (RuntimeException ignored) {
        }

        boolean walletRelated = false;
        try {
            walletRelated = currentWallet != null
                    && currentWallet.getTransaction(transaction.getTxId()) != null;
        } catch (RuntimeException ignored) {
        }

        Entry entry = new Entry(txId, riskStatus, System.currentTimeMillis(),
                transaction.getInputs().size(), transaction.getOutputs().size(),
                transaction.messageSize(), outputValue, walletRelated, 1);

        synchronized (LOCK) {
            if (!enabled || peerGroup == null || groupGeneration != expectedGroupGeneration
                    || listenerGeneration != expectedListenerGeneration) {
                return;
            }
            totalTransactions++;
            if (walletRelated) {
                walletRelatedCount++;
            }
            switch (riskStatus) {
                case OK:
                    okCount++;
                    break;
                case NON_FINAL:
                    nonFinalCount++;
                    break;
                case NON_STANDARD:
                    nonStandardCount++;
                    break;
                case ERROR:
                    errorCount++;
                    break;
            }
            RECENT.put(txId, entry);
            while (RECENT.size() > MAX_RECENT_TRANSACTIONS) {
                String oldest = RECENT.keySet().iterator().next();
                RECENT.remove(oldest);
            }
        }
        notifyObservers();
    }

    public static Snapshot snapshot(Context context) {
        boolean savedEnabled = isEnabled(context);
        synchronized (LOCK) {
            int peerCount = 0;
            if (peerGroup != null) {
                try {
                    peerCount = peerGroup.getConnectedPeers().size();
                } catch (RuntimeException ignored) {
                }
            }
            ArrayList<Entry> entries = new ArrayList<>(RECENT.values());
            Collections.reverse(entries);
            return new Snapshot(savedEnabled, messageListener != null, network,
                    peerCount, totalTransactions, totalBroadcastEvents, duplicateEvents,
                    walletRelatedCount, okCount, nonFinalCount, nonStandardCount, errorCount,
                    Collections.unmodifiableList(entries));
        }
    }

    public static void clearSessionData() {
        synchronized (LOCK) {
            clearDataLocked();
        }
        notifyObservers();
    }

    /** Removes observers from a PeerGroup before its WalletAppKit is stopped. */
    public static void detach() {
        synchronized (LOCK) {
            detachLocked();
        }
        notifyObservers();
    }

    private static void clearDataLocked() {
        RECENT.clear();
        SEEN_IDS.clear();
        totalTransactions = 0L;
        totalBroadcastEvents = 0L;
        duplicateEvents = 0L;
        walletRelatedCount = 0L;
        okCount = 0L;
        nonFinalCount = 0L;
        nonStandardCount = 0L;
        errorCount = 0L;
    }

    private static void detachLocked() {
        if (peerGroup != null) {
            if (messageListener != null) {
                peerGroup.removePreMessageReceivedEventListener(messageListener);
            }
            if (connectedListener != null) {
                peerGroup.removeConnectedEventListener(connectedListener);
            }
            if (disconnectedListener != null) {
                peerGroup.removeDisconnectedEventListener(disconnectedListener);
            }
        }
        messageListener = null;
        FILTERED_BLOCK_PEERS.clear();
        connectedListener = null;
        disconnectedListener = null;
        peerGroup = null;
        bitcoinContext = null;
        activeWallet = null;
        groupGeneration++;
        listenerGeneration++;
    }

    public static void addObserver(Observer observer) {
        if (observer != null) {
            OBSERVERS.addIfAbsent(observer);
        }
    }

    public static void removeObserver(Observer observer) {
        OBSERVERS.remove(observer);
    }

    private static void notifyObservers() {
        for (Observer observer : OBSERVERS) {
            try {
                observer.onMempoolChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
