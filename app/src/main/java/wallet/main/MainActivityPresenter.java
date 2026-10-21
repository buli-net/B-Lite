package wallet.main;

import wallet.storage.ImportedWalletStore;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.transaction.TransactionMapper;
import wallet.security.WalletSecurity;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.SendRequest;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.MnemonicCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.Collections;
import java.util.function.Function;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import wallet.sync.BitcoinSyncService;
import wallet.ui.LocalizedErrors;
import wallet.util.MoneyFormatter;
import wallet.network.NetworkConfig;
import wallet.core.WalletAddressType;
import wallet.core.WalletSelection;

/** Owns the persistent bitcoinj wallet and sync lifecycle. */
public class MainActivityPresenter
        implements MainActivityContract.MainActivityPresenter {

    private static volatile MainActivityPresenter activePresenter;

    private static final int MAX_CONNECTIONS = 12;
    private static final String FORWARDING_PREFS = "forwarding_service";

    private static String forwardingPreferencesName(android.content.Context context) {
        return FORWARDING_PREFS + "_" + NetworkConfig.storageName(NetworkConfig.get(context));
    }
    private static final String FORWARDING_ENABLED = "enabled";
    private static final String FORWARDING_ADDRESS = "destination";
    private static final String FORWARDING_CONFIRMATIONS = "confirmations";
    private static final String FORWARDING_STATUS = "status";
    private static final String FORWARDING_MODE = "mode";
    private static final String FORWARDING_FIXED_AMOUNT_SATS = "fixed_amount_sats";
    private static final String FORWARDING_FEE_RATE_SAT_VB = "fee_rate_sat_vb";
    private static final String FORWARDING_LAST_FEE_SATS = "last_fee_sats";
    private static final String FORWARDING_LAST_AMOUNT_SATS = "last_amount_sats";
    private static final String FORWARDING_LAST_RATE_SAT_VB = "last_rate_sat_vb";
    private static final String FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB = "last_effective_rate_sat_vb";
    private static final String FORWARDING_LAST_VSIZE = "last_vsize";
    private static final String FORWARDING_LAST_INCOMING_TX = "last_incoming_tx";
    private static final String FORWARDING_LAST_OUTGOING_TX = "last_outgoing_tx";
    private static final String FORWARDING_LAST_STAGE = "last_stage";
    private static final String FORWARDING_LAST_RECEIVED_SATS = "last_received_sats";
    private static final String FORWARDING_LAST_CURRENT_CONFIRMATIONS = "last_current_confirmations";
    private static final String FORWARDING_LAST_REQUIRED_CONFIRMATIONS = "last_required_confirmations";
    private static final String FORWARDING_LAST_OUTGOING_CONFIRMATIONS = "last_outgoing_confirmations";
    private static final Set<String> forwardingTransactions = ConcurrentHashMap.newKeySet();
    private static final ExecutorService FORWARDING_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "blite-forwarding-service");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService FORWARDING_STATUS_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "blite-forwarding-status");
                thread.setDaemon(true);
                return thread;
            });

    private static final long STALL_TIMEOUT_MS = 90_000L;
    private static final long NO_PEER_RECONNECT_TIMEOUT_MS = 45_000L;

    private static final long REFRESH_DEBOUNCE_MS = 500L;
    // Never push historical-sync progress to the Android main thread for every block.
    // During fast catch-up bitcoinj can report thousands of blocks per second.
    private static final long PROGRESS_UI_UPDATE_MS = 500L;
    private static final long SYNC_NOTIFICATION_UPDATE_MS = 1000L;
    private static final long DOWNLOAD_KICK_COOLDOWN_MS = 30_000L;
    // Repeated full-kit recovery attempts back off when reconnecting does not restore
    // actual block progress. Network/peer events alone do not reset this backoff.
    private static final long AUTO_RECOVERY_BASE_DELAY_MS = 30_000L;
    private static final long AUTO_RECOVERY_MAX_DELAY_MS = 300_000L;
    private static final long WAITING_NETWORK_NOTICE_COOLDOWN_MS = 60_000L;

    private static final int MAX_AUTO_RESTARTS = 1440;

    private MainActivityContract.MainActivityView view;

    private final android.content.Context applicationContext;


    private volatile NetworkParameters parameters;

    private volatile BitcoinNetwork activeNetwork;

    private volatile File walletDataDir;

    private volatile WalletAppKit walletAppKit;

    private volatile DownloadProgressTracker activeDownloadListener;
    private volatile long lastDownloadKickAt = 0L;
    private volatile int lastDownloadBlocksSoFar = 0;

    private volatile File walletFile;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Object kitLock = new Object();

    private final AtomicBoolean restartInProgress =
            new AtomicBoolean(false);

    private final Object refreshLock = new Object();
    private final Object recoveryLock = new Object();

    // Retain transaction rows independently for each live wallet and selected scope.
    // Wallet objects are weak keys so closed/replaced wallets are not kept alive.
    private static final class HistoryCacheEntry {
        final int transactionCount;
        final String newestTransactionId;
        final int chainHeight;
        final List<TransactionItem> items;

        HistoryCacheEntry(int count, String newestId, int height, List<TransactionItem> items) {
            transactionCount = count;
            newestTransactionId = newestId;
            chainHeight = height;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    private final Map<Wallet, Map<String, HistoryCacheEntry>> historyCache = new WeakHashMap<>();
    private final Map<Wallet, Long> historyCacheGenerations = new WeakHashMap<>();
    private final Object historyCacheLock = new Object();

    private boolean refreshInProgress;

    private boolean refreshPending;

    private volatile boolean walletReady = false;

    private volatile boolean downloadFinished = false;

    private volatile boolean startupInProgress = false;

    private volatile boolean shuttingDown = false;

    private volatile int lastPercent = -1;

    /** Start and target heights for the current sync session. A reconnect starts a new session
     * from the current chain tip so progress reflects only the remaining work. */
    private volatile int syncProgressStartBlock = -1;
    private volatile int syncProgressTargetBlock = -1;

    private volatile int lastChainHeight = -1;

    private volatile long lastProgressAt = 0L;
    private volatile long nextAutomaticRecoveryAt = 0L;
    private volatile int consecutiveRecoveryFailures = 0;
    private volatile long lastWaitingNetworkNoticeAt = 0L;
    private volatile long latestRefreshRequestedAt = 0L;
    private volatile long lastRefreshTotalMs = -1L;
    private volatile long lastRefreshProcessingMs = -1L;
    private volatile long lastProgressUiUpdateAt = 0L;
    private volatile long lastSyncNotificationUpdateAt = 0L;
    private volatile int lastSyncNotificationPercent = -1;
    private volatile long noPeerSince = 0L;

    private volatile int autoRestartCount = 0;

    private volatile boolean networkSwitchInProgress = false;
    private volatile boolean syncModeSwitchInProgress = false;

    private volatile double syncBlocksPerSecond = 0.0;
    private volatile long lastBlockEventAt = 0L;
    private volatile int lastRateHeight = -1;
    private volatile String bestChainHash = "";
    private volatile long bestChainTimeSeconds = 0L;

    private volatile DeterministicSeed pendingRestoreSeed;
    private volatile File pendingMnemonicBackupFile;
    private volatile List<ECKey> pendingRestoreImportedKeys = new ArrayList<>();
    private volatile List<org.bitcoinj.script.Script> pendingRestoreWatchedScripts = new ArrayList<>();
    private volatile AesKey pendingRestoreSourceSessionKey;
    private volatile boolean pendingRestoreSourceEncrypted;

    private ScheduledExecutorService watchdog;

    /** UI clients that need an immediate refresh when the wallet changes while they are visible. */
    private final CopyOnWriteArrayList<Runnable> walletUpdateListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Runnable> syncStateListeners =
            new CopyOnWriteArrayList<>();

    public MainActivityPresenter(
            MainActivityContract.MainActivityView view) {

        this.view = view;
        this.applicationContext = view.getActivityContext().getApplicationContext();
        this.activeNetwork = NetworkConfig.get(applicationContext);
        this.parameters = NetworkConfig.parameters(activeNetwork);
        this.walletDataDir = NetworkConfig.walletDirectory(applicationContext, activeNetwork);
        this.walletFile = new File(walletDataDir, Constants.WALLET_NAME + ".wallet");
        activePresenter = this;

        view.setPresenter(this);
    }

    public void attachView(MainActivityContract.MainActivityView newView) {
        if (newView == null) {
            detachView();
            return;
        }
        view = newView;
        view.setPresenter(this);
        renderCurrentState();
    }

    /** Detaches the Activity while keeping the wallet sync engine alive. */
    public void detachView() {
        view = new HeadlessView(applicationContext);
    }

    private void renderCurrentState() {
        runOnUi(() -> {
            if (walletReady && walletAppKit != null) {
                view.displayWalletLoading(text(R.string.wallet_status_loading_data), true);
                view.displayDownloadContent(!downloadFinished);

                if (!downloadFinished && lastPercent >= 0) {
                    view.displayPercentage(lastPercent);
                    view.displayProgress(lastPercent);
                }

                refresh();
                return;
            }

            view.displayWalletLoading(text(R.string.wallet_status_opening), true);
            view.displayDownloadContent(true);

            if (lastPercent >= 0) {
                view.displayPercentage(lastPercent);
                view.displayProgress(lastPercent);
            }
        });
    }

    @Override
    public void subscribe() {

        shuttingDown = false;

        activeNetwork = NetworkConfig.get(applicationContext);
        parameters = NetworkConfig.parameters(activeNetwork);
        walletDataDir = NetworkConfig.walletDirectory(applicationContext, activeNetwork);
        walletFile = new File(walletDataDir, Constants.WALLET_NAME + ".wallet");
        wallet.widget.BalanceWidgetProvider.requestRefresh(applicationContext);

        BriefLogFormatter.init();

        startWatchdog();

        if (walletAppKit != null
                || startupInProgress
                || restartInProgress.get()) {
            renderCurrentState();
            return;
        }

        renderCurrentState();
        startWalletKit();
    }

    /**
     * Calculates progress for the current sync session. The session starts at the chain height
     * already present when WalletAppKit is (re)started, so a reconnect measures only the blocks
     * that remain instead of falling back to a whole-chain percentage.
     */
    private int calculateSessionProgress(int currentBlock, int peerHeight) {
        if (currentBlock < 0) {
            return 0;
        }

        int start = syncProgressStartBlock;
        if (start < 0 || currentBlock < start) {
            start = currentBlock;
            syncProgressStartBlock = start;
        }

        int target = syncProgressTargetBlock;
        if (target <= start && peerHeight > start) {
            target = peerHeight;
            syncProgressTargetBlock = target;
        } else if (target < 0 && peerHeight > start) {
            target = peerHeight;
            syncProgressTargetBlock = target;
        }

        if (target <= start) {
            return currentBlock > start ? 100 : 0;
        }

        if (currentBlock <= start) {
            return 0;
        }

        if (currentBlock >= target) {
            return 100;
        }

        long completed = (long) currentBlock - start;
        long remaining = (long) target - start;
        return (int) Math.max(0, Math.min(100, Math.round(completed * 100.0 / remaining)));
    }

    private void startWalletKit() {

        synchronized (kitLock) {
            if (shuttingDown
                    || walletAppKit != null
                    || startupInProgress) {
                return;
            }

            startupInProgress = true;
            notifySyncStateChanged();
        }
        runOnUi(() -> view.displayWalletLoading(
                text(R.string.wallet_status_opening), true));

        new Thread(() -> {

            final NetworkParameters startParameters = parameters;
            Context.propagate(Context.getOrCreate(startParameters));

            if (shuttingDown) {
                startupInProgress = false;
                return;
            }

            WalletAppKit kit = null;

            try {

                synchronized (kitLock) {

                    if (shuttingDown) {
                        startupInProgress = false;
                        return;
                    }

                    final BitcoinNetwork startNetwork = activeNetwork;
                    final File startWalletDir = walletDataDir;
                    final WalletAppKit newKit =
                            new WalletAppKit(
                                    startNetwork,
                                    ScriptType.P2WPKH,
                                    NetworkConfig.keyChainGroupStructure(startNetwork),
                                    startWalletDir,
                                    Constants.WALLET_NAME
                            ) {

                                @Override
                                protected void onSetupCompleted() {

                                    try {

                                        peerGroup().setMaxConnections(MAX_CONNECTIONS);

                                        applySyncMode(peerGroup(), startNetwork,
                                                NetworkConfig.getSyncMode(applicationContext, startNetwork));

                                        // Use mobile-friendly connection/discovery timeouts. Do not artificially cap
                                        // the discovered peer pool: PeerGroup will maintain the
                                        // requested number of live connections itself.
                                        peerGroup().setConnectTimeout(Duration.ofSeconds(15));
                                        peerGroup().setPeerDiscoveryTimeout(Duration.ofSeconds(5));
                                        peerGroup().setStallThreshold(20, Block.HEADER_SIZE * 10);

                                    } catch (Exception ignored) {
                                        // Peer tuning is best-effort; WalletAppKit defaults remain valid.
                                    }

                                    Wallet setupWallet;
                                    PeerGroup setupPeers;
                                    synchronized (kitLock) {
                                        if (shuttingDown || walletAppKit != this) {
                                            return;
                                        }
                                        setupWallet = wallet();
                                        setupPeers = peerGroup();
                                    }

                                    // Reconcile Request-created scripts for every network through
                                    // one wallet-driven path before SPV synchronization starts.
                                    // This also migrates obsolete P2PKH/P2WPKH Request watch entries
                                    // back to normal main-wallet ownership and restores only the
                                    // P2SH-P2WPKH scripts that require explicit script watching.
                                    int requestScriptChanges =
                                            WalletSelection.ensureRequestNestedScripts(applicationContext, setupWallet);
                                    if (requestScriptChanges > 0) {
                                        try {
                                            setupWallet.saveToFile(walletFile);
                                        } catch (IOException ignored) {
                                            // WalletKit autosave will retry; continue startup with
                                            // the corrected in-memory ownership state.
                                        }
                                    }
                                    setupWalletListeners(this, setupWallet);
                                    setupPeerListeners(this, setupPeers);

                                    File restoreBackup = pendingMnemonicBackupFile;
                                    synchronized (kitLock) {
                                        if (shuttingDown || walletAppKit != this) {
                                            return;
                                        }
                                        if (restoreBackup != null && restoreBackup.exists()) {
                                            setupWallet.isConsistentOrThrow();
                                            if (!setupWallet.getParams().equals(parameters)) {
                                                throw new IllegalStateException(
                                                        text(R.string.restore_network_mismatch));
                                            }
                                            try {
                                                mergeRestoredSecondaryWalletData(
                                                        setupWallet,
                                                        pendingRestoreImportedKeys,
                                                        pendingRestoreWatchedScripts,
                                                        pendingRestoreSourceEncrypted,
                                                        pendingRestoreSourceSessionKey);
                                                // A restore may merge legacy watch-script metadata after
                                                // the initial startup reconciliation above. Reconcile again
                                                // after that merge so Request outputs backed by the main HD
                                                // wallet never remain classified as a separate watch-only
                                                // scope. This is wallet-parameter driven and identical on
                                                // Mainnet and Signet.
                                                WalletSelection.ensureRequestNestedScripts(applicationContext, setupWallet);
                                                setupWallet.saveToFile(walletFile);
                                            } catch (IOException error) {
                                                throw new IllegalStateException(
                                                        text(R.string.restore_imported_wallets_preserve_failed),
                                                        error);
                                            }
                                            restoreBackup.delete();
                                            pendingMnemonicBackupFile = null;
                                            clearPendingRestoreSecondaryData();
                                        }

                                        walletReady = true;
                                        if (networkSwitchInProgress) {
                                            networkSwitchInProgress = false;
                                        }
                                    }
                                    notifySyncStateChanged();

                                    int height =
                                            safeChainHeight(this);

                                    lastChainHeight = height;

                                    // The kit becoming ready is not blockchain progress;
                                    // preserve the recovery backoff until download activity resumes.
                                    touchProgress();

                                    runOnUi(() -> {
                                        view.displayWalletLoading(
                                                text(R.string.wallet_status_loading_data), true);
                                        view.displayDownloadContent(true);
                                        refresh();
                                    });

                                }
                            };

                    final DownloadProgressTracker downloadTracker =
                            new DownloadProgressTracker() {

                                @Override
                                protected void progress(
                                        double pct,
                                        int blocksSoFar,
                                        Instant date) {

                                    super.progress(
                                            pct,
                                            blocksSoFar,
                                            date
                                    );

                                    int chainHeight =
                                            safeChainHeight(newKit);
                                    int peerHeight =
                                            safePeerHeight(newKit);
                                    int percentage =
                                            calculateSessionProgress(chainHeight, peerHeight);

                                    synchronized (kitLock) {
                                        if (shuttingDown || walletAppKit != newKit) {
                                            return;
                                        }
                                    }

                                    lastPercent = percentage;
                                    notifySyncStateChanged();

                                    boolean newChainHeight = chainHeight > lastChainHeight;
                                    boolean moreDownloadedBlocks = blocksSoFar > lastDownloadBlocksSoFar;
                                    if (newChainHeight) {
                                        lastChainHeight = chainHeight;
                                    }
                                    if (moreDownloadedBlocks) {
                                        lastDownloadBlocksSoFar = blocksSoFar;
                                    }
                                    if (newChainHeight || moreDownloadedBlocks) {
                                        recordActualSyncProgress();
                                    }

                                    final int uiPercent =
                                            percentage;

                                    long now = System.currentTimeMillis();
                                    boolean percentChanged = uiPercent != lastSyncNotificationPercent;
                                    boolean updateNotification = percentChanged
                                            || now - lastSyncNotificationUpdateAt >= SYNC_NOTIFICATION_UPDATE_MS;

                                    if (updateNotification) {
                                        lastSyncNotificationUpdateAt = now;
                                        lastSyncNotificationPercent = uiPercent;
                                        BitcoinSyncService.updateSyncNotification(
                                                applicationContext,
                                                uiPercent,
                                                chainHeight,
                                                safePeerHeight(newKit),
                                                true);
                                    }

                                    // IMPORTANT: progress() may be called once per downloaded block.
                                    // Posting every callback to the main Looper can queue tens of
                                    // thousands of Runnables during fast catch-up and make the whole
                                    // phone appear frozen. The Sync screen already polls once/sec,
                                    // so a 500 ms UI update is more than sufficient.
                                    if (now - lastProgressUiUpdateAt >= PROGRESS_UI_UPDATE_MS) {
                                        lastProgressUiUpdateAt = now;
                                        runOnUi(() -> {
                                            view.displayDownloadContent(true);
                                            view.displayPercentage(uiPercent);
                                            view.displayProgress(uiPercent);
                                        });
                                    }
                                }

                                @Override
                                protected void doneDownload() {

                                    super.doneDownload();

                                    downloadFinished = true;
                    notifySyncStateChanged();

                                    lastPercent = 100;

                                    BitcoinSyncService.updateSyncNotification(
                                            applicationContext,
                                            100,
                                            safeChainHeight(newKit),
                                            safePeerHeight(newKit),
                                            false);

                                    recordActualSyncProgress();

                                    runOnUi(() -> {

                                        view.displayPercentage(
                                                100
                                        );

                                        view.displayProgress(
                                                100
                                        );

                                        view.displayDownloadContent(
                                                false
                                        );

                                        refresh();
                                    });
                                }
                            };

                    activeDownloadListener = downloadTracker;
                    newKit.setDownloadListener(downloadTracker);

                    newKit.setBlockingStartup(false);

                    newKit.setAutoSave(true);

                    DeterministicSeed restoreSeed = pendingRestoreSeed;
                    if (restoreSeed != null) {
                        newKit.restoreWalletFromSeed(restoreSeed);
                        pendingRestoreSeed = null;
                    }

                    walletAppKit = newKit;

                    kit = newKit;
                }

                lastPercent = -1;

                lastChainHeight =
                        safeChainHeight(kit);
                syncProgressStartBlock = lastChainHeight;
                syncProgressTargetBlock = safePeerHeight(kit);
                noPeerSince = 0L;
                lastRateHeight = lastChainHeight;
                lastBlockEventAt = 0L;
                syncBlocksPerSecond = 0.0;
                lastProgressUiUpdateAt = 0L;
                lastSyncNotificationUpdateAt = 0L;
                lastSyncNotificationPercent = -1;
                lastDownloadKickAt = 0L;
                lastDownloadBlocksSoFar = 0;
                bestChainHash = "";
                bestChainTimeSeconds = 0L;

                BitcoinSyncService.updateSyncNotification(
                        applicationContext,
                        0,
                        lastChainHeight,
                        safePeerHeight(kit),
                        true);

                downloadFinished = false;

                touchProgress();

                kit.startAsync();

                kit.awaitRunning();

                // WalletAppKit normally starts the chain download after the peer group
                // becomes ready. On Signet, especially with an empty .spvchain, a peer
                // can be connected before the download request is actually scheduled.
                // Kick the downloader once after startup; this is idempotent and also
                // works when there are currently no peers (the PeerGroup will retry).
                requestBlockchainDownload(kit, true);

                startupInProgress = false;

            } catch (Exception e) {

                if (networkSwitchInProgress) {
                    networkSwitchInProgress = false;
                }

                File restoreBackup = pendingMnemonicBackupFile;
                if (restoreBackup != null && restoreBackup.exists()) {
                    if (walletFile.exists()) {
                        walletFile.delete();
                    }
                    if (restoreBackup.renameTo(walletFile)) {
                        pendingMnemonicBackupFile = null;
                        pendingRestoreSeed = null;
                    }
                }

                startupInProgress = false;
                walletReady = false;

                WalletAppKit failedKit;

                synchronized (kitLock) {

                    failedKit = walletAppKit;
                }

                Throwable rootCause =
                        findRootCause(e);

                Throwable failureCause = null;

                if (failedKit != null) {

                    try {

                        failureCause =
                                failedKit.failureCause();

                    } catch (Exception ignored) {
                        // Failure details are optional during cleanup.
                    }
                }

                if (failureCause != null) {

                    rootCause =
                            findRootCause(
                                    failureCause
                            );
                }


                final String errorText =
                        buildFailureMessage(
                                e,
                                rootCause
                        );

                if (!shuttingDown) {
                    runOnUi(() -> {
                        view.displayWalletLoading(
                                text(R.string.wallet_status_load_failed), false);
                        view.showToastMessage(
                                text(R.string.sync_error, errorText));
                    });

                    scheduleRestartAfterFailure();
                }
            }

        }, "bitcoinj-start").start();
    }

    private void startWatchdog() {

        stopWatchdog();

        watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {

                            Thread t =
                                    new Thread(
                                            r,
                                            "bitcoinj-watchdog"
                                    );

                            t.setDaemon(true);

                            return t;
                        }
                );

        watchdog.scheduleWithFixedDelay(

                () -> {

                    if (shuttingDown
                            || restartInProgress.get()) {

                        return;
                    }

                    WalletAppKit kit =
                            walletAppKit;

                    if (kit == null) {
                        return;
                    }

                    if (!kit.isRunning()) {
                        // During normal startup awaitRunning() can briefly leave
                        // the kit in a non-running state. Do not restart it while
                        // that startup is already in progress.
                        if (!startupInProgress) {
                            restartWalletKitAutomatically(System.currentTimeMillis());
                        }
                        return;
                    }

                    int chainHeight =
                            safeChainHeight(kit);

                    int peerHeight =
                            safePeerHeight(kit);

                    int peers =
                            safePeerCount(kit);

                    int pendingPeers =
                            safePendingPeerCount(kit);

                    long now = System.currentTimeMillis();

                    BitcoinSyncService.updateSyncNotification(
                            applicationContext,
                            downloadFinished ? 100 : Math.max(0, lastPercent),
                            chainHeight,
                            peerHeight,
                            !downloadFinished);

                    // A foreground service can stay alive while bitcoinj's P2P
                    // sockets have all gone away after the screen turns off.
                    // Treat zero connected peers as its own health condition;
                    // do not wait for blockchain height to advance before
                    // deciding that the network engine needs recovery.
                    if (peers <= 0) {
                        if (noPeerSince == 0L) {
                            noPeerSince = now;
                        }

                        long noPeerFor = now - noPeerSince;
                        if (noPeerFor >= NO_PEER_RECONNECT_TIMEOUT_MS
                                && !startupInProgress) {


                            if (autoRestartCount >= MAX_AUTO_RESTARTS) {
                                notifyWaitingForNetwork(now);
                                noPeerSince = now;
                                return;
                            }

                            restartWalletKitAutomatically(now);
                            return;
                        }
                    } else {
                        noPeerSince = 0L;
                    }

                    if (chainHeight > lastChainHeight) {

                        lastChainHeight =
                                chainHeight;

                        recordActualSyncProgress();
                        notifySyncStateChanged();

                        return;
                    }

                    // Once caught up, blocks arrive on Bitcoin's natural and variable
                    // interval. An idle chain is not a stalled download and must never
                    // cause periodic WalletAppKit restarts.
                    if (downloadFinished) {
                        return;
                    }

                    long stalledFor =
                            now - lastProgressAt;

                    // If peers are healthy and clearly ahead but no block has reached
                    // the chain yet, explicitly re-arm blockchain download before doing
                    // a full WalletAppKit restart. This is particularly important for
                    // Signet where peer discovery can succeed while the initial getblocks
                    // request is not yet active.
                    if (!downloadFinished
                            && peerHeight > chainHeight
                            && stalledFor >= 10_000L) {
                        if (requestBlockchainDownload(kit, false)) {
                            return;
                        }
                    }

                    // Give a successful download re-arm its full cooldown window to
                    // produce a real block-progress event before restarting the entire kit.
                    if (lastDownloadKickAt > 0L
                            && now - lastDownloadKickAt < DOWNLOAD_KICK_COOLDOWN_MS) {
                        return;
                    }

                    if (stalledFor < STALL_TIMEOUT_MS) {
                        return;
                    }

                    if (autoRestartCount >= MAX_AUTO_RESTARTS) {
                        notifyWaitingForNetwork(now);
                        return;
                    }

                    restartWalletKitAutomatically(now);

                },

                15,
                15,
                TimeUnit.SECONDS
        );
    }

    private void scheduleRestartAfterFailure() {

        if (shuttingDown ||
                watchdog == null) {

            return;
        }

        if (autoRestartCount >=
                MAX_AUTO_RESTARTS) {


            runOnUi(() ->
                    view.showToastMessage(
                            text(R.string.wallet_start_failed_detailed)
                    )
            );

            return;
        }

        watchdog.schedule(

                () -> {

                    if (!shuttingDown && !restartInProgress.get()) {
                        restartWalletKitAutomatically(System.currentTimeMillis());
                    }

                },

                10,
                TimeUnit.SECONDS
        );
    }

    private boolean restartWalletKit() {
        return restartWalletKit(false);
    }

    private boolean restartWalletKit(boolean automaticRecovery) {
        if (shuttingDown) {
            return false;
        }

        if (!restartInProgress.compareAndSet(false, true)) {
            return false;
        }

        if (automaticRecovery) {
            synchronized (recoveryLock) {
                consecutiveRecoveryFailures = Math.min(consecutiveRecoveryFailures + 1, 5);
                int exponent = Math.min(consecutiveRecoveryFailures - 1, 4);
                long delay = AUTO_RECOVERY_BASE_DELAY_MS * (1L << exponent);
                delay = Math.min(delay, AUTO_RECOVERY_MAX_DELAY_MS);
                nextAutomaticRecoveryAt = System.currentTimeMillis() + delay;
            }
        }

        autoRestartCount++;

        walletReady = false;
        lastRefreshTotalMs = -1L;
        lastRefreshProcessingMs = -1L;

        downloadFinished = false;
        notifySyncStateChanged();


        // Automatic reconnect is an internal lifecycle transition. Do not surface
        // transient STARTING/RECONNECTING states as Toasts; the existing sync state
        // and progress UI already represent this state without interrupting the user.
        runOnUi(() -> view.displayDownloadContent(true));

        new Thread(() -> {

            try {

                WalletAppKit oldKit;

                synchronized (kitLock) {

                    oldKit =
                            walletAppKit;

                    walletAppKit = null;
                }

                if (oldKit != null) {

                    try {

                        oldKit
                                .stopAsync()
                                .awaitTerminated();

                    } catch (Exception ignored) {
                        // Stopping an already-failed WalletAppKit is best-effort cleanup.
                    }
                }

                if (!shuttingDown) {

                    lastPercent = -1;
                    syncProgressStartBlock = -1;
                    syncProgressTargetBlock = -1;

                    lastChainHeight = -1;
                    noPeerSince = 0L;
                    lastDownloadKickAt = 0L;
                    lastDownloadBlocksSoFar = 0;

                    touchProgress();

                    startWalletKit();
                }

            } finally {
                restartInProgress.set(false);
                if (walletReady) {
                    refresh();
                }
            }

        }, "bitcoinj-reconnect").start();
        return true;
    }

    /**
     * Explicitly re-arms the bitcoinj blockchain download when peers are already
     * connected but the chain height is not moving. WalletAppKit normally performs
     * this step itself; keeping the fallback here makes the sync lifecycle resilient
     * to a late peer handshake and prevents a connected-but-idle Signet engine.
     */
    private boolean requestBlockchainDownload(WalletAppKit kit, boolean force) {
        if (kit == null || shuttingDown) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastDownloadKickAt < DOWNLOAD_KICK_COOLDOWN_MS) {
            return false;
        }

        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) {
                return false;
            }

            int chainHeight = safeChainHeight(kit);
            int peerHeight = safePeerHeight(kit);
            if (peerHeight <= chainHeight) {
                return false;
            }

            Peer downloadPeer = group.getDownloadPeer();
            if (downloadPeer != null) {
                // Re-apply the selected mode to the active download peer.
                // Lite requests filtered blocks; Full Block requests full blocks.
                NetworkConfig.SyncMode syncMode =
                        NetworkConfig.getSyncMode(applicationContext, activeNetwork);
                downloadPeer.setDownloadData(true);
                downloadPeer.setDownloadParameters(syncMode == NetworkConfig.SyncMode.LITE);
            }

            if (downloadPeer != null) {
                // The peer is already connected, so bypass the normal WalletAppKit
                // startup path and explicitly send the chain-download request. The
                // tracker installed on the PeerGroup remains active for progress UI.
                downloadPeer.startBlockChainDownload();
            } else {
                DownloadProgressTracker tracker = activeDownloadListener;
                if (tracker == null) {
                    tracker = new DownloadProgressTracker();
                    activeDownloadListener = tracker;
                }
                // No download peer yet: ask PeerGroup to retry as soon as one connects.
                group.startBlockChainDownload(tracker);
            }

            lastDownloadKickAt = now;
            // A download request is only an attempted recovery action, not evidence
            // that the chain is progressing. Keep the real progress clock unchanged.
            notifySyncStateChanged();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applySyncMode(PeerGroup peerGroup, BitcoinNetwork network,
                               NetworkConfig.SyncMode syncMode) {
        if (peerGroup == null) {
            return;
        }

        boolean lite = syncMode == NetworkConfig.SyncMode.LITE;
        peerGroup.setBloomFilteringEnabled(lite);
        peerGroup.setRequiredServices(
                lite
                        ? VersionMessage.NODE_BLOOM | VersionMessage.NODE_WITNESS
                        : VersionMessage.NODE_WITNESS);
        peerGroup.setDownloadTxDependencies(0);
    }

    private void touchProgress() {
        lastProgressAt = System.currentTimeMillis();
    }

    /**
     * Records genuine sync progress. Merely finding a peer or re-sending a download
     * request does not reset recovery backoff; advancing the chain or receiving real
     * download progress does.
     */
    private void recordActualSyncProgress() {
        touchProgress();
        synchronized (recoveryLock) {
            consecutiveRecoveryFailures = 0;
            nextAutomaticRecoveryAt = 0L;
        }
        autoRestartCount = 0;
    }

    private boolean restartWalletKitAutomatically(long now) {
        if (shuttingDown || startupInProgress || restartInProgress.get()) {
            return false;
        }
        synchronized (recoveryLock) {
            if (nextAutomaticRecoveryAt > now) {
                return false;
            }
        }
        // The restart method atomically claims restartInProgress before it advances
        // the backoff. If another lifecycle event wins the race, this attempt adds
        // no false failure to the recovery sequence.
        return restartWalletKit(true);
    }

    private void notifyWaitingForNetwork(long now) {
        if (now - lastWaitingNetworkNoticeAt < WAITING_NETWORK_NOTICE_COOLDOWN_MS) {
            return;
        }
        lastWaitingNetworkNoticeAt = now;
        runOnUi(() -> view.showToastMessage(text(R.string.sync_waiting_network)));
    }

    private int safeChainHeight(
            WalletAppKit kit) {

        try {

            return kit == null ||
                    kit.chain() == null

                    ? 0

                    : kit.chain()
                            .getBestChainHeight();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePeerHeight(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getMostCommonChainHeight();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePeerCount(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getConnectedPeers()
                            .size();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePendingPeerCount(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getPendingPeers()
                            .size();

        } catch (Exception e) {

            return 0;
        }
    }

    private Throwable findRootCause(
            Throwable throwable) {

        if (throwable == null) {
            return null;
        }

        Throwable current =
                throwable;

        int guard = 0;

        while (
                current.getCause() != null
                        && current.getCause() != current
                        && guard++ < 32
        ) {

            current =
                    current.getCause();
        }

        return current;
    }

    private String buildFailureMessage(
            Exception startException,
            Throwable rootCause) {

        Throwable cause =
                rootCause != null
                        ? rootCause
                        : startException;

        String message =
                LocalizedErrors.resolve(applicationContext, cause);

        if (TextUtils.isEmpty(message)) {

            message =
                    cause.getClass()
                            .getSimpleName();
        }

        return message;
    }

    private String text(int resId, Object... formatArgs) {
        android.content.Context context = view.getActivityContext();
        return formatArgs == null || formatArgs.length == 0
                ? context.getString(resId)
                : context.getString(resId, formatArgs);
    }

    private String safeMessage(
            Exception e) {

        if (e == null) {
            return text(R.string.unknown_error);
        }

        String message =
                LocalizedErrors.resolve(applicationContext, e);

        return TextUtils.isEmpty(message)

                ? e.getClass()
                        .getSimpleName()

                : message;
    }

    @Override
    public void unsubscribe() {

        if (activePresenter == this) {
            activePresenter = null;
        }

        shuttingDown = true;
        WalletSecurity.clearSessionKey();
        walletUpdateListeners.clear();
        syncStateListeners.clear();

        startupInProgress = false;
        walletReady = false;

        stopWatchdog();

        new Thread(() -> {

            WalletAppKit kit;

            synchronized (kitLock) {

                kit =
                        walletAppKit;

                walletAppKit = null;
            }

            try {

                if (kit != null) {

                    kit.stopAsync()
                            .awaitTerminated();
                }

            } catch (Exception ignored) {
                // Shutdown cleanup is best-effort.
            }

        }, "bitcoinj-stop").start();
    }

    private void stopWatchdog() {

        if (watchdog != null) {

            watchdog.shutdownNow();

            watchdog = null;
        }
    }

    @Override
    public void refresh() {

        WalletAppKit kit = walletAppKit;

        if (!walletReady || kit == null || restartInProgress.get()) {
            return;
        }

        boolean startWorker = false;
        synchronized (refreshLock) {
            latestRefreshRequestedAt = System.currentTimeMillis();
            refreshPending = true;
            if (!refreshInProgress) {
                refreshInProgress = true;
                startWorker = true;
            }
        }

        if (startWorker) {
            startRefreshWorker();
        }
    }

    private void startRefreshWorker() {
        new Thread(() -> {
            long lastRefreshAt = 0L;
            boolean restartWorker = false;
            try {
                while (true) {
                    long requestAt;
                    synchronized (refreshLock) {
                        if (!refreshPending) {
                            // Keep refreshInProgress true until finally has
                            // completed the hand-off. Any refresh arriving
                            // after this check will either be observed there
                            // or start its own worker after the flag is cleared.
                            break;
                        }
                        refreshPending = false;
                        requestAt = latestRefreshRequestedAt;
                    }

                    long workerStartedAt = System.currentTimeMillis();
                    long waitMs = REFRESH_DEBOUNCE_MS - (workerStartedAt - lastRefreshAt);
                    if (lastRefreshAt != 0L && waitMs > 0L) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    long processingStartedAt = System.currentTimeMillis();

                    if (!walletReady || restartInProgress.get()) {
                        continue;
                    }

                    try {
                        Wallet currentWallet = getActiveWallet();
                        if (currentWallet == null) {
                            continue;
                        }
                        Context.propagate(Context.getOrCreate(parameters));
                        renderSelectedWallet(currentWallet, requestAt, processingStartedAt);

                        // Keep the home-screen widget on the exact same refresh path
                        // as the wallet screen. The widget reads the same live
                        // bitcoinj Wallet state, so confirmed/unconfirmed balance
                        // changes are reflected without polling or an online API.
                        wallet.widget.BalanceWidgetProvider.requestRefresh(applicationContext);

                        lastRefreshAt = System.currentTimeMillis();
                    } catch (Exception ignored) {
                        long failedAt = System.currentTimeMillis();
                        lastRefreshTotalMs = -1L;
                        lastRefreshProcessingMs = Math.max(0L, failedAt - processingStartedAt);
                        // Do not leave a visible loading indicator running forever if
                        // history/balance mapping fails. Ordinary background refresh
                        // failures remain quiet unless the user is actively waiting.
                        runOnUi(() -> {
                            if (view.isWalletLoadingVisible()) {
                                view.displayWalletLoading(
                                        text(R.string.wallet_status_load_failed), false);
                            }
                        });
                    }
                }
            } finally {
                synchronized (refreshLock) {
                    if (refreshPending) {
                        // A new update arrived while the worker was ending.
                        // Keep ownership of the worker and process the newest
                        // pending state instead of losing that final update.
                        restartWorker = true;
                    } else {
                        refreshInProgress = false;
                    }
                }

                if (restartWorker) {
                    startRefreshWorker();
                }
            }
        }, "bitcoinj-refresh").start();
    }

    private List<TransactionItem> getCachedHistory(
            Wallet wallet, String scope, Function<List<Transaction>, List<TransactionItem>> mapper) {
        if (wallet == null) return Collections.emptyList();

        // Use the same complete transaction set as TransactionMapper, matching
        // the reference app's getTransactions(true) live history source.
        List<Transaction> orderedTransactions =
                TransactionMapper.getTransactionsInDisplayOrder(wallet);
        int count = orderedTransactions.size();
        String newestId = count == 0 ? "" : orderedTransactions.get(0).getTxId().toString();
        int height = wallet.getLastBlockSeenHeight();
        final long generation;
        synchronized (historyCacheLock) {
            Map<String, HistoryCacheEntry> walletEntries = historyCache.get(wallet);
            if (walletEntries != null) {
                HistoryCacheEntry cached = walletEntries.get(scope);
                if (cached != null && cached.transactionCount == count
                        && cached.chainHeight == height
                        && cached.newestTransactionId.equals(newestId)) {
                    return cached.items;
                }
            }
            generation = historyCacheGenerations.getOrDefault(wallet, 0L);
        }

        // Critical: never hold historyCacheLock while walking transactions and
        // inputs/outputs. Wallet event callbacks invalidate this cache on bitcoinj's
        // event thread; holding the lock during mapping can block block processing.
        List<TransactionItem> mapped = mapper.apply(orderedTransactions);
        List<TransactionItem> snapshot = Collections.unmodifiableList(new ArrayList<>(mapped));
        synchronized (historyCacheLock) {
            long currentGeneration = historyCacheGenerations.getOrDefault(wallet, 0L);
            if (currentGeneration == generation) {
                Map<String, HistoryCacheEntry> walletEntries = historyCache.get(wallet);
                if (walletEntries == null) {
                    walletEntries = new HashMap<>();
                    historyCache.put(wallet, walletEntries);
                }
                walletEntries.put(scope, new HistoryCacheEntry(count, newestId, height, snapshot));
            }
        }
        return snapshot;
    }

    /**
     * Publish the balance as an independent live value, like WalletBalanceLiveData
     * in the reference implementation. Transaction history is mapped afterwards,
     * so a large saved transaction set cannot hold the balance behind it.
     */
    private void postBalanceSnapshot(Wallet wallet,
                                     String renderedWatchSelection,
                                     String renderedImportedSelection,
                                     Coin balance, Coin available, Coin pending,
                                     String address, String walletType) {
        final String balanceText = MoneyFormatter.format(applicationContext, balance);
        final String availableText = text(R.string.available_balance,
                MoneyFormatter.format(applicationContext, available));
        final String pendingText = text(R.string.pending_balance,
                MoneyFormatter.format(applicationContext, pending));
        final String selectedAddress = address;
        final String selectedWalletType = walletType;
        runOnUi(() -> {
            if (wallet != getActiveWallet()
                    || !java.util.Objects.equals(renderedWatchSelection,
                    WalletSelection.getSelectedWatchAddress(applicationContext))
                    || !java.util.Objects.equals(renderedImportedSelection,
                    WalletSelection.getSelectedImportedAddress(applicationContext))) {
                return;
            }
            view.displayWalletType(selectedWalletType);
            view.displayMyBalance(balanceText);
            view.displayBalanceState(availableText, pendingText);
            if (!TextUtils.isEmpty(selectedAddress)) {
                view.displayMyAddress(selectedAddress);
            }
        });
    }

    private void renderSelectedWallet(Wallet wallet, long refreshRequestedAt,
                                      long refreshProcessingStartedAt) {
        android.content.Context context = applicationContext;
        // Capture selection before resolution; if preferences change during script
        // resolution/history mapping, the UI callback below rejects this stale snapshot.
        final String renderedWatchSelection = WalletSelection.getSelectedWatchAddress(context);
        final String renderedImportedSelection = WalletSelection.getSelectedImportedAddress(context);
        Script selectedScript = WalletSelection.findSelectedScript(context, wallet);
        Script importedScript = selectedScript == null
                ? WalletSelection.findSelectedImportedScript(context, wallet) : null;

        Coin balance;
        Coin available;
        Coin pending;
        String address;
        String walletType;
        List<TransactionItem> transactions;

        if (selectedScript != null || importedScript != null) {
            Script displayScript = selectedScript != null ? selectedScript : importedScript;
            long confirmedSat = 0L;
            long pendingSat = 0L;
            Iterable<TransactionOutput> selectedOutputs;
            if (selectedScript != null) {
                selectedOutputs = wallet.getWatchedOutputs(false);
            } else {
                selectedOutputs = WalletSelection.selectedImportedUsesWatchedOutputs(context)
                        ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
            }
            for (TransactionOutput output : selectedOutputs) {
                if (!output.isAvailableForSpending()
                        || !displayScript.equals(output.getScriptPubKey())) {
                    continue;
                }
                if (output.getParentTransactionDepthInBlocks() > 0) {
                    confirmedSat += output.getValue().value;
                } else {
                    pendingSat += output.getValue().value;
                }
            }

            available = Coin.valueOf(confirmedSat);
            pending = Coin.valueOf(pendingSat);
            balance = available.add(pending);
            address = WalletSelection.addressForScript(displayScript, parameters);
            if (selectedScript != null && WalletSelection.isRequestNestedScript(
                    context, wallet, selectedScript)) {
                walletType = text(R.string.wallet_type_main) + " • "
                        + WalletAddressType.label(context, WalletAddressType.P2SH_P2WPKH);
            } else if (selectedScript != null) {
                walletType = text(R.string.wallet_type_watch);
            } else {
                String importedType = ImportedWalletStore.getAddressType(context,
                        WalletSelection.getSelectedImportedAddress(context));
                walletType = text(R.string.wallet_type_imported) + " • "
                        + WalletAddressType.label(context, importedType);
            }
            // Match the reference wallet architecture: balance is an independent
            // live value and must reach the UI before transaction-list mapping.
            postBalanceSnapshot(wallet, renderedWatchSelection, renderedImportedSelection,
                    balance, available, pending, address, walletType);
        } else {
            // Keep request-owned Nested SegWit outputs in the main-wallet balance.
            balance = WalletSelection.mainEstimatedBalance(context, wallet);
            available = WalletSelection.mainAvailableBalance(context, wallet);
            pending = balance.subtract(available);
            address = wallet.currentReceiveAddress().toString();
            walletType = text(R.string.wallet_type_main);
            // Ownership metadata affects how watched P2SH outputs are classified even
            // when transaction count/latest tx/block height have not changed. Include
            // the current registered Request and imported-script sets in the history
            // cache key so corrected output ownership is rendered immediately.
            postBalanceSnapshot(wallet, renderedWatchSelection, renderedImportedSelection,
                    balance, available, pending, address, walletType);
        }

        // Build the transaction history only after the current balance snapshot has
        // already been posted. Mapping can traverse the complete wallet transaction
        // graph, but it must not delay the balance display.
        if (selectedScript != null || importedScript != null) {
            Script displayScript = selectedScript != null ? selectedScript : importedScript;
            transactions = getCachedHistory(wallet, "script:" + displayScript.toString(),
                    orderedTransactions -> TransactionMapper.mapForWatchedScript(
                            context, wallet, displayScript, orderedTransactions));
        } else {
            String historyOwnershipKey = "main:request="
                    + WalletSelection.getRequestNestedScripts(context, wallet).hashCode()
                    + ":imported=" + WalletSelection.getImportedScripts(context, wallet).hashCode();
            transactions = getCachedHistory(wallet, historyOwnershipKey,
                    orderedTransactions -> TransactionMapper.mapForMainWallet(
                            context, wallet, orderedTransactions));
        }

        final String balanceText = MoneyFormatter.format(applicationContext, balance);
        final String availableText = text(R.string.available_balance,
                MoneyFormatter.format(applicationContext, available));
        final String pendingText = text(R.string.pending_balance,
                MoneyFormatter.format(applicationContext, pending));
        final String selectedAddress = address;
        final String selectedWalletType = walletType;
        final List<TransactionItem> selectedTransactions = transactions;
        // Measure computation/mapping separately from the time spent waiting for
        // the Android main thread to apply this snapshot.
        lastRefreshProcessingMs = Math.max(0L,
                System.currentTimeMillis() - refreshProcessingStartedAt);

        runOnUi(() -> {
            // A slower render from the previous scope must never overwrite a newer
            // wallet selection or hide its loading indicator. If selection changed
            // while this snapshot was being calculated, request a fresh render.
            if (!java.util.Objects.equals(renderedWatchSelection,
                    WalletSelection.getSelectedWatchAddress(applicationContext))
                    || !java.util.Objects.equals(renderedImportedSelection,
                    WalletSelection.getSelectedImportedAddress(applicationContext))) {
                refresh();
                return;
            }
            view.displayWalletType(selectedWalletType);
            view.displayMyBalance(balanceText);
            view.displayBalanceState(availableText, pendingText);
            if (!TextUtils.isEmpty(selectedAddress)) {
                view.displayMyAddress(selectedAddress);
            }
            view.displayTransactions(selectedTransactions);
            view.displayWalletLoading("", false);
            long appliedAt = System.currentTimeMillis();
            lastRefreshTotalMs = refreshRequestedAt > 0L
                    ? Math.max(0L, appliedAt - refreshRequestedAt) : 0L;
        });
    }

    @Override
    public void restoreWallet(final android.net.Uri backupUri) {
        restoreWallet(backupUri, null);
    }

    /**
     * Restores a wallet backup after the current wallet password has been
     * verified. The authorization key is kept local to this restore operation
     * and is never installed as the persistent WalletSecurity session key.
     */
    public void restoreWallet(
            final android.net.Uri backupUri,
            final AesKey currentWalletAuthorizationKey) {

        if (backupUri == null) {
            runOnUi(() ->
                    view.showToastMessage(text(R.string.file_restore_invalid))
            );
            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            File tempFile =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".restore.tmp"
                    );
            File backupOfCurrent =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".before-restore.wallet"
                    );

            WalletAppKit oldKit;
            boolean restoreNeedsWalletRestart = false;

            try {

                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

                Wallet currentWallet = null;
                synchronized (kitLock) {
                    if (walletAppKit != null && walletReady) {
                        currentWallet = walletAppKit.wallet();
                    }
                }
                if (currentWallet == null && walletFile.exists()) {
                    currentWallet = Wallet.loadFromFile(walletFile);
                }
                captureRestoreSecondaryWalletData(
                        currentWallet,
                        currentWalletAuthorizationKey);
                WalletSecurity.clearSessionKey();

                try (InputStream input =
                             ((android.content.Context) view)
                                     .getContentResolver()
                                     .openInputStream(backupUri);
                     FileOutputStream output =
                             new FileOutputStream(tempFile)) {

                    if (input == null) {
                        throw new IOException(text(R.string.restore_read_failed));
                    }

                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0L;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        total += count;

                        if (total > 64L * 1024L * 1024L) {
                            throw new IOException(text(R.string.restore_too_large));
                        }
                    }

                    output.flush();

                    if (total == 0L) {
                        throw new IOException(text(R.string.restore_empty));
                    }
                }

                Wallet restoredWallet = Wallet.loadFromFile(tempFile);

                if (!restoredWallet.getParams().equals(parameters)) {
                    throw new IOException(
                            text(R.string.restore_network_mismatch)
                    );
                }

                restoredWallet.isConsistentOrThrow();

                stopWatchdog();

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                }

                if (oldKit != null) {
                    restoreNeedsWalletRestart = true;
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception stopError) {
                        throw new IOException(
                                text(R.string.restore_stop_failed),
                                stopError
                        );
                    }
                }

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException(text(R.string.restore_before_delete_failed));
                    }

                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException(text(R.string.restore_current_wallet_preserve_failed));
                    }
                }

                if (!tempFile.renameTo(walletFile)) {
                    if (!walletFile.exists() && backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException(text(R.string.restore_install_failed));
                }

                // Verify the exact wallet file that will be used by WalletAppKit before
                // deleting the safety copy of the previous wallet.
                Wallet installedWallet = Wallet.loadFromFile(walletFile);
                if (!installedWallet.getParams().equals(parameters)) {
                    throw new IOException(text(R.string.restore_network_mismatch));
                }
                installedWallet.isConsistentOrThrow();

                mergeRestoredSecondaryWalletData(
                        installedWallet,
                        pendingRestoreImportedKeys,
                        pendingRestoreWatchedScripts,
                        pendingRestoreSourceEncrypted,
                        pendingRestoreSourceSessionKey);
                // Keep restore-file installation on the same ownership path as normal
                // startup: only Request Nested SegWit scripts need internal watching;
                // main-wallet Legacy/Native SegWit scripts remain normal HD outputs.
                WalletSelection.ensureRequestNestedScripts(applicationContext, installedWallet);
                installedWallet.saveToFile(walletFile);

                if (backupOfCurrent.exists()) {
                    backupOfCurrent.delete();
                }
                clearPendingRestoreSecondaryData();

                autoRestartCount = 0;
                synchronized (recoveryLock) {
                    consecutiveRecoveryFailures = 0;
                    nextAutomaticRecoveryAt = 0L;
                }
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.restore_success)
                        )
                );

                startWalletKit();
                startWatchdog();

            } catch (Exception e) {

                if (tempFile.exists()) {
                    tempFile.delete();
                }

                // If installation happened but verification/startup failed, restore the
                // previous wallet instead of leaving a questionable wallet in place.
                if (backupOfCurrent.exists()) {
                    if (walletFile.exists()) {
                        walletFile.delete();
                    }
                    backupOfCurrent.renameTo(walletFile);
                }
                clearPendingRestoreSecondaryData();

                if (restoreNeedsWalletRestart && !shuttingDown) {
                    autoRestartCount = 0;
                    lastPercent = -1;
                    syncProgressStartBlock = -1;
                    syncProgressTargetBlock = -1;
                    lastChainHeight = -1;
                    downloadFinished = false;
                    startWalletKit();
                    startWatchdog();
                }

                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.restore_failed, safeMessage(e))
                        )
                );
            }

        }, "bitcoinj-wallet-restore").start();
    }

    @Override
    public void restoreWalletFromMnemonic(
            final String mnemonic,
            final String birthday) {

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            File backupOfCurrent =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".before-mnemonic-restore.wallet");
            File chainFile =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".spvchain");

            try {
                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

                List<String> words = new ArrayList<>();
                for (String word : mnemonic.trim().split("\\s+")) {
                    if (!word.isEmpty()) {
                        words.add(word.toLowerCase(java.util.Locale.US));
                    }
                }

                new MnemonicCode().check(words);

                DeterministicSeed seed;
                if (birthday == null || birthday.trim().isEmpty()) {
                    seed = DeterministicSeed.ofMnemonic(words, "");
                } else {
                    Instant creationTime = parseBirthday(birthday);
                    seed = DeterministicSeed.ofMnemonic(words, "", creationTime);
                }

                Wallet currentWallet = null;
                synchronized (kitLock) {
                    if (walletAppKit != null && walletReady) {
                        currentWallet = walletAppKit.wallet();
                    }
                }
                if (currentWallet == null && walletFile.exists()) {
                    currentWallet = Wallet.loadFromFile(walletFile);
                }
                captureRestoreSecondaryWalletData(currentWallet);

                stopWatchdog();
                WalletSecurity.clearSessionKey();

                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                }

                if (oldKit != null) {
                    oldKit.stopAsync().awaitTerminated();
                }

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException(
                                text(R.string.restore_before_delete_failed));
                    }
                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException(
                                text(R.string.restore_current_wallet_preserve_failed));
                    }
                }

                if (chainFile.exists() && !chainFile.delete()) {
                    if (backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException(text(R.string.restore_chain_delete_failed));
                }

                pendingRestoreSeed = seed;
                pendingMnemonicBackupFile = backupOfCurrent;
                autoRestartCount = 0;
                synchronized (recoveryLock) {
                    consecutiveRecoveryFailures = 0;
                    nextAutomaticRecoveryAt = 0L;
                }
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() ->
                        view.showToastMessage(text(R.string.mnemonic_restore_in_progress)));

                startWalletKit();
                startWatchdog();
            } catch (Exception error) {
                pendingRestoreSeed = null;
                pendingMnemonicBackupFile = null;
                if (backupOfCurrent.exists() && !walletFile.exists()) {
                    backupOfCurrent.renameTo(walletFile);
                }
                clearPendingRestoreSecondaryData();
                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.mnemonic_restore_failed, safeMessage(error))));
            }
        }, "bitcoinj-mnemonic-restore").start();
    }

    private void captureRestoreSecondaryWalletData(Wallet source) throws IOException {
        captureRestoreSecondaryWalletData(source, null);
    }

    private void captureRestoreSecondaryWalletData(
            Wallet source,
            AesKey authorizationKey) throws IOException {
        pendingRestoreImportedKeys = new ArrayList<>();
        pendingRestoreWatchedScripts = new ArrayList<>();
        pendingRestoreSourceSessionKey = null;
        pendingRestoreSourceEncrypted = false;
        if (source == null) return;

        pendingRestoreSourceEncrypted = WalletSecurity.isEncrypted(source);
        if (pendingRestoreSourceEncrypted) {
            if (authorizationKey != null && source.checkAESKey(authorizationKey)) {
                pendingRestoreSourceSessionKey = authorizationKey;
            } else if (WalletSecurity.isSessionValid(source)) {
                pendingRestoreSourceSessionKey = WalletSecurity.getSessionKey();
            }
        }
        pendingRestoreImportedKeys.addAll(source.getImportedKeys());

        // Preserve explicitly watched external addresses, but do not copy Request
        // scripts whose output scripts are derived from this wallet's own HD receive
        // keys. Older builds stored those scripts in watchedScripts, so blindly merging
        // the list during restore turned every Request address into a Watch-only entry.
        java.util.Set<org.bitcoinj.script.Script> mainWalletReceiveScripts =
                WalletSelection.getMainWalletReceiveScripts(source);
        for (org.bitcoinj.script.Script script : source.getWatchedScripts()) {
            if (!mainWalletReceiveScripts.contains(script)) {
                pendingRestoreWatchedScripts.add(script);
            }
        }
    }

    private void mergeRestoredSecondaryWalletData(
            Wallet target,
            List<ECKey> importedKeys,
            List<org.bitcoinj.script.Script> watchedScripts,
            boolean sourceEncrypted,
            AesKey sourceSessionKey) throws IOException {
        if (target == null) throw new IOException(text(R.string.restored_wallet_unavailable));

        if (watchedScripts != null && !watchedScripts.isEmpty()) {
            List<org.bitcoinj.script.Script> existing = target.getWatchedScripts();
            List<org.bitcoinj.script.Script> missing = new ArrayList<>();
            for (org.bitcoinj.script.Script script : watchedScripts) {
                if (!existing.contains(script)) missing.add(script);
            }
            if (!missing.isEmpty()) target.addWatchedScripts(missing);
        }

        if (importedKeys == null || importedKeys.isEmpty()) return;

        List<ECKey> keysToImport = new ArrayList<>();
        if (sourceEncrypted && !target.isEncrypted()) {
            if (sourceSessionKey == null) {
                throw new IOException(text(R.string.restore_imported_wallet_unlock_current));
            }
            for (ECKey key : importedKeys) {
                if (key.isEncrypted()) {
                    try {
                        keysToImport.add(key.decrypt(key.getKeyCrypter(), sourceSessionKey));
                    } catch (Exception error) {
                        throw new IOException(text(R.string.restore_imported_wallet_preserve_failed), error);
                    }
                } else {
                    keysToImport.add(key);
                }
            }
        } else {
            keysToImport.addAll(importedKeys);
        }

        List<ECKey> missingKeys = new ArrayList<>();
        for (ECKey key : keysToImport) {
            if (target.findKeyFromPubKey(key.getPubKey()) == null) missingKeys.add(key);
        }
        if (missingKeys.isEmpty()) return;

        try {
            if (target.isEncrypted()) {
                if (sourceEncrypted && missingKeys.get(0).isEncrypted()) {
                    for (ECKey key : missingKeys) target.importKey(key);
                } else {
                    AesKey targetSessionKey = WalletSecurity.getSessionKey();
                    if (targetSessionKey == null) {
                        throw new IOException(text(R.string.restore_imported_wallet_unlock_restored));
                    }
                    target.importKeysAndEncrypt(missingKeys, targetSessionKey);
                }
            } else {
                target.importKeys(missingKeys);
            }
        } catch (Exception error) {
            throw new IOException(text(R.string.restore_imported_wallets_preserve_failed), error);
        }
    }

    private void clearPendingRestoreSecondaryData() {
        pendingRestoreImportedKeys = new ArrayList<>();
        pendingRestoreWatchedScripts = new ArrayList<>();
        pendingRestoreSourceSessionKey = null;
        pendingRestoreSourceEncrypted = false;
    }

    private Instant parseBirthday(String birthday) {
        try {
            return java.time.LocalDate.parse(birthday.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    text(R.string.invalid_wallet_birthday),
                    e
            );
        }
    }

    public void rescanWatchedAddresses(
            final Instant scanFrom,
            final java.util.function.Consumer<Exception> finished) {

        new Thread(() -> {
            WalletAppKit oldKit = null;
            Exception failure = null;
            File safetyCopy = null;

            // A watch-only rescan replaces the running WalletAppKit. Serialize it with
            // the normal watchdog/reconnect path so the kit cannot be restarted twice
            // while the wallet file and SPV chain are being replaced.
            if (!restartInProgress.compareAndSet(false, true)) {
                failure = new IOException(text(R.string.watch_rescan_already_restarting));
                if (finished != null) {
                    final Exception result = failure;
                    runOnUi(() -> finished.accept(result));
                }
                return;
            }

            try {
                Context.propagate(Context.getOrCreate(parameters));

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                    downloadFinished = false;
                    lastPercent = -1;
                    syncProgressStartBlock = -1;
                    syncProgressTargetBlock = -1;
                    lastChainHeight = -1;
                }

                if (oldKit == null) {
                    throw new IOException(text(R.string.watch_rescan_wallet_not_running));
                }

                // WalletAppKit.wallet() is only accessible while the kit is STARTING/RUNNING.
                // Capture the wallet and its watched scripts BEFORE stopping the kit. Calling
                // oldKit.wallet() after awaitTerminated() throws:
                // "cannot call until startup is complete".
                Wallet wallet = oldKit.wallet();
                List<org.bitcoinj.script.Script> oldScripts = wallet.getWatchedScripts();
                safetyCopy = createWalletSafetyCopy("watch-rescan");

                // Stop the running kit BEFORE rewriting the wallet. This prevents its autosave/shutdown
                // path from writing the pre-rescan state back over our reset wallet.
                oldKit.stopAsync().awaitTerminated();
                List<org.bitcoinj.script.Script> rescannedScripts = new ArrayList<>();
                for (org.bitcoinj.script.Script script : oldScripts) {
                    // Preserve the exact output script bytes. Only replace the creation timestamp used by
                    // bitcoinj for fast-catchup/scanning. This avoids converting a script through Address and
                    // accidentally changing an uncommon script form.
                    rescannedScripts.add(
                            org.bitcoinj.script.Script.parse(script.program(), scanFrom));
                }

                wallet.removeWatchedScripts(oldScripts);
                wallet.addWatchedScripts(rescannedScripts);
                wallet.reset();
                wallet.saveToFile(walletFile);

                File chainFile =
                        new File(
                                walletDataDir,
                                Constants.WALLET_NAME + ".spvchain");
                if (chainFile.exists() && !chainFile.delete()) {
                    throw new IOException(text(R.string.watch_rescan_chain_reset_failed));
                }

                shuttingDown = false;
                autoRestartCount = 0;
                startWalletKit();
            } catch (Exception error) {
                failure = error;
                if (safetyCopy != null && safetyCopy.exists()) {
                    try {
                        copyFile(safetyCopy, walletFile);
                    } catch (Exception ignored) {
                        // The original wallet file remains untouched if recovery fails.
                    }
                }

                synchronized (kitLock) {
                    walletAppKit = null;
                    walletReady = false;
                }
                if (!shuttingDown) {
                    try {
                        startWalletKit();
                    } catch (Exception ignored) {
                        // The completion callback reports the restart failure to the caller.
                    }
                }
            } finally {
                restartInProgress.set(false);
                final Exception result = failure;
                if (finished != null) {
                    runOnUi(() -> finished.accept(result));
                }
            }
        }, "bitcoinj-watch-rescan").start();
    }

    private File createWalletSafetyCopy(String reason) throws IOException {
        if (!walletFile.exists()) {
            throw new IOException(text(R.string.wallet_file_missing));
        }
        File dir = new File(walletDataDir, "backup-safety");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException(text(R.string.wallet_safety_directory_failed));
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        File target = new File(dir, Constants.WALLET_NAME + "-" + reason + "-" + stamp + ".wallet");
        copyFile(walletFile, target);
        return target;
    }

    private void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    public static void saveActiveWalletToFile(File target) throws IOException {
        if (target == null) {
            throw new LocalizedErrors.IOException(R.string.error_wallet_reference_missing);
        }
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null) {
            throw new IOException("wallet_not_ready");
        }
        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (presenter.shuttingDown || !presenter.walletReady
                    || kit == null || !kit.isRunning()) {
                throw new IOException("wallet_not_ready");
            }
            try {
                kit.wallet().saveToFile(target);
            } catch (IllegalStateException error) {
                throw new IOException("wallet_not_ready", error);
            }
        }
    }

    public void saveWalletNow() throws IOException {
        saveActiveWalletToFile(walletFile);
    }

    /**
     * Explicitly starts a whole-wallet sweep using an existing confirmed incoming transaction
     * as the confirmation gate. In wallet_sweep mode, the coin selector includes all spendable
     * wallet outputs, not just outputs from the trigger transaction.
     */
    public static boolean requestSweepCurrentWalletBalance() {
        MainActivityPresenter presenter = activePresenter;
        Wallet wallet = getActiveWallet();
        if (presenter == null || wallet == null) return false;
        android.content.SharedPreferences prefs = presenter.applicationContext.getSharedPreferences(
                forwardingPreferencesName(presenter.applicationContext), android.content.Context.MODE_PRIVATE);
        if (!prefs.getBoolean(FORWARDING_ENABLED, false)
                || !"wallet_sweep".equals(prefs.getString(FORWARDING_MODE, "sweep"))) return false;
        if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) return false;
        Transaction trigger = null;
        int bestDepth = -1;
        try {
            for (Transaction tx : wallet.getTransactions(true)) {
                if (tx == null || forwardingTransactions.contains(tx.getTxId().toString())) continue;
                Coin received = tx.getValueSentToMe(wallet);
                if (received == null || !received.isPositive()) continue;
                int depth = tx.getConfidence() == null ? 0 : Math.max(0, tx.getConfidence().getDepthInBlocks());
                if (depth >= Math.max(1, prefs.getInt(FORWARDING_CONFIRMATIONS, 1)) && depth > bestDepth) {
                    trigger = tx;
                    bestDepth = depth;
                }
            }
        } catch (RuntimeException error) {
            return false;
        }
        if (trigger == null) return false;
        presenter.maybeForwardIncoming(wallet, trigger);
        return true;
    }

    /**
     * Optional automatic forwarding, using the already-running wallet and peer group.
     * Spending is restricted to the triggering incoming transaction except in explicit wallet_sweep mode.
     */
    private void maybeForwardIncoming(Wallet wallet, Transaction incomingTx) {
        android.content.SharedPreferences prefs = applicationContext.getSharedPreferences(
                forwardingPreferencesName(applicationContext), android.content.Context.MODE_PRIVATE);
        if (!prefs.getBoolean(FORWARDING_ENABLED, false) || wallet == null || incomingTx == null) {
            return;
        }
        String incomingTxId = incomingTx.getTxId().toString();
        // Do not treat any previously recorded forwarding output (including change-related
        // wallet events after app restart) as a new external receipt.
        if (incomingTx.getPurpose() != Transaction.Purpose.UNKNOWN
                || forwardingTransactions.contains(incomingTxId)
                || isPreviouslyForwardedOutput(prefs, incomingTxId)) {
            return;
        }
        String existingStage = findForwardingLedgerStage(prefs, incomingTxId);
        // Do not restart terminal/in-flight entries on duplicate wallet notifications.
        // A waiting entry is allowed through for explicit recovery/retry after restart.
        if ("failed".equals(existingStage) || "preparing".equals(existingStage)
                || "broadcasting".equals(existingStage) || "relayed".equals(existingStage)
                || "confirmed".equals(existingStage) || "disabled".equals(existingStage)) {
            return;
        }
        if (!forwardingTransactions.add(incomingTxId)) {
            return;
        }
        final BitcoinNetwork forwardingNetwork = wallet.network();

        Coin received;
        try {
            received = incomingTx.getValueSentToMe(wallet);
        } catch (RuntimeException error) {
            synchronized (FORWARDING_LEDGER_LOCK) {
                prefs.edit()
                        .putString(FORWARDING_LAST_STAGE, "failed")
                        .putString(FORWARDING_LAST_INCOMING_TX, incomingTxId)
                        .remove(FORWARDING_LAST_OUTGOING_TX)
                        .remove(FORWARDING_LAST_FEE_SATS)
                        .remove(FORWARDING_LAST_AMOUNT_SATS)
                        .remove(FORWARDING_LAST_RATE_SAT_VB)
                        .remove(FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB)
                        .remove(FORWARDING_LAST_VSIZE)
                        .putLong(FORWARDING_LAST_RECEIVED_SATS, 0L)
                        .putString(FORWARDING_STATUS,
                                applicationContext.getString(R.string.forwarding_status_failed,
                                        error.getClass().getSimpleName(), incomingTxId))
                        .apply();
                persistForwardingActivitySnapshot(prefs, incomingTxId);
            }
            forwardingTransactions.remove(incomingTxId);
            return;
        }
        if (received == null || !received.isPositive()) {
            forwardingTransactions.remove(incomingTxId);
            return;
        }

        int requiredConfirmations = Math.max(1, Math.min(100,
                prefs.getInt(FORWARDING_CONFIRMATIONS, 1)));
        int initialDepth = 0;
        try {
            if (incomingTx.getConfidence() != null) {
                initialDepth = Math.max(0, incomingTx.getConfidence().getDepthInBlocks());
            }
        } catch (RuntimeException ignored) { }
        synchronized (FORWARDING_LEDGER_LOCK) {
            prefs.edit()
                    .putString(FORWARDING_LAST_STAGE, "waiting")
                    .putString(FORWARDING_LAST_INCOMING_TX, incomingTxId)
                    .remove(FORWARDING_LAST_OUTGOING_TX)
                    .putLong(FORWARDING_LAST_RECEIVED_SATS, received.value)
                    .remove(FORWARDING_LAST_FEE_SATS)
                    .remove(FORWARDING_LAST_AMOUNT_SATS)
                    .remove(FORWARDING_LAST_RATE_SAT_VB)
                    .remove(FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB)
                    .remove(FORWARDING_LAST_VSIZE)
                    .putInt(FORWARDING_LAST_CURRENT_CONFIRMATIONS, Math.min(initialDepth, requiredConfirmations))
                    .putInt(FORWARDING_LAST_REQUIRED_CONFIRMATIONS, requiredConfirmations)
                    .putInt(FORWARDING_LAST_OUTGOING_CONFIRMATIONS, -1)
                    .putString(FORWARDING_STATUS,
                            applicationContext.getString(R.string.forwarding_status_waiting_confirmation,
                                    incomingTxId, requiredConfirmations))
                    .apply();
            persistForwardingActivitySnapshot(prefs, incomingTxId);
        }

        // React immediately to bitcoinj confidence changes. The scheduled task below remains
        // as a recovery fallback in case a confidence event is missed during wallet lifecycle changes.
        final org.bitcoinj.core.listeners.TransactionConfidenceEventListener incomingConfidenceListener =
                (changedWallet, changedTx) -> {
                    if (changedWallet != wallet || changedTx == null
                            || !incomingTxId.equals(changedTx.getTxId().toString())) return;
                    String stage = findForwardingLedgerStage(prefs, incomingTxId);
                    if (!"waiting".equals(stage)
                            || !prefs.getBoolean(FORWARDING_ENABLED, false)) return;
                    try {
                        int depth = changedTx.getConfidence() == null ? 0
                                : Math.max(0, changedTx.getConfidence().getDepthInBlocks());
                        int current = Math.min(depth, requiredConfirmations);
                        org.json.JSONObject fields = forwardingFields("currentConfirmations", current);
                        updateForwardingLedgerEntry(prefs, incomingTxId, fields);
                        // Use the same wallet-history refresh path as the main-screen transaction summary.
                        invalidateHistoryCache(changedWallet);
                        refresh();
                        notifyWalletUpdated();
                    } catch (RuntimeException ignored) { }
                };
        wallet.addTransactionConfidenceEventListener(FORWARDING_STATUS_EXECUTOR, incomingConfidenceListener);

        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> statusUpdaterRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> statusUpdater = FORWARDING_STATUS_EXECUTOR.scheduleAtFixedRate(() -> {
            String stage = findForwardingLedgerStage(prefs, incomingTxId);
            if (!"waiting".equals(stage)
                    || !prefs.getBoolean(FORWARDING_ENABLED, false)) {
                java.util.concurrent.ScheduledFuture<?> updater = statusUpdaterRef.get();
                if (updater != null) updater.cancel(false);
                return;
            }
            try {
                int depth = incomingTx.getConfidence() == null ? 0
                        : Math.max(0, incomingTx.getConfidence().getDepthInBlocks());
                int currentConfirmations = Math.min(depth, requiredConfirmations);
                org.json.JSONObject fields = forwardingFields("currentConfirmations", currentConfirmations);
                org.json.JSONObject entry = getForwardingLedgerEntry(prefs, incomingTxId);
                if (entry != null && entry.optInt("currentConfirmations", -1) != currentConfirmations) {
                    updateForwardingLedgerEntry(prefs, incomingTxId, fields);
                }
            } catch (RuntimeException ignored) { }
        }, 1L, 5L, TimeUnit.SECONDS);
        statusUpdaterRef.set(statusUpdater);

        wallet.waitForConfirmations(incomingTx, requiredConfirmations)
                .thenCompose(confidence -> CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!prefs.getBoolean(FORWARDING_ENABLED, false)) {
                            throw new IllegalStateException("forwarding_disabled");
                        }
                        if (getActiveWallet() != wallet) {
                            throw new IllegalStateException("wallet_not_ready");
                        }
                        if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
                            throw new IllegalStateException("wallet_locked");
                        }

                        String destinationText = prefs.getString(FORWARDING_ADDRESS, "").trim();
                        Address destination;
                        BitcoinNetwork activeNetwork = NetworkConfig.get(applicationContext);
                        if (!activeNetwork.equals(wallet.network())) {
                            throw new IllegalStateException("wallet_not_ready");
                        }
                        try {
                            destination = AddressParser.getDefault().parseAddress(destinationText);
                            activeNetwork.checkAddress(destination);
                        } catch (Exception addressError) {
                            throw new IllegalArgumentException("forwarding_invalid_destination_network", addressError);
                        }
                        if (wallet.isAddressMine(destination)) {
                            throw new IllegalArgumentException("forwarding_destination_is_wallet_owned");
                        }

                        String mode = prefs.getString(FORWARDING_MODE, "sweep");
                        long fixedAmountSats = prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L);
                        int feeRateSatVb = Math.max(1, Math.min(10,
                                prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)));
                        SendRequest request;
                        Coin expectedForwardAmount = null;
                        if ("fixed".equals(mode)) {
                            if (fixedAmountSats <= 0L) {
                                throw new IllegalArgumentException("forwarding_invalid_fixed_amount");
                            }
                            expectedForwardAmount = Coin.valueOf(fixedAmountSats);
                            request = SendRequest.to(destination, expectedForwardAmount);
                        } else {
                            request = SendRequest.emptyWallet(destination);
                        }
                        final Coin selectedFixedAmount = expectedForwardAmount;
                        // Standard modes spend only outputs from this incoming transaction.
                        // The explicit wallet_sweep mode intentionally includes all spendable
                        // wallet UTXOs, including older confirmed funds, as requested by the user.
                        if (!"wallet_sweep".equals(mode)) {
                            request.coinSelector = CoinSelector.fromPredicate(output ->
                                    Objects.equals(output.getParentTransactionHash(), incomingTx.getTxId()));
                        }
                        // Match the ordinary Send screen's fee policy: user-selected sat/vB,
                        // expressed in sat/kB, and bitcoinj's minimum-required-fee enforcement.
                        request.setFeePerVkb(Coin.valueOf(feeRateSatVb * 1000L));
                        request.ensureMinRequiredFee = true;
                        request.aesKey = WalletSecurity.getSessionKey();

                        updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                "stage", "preparing",
                                "currentConfirmations", requiredConfirmations,
                                "status", applicationContext.getString(R.string.forwarding_status_preparing,
                                        incomingTxId)));
                        TransactionBroadcast broadcast = prepareCommitAndBroadcastForwarding(
                                wallet, request, transaction -> {
                                    // Prevent the transaction's own change output from triggering
                                    // another forwarding task when bitcoinj emits wallet events.
                                    forwardingTransactions.add(transaction.getTxId().toString());
                                    Coin fee = transaction.getFee();
                                    if (fee == null || fee.value < 0L) {
                                        throw new IllegalStateException("forwarding_fee_unavailable");
                                    }
                                    long forwardedSats;
                                    if (selectedFixedAmount != null) {
                                        forwardedSats = selectedFixedAmount.value;
                                    } else {
                                        if (transaction.getOutputs().isEmpty()) {
                                            throw new IllegalStateException("forwarding_output_unavailable");
                                        }
                                        forwardedSats = transaction.getOutput(0).getValue().value;
                                    }
                                    if (forwardedSats <= 0L) {
                                        throw new IllegalStateException("forwarding_fee_exceeds_amount");
                                    }
                                    if (!prefs.getBoolean(FORWARDING_ENABLED, false)) {
                                        throw new IllegalStateException("forwarding_disabled");
                                    }
                                    int vsize = transaction.getVsize();
                                    int effectiveRate = vsize > 0
                                            ? (int) Math.min(Integer.MAX_VALUE, (fee.value + vsize - 1L) / vsize)
                                            : feeRateSatVb;
                                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                            "fee", fee.value,
                                            "amount", forwardedSats,
                                            "rate", feeRateSatVb,
                                            "effectiveRate", effectiveRate,
                                            "vsize", vsize,
                                            "outgoing", transaction.getTxId().toString(),
                                            "stage", "broadcasting",
                                            "status", applicationContext.getString(
                                                    R.string.forwarding_status_broadcasting, incomingTxId)));
                                });
                        return broadcast;
                    } catch (Exception error) {
                        throw new CompletionException(error);
                    }
                }, FORWARDING_EXECUTOR).thenCompose(TransactionBroadcast::awaitRelayed))
                .whenComplete((broadcast, error) -> {
                    if (error != null) {
                        Throwable cause = error;
                        while ((cause instanceof CompletionException
                                || cause instanceof java.util.concurrent.ExecutionException)
                                && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (isForwardingWalletNotReady(cause)) {
                            // The kit can be temporarily unavailable during a restart or network
                            // switch. Preserve this receipt as pending and retry on its original
                            // network rather than permanently recording a failure.
                            String pausedStatus = applicationContext.getString(
                                    R.string.forwarding_status_paused_wallet_not_ready, incomingTxId);
                            updateForwardingLedgerStage(prefs, incomingTxId, "waiting", pausedStatus);
                            forwardingTransactions.remove(incomingTxId);
                            scheduleForwardingRetry(prefs, incomingTxId, forwardingNetwork, 0);
                        } else {
                            int reasonRes = forwardingFailureString(cause);
                            String failureStatus = applicationContext.getString(reasonRes,
                                    cause.getClass().getSimpleName(), incomingTxId);
                            updateForwardingLedgerStage(prefs, incomingTxId, "failed", failureStatus);
                            forwardingTransactions.remove(incomingTxId);
                        }
                    } else {
                        updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                "stage", "relayed",
                                "outgoing", broadcast.transaction().getTxId().toString(),
                                "outgoingConfirmations", 0,
                                "status", applicationContext.getString(R.string.forwarding_status_forwarded,
                                        incomingTxId, broadcast.transaction().getTxId().toString())));
                        watchForwardedTransactionConfirmation(wallet, prefs, incomingTxId,
                                broadcast.transaction());
                    }
                    statusUpdater.cancel(false);
                    wallet.removeTransactionConfidenceEventListener(incomingConfidenceListener);
                });
    }

    private boolean isForwardingWalletNotReady(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if ("wallet_not_ready".equals(current.getMessage())) return true;
        }
        return false;
    }

    /** Update one transaction's ledger row without allowing an older async task to overwrite another row. */
    private void updateForwardingLedgerStage(android.content.SharedPreferences prefs,
            String incomingTxId, String stage, String status) {
        if (incomingTxId == null || incomingTxId.isEmpty()) return;
        updateForwardingLedgerEntry(prefs, incomingTxId,
                forwardingFields("stage", stage, "status", status));
    }

    /** Retry a temporarily deferred receipt when its own network and wallet are active again. */
    private void scheduleForwardingRetry(android.content.SharedPreferences prefs, String incomingTxId,
            BitcoinNetwork expectedNetwork, int attempt) {
        final int maxAttempts = 60; // Five minutes; pending entries are also recovered on wallet startup.
        if (attempt >= maxAttempts || prefs == null || incomingTxId == null || expectedNetwork == null) return;
        FORWARDING_STATUS_EXECUTOR.schedule(() -> {
            if (!prefs.getBoolean(FORWARDING_ENABLED, false)
                    || !"waiting".equals(findForwardingLedgerStage(prefs, incomingTxId))) return;
            if (!expectedNetwork.equals(NetworkConfig.get(applicationContext))) {
                scheduleForwardingRetry(prefs, incomingTxId, expectedNetwork, attempt + 1);
                return;
            }
            Wallet currentWallet = getActiveWallet();
            if (currentWallet == null || !expectedNetwork.equals(currentWallet.network())) {
                scheduleForwardingRetry(prefs, incomingTxId, expectedNetwork, attempt + 1);
                return;
            }
            try {
                Transaction pending = currentWallet.getTransaction(
                        org.bitcoinj.base.Sha256Hash.wrap(incomingTxId));
                if (pending == null) {
                    scheduleForwardingRetry(prefs, incomingTxId, expectedNetwork, attempt + 1);
                    return;
                }
                maybeForwardIncoming(currentWallet, pending);
            } catch (RuntimeException ignored) {
                scheduleForwardingRetry(prefs, incomingTxId, expectedNetwork, attempt + 1);
            }
        }, 5L, TimeUnit.SECONDS);
    }

    private String findForwardingLedgerStage(android.content.SharedPreferences prefs, String incomingTxId) {
        if (incomingTxId.equals(prefs.getString(FORWARDING_LAST_INCOMING_TX, ""))) {
            return prefs.getString(FORWARDING_LAST_STAGE, "");
        }
        try {
            org.json.JSONArray ledger = new org.json.JSONArray(
                    prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
            for (int i = 0; i < ledger.length(); i++) {
                org.json.JSONObject entry = ledger.optJSONObject(i);
                if (entry != null && incomingTxId.equals(entry.optString("incoming", ""))) {
                    return entry.optString("stage", "");
                }
            }
        } catch (org.json.JSONException ignored) { }
        return "";
    }

    private void watchForwardedTransactionConfirmation(Wallet expectedWallet,
            android.content.SharedPreferences prefs, String incomingTxId, Transaction forwardedTransaction) {
        if (expectedWallet == null || forwardedTransaction == null) return;
        String outgoingTxId = forwardedTransaction.getTxId().toString();
        final org.bitcoinj.core.listeners.TransactionConfidenceEventListener outgoingConfidenceListener =
                (changedWallet, changedTx) -> {
                    if (changedWallet != expectedWallet || changedTx == null
                            || !outgoingTxId.equals(changedTx.getTxId().toString())) return;
                    if (!"relayed".equals(findForwardingLedgerStage(prefs, incomingTxId))
                            || !outgoingTxId.equals(findForwardingLedgerOutgoing(prefs, incomingTxId))) return;
                    try {
                        int depth = changedTx.getConfidence() == null ? 0
                                : Math.max(0, changedTx.getConfidence().getDepthInBlocks());
                        int confirmations = depth > 0 ? 1 : 0;
                        org.json.JSONObject fields = confirmations >= 1
                                ? forwardingFields("outgoingConfirmations", confirmations,
                                        "stage", "confirmed",
                                        "status", applicationContext.getString(
                                                R.string.forwarding_status_confirmed, outgoingTxId))
                                : forwardingFields("outgoingConfirmations", confirmations);
                        updateForwardingLedgerEntry(prefs, incomingTxId, fields);
                        invalidateHistoryCache(changedWallet);
                        refresh();
                        notifyWalletUpdated();
                    } catch (RuntimeException ignored) { }
                };
        expectedWallet.addTransactionConfidenceEventListener(FORWARDING_STATUS_EXECUTOR, outgoingConfidenceListener);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> watcherRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> watcher = FORWARDING_STATUS_EXECUTOR.scheduleAtFixedRate(() -> {
            String stage = findForwardingLedgerStage(prefs, incomingTxId);
            if (!"relayed".equals(stage)
                    || !outgoingTxId.equals(findForwardingLedgerOutgoing(prefs, incomingTxId))) {
                java.util.concurrent.ScheduledFuture<?> task = watcherRef.get();
                if (task != null) task.cancel(false);
                expectedWallet.removeTransactionConfidenceEventListener(outgoingConfidenceListener);
                return;
            }
            Wallet liveWallet = getActiveWallet();
            if (liveWallet != expectedWallet) {
                java.util.concurrent.ScheduledFuture<?> task = watcherRef.get();
                if (task != null) task.cancel(false);
                expectedWallet.removeTransactionConfidenceEventListener(outgoingConfidenceListener);
                return;
            }
            try {
                Transaction tracked = liveWallet.getTransaction(
                        org.bitcoinj.base.Sha256Hash.wrap(outgoingTxId));
                if (tracked == null) tracked = forwardedTransaction;
                int depth = tracked.getConfidence() == null ? 0
                        : Math.max(0, tracked.getConfidence().getDepthInBlocks());
                int confirmations = depth > 0 ? 1 : 0;
                org.json.JSONObject fields = confirmations >= 1
                        ? forwardingFields("outgoingConfirmations", confirmations,
                                "stage", "confirmed",
                                "status", applicationContext.getString(
                                        R.string.forwarding_status_confirmed, outgoingTxId))
                        : forwardingFields("outgoingConfirmations", confirmations);
                updateForwardingLedgerEntry(prefs, incomingTxId, fields);
                if (confirmations >= 1) {
                    java.util.concurrent.ScheduledFuture<?> task = watcherRef.get();
                    if (task != null) task.cancel(false);
                    expectedWallet.removeTransactionConfidenceEventListener(outgoingConfidenceListener);
                    invalidateHistoryCache(liveWallet);
                    refresh();
                    notifyWalletUpdated();
                }
            } catch (RuntimeException ignored) { }
        }, 3L, 15L, TimeUnit.SECONDS);
        watcherRef.set(watcher);
    }

    /** Persist or update a bounded ledger row. Snapshot fields are read from LAST_* preferences
     * only when this is the currently selected summary transaction; old async tasks update their
     * own existing ledger row instead of copying another transaction's global state. */
    private static final String FORWARDING_ACTIVITY_LEDGER = "activity_ledger_json";
    private static final Object FORWARDING_LEDGER_LOCK = new Object();

    private org.json.JSONObject forwardingFields(Object... pairs) {
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                result.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (org.json.JSONException ignored) { }
        return result;
    }

    private org.json.JSONObject getForwardingLedgerEntry(android.content.SharedPreferences prefs,
            String incomingTxId) {
        if (prefs == null || incomingTxId == null) return null;
        try {
            org.json.JSONArray ledger = new org.json.JSONArray(prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
            for (int i = 0; i < ledger.length(); i++) {
                org.json.JSONObject row = ledger.optJSONObject(i);
                if (row != null && incomingTxId.equals(row.optString("incoming", ""))) {
                    return new org.json.JSONObject(row.toString());
                }
            }
        } catch (org.json.JSONException ignored) { }
        return null;
    }

    private String findForwardingLedgerOutgoing(android.content.SharedPreferences prefs, String incomingTxId) {
        org.json.JSONObject row = getForwardingLedgerEntry(prefs, incomingTxId);
        return row == null ? "" : row.optString("outgoing", "");
    }

    private boolean isPreviouslyForwardedOutput(android.content.SharedPreferences prefs, String txId) {
        if (prefs == null || txId == null || txId.isEmpty()) return false;
        if (txId.equals(prefs.getString(FORWARDING_LAST_OUTGOING_TX, ""))) return true;
        try {
            org.json.JSONArray ledger = new org.json.JSONArray(prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
            for (int i = 0; i < ledger.length(); i++) {
                org.json.JSONObject row = ledger.optJSONObject(i);
                if (row != null && txId.equals(row.optString("outgoing", ""))) return true;
            }
        } catch (org.json.JSONException ignored) { }
        return false;
    }

    private void persistForwardingActivitySnapshot(android.content.SharedPreferences prefs) {
        if (prefs == null) return;
        persistForwardingActivitySnapshot(prefs, prefs.getString(FORWARDING_LAST_INCOMING_TX, ""));
    }

    private void persistForwardingActivitySnapshot(android.content.SharedPreferences prefs, String incomingTxId) {
        updateForwardingLedgerEntry(prefs, incomingTxId, new org.json.JSONObject());
    }

    private void updateForwardingLedgerEntry(android.content.SharedPreferences prefs, String incomingTxId,
            org.json.JSONObject changes) {
        if (prefs == null || incomingTxId == null || incomingTxId.isEmpty()) return;
        if (changes == null) changes = new org.json.JSONObject();
        synchronized (FORWARDING_LEDGER_LOCK) {
            try {
                boolean latest = incomingTxId.equals(prefs.getString(FORWARDING_LAST_INCOMING_TX, ""));
                if (latest) {
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    if (changes.has("stage")) editor.putString(FORWARDING_LAST_STAGE, changes.optString("stage", ""));
                    if (changes.has("status")) editor.putString(FORWARDING_STATUS, changes.optString("status", ""));
                    if (changes.has("outgoing")) editor.putString(FORWARDING_LAST_OUTGOING_TX, changes.optString("outgoing", ""));
                    if (changes.has("received")) editor.putLong(FORWARDING_LAST_RECEIVED_SATS, changes.optLong("received", 0L));
                    if (changes.has("amount")) editor.putLong(FORWARDING_LAST_AMOUNT_SATS, changes.optLong("amount", 0L));
                    if (changes.has("fee")) editor.putLong(FORWARDING_LAST_FEE_SATS, changes.optLong("fee", 0L));
                    if (changes.has("rate")) editor.putInt(FORWARDING_LAST_RATE_SAT_VB, changes.optInt("rate", 0));
                    if (changes.has("effectiveRate")) editor.putInt(FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB, changes.optInt("effectiveRate", 0));
                    if (changes.has("vsize")) editor.putInt(FORWARDING_LAST_VSIZE, changes.optInt("vsize", 0));
                    if (changes.has("currentConfirmations")) editor.putInt(FORWARDING_LAST_CURRENT_CONFIRMATIONS,
                            changes.optInt("currentConfirmations", 0));
                    if (changes.has("requiredConfirmations")) editor.putInt(FORWARDING_LAST_REQUIRED_CONFIRMATIONS,
                            changes.optInt("requiredConfirmations", 1));
                    if (changes.has("outgoingConfirmations")) editor.putInt(FORWARDING_LAST_OUTGOING_CONFIRMATIONS,
                            changes.optInt("outgoingConfirmations", 0));
                    editor.apply();
                }

                org.json.JSONArray old = new org.json.JSONArray(prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
                org.json.JSONObject previous = null;
                java.util.ArrayList<org.json.JSONObject> others = new java.util.ArrayList<>();
                java.util.HashSet<String> seenIncoming = new java.util.HashSet<>();
                java.util.HashSet<String> outgoingIds = new java.util.HashSet<>();
                for (int i = 0; i < old.length(); i++) {
                    org.json.JSONObject row = old.optJSONObject(i);
                    if (row == null) continue;
                    String rowIncoming = row.optString("incoming", "");
                    if (rowIncoming.equals(incomingTxId)) {
                        if (previous == null) previous = new org.json.JSONObject(row.toString());
                        String outgoing = row.optString("outgoing", "");
                        if (!outgoing.isEmpty()) outgoingIds.add(outgoing);
                        continue;
                    }
                    if (!seenIncoming.add(rowIncoming)) continue;
                    String outgoing = row.optString("outgoing", "");
                    if (!outgoing.isEmpty()) outgoingIds.add(outgoing);
                    others.add(new org.json.JSONObject(row.toString()));
                }

                org.json.JSONObject item;
                if (latest) {
                    item = new org.json.JSONObject();
                    item.put("created", previous != null
                            ? previous.optLong("created", previous.optLong("updated", System.currentTimeMillis()))
                            : System.currentTimeMillis());
                    if (previous != null) {
                        for (String key : new String[] {"destination", "mode", "fixedAmount", "network"}) {
                            if (previous.has(key)) item.put(key, previous.opt(key));
                        }
                    }
                    item.put("incoming", incomingTxId);
                    item.put("outgoing", prefs.getString(FORWARDING_LAST_OUTGOING_TX, ""));
                    item.put("stage", prefs.getString(FORWARDING_LAST_STAGE, ""));
                    item.put("status", prefs.getString(FORWARDING_STATUS, ""));
                    item.put("received", prefs.getLong(FORWARDING_LAST_RECEIVED_SATS, 0L));
                    item.put("amount", prefs.getLong(FORWARDING_LAST_AMOUNT_SATS, 0L));
                    item.put("fee", prefs.getLong(FORWARDING_LAST_FEE_SATS, 0L));
                    item.put("rate", prefs.getInt(FORWARDING_LAST_RATE_SAT_VB, 0));
                    item.put("effectiveRate", prefs.getInt(FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB, 0));
                    item.put("vsize", prefs.getInt(FORWARDING_LAST_VSIZE, 0));
                    item.put("currentConfirmations", prefs.getInt(FORWARDING_LAST_CURRENT_CONFIRMATIONS, -1));
                    item.put("requiredConfirmations", prefs.getInt(FORWARDING_LAST_REQUIRED_CONFIRMATIONS,
                            prefs.getInt(FORWARDING_CONFIRMATIONS, 1)));
                    item.put("outgoingConfirmations", prefs.getInt(FORWARDING_LAST_OUTGOING_CONFIRMATIONS, -1));
                    if (!item.has("destination")) item.put("destination", prefs.getString(FORWARDING_ADDRESS, ""));
                    if (!item.has("mode")) item.put("mode", prefs.getString(FORWARDING_MODE, "sweep"));
                    if (!item.has("fixedAmount")) item.put("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L));
                    if (!item.has("network")) item.put("network", NetworkConfig.storageName(NetworkConfig.get(applicationContext)));
                } else if (previous != null) {
                    item = previous;
                } else {
                    item = new org.json.JSONObject();
                    item.put("incoming", incomingTxId);
                    item.put("outgoing", "");
                    item.put("stage", "waiting");
                    item.put("status", "");
                    item.put("received", 0L);
                    item.put("amount", 0L);
                    item.put("fee", 0L);
                    item.put("rate", 0);
                    item.put("effectiveRate", 0);
                    item.put("vsize", 0);
                    item.put("currentConfirmations", -1);
                    item.put("requiredConfirmations", 1);
                    item.put("outgoingConfirmations", -1);
                    item.put("destination", prefs.getString(FORWARDING_ADDRESS, ""));
                    item.put("mode", prefs.getString(FORWARDING_MODE, "sweep"));
                    item.put("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L));
                    item.put("network", NetworkConfig.storageName(NetworkConfig.get(applicationContext)));
                    item.put("created", System.currentTimeMillis());
                }
                for (int i = 0; i < changes.length(); i++) {
                    org.json.JSONArray names = changes.names();
                    if (names == null) break;
                    String key = names.optString(i, "");
                    if (!key.isEmpty()) item.put(key, changes.opt(key));
                }
                if (!item.has("created")) item.put("created", System.currentTimeMillis());
                item.put("incoming", incomingTxId);
                item.put("updated", System.currentTimeMillis());
                String currentOutgoing = item.optString("outgoing", "");
                if (!currentOutgoing.isEmpty()) outgoingIds.add(currentOutgoing);

                // Normalize existing history: one row per received TXID, and no row for a TXID
                // that this ledger already identifies as an outgoing forwarding transaction.
                java.util.ArrayList<org.json.JSONObject> normalized = new java.util.ArrayList<>();
                if (!outgoingIds.contains(item.optString("incoming", ""))) normalized.add(item);
                normalized.addAll(others);
                java.util.Collections.sort(normalized, (a, b) -> Long.compare(
                        b.optLong("created", b.optLong("updated", 0L)),
                        a.optLong("created", a.optLong("updated", 0L))));
                org.json.JSONArray next = new org.json.JSONArray();
                java.util.HashSet<String> finalSeen = new java.util.HashSet<>();
                for (org.json.JSONObject row : normalized) {
                    String rowIncoming = row.optString("incoming", "");
                    if (rowIncoming.isEmpty() || outgoingIds.contains(rowIncoming) || !finalSeen.add(rowIncoming)) continue;
                    if (next.length() >= 100) break;
                    next.put(row);
                }
                prefs.edit().putString(FORWARDING_ACTIVITY_LEDGER, next.toString()).apply();
            } catch (Exception ignored) { }
        }
    }

    private int forwardingFailureString(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof LocalizedErrors.ResourceMessage) {
                return ((LocalizedErrors.ResourceMessage) current).resourceId();
            }
        }
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if ("forwarding_disabled".equals(message)) return R.string.forwarding_status_disabled_before_confirmation;
        if ("wallet_locked".equals(message)) return R.string.forwarding_error_wallet_locked;
        if ("wallet_not_ready".equals(message)) return R.string.forwarding_error_wallet_not_ready;
        if ("forwarding_destination_is_wallet_owned".equals(message)) return R.string.forwarding_error_self_destination;
        if ("forwarding_invalid_destination_network".equals(message)) return R.string.forwarding_error_destination_network;
        if ("forwarding_invalid_fixed_amount".equals(message)) return R.string.forwarding_error_invalid_fixed_amount;
        if ("forwarding_fee_unavailable".equals(message)) return R.string.forwarding_error_fee_unavailable;
        if ("forwarding_output_unavailable".equals(message)) return R.string.forwarding_error_output_unavailable;
        if ("forwarding_fee_exceeds_amount".equals(message)
                || error instanceof Wallet.CouldNotAdjustDownwards
                || error instanceof Wallet.DustySendRequested) {
            return R.string.forwarding_error_fee_exceeds_amount;
        }
        if (error instanceof org.bitcoinj.core.InsufficientMoneyException) {
            return R.string.forwarding_error_received_funds_insufficient;
        }
        return R.string.forwarding_status_failed;
    }

    /**
     * Wallet event callbacks can fire while bitcoinj is updating an existing
     * transaction (for example confidence/confirmation changes) without changing
     * the history cache key's transaction count or newest transaction id.
     * Invalidate cached history before scheduling the UI refresh so newly sent,
     * received, and updated transactions are mapped from the live wallet state.
     */
    private void invalidateHistoryCache(Wallet wallet) {
        if (wallet == null) {
            return;
        }
        synchronized (historyCacheLock) {
            historyCache.remove(wallet);
            historyCacheGenerations.put(wallet,
                    historyCacheGenerations.getOrDefault(wallet, 0L) + 1L);
        }
    }

    private void setupWalletListeners(
            WalletAppKit ownerKit,
            Wallet wallet) {

        // Recover a previously recorded pending forwarding transaction after wallet restart.
        // Do not sweep arbitrary historical receipts here: only resume the exact TXID that
        // was already recorded as waiting before shutdown. Whole-wallet sweep remains an
        // explicit user action in ForwardingServiceActivity.
        try {
            android.content.SharedPreferences forwardingPrefs = applicationContext.getSharedPreferences(
                    forwardingPreferencesName(applicationContext), android.content.Context.MODE_PRIVATE);
            if (forwardingPrefs.getBoolean(FORWARDING_ENABLED, false)) {
                java.util.Set<String> pendingIds = new java.util.LinkedHashSet<>();
                String pendingTxId = forwardingPrefs.getString(FORWARDING_LAST_INCOMING_TX, "");
                if ("waiting".equals(forwardingPrefs.getString(FORWARDING_LAST_STAGE, ""))
                        && pendingTxId != null && !pendingTxId.isEmpty()) pendingIds.add(pendingTxId);
                org.json.JSONArray ledger = new org.json.JSONArray(
                        forwardingPrefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
                for (int i = 0; i < ledger.length(); i++) {
                    org.json.JSONObject entry = ledger.optJSONObject(i);
                    if (entry != null && "waiting".equals(entry.optString("stage", ""))) {
                        String id = entry.optString("incoming", "");
                        if (!id.isEmpty()) pendingIds.add(id);
                    }
                }
                if (!pendingIds.isEmpty()) {
                    for (Transaction existingTx : wallet.getTransactions(true)) {
                        if (existingTx != null && pendingIds.contains(existingTx.getTxId().toString())) {
                            maybeForwardIncoming(wallet, existingTx);
                        }
                    }
                }
            }
        } catch (org.json.JSONException | RuntimeException ignored) {
            // Wallet listeners must still be installed if recovery of forwarding state fails.
        }

        wallet.addCoinsReceivedEventListener(
                (wallet1, tx, prevBalance, newBalance) -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    maybeForwardIncoming(wallet1, tx);
                    WalletSelection.observeRequestNestedTransaction(applicationContext, wallet1, tx);
                    invalidateHistoryCache(wallet1);
                    refresh();
                    notifyWalletUpdated();
                    if (tx.getPurpose() == Transaction.Purpose.UNKNOWN) {
                        try {
                            // Use the same selected-wallet ownership scope as transaction
                            // history. The raw bitcoinj balance delta can include imported
                            // and watched scopes held inside the same Wallet object.
                            Coin received = BitcoinSyncService.calculateReceivedAmount(
                                    applicationContext, wallet1, tx);
                            // Outgoing and self-change transactions can also trigger this
                            // listener. Only report a positive net value for this exact scope.
                            if (received.isPositive()) {
                                BitcoinSyncService.notifyReceived(
                                        applicationContext, received, tx, wallet1);
                                runOnUi(() -> view.showToastMessage(
                                        text(R.string.receive_message,
                                                MoneyFormatter.format(applicationContext, received))));
                            }
                        } catch (Exception ignored) {
                            // UI refresh remains the important part of the event.
                        }
                    }
                }
        );

        wallet.addCoinsSentEventListener(
                (wallet1, tx, prevBalance, newBalance) -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    invalidateHistoryCache(wallet1);
                    refresh();
                    notifyWalletUpdated();
                    try {
                        Coin sent = BitcoinSyncService.calculateExternalSentAmount(applicationContext, wallet1, tx);
                        if (sent.isPositive()) {
                            BitcoinSyncService.notifySent(
                                    applicationContext, sent, tx, wallet1);
                        }
                    } catch (Exception ignored) {
                        // The wallet refresh remains the important part of the event.
                    }
                }
        );

        wallet.addTransactionConfidenceEventListener(
                (wallet1, tx) -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    invalidateHistoryCache(wallet1);
                    refresh();
                    notifyWalletUpdated();
                }
        );

        wallet.addChangeEventListener(
                wallet1 -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    invalidateHistoryCache(wallet1);
                    refresh();
                    notifyWalletUpdated();
                }
        );

        // The reference history live-data also reloads on wallet reorganization.
        // Reorgs can change confirmations and transaction ownership without a new
        // receive/send callback, so invalidate and republish the complete history.
        wallet.addReorganizeEventListener(
                wallet1 -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    invalidateHistoryCache(wallet1);
                    refresh();
                    notifyWalletUpdated();
                }
        );
    }

    /**
     * Registers a lightweight UI callback. The callback is responsible for
     * switching back to its own UI thread if necessary.
     */
    public void addWalletUpdateListener(Runnable listener) {
        if (listener != null) {
            walletUpdateListeners.addIfAbsent(listener);
        }
    }

    public void removeWalletUpdateListener(Runnable listener) {
        if (listener != null) {
            walletUpdateListeners.remove(listener);
        }
    }

    public void addSyncStateListener(Runnable listener) {
        if (listener != null) {
            syncStateListeners.addIfAbsent(listener);
        }
    }

    public void removeSyncStateListener(Runnable listener) {
        if (listener != null) {
            syncStateListeners.remove(listener);
        }
    }

    private void notifySyncStateChanged() {
        for (Runnable listener : syncStateListeners) {
            try {
                listener.run();
            } catch (Exception e) {

            }
        }
    }

    public boolean isSyncing() {
        return startupInProgress || restartInProgress.get() || !downloadFinished;
    }

    public boolean isSyncStalled() {
        return !downloadFinished
                && walletAppKit != null
                && walletAppKit.isRunning()
                && System.currentTimeMillis() - lastProgressAt >= STALL_TIMEOUT_MS;
    }

    public int getSyncStatusResId() {
        if (restartInProgress.get()) {
            return R.string.sync_status_reconnecting;
        }
        if (!walletReady || walletAppKit == null) {
            return R.string.sync_status_starting;
        }
        if (getConnectedPeerCount() == 0) {
            return R.string.sync_status_no_peers;
        }
        if (isSyncStalled()) {
            return R.string.sync_status_stalled;
        }
        return isSyncing()
                ? R.string.sync_status_syncing
                : R.string.sync_status_live;
    }

    public boolean isWalletReady() {
        synchronized (kitLock) {
            try {
                return walletReady && walletAppKit != null && walletAppKit.isRunning();
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    public int getSyncPercent() {
        return Math.max(0, Math.min(100, lastPercent));
    }

    public int getCurrentBlock() {
        return safeChainHeight(walletAppKit);
    }

    /** Best chain height known by bitcoinj. */
    public int getBestChainHeight() {
        return safeChainHeight(walletAppKit);
    }

    public int getNetworkBlock() {
        return safePeerHeight(walletAppKit);
    }

    public int getBlocksBehind() {
        int best = getBestChainHeight();
        int network = getNetworkBlock();
        return best > 0 && network > 0 ? Math.max(0, network - best) : 0;
    }

    public int getConnectedPeerCount() {
        return safePeerCount(walletAppKit);
    }

    public int getPendingPeerCount() {
        try {
            PeerGroup peers = walletAppKit == null ? null : walletAppKit.peerGroup();
            return peers == null ? 0 : peers.getPendingPeers().size();
        } catch (Exception e) {
            return 0;
        }
    }

    public double getSyncBlocksPerSecond() {
        return syncBlocksPerSecond;
    }

    /** End-to-end time from the last refresh request until balance/history reached the UI. */
    public long getLastRefreshTotalMs() {
        return lastRefreshTotalMs;
    }

    /** Time from beginning refresh work until the UI applied the prepared data. */
    public long getLastRefreshProcessingMs() {
        return lastRefreshProcessingMs;
    }

    public long getNextAutomaticRecoveryDelayMs() {
        return Math.max(0L, nextAutomaticRecoveryAt - System.currentTimeMillis());
    }

    public long getAutomaticRecoveryWaitSeconds() {
        if (startupInProgress || restartInProgress.get() || shuttingDown) {
            return 0L;
        }
        long delay = getNextAutomaticRecoveryDelayMs();
        return delay <= 0L ? 0L : (delay + 999L) / 1000L;
    }

    public String getBestChainHash() {
        updateBestChainSnapshot();
        return bestChainHash;
    }

    public long getBestChainTimeSeconds() {
        updateBestChainSnapshot();
        return bestChainTimeSeconds;
    }

    private void updateBestChainSnapshot() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return;
        }
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head != null && head.getHeader() != null) {
                bestChainHash = head.getHeader().getHashAsString();
                bestChainTimeSeconds = head.getHeader().getTimeSeconds();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Called by the foreground sync service when Android reports that a network
     * is available again. A transient network loss does not stop WalletAppKit;
     * this method only repairs the peer lifecycle when the kit is running but
     * has no connected peers.
     */
    public void onNetworkAvailable() {
        if (shuttingDown || restartInProgress.get()) {
            return;
        }

        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            startWalletKit();
            return;
        }

        try {
            if (!kit.isRunning()) {
                startWalletKit();
                return;
            }
            PeerGroup group = kit.peerGroup();
            if (group != null && !group.getConnectedPeers().isEmpty()) {
                noPeerSince = 0L;
                return;
            }
        } catch (Exception ignored) {
            // Treat an inaccessible/stale kit as a lifecycle recovery case.
        }

        noPeerSince = 0L;
        synchronized (recoveryLock) {
            consecutiveRecoveryFailures = 0;
            nextAutomaticRecoveryAt = 0L;
        }
        restartWalletKit();
    }

    public boolean isWalletKitRunning() {
        WalletAppKit kit = walletAppKit;
        try {
            return kit != null && kit.isRunning();
        } catch (Exception ignored) {
            return false;
        }
    }

    public String getPeerGroupDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return "—";
        }
        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) {
                return "—";
            }
            List<Peer> connected = group.getConnectedPeers();
            if (connected.isEmpty()) {
                return applicationContext.getString(R.string.sync_peers_none);
            }
            StringBuilder out = new StringBuilder();
            Peer downloadPeer = group.getDownloadPeer();
            for (int i = 0; i < connected.size(); i++) {
                Peer peer = connected.get(i);
                if (i > 0) out.append("\n\n");
                out.append(i + 1).append("  ")
                        .append(peer.getAddress() == null
                                ? applicationContext.getString(R.string.sync_peer_unknown)
                                : peer.getAddress().toString());
                out.append("\n").append(applicationContext.getString(
                        R.string.sync_peer_height, peer.getBestHeight()));
                out.append("  ").append(applicationContext.getString(
                        R.string.sync_peer_behind_ahead, peer.getPeerBlockHeightDifference()));
                out.append("\n").append(applicationContext.getString(
                        R.string.sync_peer_download,
                        peer.isDownloadData()
                                ? applicationContext.getString(R.string.sync_yes)
                                : applicationContext.getString(R.string.sync_no)));
                try {
                    if (peer.pingInterval().isPresent()) {
                        out.append("  ").append(applicationContext.getString(
                                R.string.sync_peer_ping, peer.pingInterval().get().toMillis()));
                    } else {
                        out.append("  ").append(applicationContext.getString(R.string.sync_peer_ping_unavailable));
                    }
                } catch (Exception ignored) {
                    out.append("  ").append(applicationContext.getString(R.string.sync_peer_ping_unavailable));
                }
                try {
                    if (peer.getFeeFilter() != null) {
                        out.append("\n").append(applicationContext.getString(
                                R.string.sync_peer_fee_filter, MoneyFormatter.format(applicationContext, peer.getFeeFilter())));
                    }
                } catch (Exception ignored) {
                }
                if (peer == downloadPeer) {
                    out.append("\n").append(applicationContext.getString(R.string.sync_peer_role_download));
                }
            }
            return out.toString();
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getNetworkCapabilities() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) return "—";
            return applicationContext.getString(
                    R.string.sync_capabilities_details,
                    group.getMaxConnections(),
                    group.getMinBroadcastConnections(),
                    group.getMinRequiredProtocolVersion(),
                    group.getPingIntervalMsec(),
                    group.isBloomFilteringEnabled()
                            ? applicationContext.getString(R.string.sync_enabled)
                            : applicationContext.getString(R.string.sync_disabled),
                    group.getFastCatchupTime());
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getChainTechnicalDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head == null || head.getHeader() == null) return "—";
            org.bitcoinj.core.Block header = head.getHeader();
            return applicationContext.getString(
                    R.string.sync_chain_technical_details,
                    head.getChainWork(),
                    header.getVersion(),
                    header.getNonce(),
                    Long.toUnsignedString(header.getDifficultyTarget()));
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getChainMerkleRoot() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head == null || head.getHeader() == null || head.getHeader().getMerkleRoot() == null) return "—";
            return head.getHeader().getMerkleRoot().toString();
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getWalletChainState() {
        try {
            Wallet wallet = getActiveWallet();
            if (wallet == null) return "—";
            return applicationContext.getString(
                    R.string.sync_wallet_chain_state,
                    wallet.getLastBlockSeenHeight(),
                    wallet.getPendingTransactions().size());
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public int getWalletLastSeenHeight() {
        try {
            Wallet wallet = getActiveWallet();
            if (wallet == null) {
                return 0;
            }
            return wallet.getLastBlockSeenHeight();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public String getDownloadPeerDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return "—";
        }
        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) {
                return "—";
            }
            Peer peer = group.getDownloadPeer();
            if (peer == null) {
                return applicationContext.getString(R.string.sync_none);
            }
            String address = peer.getAddress() == null
                    ? applicationContext.getString(R.string.sync_peer_unknown)
                    : peer.getAddress().toString();
            return applicationContext.getString(
                    R.string.sync_download_peer_details,
                    address,
                    peer.getBestHeight());
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public int getAutoRestartCount() {
        return autoRestartCount;
    }

    public String getNetworkName() {
        return NetworkConfig.displayName(applicationContext, activeNetwork);
    }

    public BitcoinNetwork getActiveNetwork() {
        return activeNetwork;
    }

    public boolean isNetworkSwitching() {
        return networkSwitchInProgress;
    }

    public boolean isSyncModeSwitching() {
        return syncModeSwitchInProgress;
    }

    public NetworkConfig.SyncMode getSyncMode() {
        return NetworkConfig.getSyncMode(applicationContext, activeNetwork);
    }

    public void switchSyncMode(final NetworkConfig.SyncMode newMode) {
        if (newMode == null || newMode == getSyncMode()) {
            return;
        }
        if (shuttingDown || startupInProgress || networkSwitchInProgress
                || syncModeSwitchInProgress
                || !restartInProgress.compareAndSet(false, true)) {
            runOnUi(() -> view.showToastMessage(text(R.string.network_sync_mode_switch_busy)));
            return;
        }

        syncModeSwitchInProgress = true;
        walletReady = false;
        lastRefreshTotalMs = -1L;
        lastRefreshProcessingMs = -1L;
        downloadFinished = false;
        notifySyncStateChanged();

        new Thread(() -> {
            try {
                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    activeDownloadListener = null;
                }

                if (oldKit != null) {
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception ignored) {
                    }
                }

                NetworkConfig.setSyncMode(applicationContext, activeNetwork, newMode);
                autoRestartCount = 0;
                synchronized (recoveryLock) {
                    consecutiveRecoveryFailures = 0;
                    nextAutomaticRecoveryAt = 0L;
                }
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                lastProgressAt = System.currentTimeMillis();
                lastProgressUiUpdateAt = 0L;
                lastSyncNotificationUpdateAt = 0L;
                lastSyncNotificationPercent = -1;
                noPeerSince = 0L;
                lastDownloadKickAt = 0L;
                downloadFinished = false;

                runOnUi(() -> view.showToastMessage(
                        text(R.string.network_sync_mode_switching,
                                modeDisplayName(newMode))));

                startWalletKit();
                startWatchdog();
            } finally {
                syncModeSwitchInProgress = false;
                restartInProgress.set(false);
                notifySyncStateChanged();
                if (walletReady) {
                    refresh();
                }
            }
        }, "bitcoinj-sync-mode-switch").start();
    }

    private String modeDisplayName(NetworkConfig.SyncMode mode) {
        return applicationContext.getString(
                mode == NetworkConfig.SyncMode.FULL
                        ? R.string.network_sync_mode_full
                        : R.string.network_sync_mode_lite);
    }

    public void switchNetwork(final BitcoinNetwork newNetwork) {
        if (newNetwork == null
                || (newNetwork != BitcoinNetwork.MAINNET
                && newNetwork != BitcoinNetwork.SIGNET)
                || newNetwork == activeNetwork) {
            return;
        }
        if (shuttingDown || startupInProgress || networkSwitchInProgress
                || syncModeSwitchInProgress
                || !restartInProgress.compareAndSet(false, true)) {
            runOnUi(() -> view.showToastMessage(text(R.string.network_switch_busy)));
            return;
        }

        networkSwitchInProgress = true;
        walletReady = false;
        lastRefreshTotalMs = -1L;
        lastRefreshProcessingMs = -1L;
        downloadFinished = false;
        notifySyncStateChanged();

        new Thread(() -> {
            try {
                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    activeDownloadListener = null;
                }
                if (oldKit != null) {
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception ignored) {
                    }
                }
                WalletSecurity.clearSessionKey();

                activeNetwork = newNetwork;
                NetworkConfig.set(applicationContext, newNetwork);
                parameters = NetworkConfig.parameters(newNetwork);
                walletDataDir = NetworkConfig.walletDirectory(applicationContext, newNetwork);
                walletFile = new File(walletDataDir, Constants.WALLET_NAME + ".wallet");

                autoRestartCount = 0;
                synchronized (recoveryLock) {
                    consecutiveRecoveryFailures = 0;
                    nextAutomaticRecoveryAt = 0L;
                }
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                lastProgressAt = System.currentTimeMillis();
                noPeerSince = 0L;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() -> view.showToastMessage(
                        text(R.string.network_switching,
                                NetworkConfig.displayName(applicationContext, newNetwork))));

                startWalletKit();
                startWatchdog();
            } finally {
                restartInProgress.set(false);
                notifySyncStateChanged();
                if (walletReady) {
                    refresh();
                }
            }
        }, "bitcoinj-network-switch").start();
    }

    public void reconnectNow() {
        synchronized (recoveryLock) {
            consecutiveRecoveryFailures = 0;
            nextAutomaticRecoveryAt = 0L;
        }
        restartWalletKit();
    }

    private void notifyWalletUpdated() {
        wallet.widget.BalanceWidgetProvider.requestRefresh(applicationContext);
        for (Runnable listener : walletUpdateListeners) {
            try {
                listener.run();
            } catch (Exception e) {

            }
        }
    }

    private boolean isCurrentKit(WalletAppKit candidate) {
        if (candidate == null) {
            return false;
        }
        synchronized (kitLock) {
            return !shuttingDown && walletAppKit == candidate;
        }
    }

    /**
     * Listens to blocks arriving after the initial download as well as during
     * catch-up. This is the important realtime path: WalletAppKit continues
     * receiving blocks even when MainActivity is not visible.
     */
    private void setupPeerListeners(WalletAppKit ownerKit, PeerGroup peerGroup) {
        if (peerGroup == null) {
            return;
        }

        peerGroup.addBlocksDownloadedEventListener(
                (peer, block, filteredBlock, blocksLeft) -> {
                    if (shuttingDown || !isCurrentKit(ownerKit)) {
                        return;
                    }

                    int chainHeight = safeChainHeight(ownerKit);
                    int peerHeight = safePeerHeight(ownerKit);

                    if (chainHeight > lastChainHeight) {
                        lastChainHeight = chainHeight;
                    }

                    long now = System.currentTimeMillis();
                    if (chainHeight > 0 && chainHeight > lastRateHeight && lastBlockEventAt > 0L) {
                        long elapsed = now - lastBlockEventAt;
                        if (elapsed > 0L) {
                            double instantRate = (chainHeight - lastRateHeight) * 1000.0 / elapsed;
                            syncBlocksPerSecond = syncBlocksPerSecond == 0.0
                                    ? instantRate
                                    : (syncBlocksPerSecond * 0.7) + (instantRate * 0.3);
                        }
                    }
                    if (chainHeight > lastRateHeight) {
                        lastRateHeight = chainHeight;
                        lastBlockEventAt = now;
                    }
                    try {
                        org.bitcoinj.core.StoredBlock head = ownerKit.chain().getChainHead();
                        if (head != null && head.getHeader() != null) {
                            bestChainHash = head.getHeader().getHashAsString();
                            bestChainTimeSeconds = head.getHeader().getTimeSeconds();
                        }
                    } catch (Exception ignored) {
                    }
                    recordActualSyncProgress();

                    final boolean liveSynced = downloadFinished;
                    if (liveSynced) {
                        notifySyncStateChanged();
                    }
                    int percent = liveSynced
                            ? 100
                            : calculateSessionProgress(chainHeight, peerHeight);
                    if (!liveSynced && percent != lastPercent) {
                        lastPercent = percent;
                        notifySyncStateChanged();
                    }

                    long notificationNow = System.currentTimeMillis();
                    boolean notificationPercentChanged = percent != lastSyncNotificationPercent;
                    if (notificationPercentChanged
                            || notificationNow - lastSyncNotificationUpdateAt >= SYNC_NOTIFICATION_UPDATE_MS) {
                        lastSyncNotificationUpdateAt = notificationNow;
                        lastSyncNotificationPercent = percent;
                        BitcoinSyncService.updateSyncNotification(
                                applicationContext,
                                percent,
                                chainHeight,
                                peerHeight,
                                !liveSynced);
                    }

                    // Once caught up, every newly accepted block is a wallet UI
                    // refresh point. During historical sync, DownloadProgressTracker
                    // already drives the progress/UI updates and we avoid a refresh
                    // for every historical block.
                    if (liveSynced || blocksLeft == 0) {
                        refresh();
                        notifyWalletUpdated();
                    }
                });
    }

    /** Minimal non-UI view used while the foreground sync service owns the presenter. */
    private static final class HeadlessView implements MainActivityContract.MainActivityView {
        private final android.content.Context context;

        HeadlessView(android.content.Context context) {
            this.context = context.getApplicationContext();
        }

        @Override public void setPresenter(MainActivityContract.MainActivityPresenter presenter) { }
        @Override public void displayDownloadContent(boolean shown) { }
        @Override public void displayProgress(int percent) { }
        @Override public void displayPercentage(int percent) { }
        @Override public void displayMyBalance(String balance) { }
        @Override public void displayBalanceState(String available, String pending) { }
        @Override public void displayMyAddress(String address) { }
        @Override public void displayWalletType(String type) { }
        @Override public void displayWalletLoading(String message, boolean loading) { }
        @Override public boolean isWalletLoadingVisible() { return false; }
        @Override public void displayTransactions(List<TransactionItem> transactions) { }
        @Override public void showToastMessage(String message) { }
        @Override public android.content.Context getActivityContext() { return context; }
    }

    /** Snapshot used by the home-screen balance widget. */
    public static final class WidgetSnapshot {
        public final boolean available;
        public final String balance;
        public final String availableBalance;
        public final String pendingBalance;
        public final String networkLabel;
        public final String receiveAddress;

        public WidgetSnapshot(
                boolean available,
                String balance,
                String availableBalance,
                String pendingBalance,
                String networkLabel,
                String receiveAddress) {
            this.available = available;
            this.balance = balance;
            this.availableBalance = availableBalance;
            this.pendingBalance = pendingBalance;
            this.networkLabel = networkLabel;
            this.receiveAddress = receiveAddress;
        }

        public static WidgetSnapshot unavailable(
                String balance,
                String networkLabel) {
            return new WidgetSnapshot(
                    false,
                    balance,
                    balance,
                    balance,
                    networkLabel,
                    null);
        }
    }

    /**
     * Returns the same wallet-scoped balance used by MainActivity, without any
     * online API. A running presenter is preferred so the widget sees live
     * bitcoinj state; otherwise a local wallet-file snapshot is used.
     */
    public static WidgetSnapshot getWidgetSnapshot(android.content.Context context) {
        if (context == null) {
            return WidgetSnapshot.unavailable("--", "");
        }

        android.content.Context app = context.getApplicationContext();
        MainActivityPresenter presenter = activePresenter;
        if (presenter != null) {
            synchronized (presenter.kitLock) {
                WalletAppKit kit = presenter.walletAppKit;
                if (presenter.walletReady && kit != null && kit.isRunning()) {
                    try {
                        Wallet liveWallet = kit.wallet();
                        return presenter.buildWidgetSnapshot(app, liveWallet, presenter.parameters);
                    } catch (Exception ignored) {
                        // Fall through to the local wallet-file snapshot only when the
                        // live wallet cannot be read.
                    }
                }
            }
        }

        BitcoinNetwork network = NetworkConfig.get(app);
        NetworkParameters params = NetworkConfig.parameters(network);
        File walletFile = new File(
                NetworkConfig.walletDirectory(app, network),
                Constants.WALLET_NAME + ".wallet");
        if (!walletFile.isFile()) {
            return WidgetSnapshot.unavailable(
                    "--",
                    NetworkConfig.displayName(app, network));
        }

        try {
            Context.propagate(Context.getOrCreate(params));
            Wallet wallet = Wallet.loadFromFile(walletFile);
            return presenter != null
                    ? presenter.buildWidgetSnapshot(app, wallet, params)
                    : buildWidgetSnapshotStatic(app, wallet, params);
        } catch (Exception ignored) {
            return WidgetSnapshot.unavailable(
                    "--",
                    NetworkConfig.displayName(app, network));
        }
    }

    private WidgetSnapshot buildWidgetSnapshot(
            android.content.Context context,
            Wallet wallet,
            NetworkParameters walletParameters) {
        return buildWidgetSnapshotStatic(context, wallet, walletParameters);
    }

    private static WidgetSnapshot buildWidgetSnapshotStatic(
            android.content.Context context,
            Wallet wallet,
            NetworkParameters walletParameters) {
        BitcoinNetwork network = NetworkConfig.get(context);

        Script selectedWatchScript = WalletSelection.findSelectedScript(context, wallet);
        Script selectedImportedScript = selectedWatchScript == null
                ? WalletSelection.findSelectedImportedScript(context, wallet)
                : null;

        Coin balance;
        Coin available;
        Coin pending;
        String address;

        if (selectedWatchScript != null || selectedImportedScript != null) {
            Script displayScript = selectedWatchScript != null
                    ? selectedWatchScript : selectedImportedScript;
            long confirmedSat = 0L;
            long pendingSat = 0L;
            Iterable<TransactionOutput> selectedOutputs;
            if (selectedWatchScript != null) {
                selectedOutputs = wallet.getWatchedOutputs(false);
            } else {
                selectedOutputs = WalletSelection.selectedImportedUsesWatchedOutputs(context)
                        ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
            }

            for (TransactionOutput output : selectedOutputs) {
                if (!displayScript.equals(output.getScriptPubKey())
                        || !output.isAvailableForSpending()) {
                    continue;
                }
                if (output.getParentTransactionDepthInBlocks() > 0) {
                    confirmedSat += output.getValue().value;
                } else {
                    pendingSat += output.getValue().value;
                }
            }
            available = Coin.valueOf(confirmedSat);
            pending = Coin.valueOf(pendingSat);
            balance = available.add(pending);
            address = WalletSelection.addressForScript(displayScript, walletParameters);
        } else {
            // Keep the widget aligned with MainActivity's wallet-scoped balance.
            balance = WalletSelection.mainEstimatedBalance(context, wallet);
            available = WalletSelection.mainAvailableBalance(context, wallet);
            pending = balance.subtract(available);
            address = wallet.currentReceiveAddress().toString();
        }

        String networkLabel = context.getString(
                network == BitcoinNetwork.SIGNET
                        ? R.string.network_badge_signet
                        : R.string.network_badge_mainnet);

        return new WidgetSnapshot(
                true,
                MoneyFormatter.format(context, balance),
                MoneyFormatter.format(context, available),
                MoneyFormatter.format(context, pending),
                networkLabel,
                address);
    }

    public static MainActivityPresenter getActivePresenter() {
        return activePresenter;
    }

    /**
     * Returns a wallet snapshot handle only while WalletAppKit is fully running.
     * The critical wallet() call is made while kitLock is held, preventing the
     * common stop/restart race that can throw
     * "cannot call until startup is complete".
     */
    public static Wallet getActiveWallet() {
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null || !presenter.walletReady) {
            return null;
        }

        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (kit == null || !kit.isRunning()) {
                return null;
            }
            try {
                return kit.wallet();
            } catch (IllegalStateException ignored) {
                return null;
            }
        }
    }

    /**
     * Completes a forwarding request (including bitcoinj fee calculation and signing), records its
     * preview before broadcast, and commits/broadcasts that exact transaction against the same
     * running wallet instance. No second wallet or peer group is created.
     */
    public static TransactionBroadcast prepareCommitAndBroadcastForwarding(
            Wallet expectedWallet,
            SendRequest request,
            Consumer<Transaction> preparedCallback) throws Exception {
        if (expectedWallet == null || request == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_forwarding_request_missing);
        }
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null || !presenter.walletReady) {
            throw new IllegalStateException("wallet_not_ready");
        }
        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (presenter.shuttingDown || kit == null || !kit.isRunning()) {
                throw new IllegalStateException("wallet_not_ready");
            }
            if (presenter.parameters != null) {
                Context.propagate(Context.getOrCreate(presenter.parameters));
            }
            Wallet currentWallet;
            try {
                currentWallet = kit.wallet();
            } catch (IllegalStateException error) {
                throw new IllegalStateException("wallet_not_ready", error);
            }
            if (currentWallet != expectedWallet) {
                throw new IllegalStateException("wallet_not_ready");
            }
            currentWallet.completeTx(request);
            Transaction transaction = request.tx;
            if (transaction == null || transaction.getFee() == null) {
                throw new IllegalStateException("forwarding_fee_unavailable");
            }
            if (preparedCallback != null) {
                preparedCallback.accept(transaction);
            }
            currentWallet.commitTx(transaction);
            TransactionBroadcast broadcast = kit.peerGroup().broadcastTransaction(transaction);
            broadcast.broadcast();
            return broadcast;
        }
    }

    /**
     * Commits and broadcasts a transaction while the active WalletAppKit lifecycle
     * lock is held. This prevents a stop/restart from invalidating the kit between
     * wallet.commitTx() and peerGroup().broadcastTransaction().
     */
    public static org.bitcoinj.core.TransactionBroadcast commitAndBroadcastTransaction(
            org.bitcoinj.core.Transaction transaction) throws Exception {
        if (transaction == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_transaction_missing);
        }

        MainActivityPresenter presenter = activePresenter;
        if (presenter == null || !presenter.walletReady) {
            throw new IllegalStateException("wallet_not_ready");
        }

        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (kit == null || !kit.isRunning()) {
                throw new IllegalStateException("wallet_not_ready");
            }

            Wallet wallet;
            try {
                wallet = kit.wallet();
            } catch (IllegalStateException error) {
                throw new IllegalStateException("wallet_not_ready", error);
            }

            wallet.commitTx(transaction);
            org.bitcoinj.core.TransactionBroadcast broadcast =
                    kit.peerGroup().broadcastTransaction(transaction);
            broadcast.broadcast();
            return broadcast;
        }
    }

    public static File getActiveWalletFile() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.walletFile;
    }

    public static NetworkParameters getActiveParameters() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.parameters;
    }

    private void runOnUi(
            Runnable r) {

        if (Looper.myLooper()
                == Looper.getMainLooper()) {

            r.run();

        } else {

            mainHandler.post(r);
        }
    }

}
