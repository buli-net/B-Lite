package wallet.main;

import wallet.storage.ImportedWalletStore;
import wallet.backup.ForwardingBackupBundle;

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
    private static final String FORWARDING_ENABLED_AT_MS = "enabled_at_ms";
    private static final String FORWARDING_ENABLED_AT_HEIGHT = "enabled_at_height";
    private static final String FORWARDING_PRE_ENABLE_PENDING_TXIDS = "pre_enable_pending_txids_json";
    private static final String FORWARDING_RECOVERY_LAST_SCAN_MS = "recovery_last_scan_ms";
    // After one activation-gap catch-up pass, ordinary recovery is ledger/event-driven.
    private static final String FORWARDING_RECOVERY_INCREMENTAL_BOOTSTRAP_DONE = "recovery_incremental_bootstrap_done";
    private static final String FORWARDING_GENERATION = "forwarding_generation";
    private static final Object FORWARDING_COMMIT_LOCK = new Object();
    private static final Set<String> forwardingTransactions = ConcurrentHashMap.newKeySet();
    // Rebroadcasts always reuse the already-committed TXID. This set only prevents concurrent
    // attempts for that same transaction; it is intentionally not a transaction history ledger.
    private static final Set<String> forwardingBroadcastRetries = ConcurrentHashMap.newKeySet();
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
    private final Object forwardingRecoveryLock = new Object();
    private final AtomicBoolean forwardingRecoveryScanInProgress = new AtomicBoolean(false);
    private volatile java.util.concurrent.ScheduledFuture<?> forwardingRecoveryTask;
    private volatile WalletAppKit forwardingRecoveryOwnerKit;

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

            File incomingBackupFile =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".restore-input.tmp"
                    );
            File tempFile =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".restore.tmp"
                    );
            org.json.JSONObject restoredAppDataSnapshot = null;
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
                             new FileOutputStream(incomingBackupFile)) {

                    if (input == null) {
                        throw new IOException(text(R.string.restore_read_failed));
                    }

                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0L;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        total += count;

                        if (total > 80L * 1024L * 1024L) {
                            throw new IOException(text(R.string.restore_too_large));
                        }
                    }

                    output.flush();

                    if (total == 0L) {
                        throw new IOException(text(R.string.restore_empty));
                    }
                }

                restoredAppDataSnapshot = ForwardingBackupBundle.extractWalletFile(
                        incomingBackupFile, tempFile);
                if (incomingBackupFile.exists()) incomingBackupFile.delete();

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

                // Apply only optional data categories carried by the backup package. Legacy raw
                // bitcoinj wallet files remain supported and leave current app preferences alone.
                if (restoredAppDataSnapshot != null) {
                    ForwardingBackupBundle.restoreAppDataSnapshot(
                            applicationContext, restoredAppDataSnapshot);
                }

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
                if (incomingBackupFile.exists()) {
                    incomingBackupFile.delete();
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

    /** Mark only jobs that are still waiting as intentionally disabled; committed broadcasts are not undone. */
    public static void disablePendingForwardingJobs(android.content.Context context) {
        if (context == null) return;
        android.content.Context appContext = context.getApplicationContext();
        android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                forwardingPreferencesName(appContext), android.content.Context.MODE_PRIVATE);
        // Serialize disable/re-enable against the final commit boundary. Any async task started
        // under an older generation must stop, even if the user re-enables forwarding immediately.
        synchronized (FORWARDING_COMMIT_LOCK) {
            long generation = prefs.getLong(FORWARDING_GENERATION, 0L);
            prefs.edit().putLong(FORWARDING_GENERATION, generation + 1L).commit();
        }
        Wallet activeWallet = getActiveWallet();
        java.util.Set<String> activeWalletTransactionIds = new java.util.HashSet<>();
        boolean activeWalletViewAvailable = false;
        if (activeWallet != null && activeWallet.network().equals(NetworkConfig.get(appContext))) {
            try {
                for (Transaction tx : activeWallet.getTransactions(true)) {
                    if (tx != null) activeWalletTransactionIds.add(tx.getTxId().toString());
                }
                activeWalletViewAvailable = true;
            } catch (RuntimeException ignored) {
                // Without a complete wallet view, don't guess whether an outgoing TX was committed.
            }
        }
        synchronized (FORWARDING_LEDGER_LOCK) {
            try {
                org.json.JSONArray old = new org.json.JSONArray(
                        prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
                org.json.JSONArray next = new org.json.JSONArray();
                String message = appContext.getString(R.string.forwarding_activity_disabled_detail);
                long now = System.currentTimeMillis();
                String lastIncoming = prefs.getString(FORWARDING_LAST_INCOMING_TX, "");
                for (int i = 0; i < old.length(); i++) {
                    org.json.JSONObject row = old.optJSONObject(i);
                    if (row == null) continue;
                    String rowStage = row.optString("stage", "");
                    String rowOutgoing = row.optString("outgoing", "");
                    boolean uncommittedSignedProposal = "broadcasting".equals(rowStage)
                            && !row.optString("raw", "").isEmpty()
                            && activeWalletViewAvailable
                            && (rowOutgoing.isEmpty() || !activeWalletTransactionIds.contains(rowOutgoing));
                    boolean cancellable = "waiting".equals(rowStage)
                            || ("preparing".equals(rowStage) && rowOutgoing.isEmpty())
                            || uncommittedSignedProposal;
                    if (cancellable) {
                        row.remove("raw");
                        row.put("stage", "disabled");
                        row.put("status", message);
                        row.put("updated", now);
                        if (lastIncoming.equals(row.optString("incoming", ""))) {
                            prefs.edit().putString(FORWARDING_LAST_STAGE, "disabled")
                                    .putString(FORWARDING_STATUS, message).apply();
                        }
                    }
                    next.put(row);
                }
                prefs.edit().putString(FORWARDING_ACTIVITY_LEDGER, next.toString()).apply();
            } catch (Exception ignored) { }
        }
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
        maybeForwardIncoming(wallet, incomingTx, false);
    }

    /**
     * Resumes a saved forwarding ledger item after a transient wallet/network context failure.
     * This override is only used after the recovery loop has confirmed the same incoming TXID,
     * network, confirmation threshold, and absence of a durable outgoing TX/raw transaction.
     */
    private void maybeForwardIncoming(Wallet wallet, Transaction incomingTx, boolean resumeSavedContextFailure) {
        android.content.SharedPreferences prefs = applicationContext.getSharedPreferences(
                forwardingPreferencesName(applicationContext), android.content.Context.MODE_PRIVATE);
        if (!prefs.getBoolean(FORWARDING_ENABLED, false) || wallet == null || incomingTx == null) {
            return;
        }
        final long forwardingGeneration = prefs.getLong(FORWARDING_GENERATION, 0L);
        String incomingTxId = incomingTx.getTxId().toString();
        String existingStage = findForwardingLedgerStage(prefs, incomingTxId);
        org.json.JSONObject savedOperation = getForwardingLedgerEntry(prefs, incomingTxId);
        // Only an explicitly saved context-failure row may bypass the generic "already handled"
        // filters. It is called by recovery only after verifying that no signed bytes/outgoing TXID
        // exist and the incoming transaction still has spendable outputs in this wallet.
        boolean resumeRecordedFailure = resumeSavedContextFailure
                && "waiting".equals(existingStage)
                && savedOperation != null
                && savedOperation.optString("raw", "").trim().isEmpty()
                && savedOperation.optString("outgoing", "").trim().isEmpty()
                && hasSpendableOutputFromIncoming(wallet, incomingTx)
                && !hasPositiveValueSentFromWallet(wallet, incomingTx);
        // A TXID may also be another step's outgoing TXID. Normal receive events must not loop
        // over our own change, while a verified saved retry is allowed through these guards.
        if ((!resumeRecordedFailure && incomingTx.getPurpose() != Transaction.Purpose.UNKNOWN)
                || forwardingTransactions.contains(incomingTxId)
                || (!resumeRecordedFailure
                        && isPreviouslyForwardedOutputFromThisWallet(wallet, prefs, incomingTx))) {
            return;
        }
        // A retryable failure may leave an older row marked waiting even though a signed TX was
        // durably recorded. Never sign a replacement while that exact TX can still be recovered.
        if ("waiting".equals(existingStage) && savedOperation != null
                && (!savedOperation.optString("raw", "").isEmpty()
                        || !savedOperation.optString("outgoing", "").isEmpty())) {
            recoverInFlightForwarding(wallet, prefs, incomingTx, "broadcasting");
            return;
        }
        // Wallet listeners may fire repeatedly while a retry is deferred. They must not bypass
        // persisted backoff. Recovery of a saved outgoing TXID/raw transaction above is exempt.
        if ("waiting".equals(existingStage) && savedOperation != null
                && savedOperation.optBoolean("retryableFailure", false)
                && savedOperation.optLong("nextRetryAt", 0L) > System.currentTimeMillis()) {
            return;
        }
        // New receipts discovered during initial blockchain catch-up must still respect the time
        // when auto-forwarding was enabled. Existing ledger work is exempt so it can be resumed.
        if (existingStage.isEmpty() && !isReceiptEligibleSinceActivation(prefs, wallet, incomingTx)) {
            return;
        }
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
        // Keep a stable start time for this in-memory attempt. The ordinary "updated" timestamp
        // changes as confirmation metadata is refreshed and must not be used as a stale-lease clock.
        // A new real attempt gets a fresh lease timestamp and consumes the stale-retry marker.
        // Never retain the timestamp from an older failed attempt, or recovery can repeatedly
        // release this new in-flight operation before it persists its signed transaction.
        updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                "attemptStarted", System.currentTimeMillis()));
        final BitcoinNetwork forwardingNetwork = NetworkConfig.get(applicationContext);
        if (!forwardingNetwork.equals(wallet.network())) {
            forwardingTransactions.remove(incomingTxId);
            return;
        }

        Coin received;
        try {
            received = incomingTx.getValueSentToMe(wallet);
        } catch (RuntimeException error) {
            synchronized (FORWARDING_LEDGER_LOCK) {
                prefs.edit()
                        .putString(FORWARDING_LAST_STAGE, "waiting")
                        .putString(FORWARDING_LAST_INCOMING_TX, incomingTxId)
                        .remove(FORWARDING_LAST_OUTGOING_TX)
                        .remove(FORWARDING_LAST_FEE_SATS)
                        .remove(FORWARDING_LAST_AMOUNT_SATS)
                        .remove(FORWARDING_LAST_RATE_SAT_VB)
                        .remove(FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB)
                        .remove(FORWARDING_LAST_VSIZE)
                        .putLong(FORWARDING_LAST_RECEIVED_SATS, 0L)
                        .putInt(FORWARDING_LAST_CURRENT_CONFIRMATIONS, 0)
                        .putInt(FORWARDING_LAST_REQUIRED_CONFIRMATIONS,
                                Math.max(1, prefs.getInt(FORWARDING_CONFIRMATIONS, 1)))
                        .putInt(FORWARDING_LAST_OUTGOING_CONFIRMATIONS, -1)
                        .putString(FORWARDING_STATUS,
                                applicationContext.getString(
                                        R.string.forwarding_status_waiting_conditions, incomingTxId))
                        .apply();
                persistForwardingActivitySnapshot(prefs, incomingTxId);
            }
            forwardingTransactions.remove(incomingTxId);
            scheduleForwardingRetry(prefs, incomingTxId, NetworkConfig.get(applicationContext), 0);
            return;
        }
        if (received == null) {
            updateForwardingLedgerStage(prefs, incomingTxId, "waiting",
                    applicationContext.getString(R.string.forwarding_status_waiting_conditions, incomingTxId));
            forwardingTransactions.remove(incomingTxId);
            scheduleForwardingRetry(prefs, incomingTxId, NetworkConfig.get(applicationContext), 0);
            return;
        }
        if (!received.isPositive()) {
            forwardingTransactions.remove(incomingTxId);
            return;
        }

        int configuredConfirmations = Math.max(1, Math.min(100,
                prefs.getInt(FORWARDING_CONFIRMATIONS, 1)));
        int requiredConfirmations = Math.max(1, Math.min(100,
                savedOperation != null && savedOperation.has("requiredConfirmations")
                        ? savedOperation.optInt("requiredConfirmations", configuredConfirmations)
                        : configuredConfirmations));
        String savedDestination = savedOperation == null ? ""
                : savedOperation.optString("destination", "").trim();
        final String forwardingDestinationText = savedDestination.isEmpty()
                ? prefs.getString(FORWARDING_ADDRESS, "").trim() : savedDestination;
        String savedMode = savedOperation == null ? ""
                : savedOperation.optString("mode", "").trim();
        final String forwardingMode = savedMode.isEmpty()
                ? prefs.getString(FORWARDING_MODE, "sweep") : savedMode;
        final long forwardingFixedAmountSats = savedOperation != null && savedOperation.has("fixedAmount")
                ? savedOperation.optLong("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L))
                : prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L);
        final int forwardingFeeRateSatVb = Math.max(1, Math.min(10,
                savedOperation != null && savedOperation.has("feeRate")
                        ? savedOperation.optInt("feeRate", prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5))
                        : prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)));
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

        // A restored wallet can already contain this receipt at the required depth before the
        // recovery listener is attached. Complete immediately in that case instead of relying on
        // a future confidence transition that may never happen for an already-confirmed TX.
        CompletableFuture<org.bitcoinj.core.TransactionConfidence> confirmationFuture =
                initialDepth >= requiredConfirmations
                        ? CompletableFuture.completedFuture(incomingTx.getConfidence())
                        : wallet.waitForConfirmations(incomingTx, requiredConfirmations);
        confirmationFuture.thenCompose(confidence -> CompletableFuture.supplyAsync(() -> {
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

                        String destinationText = forwardingDestinationText;
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

                        String mode = forwardingMode;
                        long fixedAmountSats = forwardingFixedAmountSats;
                        int feeRateSatVb = forwardingFeeRateSatVb;
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
                                wallet, request, forwardingGeneration, transaction -> {
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
                                    if (!prefs.getBoolean(FORWARDING_ENABLED, false)
                                            || prefs.getLong(FORWARDING_GENERATION, 0L) != forwardingGeneration
                                            || "disabled".equals(findForwardingLedgerStage(prefs, incomingTxId))) {
                                        throw new IllegalStateException("forwarding_disabled");
                                    }
                                    int vsize = transaction.getVsize();
                                    int effectiveRate = vsize > 0
                                            ? (int) Math.min(Integer.MAX_VALUE, (fee.value + vsize - 1L) / vsize)
                                            : feeRateSatVb;
                                    String rawTransactionHex = forwardingTransactionToHex(transaction);
                                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                            "fee", fee.value,
                                            "amount", forwardedSats,
                                            "rate", feeRateSatVb,
                                            "effectiveRate", effectiveRate,
                                            "vsize", vsize,
                                            "outgoing", transaction.getTxId().toString(),
                                            "raw", rawTransactionHex,
                                            "generation", forwardingGeneration,
                                            "requeuedStaleFailure", false,
                                            "stage", "broadcasting",
                                            "status", applicationContext.getString(
                                                    R.string.forwarding_status_broadcasting, incomingTxId)));
                                    // The signed raw transaction must survive a process kill before or
                                    // immediately after commitTx(). If persisting it fails, abort before
                                    // committing/broadcasting so recovery never has to guess a new TX.
                                    synchronized (FORWARDING_LEDGER_LOCK) {
                                        String ledger = prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]");
                                        if (!prefs.edit().putString(FORWARDING_ACTIVITY_LEDGER, ledger).commit()) {
                                            throw new IllegalStateException("forwarding_ledger_persist_failed");
                                        }
                                    }
                                    if (!prefs.getBoolean(FORWARDING_ENABLED, false)) {
                                        throw new IllegalStateException("forwarding_disabled");
                                    }
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

                        // awaitRelayed() can fail after bitcoinj has already committed the
                        // outgoing transaction to the wallet. In that case, do not tell the
                        // user forwarding failed and do not retry the same incoming output.
                        // Keep it in the broadcasting state and continue watching confirmations.
                        Transaction committedOutgoing = null;
                        String recordedOutgoingId = findForwardingLedgerOutgoing(prefs, incomingTxId);
                        if (!recordedOutgoingId.isEmpty()) {
                            try {
                                committedOutgoing = wallet.getTransaction(
                                        org.bitcoinj.base.Sha256Hash.wrap(recordedOutgoingId));
                            } catch (RuntimeException ignored) { }
                        }
                        if (committedOutgoing != null) {
                            org.bitcoinj.core.TransactionConfidence confidence = committedOutgoing.getConfidence();
                            int depth = confidence == null ? 0 : Math.max(0, confidence.getDepthInBlocks());
                            boolean peerRelayed = confidence != null
                                    && confidence.getConfidenceType()
                                            == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                                    && confidence.numBroadcastPeers() > 0;
                            String recoveredStage = depth > 0 ? "confirmed" : (peerRelayed ? "relayed" : "broadcasting");
                            String recoveredStatus = depth > 0
                                    ? applicationContext.getString(R.string.forwarding_status_confirmed,
                                            committedOutgoing.getTxId().toString())
                                    : (peerRelayed
                                            ? applicationContext.getString(R.string.forwarding_status_forwarded,
                                                    incomingTxId, committedOutgoing.getTxId().toString())
                                            : applicationContext.getString(
                                                    R.string.forwarding_status_paused_wallet_not_ready, incomingTxId));
                            updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                    "stage", recoveredStage,
                                    "outgoingConfirmations", depth,
                                    "status", recoveredStatus));
                            forwardingTransactions.remove(incomingTxId);
                            forwardingTransactions.remove(recordedOutgoingId);
                            // If commit succeeded but relay did not, recover by rebroadcasting this
                            // exact transaction after connectivity returns. Never sign a second TX.
                            // The wallet-scoped recovery loop below will retry the saved TXID.
                        } else if ("forwarding_disabled".equals(String.valueOf(cause.getMessage()))) {
                            updateForwardingLedgerStage(prefs, incomingTxId, "disabled",
                                    applicationContext.getString(R.string.forwarding_activity_disabled_detail));
                            forwardingTransactions.remove(incomingTxId);
                            forwardingTransactions.remove(recordedOutgoingId);
                        } else if (isRetryableForwardingFailure(cause)) {
                            // If the signed TXID/raw bytes were recorded, keep this as an in-flight
                            // broadcast. The recovery loop must restore/rebroadcast these exact bytes
                            // rather than invoke the signer to build a competing transaction.
                            org.json.JSONObject durableRow = getForwardingLedgerEntry(prefs, incomingTxId);
                            boolean hasDurableSignedTx = !recordedOutgoingId.isEmpty()
                                    || (durableRow != null
                                            && !durableRow.optString("raw", "").isEmpty());
                            long failedAt = System.currentTimeMillis();
                            int retryCount = durableRow == null ? 1
                                    : Math.min(32, durableRow.optInt("retryCount", 0) + 1);
                            long nextRetryAt = failedAt + forwardingRetryDelayMs(retryCount);
                            String pausedStatus = applicationContext.getString(
                                    R.string.forwarding_status_waiting_conditions, incomingTxId);
                            org.json.JSONObject deferredFields = forwardingFields(
                                    "stage", hasDurableSignedTx ? "broadcasting" : "waiting",
                                    "status", pausedStatus,
                                    "retryableFailure", !hasDurableSignedTx,
                                    "retryCount", retryCount,
                                    "nextRetryAt", nextRetryAt,
                                    "lastRetryError", String.valueOf(cause.getMessage()),
                                    "lastRetryAt", failedAt);
                            if (!hasDurableSignedTx && "wallet_not_ready".equals(String.valueOf(cause.getMessage()))) {
                                deferredFields = mergeForwardingFields(deferredFields, forwardingFields(
                                        "previousFailureStatus",
                                        applicationContext.getString(R.string.forwarding_error_wallet_not_ready),
                                        "requeuedStaleFailure", true));
                            }
                            updateForwardingLedgerEntry(prefs, incomingTxId, deferredFields);
                            forwardingTransactions.remove(incomingTxId);
                            forwardingTransactions.remove(recordedOutgoingId);
                            scheduleForwardingRetry(prefs, incomingTxId, forwardingNetwork, retryCount);
                        } else {
                            int reasonRes = forwardingFailureString(cause);
                            String failureStatus = applicationContext.getString(reasonRes,
                                    cause.getClass().getSimpleName(), incomingTxId);
                            updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                    "stage", "failed",
                                    "status", failureStatus,
                                    "retryableFailure", false,
                                    "failureConfigFingerprint", forwardingConfigFingerprint(prefs),
                                    "lastPermanentFailureAt", System.currentTimeMillis(),
                                    "lastRetryError", String.valueOf(cause.getMessage())));
                            forwardingTransactions.remove(incomingTxId);
                            forwardingTransactions.remove(recordedOutgoingId);
                        }
                    } else {
                        updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                "stage", "relayed",
                                "outgoing", broadcast.transaction().getTxId().toString(),
                                "outgoingConfirmations", 0,
                                "status", applicationContext.getString(R.string.forwarding_status_forwarded,
                                        incomingTxId, broadcast.transaction().getTxId().toString())));
                        forwardingTransactions.remove(incomingTxId);
                        forwardingTransactions.remove(broadcast.transaction().getTxId().toString());
                        // The forwarding operation is complete once the transaction is relayed.
                        // Keep one stable success card; confirmation watching is not part of this
                        // activity's status lifecycle.
                    }
                    statusUpdater.cancel(false);
                    wallet.removeTransactionConfidenceEventListener(incomingConfidenceListener);
                    invalidateHistoryCache(wallet);
                    refresh();
                    notifyWalletUpdated();
                });
    }

    private boolean isForwardingWalletNotReady(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if ("wallet_not_ready".equals(current.getMessage())) return true;
        }
        return false;
    }

    private boolean isRetryableForwardingFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if ("wallet_not_ready".equals(message) || "wallet_locked".equals(message)) return true;
            if (current instanceof java.io.IOException
                    || current instanceof java.net.SocketException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.util.concurrent.CancellationException) return true;
            if (message.contains("no peers") || message.contains("peer group")
                    || (message.contains("network") && !message.contains("invalid_destination_network"))
                    || message.contains("connection") || message.contains("connect")
                    || message.contains("timeout")
                    || message.contains("timed out") || message.contains("socket")
                    || message.contains("disconnected") || message.contains("temporarily unavailable")
                    || message.contains("interrupted")) return true;
        }
        return isForwardingWalletNotReady(error);
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
        final int maxAttempts = 60; // Bounded in-process nudges; ledger recovery survives process death.
        if (attempt >= maxAttempts || prefs == null || incomingTxId == null || expectedNetwork == null) return;
        long delayMs = forwardingRetryDelayMs(Math.max(1, attempt));
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
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Exponential retry/reconciliation spacing: 1m, 2m, 4m ... capped at 6h. */
    private static long forwardingRetryDelayMs(int attempt) {
        int exponent = Math.max(0, Math.min(12, attempt - 1));
        // exponent is capped at 12, so this multiplication cannot overflow; avoid
        // newer Math APIs that may not exist on the app's minimum Android runtime.
        long delay = TimeUnit.MINUTES.toMillis(1L) * (1L << exponent);
        return Math.max(TimeUnit.MINUTES.toMillis(1L),
                Math.min(TimeUnit.HOURS.toMillis(6L), delay));
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
                    String currentStage = findForwardingLedgerStage(prefs, incomingTxId);
                    if (!("relayed".equals(currentStage) || "broadcasting".equals(currentStage))
                            || !outgoingTxId.equals(findForwardingLedgerOutgoing(prefs, incomingTxId))) return;
                    try {
                        org.bitcoinj.core.TransactionConfidence confidence = changedTx.getConfidence();
                        int depth = confidence == null ? 0
                                : Math.max(0, confidence.getDepthInBlocks());
                        int confirmations = depth > 0 ? 1 : 0;
                        org.json.JSONObject fields;
                        if (confirmations >= 1) {
                            fields = forwardingFields("outgoingConfirmations", confirmations,
                                    "stage", "confirmed",
                                    "status", applicationContext.getString(
                                            R.string.forwarding_status_confirmed, outgoingTxId));
                        } else if ("broadcasting".equals(currentStage) && confidence != null
                                && confidence.getConfidenceType()
                                        == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                                && confidence.numBroadcastPeers() > 0) {
                            fields = forwardingFields("outgoingConfirmations", 0,
                                    "stage", "relayed",
                                    "status", applicationContext.getString(
                                            R.string.forwarding_status_forwarded, incomingTxId, outgoingTxId));
                        } else {
                            fields = forwardingFields("outgoingConfirmations", confirmations);
                        }
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
            if (!("relayed".equals(stage) || "broadcasting".equals(stage))
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
                org.bitcoinj.core.TransactionConfidence confidence = tracked.getConfidence();
                int depth = confidence == null ? 0
                        : Math.max(0, confidence.getDepthInBlocks());
                int confirmations = depth > 0 ? 1 : 0;
                org.json.JSONObject fields;
                boolean becameRelayed = false;
                if (confirmations >= 1) {
                    fields = forwardingFields("outgoingConfirmations", confirmations,
                            "stage", "confirmed",
                            "status", applicationContext.getString(
                                    R.string.forwarding_status_confirmed, outgoingTxId));
                } else if ("broadcasting".equals(stage) && confidence != null
                        && confidence.getConfidenceType()
                                == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                        && confidence.numBroadcastPeers() > 0) {
                    becameRelayed = true;
                    fields = forwardingFields("outgoingConfirmations", 0,
                            "stage", "relayed",
                            "status", applicationContext.getString(
                                    R.string.forwarding_status_forwarded, incomingTxId, outgoingTxId));
                } else {
                    fields = forwardingFields("outgoingConfirmations", confirmations);
                }
                updateForwardingLedgerEntry(prefs, incomingTxId, fields);
                if (becameRelayed) {
                    invalidateHistoryCache(liveWallet);
                    refresh();
                    notifyWalletUpdated();
                }
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

    /**
     * Network-scoped forwarding history may link an outgoing TXID from one wallet to a
     * legitimate receipt in another wallet. Treat it as our own change only when the active
     * wallet confirms that the transaction spends value from this wallet. If the wallet view is
     * temporarily incomplete, defer this pass; the recovery scan will check again later.
     */
    private boolean isPreviouslyForwardedOutputFromThisWallet(Wallet wallet,
            android.content.SharedPreferences prefs, Transaction transaction) {
        if (transaction == null || !isPreviouslyForwardedOutput(prefs, transaction.getTxId().toString())) {
            return false;
        }
        if (wallet == null) return true;
        try {
            Coin sentFromWallet = transaction.getValueSentFromMe(wallet);
            return sentFromWallet != null && sentFromWallet.isPositive();
        } catch (RuntimeException incompleteWalletView) {
            return true;
        }
    }

    private void persistForwardingActivitySnapshot(android.content.SharedPreferences prefs) {
        if (prefs == null) return;
        persistForwardingActivitySnapshot(prefs, prefs.getString(FORWARDING_LAST_INCOMING_TX, ""));
    }

    private void persistForwardingActivitySnapshot(android.content.SharedPreferences prefs, String incomingTxId) {
        updateForwardingLedgerEntry(prefs, incomingTxId, new org.json.JSONObject());
    }

    /** Returns bitcoinj's best-known timestamp for a transaction, or zero when not available. */
    private long resolveForwardingTransactionTime(String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) return 0L;
        try {
            Wallet wallet = getActiveWallet();
            if (wallet == null) return 0L;
            Transaction transaction = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(transactionId));
            if (transaction == null) return 0L;
            java.util.Optional<java.time.Instant> updateTime = transaction.updateTime();
            if (!updateTime.isPresent()) return 0L;
            long timestamp = updateTime.get().toEpochMilli();
            return timestamp > 0L ? timestamp : 0L;
        } catch (RuntimeException unavailableOrInvalidTransactionId) {
            return 0L;
        }
    }

    private long forwardingHistoryOrderTime(org.json.JSONObject row) {
        if (row == null) return 0L;
        long transactionTime = row.optLong("transactionTime", 0L);
        return transactionTime > 0L
                ? transactionTime
                : row.optLong("created", row.optLong("updated", 0L));
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
                // Older versions could leave multiple snapshots for the same incoming TXID.
                // Keep the freshest state for each receipt, while preserving its original created time.
                java.util.LinkedHashMap<String, org.json.JSONObject> entriesByIncoming =
                        new java.util.LinkedHashMap<>();
                for (int i = 0; i < old.length(); i++) {
                    org.json.JSONObject row = old.optJSONObject(i);
                    if (row == null) continue;
                    String rowIncoming = row.optString("incoming", "");
                    if (rowIncoming.isEmpty()) continue;
                    org.json.JSONObject existing = entriesByIncoming.get(rowIncoming);
                    if (existing == null) {
                        entriesByIncoming.put(rowIncoming, new org.json.JSONObject(row.toString()));
                        continue;
                    }
                    long existingUpdated = existing.optLong("updated",
                            existing.optLong("created", 0L));
                    long rowUpdated = row.optLong("updated", row.optLong("created", 0L));
                    long firstCreated = Math.min(existing.optLong("created", existingUpdated),
                            row.optLong("created", rowUpdated));
                    org.json.JSONObject freshest = rowUpdated >= existingUpdated
                            ? new org.json.JSONObject(row.toString()) : existing;
                    freshest.put("created", firstCreated);
                    // Retain stable metadata if the freshest legacy snapshot lacks it.
                    for (String key : new String[] {"destination", "mode", "fixedAmount", "network", "feeRate", "raw", "attemptStarted",
                            "transactionTime", "retryableFailure", "retryCount", "nextRetryAt", "lastRetryError", "lastRetryAt",
                            "failureConfigFingerprint", "failureReconcileCount", "lastFailureReconcileAt",
                            "nextFailureReconcileAt"}) {
                        if (freshest.has(key)) continue;
                        if (existing.has(key)) freshest.put(key, existing.opt(key));
                        else if (row.has(key)) freshest.put(key, row.opt(key));
                    }
                    entriesByIncoming.put(rowIncoming, freshest);
                }
                org.json.JSONObject previous = entriesByIncoming.remove(incomingTxId);
                java.util.ArrayList<org.json.JSONObject> others =
                        new java.util.ArrayList<>(entriesByIncoming.values());

                org.json.JSONObject item;
                if (latest) {
                    item = new org.json.JSONObject();
                    item.put("created", previous != null
                            ? previous.optLong("created", previous.optLong("updated", System.currentTimeMillis()))
                            : System.currentTimeMillis());
                    if (previous != null) {
                        // The global LAST_* fields are the UI snapshot, not the complete
                        // durable job record. Retain per-job recovery metadata when rebuilding
                        // the current row; otherwise ordinary confirmation updates can erase
                        // attemptStarted and stale-failure markers, making a live attempt look
                        // abandoned and repeatedly releasing its sender lease.
                        for (String key : new String[] {
                                "destination", "mode", "fixedAmount", "network", "feeRate", "raw",
                                "transactionTime", "attemptStarted", "requiredConfirmations", "previousFailureStatus",
                                "requeuedStaleFailure", "lastRetryError", "lastRetryAt", "generation",
                                "lastRecoveryChecked", "reconciledLegacyFailure", "reconciledAt",
                                "retryableFailure", "retryCount", "nextRetryAt", "lastPermanentFailureAt",
                                "failureConfigFingerprint", "failureReconcileCount", "lastFailureReconcileAt",
                                "nextFailureReconcileAt"
                        }) {
                            if (previous.has(key)) item.put(key, previous.opt(key));
                        }
                    }
                    item.put("incoming", incomingTxId);
                    String snapshotOutgoing = prefs.getString(FORWARDING_LAST_OUTGOING_TX, "");
                    // A legacy/current row may carry a durable outgoing TXID even when the
                    // summary preference was lost or stale. Keep the ledger's TXID unless the
                    // caller explicitly overwrites it in `changes` below.
                    item.put("outgoing", !snapshotOutgoing.isEmpty() ? snapshotOutgoing
                            : (previous == null ? "" : previous.optString("outgoing", "")));
                    item.put("stage", prefs.getString(FORWARDING_LAST_STAGE, ""));
                    item.put("status", prefs.getString(FORWARDING_STATUS, ""));
                    item.put("received", prefs.getLong(FORWARDING_LAST_RECEIVED_SATS, 0L));
                    item.put("amount", prefs.getLong(FORWARDING_LAST_AMOUNT_SATS, 0L));
                    item.put("fee", prefs.getLong(FORWARDING_LAST_FEE_SATS, 0L));
                    item.put("rate", prefs.getInt(FORWARDING_LAST_RATE_SAT_VB, 0));
                    item.put("effectiveRate", prefs.getInt(FORWARDING_LAST_EFFECTIVE_RATE_SAT_VB, 0));
                    item.put("vsize", prefs.getInt(FORWARDING_LAST_VSIZE, 0));
                    item.put("currentConfirmations", prefs.getInt(FORWARDING_LAST_CURRENT_CONFIRMATIONS, -1));
                    item.put("requiredConfirmations", previous != null
                            ? previous.optInt("requiredConfirmations",
                                    prefs.getInt(FORWARDING_LAST_REQUIRED_CONFIRMATIONS,
                                            prefs.getInt(FORWARDING_CONFIRMATIONS, 1)))
                            : prefs.getInt(FORWARDING_LAST_REQUIRED_CONFIRMATIONS,
                                    prefs.getInt(FORWARDING_CONFIRMATIONS, 1)));
                    item.put("outgoingConfirmations", prefs.getInt(FORWARDING_LAST_OUTGOING_CONFIRMATIONS, -1));
                    if (!item.has("destination")) item.put("destination", prefs.getString(FORWARDING_ADDRESS, ""));
                    if (!item.has("mode")) item.put("mode", prefs.getString(FORWARDING_MODE, "sweep"));
                    if (!item.has("fixedAmount")) item.put("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L));
                    if (!item.has("feeRate")) item.put("feeRate", prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5));
                    if (!item.has("network")) item.put("network", NetworkConfig.storageName(NetworkConfig.get(applicationContext)));
                } else if (previous != null) {
                    // Non-current history rows must still accept reconciliation updates.
                    // Previously this branch reused the old object unchanged, silently dropping
                    // stage/status/TXID/amount changes for every card except the global "latest"
                    // transaction. That made old false failures appear unrepaired in the UI.
                    item = new org.json.JSONObject(previous.toString());
                    java.util.Iterator<String> changedKeys = changes.keys();
                    while (changedKeys.hasNext()) {
                        String changedKey = changedKeys.next();
                        item.put(changedKey, changes.opt(changedKey));
                    }
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
                    item.put("requiredConfirmations", Math.max(1, prefs.getInt(FORWARDING_CONFIRMATIONS, 1)));
                    item.put("outgoingConfirmations", -1);
                    item.put("destination", prefs.getString(FORWARDING_ADDRESS, ""));
                    item.put("mode", prefs.getString(FORWARDING_MODE, "sweep"));
                    item.put("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L));
                    item.put("feeRate", prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5));
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
                long knownTransactionTime = resolveForwardingTransactionTime(incomingTxId);
                if (knownTransactionTime > 0L) {
                    // Bitcoinj's transaction update time becomes block time when confirmed.
                    // Keep a ledger creation-time fallback until the wallet knows the TX time.
                    item.put("transactionTime", knownTransactionTime);
                }
                // Leave transactionTime absent when bitcoinj has no timestamp yet. The UI can
                // continue resolving it from the wallet later; created is only a stable fallback.
                item.put("updated", System.currentTimeMillis());
                String ledgerStage = item.optString("stage", "");
                if ("confirmed".equals(ledgerStage) || "disabled".equals(ledgerStage)) {
                    // Pending signed bytes are only needed until this chain reaches a terminal state.
                    item.remove("raw");
                }

                // One card per distinct incoming transaction. Never suppress a receipt merely
                // because its TXID appears as another row's outgoing TXID: historical records
                // and wallet/network relationships are not sufficient proof that it is a duplicate.
                java.util.ArrayList<org.json.JSONObject> normalized = new java.util.ArrayList<>();
                normalized.add(item);
                normalized.addAll(others);
                java.util.Collections.sort(normalized, (a, b) -> {
                    int byTransactionTime = Long.compare(
                            forwardingHistoryOrderTime(b), forwardingHistoryOrderTime(a));
                    if (byTransactionTime != 0) return byTransactionTime;
                    int byCreated = Long.compare(
                            b.optLong("created", b.optLong("updated", 0L)),
                            a.optLong("created", a.optLong("updated", 0L)));
                    if (byCreated != 0) return byCreated;
                    return a.optString("incoming", "").compareTo(b.optString("incoming", ""));
                });
                org.json.JSONArray next = new org.json.JSONArray();
                java.util.HashSet<String> finalSeen = new java.util.HashSet<>();
                for (org.json.JSONObject row : normalized) {
                    String rowIncoming = row.optString("incoming", "");
                    if (rowIncoming.isEmpty() || !finalSeen.add(rowIncoming)) continue;
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

    /**
     * Returns the activation time used to distinguish missed receipts from transactions that
     * pre-date enabling forwarding. Older app versions did not persist this value, so migrate from
     * the most recent ledger activity when possible; if no forwarding history exists, start now
     * rather than automatically sweeping old wallet history.
     */
    private long ensureForwardingEnabledAt(android.content.SharedPreferences prefs, Wallet wallet) {
        long enabledAt = prefs.getLong(FORWARDING_ENABLED_AT_MS, 0L);
        if (enabledAt <= 0L) {
            long migratedAt = 0L;
            try {
                org.json.JSONArray ledger = new org.json.JSONArray(
                        prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
                for (int i = 0; i < ledger.length(); i++) {
                    org.json.JSONObject row = ledger.optJSONObject(i);
                    if (row == null) continue;
                    migratedAt = Math.max(migratedAt,
                            row.optLong("updated", row.optLong("created", 0L)));
                }
            } catch (org.json.JSONException ignored) { }
            enabledAt = migratedAt > 0L ? migratedAt : System.currentTimeMillis();
            prefs.edit().putLong(FORWARDING_ENABLED_AT_MS, enabledAt).apply();
        }

        int enabledAtHeight = prefs.getInt(FORWARDING_ENABLED_AT_HEIGHT, -1);
        if (enabledAtHeight < 0) {
            int bestHeight = getActiveBestChainHeight();
            int highestRecordedHeight = -1;
            try {
                org.json.JSONArray ledger = new org.json.JSONArray(
                        prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
                for (int i = 0; i < ledger.length(); i++) {
                    org.json.JSONObject row = ledger.optJSONObject(i);
                    if (row == null) continue;
                    String txId = row.optString("incoming", "");
                    if (txId.isEmpty() || wallet == null) continue;
                    Transaction tx = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(txId));
                    if (tx == null || tx.getConfidence() == null) continue;
                    int depth = Math.max(0, tx.getConfidence().getDepthInBlocks());
                    if (depth > 0 && bestHeight >= 0) {
                        highestRecordedHeight = Math.max(highestRecordedHeight, bestHeight - depth + 1);
                    }
                }
            } catch (Exception ignored) { }
            if (highestRecordedHeight >= 0) {
                enabledAtHeight = highestRecordedHeight;
                prefs.edit().putInt(FORWARDING_ENABLED_AT_HEIGHT, enabledAtHeight).apply();
            } else if (bestHeight >= 0) {
                // For legacy settings without an incoming ledger row, don't replay pre-existing
                // chain history. If the chain height is not known yet, defer this migration.
                enabledAtHeight = bestHeight;
                prefs.edit().putInt(FORWARDING_ENABLED_AT_HEIGHT, enabledAtHeight).apply();
            }
        }
        return enabledAt;
    }

    private boolean isReceiptEligibleSinceActivation(
            android.content.SharedPreferences prefs, Wallet wallet, Transaction transaction) {
        if (prefs == null || wallet == null || transaction == null) return false;
        if (wasPendingBeforeForwardingEnabled(prefs, transaction.getTxId().toString())) return false;
        long enabledAt = ensureForwardingEnabledAt(prefs, wallet);
        try {
            int depth = transaction.getConfidence() == null ? 0
                    : Math.max(0, transaction.getConfidence().getDepthInBlocks());
            int bestHeight = getActiveBestChainHeight();
            int enabledAtHeight = prefs.getInt(FORWARDING_ENABLED_AT_HEIGHT, -1);
            if (depth > 0 && bestHeight >= 0 && enabledAtHeight > 0) {
                int transactionHeight = bestHeight - depth + 1;
                // Block time can pre-date the wallet's observation by minutes or hours; use the
                // saved chain-height checkpoint for confirmed receipts instead of block timestamp.
                // A zero-height checkpoint is not trustworthy (the wallet may have been enabled
                // before initial sync), so use the activation timestamp until a real height is saved.
                // This avoids treating the entire history imported during initial sync as new work.
                return transactionHeight > enabledAtHeight;
            }
            java.util.Optional<Instant> updateTime = transaction.updateTime();
            // A timestamp-less, unconfirmed transaction cannot safely be distinguished from old
            // history. Skip it until a normal receive event or a later confidence update provides
            // enough information, avoiding an accidental history-wide sweep.
            return updateTime.isPresent() && updateTime.get().toEpochMilli() >= enabledAt;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean wasPendingBeforeForwardingEnabled(
            android.content.SharedPreferences prefs, String txId) {
        if (prefs == null || txId == null || txId.isEmpty()) return false;
        try {
            org.json.JSONArray baseline = new org.json.JSONArray(
                    prefs.getString(FORWARDING_PRE_ENABLE_PENDING_TXIDS, "[]"));
            for (int i = 0; i < baseline.length(); i++) {
                if (txId.equals(baseline.optString(i, ""))) return true;
            }
        } catch (org.json.JSONException ignored) { }
        return false;
    }

    /** Keep recovery running while this wallet kit is active; app restarts install a fresh loop. */
    private void scheduleForwardingRecovery(WalletAppKit ownerKit, Wallet wallet) {
        if (ownerKit == null || wallet == null) return;
        synchronized (forwardingRecoveryLock) {
            if (forwardingRecoveryTask != null) {
                forwardingRecoveryTask.cancel(false);
                forwardingRecoveryTask = null;
            }
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> reference =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.ScheduledFuture<?> task = FORWARDING_STATUS_EXECUTOR.scheduleWithFixedDelay(() -> {
                java.util.concurrent.ScheduledFuture<?> currentTask = reference.get();
                if (shuttingDown || !isCurrentKit(ownerKit)) {
                    if (currentTask != null) currentTask.cancel(false);
                    synchronized (forwardingRecoveryLock) {
                        if (forwardingRecoveryTask == currentTask) forwardingRecoveryTask = null;
                    }
                    return;
                }
                // A short-lived wallet selection/lifecycle switch must not permanently cancel
                // this recovery loop. Leave the durable ledger untouched and retry next tick.
                if (getActiveWallet() != wallet) return;
                recoverForwardingWork(ownerKit, wallet);
            }, 5L, 15L, TimeUnit.SECONDS);
            reference.set(task);
            forwardingRecoveryTask = task;
        }
    }

    /**
     * Repairs false failures left by older forwarding implementations. A ledger status is only
     * historical app state; the wallet's real transaction graph and confidence are authoritative.
     * A row is repaired in place (keyed by the original incoming TXID), never duplicated, and no
     * replacement transaction is constructed by this reconciliation pass.
     */
    private void reconcileFailedForwardingLedgerEntries(
            WalletAppKit ownerKit, Wallet wallet, android.content.SharedPreferences prefs,
            List<Transaction> walletTransactions) {
        if (ownerKit == null || wallet == null || prefs == null || walletTransactions == null
                || !isCurrentKit(ownerKit) || getActiveWallet() != wallet) return;

        final String activeNetwork = NetworkConfig.storageName(NetworkConfig.get(applicationContext));
        final java.util.Map<String, Transaction> byTxId = new java.util.HashMap<>();
        for (Transaction transaction : walletTransactions) {
            if (transaction != null) byTxId.put(transaction.getTxId().toString(), transaction);
        }

        final List<org.json.JSONObject> failedRows = new ArrayList<>();
        int failedRowsIncluded = 0;
        synchronized (FORWARDING_LEDGER_LOCK) {
            try {
                org.json.JSONArray ledger = new org.json.JSONArray(
                        prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
                for (int i = 0; i < ledger.length(); i++) {
                    org.json.JSONObject row = ledger.optJSONObject(i);
                    if (row != null) {
                        String rowNetwork = row.optString("network", "").trim();
                        if (!rowNetwork.isEmpty() && !activeNetwork.equalsIgnoreCase(rowNetwork)) continue;
                        String ledgerStage = row.optString("stage", "").trim();
                        // A previous recovery pass may have changed a stale false-failure to
                        // waiting. Keep reconciling waiting rows too, because their real outgoing
                        // transaction may already exist in wallet/blockchain history.
                        long now = System.currentTimeMillis();
                        // Failed rows are not hammered on every 15-second recovery tick.
                        // Their TXID/history is rechecked with a persisted exponential backoff.
                        if ("failed".equals(ledgerStage)) {
                            if (row.optLong("nextFailureReconcileAt", 0L) > now
                                    || failedRowsIncluded >= FORWARDING_FAILED_RECONCILIATION_BATCH) {
                                continue;
                            }
                            failedRowsIncluded++;
                        }
                        if ("failed".equals(ledgerStage) || "waiting".equals(ledgerStage)
                                || "broadcasting".equals(ledgerStage)
                                || "relayed".equals(ledgerStage)) {
                            failedRows.add(new org.json.JSONObject(row.toString()));
                        }
                    }
                }
            } catch (org.json.JSONException ignored) {
                return;
            }
        }

        for (org.json.JSONObject row : failedRows) {
            if (!isCurrentKit(ownerKit) || getActiveWallet() != wallet) return;
            String incomingTxId = row.optString("incoming", "").trim();
            if (incomingTxId.isEmpty()) continue;
            String rowNetwork = row.optString("network", "").trim();
            if (!rowNetwork.isEmpty() && !activeNetwork.equalsIgnoreCase(rowNetwork)) continue;

            if ("failed".equals(row.optString("stage", "").trim())) {
                long checkedAt = System.currentTimeMillis();
                int reconcileCount = Math.min(32, row.optInt("failureReconcileCount", 0) + 1);
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "failureReconcileCount", reconcileCount,
                        "lastFailureReconcileAt", checkedAt,
                        "nextFailureReconcileAt", checkedAt + forwardingRetryDelayMs(reconcileCount)));
            }

            Transaction incoming = byTxId.get(incomingTxId);
            if (incoming == null) {
                try {
                    incoming = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(incomingTxId));
                } catch (RuntimeException ignored) { }
            }
            String savedOutgoingId = row.optString("outgoing", "").trim();
            String destination = row.optString("destination", "").trim();
            if (incoming == null) {
                // A wallet rescan/import can temporarily omit the incoming transaction while the
                // already-recorded outgoing TX is still present. Reconcile that existing TXID
                // directly by its input outpoints; do not require the receipt object just to
                // repair the old card, and never create a replacement transaction here.
                Transaction knownOutgoing = savedOutgoingId.isEmpty()
                        ? null : byTxId.get(savedOutgoingId);
                if (knownOutgoing == null && !savedOutgoingId.isEmpty()) {
                    try {
                        knownOutgoing = wallet.getTransaction(
                                org.bitcoinj.base.Sha256Hash.wrap(savedOutgoingId));
                    } catch (RuntimeException ignored) { }
                }
                if (knownOutgoing == null || !forwardingTransactionSpends(knownOutgoing, incomingTxId)) {
                    continue;
                }
                if (!destination.isEmpty()
                        && forwardingValuePaidToAddress(knownOutgoing, wallet, destination) <= 0L) {
                    continue;
                }
                org.bitcoinj.core.TransactionConfidence knownConfidence;
                try { knownConfidence = knownOutgoing.getConfidence(); }
                catch (RuntimeException incompleteConfidence) { continue; }
                if (knownConfidence != null && knownConfidence.getConfidenceType()
                        == org.bitcoinj.core.TransactionConfidence.ConfidenceType.DEAD) continue;
                int knownDepth = knownConfidence == null ? 0
                        : Math.max(0, knownConfidence.getDepthInBlocks());
                boolean knownRelayed = knownDepth == 0 && knownConfidence != null
                        && knownConfidence.getConfidenceType()
                                == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                        && knownConfidence.numBroadcastPeers() > 0;
                String knownStage = knownDepth > 0 ? "confirmed"
                        : (knownRelayed ? "relayed" : "broadcasting");
                String knownStatus = knownDepth > 0
                        ? applicationContext.getString(R.string.forwarding_status_confirmed,
                                knownOutgoing.getTxId().toString())
                        : (knownRelayed
                                ? applicationContext.getString(R.string.forwarding_status_forwarded,
                                        incomingTxId, knownOutgoing.getTxId().toString())
                                : applicationContext.getString(R.string.forwarding_status_broadcasting,
                                        incomingTxId));
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "outgoing", knownOutgoing.getTxId().toString(),
                        "stage", knownStage,
                        "status", knownStatus,
                        "destination", destination,
                        "outgoingConfirmations", knownDepth,
                        "requeuedStaleFailure", false,
                        "reconciledLegacyFailure", true,
                        "reconciledAt", System.currentTimeMillis()));
                continue;
            }

            boolean destinationWasPersisted = !destination.isEmpty();
            if (destination.isEmpty()) {
                // Older ledger rows may not have persisted the original destination. The current
                // setting is the first search hint, but it may have changed since this row was made.
                destination = prefs.getString(FORWARDING_ADDRESS, "").trim();
            }
            String currentStoredStatus = row.optString("status", "").trim();
            String priorFailureStatus = row.optString("previousFailureStatus", "").trim();
            String historicalStatus = priorFailureStatus.isEmpty()
                    ? currentStoredStatus : priorFailureStatus;
            boolean knownContextFailure = isForwardingContextChangeFailure(historicalStatus)
                    || row.optBoolean("requeuedStaleFailure", false);
            Transaction outgoing = savedOutgoingId.isEmpty() ? null : byTxId.get(savedOutgoingId);
            if (outgoing == null && !savedOutgoingId.isEmpty()) {
                try {
                    outgoing = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(savedOutgoingId));
                } catch (RuntimeException ignored) { }
            }

            // Older versions sometimes wrote "failed" without persisting the outgoing TXID.
            // Recover it only from a transaction which spends this exact incoming TX and pays
            // the exact destination recorded for that historical forwarding row.
            if (outgoing == null && savedOutgoingId.isEmpty() && !destination.isEmpty()) {
                outgoing = findHistoricalForwardingSpend(wallet, walletTransactions,
                        incomingTxId, destination);
            }
            String inferredExternalDestination = "";
            if (outgoing == null && savedOutgoingId.isEmpty()
                    && !destinationWasPersisted && knownContextFailure) {
                // Legacy false-failure rows can have lost the original destination. If a real
                // wallet transaction already spends the exact incoming TX and pays an external
                // address, recover that existing TX instead of creating another transaction.
                outgoing = findHistoricalExternalSpend(wallet, walletTransactions, incomingTxId);
                if (outgoing != null) {
                    inferredExternalDestination = forwardingExternalDestination(outgoing, wallet);
                    if (!inferredExternalDestination.isEmpty()) destination = inferredExternalDestination;
                    else outgoing = null;
                }
            }
            if (outgoing == null) {
                // Some older versions incorrectly marked an operation failed when the active
                // wallet/network changed at the final pre-broadcast check. That is not proof that
                // the Bitcoin spend failed: no outgoing TX was committed. Requeue only this
                // explicitly transient failure, only on its original network, and only when the
                // feature is enabled and a usable destination/configuration is available.
                String currentStatus = row.optString("status", "").trim();
                String previousFailureStatus = row.optString("previousFailureStatus", "").trim();
                // Once a false failure was requeued, "status" becomes a waiting message.
                // The original failure reason must remain the source for deciding whether
                // it is safe to resume; otherwise the next scan forgets why it was requeued.
                String previousStatus = previousFailureStatus.isEmpty()
                        ? currentStatus : previousFailureStatus;
                String normalizedCurrent = currentStatus.toLowerCase(java.util.Locale.ROOT);
                String normalizedPrevious = previousStatus.toLowerCase(java.util.Locale.ROOT);
                boolean currentIsTransient =
                        (normalizedCurrent.contains("wallet or network")
                                && normalizedCurrent.contains("before broadcast"))
                        || (normalizedCurrent.contains("ví hoặc mạng")
                                && normalizedCurrent.contains("trước khi phát giao dịch"));
                boolean previousIsTransient =
                        (normalizedPrevious.contains("wallet or network")
                                && normalizedPrevious.contains("before broadcast"))
                        || (normalizedPrevious.contains("ví hoặc mạng")
                                && normalizedPrevious.contains("trước khi phát giao dịch"));
                boolean hasRetryabilityDecision = row.has("retryableFailure");
                boolean configChangedSinceFailure = row.has("failureConfigFingerprint")
                        && !row.optString("failureConfigFingerprint", "").equals(
                                forwardingConfigFingerprint(prefs));
                boolean transientContextFailure = row.optBoolean("retryableFailure", false)
                        || (!hasRetryabilityDecision && (currentIsTransient || previousIsTransient
                                || row.optBoolean("requeuedStaleFailure", false)))
                        || configChangedSinceFailure;
                if (!transientContextFailure
                        || !prefs.getBoolean(FORWARDING_ENABLED, false)
                        || !activeNetwork.equalsIgnoreCase(rowNetwork.isEmpty()
                                ? activeNetwork : rowNetwork)) continue;

                // A persisted signed TX or known outgoing TXID must always go through in-flight
                // recovery; never create another transaction while those bytes/IDs are available.
                if (!row.optString("raw", "").trim().isEmpty() || !savedOutgoingId.isEmpty()) {
                    continue;
                }

                String retryDestination = configChangedSinceFailure
                        ? prefs.getString(FORWARDING_ADDRESS, "").trim()
                        : (destination.isEmpty()
                                ? prefs.getString(FORWARDING_ADDRESS, "").trim() : destination);
                if (retryDestination.isEmpty()) continue;
                int required = Math.max(1, Math.min(100, configChangedSinceFailure
                        ? prefs.getInt(FORWARDING_CONFIRMATIONS, 1)
                        : row.optInt("requiredConfirmations",
                                prefs.getInt(FORWARDING_CONFIRMATIONS, 1))));
                int depth;
                long receivedSats;
                try {
                    org.bitcoinj.core.TransactionConfidence incomingConfidence = incoming.getConfidence();
                    depth = incomingConfidence == null ? 0
                            : Math.max(0, incomingConfidence.getDepthInBlocks());
                    Coin received = incoming.getValueSentToMe(wallet);
                    if (received == null || !received.isPositive()) continue;
                    receivedSats = received.value;
                } catch (RuntimeException walletNotReady) {
                    continue;
                }
                if (depth < required) {
                    // The old failure is stale, but the receipt still has a real confirmation
                    // requirement. Restore it to waiting so the regular confidence/recovery loop
                    // can resume it when the threshold is met.
                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                            "stage", "waiting",
                            "status", applicationContext.getString(
                                    R.string.forwarding_status_waiting_confirmation,
                                    incomingTxId, required),
                            "destination", retryDestination,
                            "mode", configChangedSinceFailure
                                    ? prefs.getString(FORWARDING_MODE, "sweep")
                                    : row.optString("mode", prefs.getString(FORWARDING_MODE, "sweep")),
                            "fixedAmount", configChangedSinceFailure
                                    ? prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L)
                                    : row.optLong("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L)),
                            "feeRate", configChangedSinceFailure
                                    ? prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)
                                    : row.optInt("feeRate", prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)),
                            "network", activeNetwork,
                            "received", receivedSats,
                            "currentConfirmations", depth,
                            "requiredConfirmations", required,
                            "previousFailureStatus", previousStatus,
                            "requeuedStaleFailure", true,
                            "retryableFailure", true,
                            "retryCount", configChangedSinceFailure ? 0 : row.optInt("retryCount", 0),
                            "nextRetryAt", System.currentTimeMillis(),
                            "updated", System.currentTimeMillis()));
                    continue;
                }

                String retryMode = configChangedSinceFailure
                        ? prefs.getString(FORWARDING_MODE, "sweep") : row.optString("mode", "").trim();
                if (retryMode.isEmpty()) retryMode = prefs.getString(FORWARDING_MODE, "sweep");
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "stage", "waiting",
                        "status", applicationContext.getString(
                                R.string.forwarding_status_waiting_conditions, incomingTxId),
                        "destination", retryDestination,
                        "mode", retryMode,
                        "fixedAmount", configChangedSinceFailure
                                ? prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L)
                                : row.optLong("fixedAmount", prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L)),
                        "feeRate", configChangedSinceFailure
                                ? prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)
                                : row.optInt("feeRate", prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)),
                        "network", activeNetwork,
                        "received", receivedSats,
                        "currentConfirmations", depth,
                        "requiredConfirmations", required,
                        "previousFailureStatus", previousStatus,
                        "requeuedStaleFailure", true,
                        "retryableFailure", true,
                        "retryCount", configChangedSinceFailure ? 0 : row.optInt("retryCount", 0),
                        "nextRetryAt", System.currentTimeMillis(),
                        "updated", System.currentTimeMillis()));

                // The outgoing lookup above proved there is no committed transaction in this
                // wallet and the row has no saved signed bytes/TXID. Release a stale in-memory
                // lease left by the old failed attempt. If confirmations are already sufficient,
                // immediately re-enter the guarded sender instead of leaving a cosmetic waiting
                // card until another unrelated wallet event occurs.
                // Keep the existing sender lease. The waiting-stage dispatcher below releases it
                // only after the two-minute stale threshold; clearing it here could start duplicates.
                // Do not start the sender here: the main recovery dispatcher handles this waiting
                // row a few lines later in the same scan. Starting both paths races the lease and
                // can leave a valid attempt pinned as waiting.
                continue;
            }

            String actualOutgoingId = outgoing.getTxId().toString();
            boolean spendsIncoming = forwardingTransactionSpends(outgoing, incomingTxId);
            long amountToDestination = forwardingValuePaidToAddress(outgoing, wallet, destination);
            boolean paysSavedDestination = amountToDestination > 0L;
            if (!paysSavedDestination && !destinationWasPersisted && knownContextFailure) {
                String inferred = forwardingExternalDestination(outgoing, wallet);
                if (!inferred.isEmpty()) {
                    long inferredAmount = forwardingValuePaidToAddress(outgoing, wallet, inferred);
                    if (inferredAmount > 0L) {
                        destination = inferred;
                        amountToDestination = inferredAmount;
                        paysSavedDestination = true;
                    }
                }
            }
            if (!spendsIncoming && !paysSavedDestination) {
                // A stale/global TXID can be attached to the wrong card by an old version. Do not
                // promote it unless the live transaction graph or saved destination links it back.
                continue;
            }
            if (!destination.isEmpty() && !paysSavedDestination) {
                // A transaction associated with this row but paying somewhere else is not proof
                // that the configured forwarding operation succeeded.
                continue;
            }

            org.bitcoinj.core.TransactionConfidence confidence;
            try { confidence = outgoing.getConfidence(); }
            catch (RuntimeException incompleteConfidence) { continue; }
            if (confidence != null && confidence.getConfidenceType()
                    == org.bitcoinj.core.TransactionConfidence.ConfidenceType.DEAD) {
                // A dead/conflicted transaction is not a successful forward. Leave it for normal
                // failure/recovery handling rather than hiding a genuine failure.
                continue;
            }
            int outgoingDepth = confidence == null ? 0
                    : Math.max(0, confidence.getDepthInBlocks());
            boolean relayed = confidence != null
                    && confidence.getConfidenceType()
                            == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                    && confidence.numBroadcastPeers() > 0;
            String repairedStage = outgoingDepth > 0 ? "confirmed"
                    : (relayed ? "relayed" : "broadcasting");
            String repairedStatus = outgoingDepth > 0
                    ? applicationContext.getString(R.string.forwarding_status_confirmed, actualOutgoingId)
                    : (relayed
                            ? applicationContext.getString(R.string.forwarding_status_forwarded,
                                    incomingTxId, actualOutgoingId)
                            : applicationContext.getString(R.string.forwarding_status_broadcasting,
                                    incomingTxId));

            org.json.JSONObject changes = forwardingFields(
                    "outgoing", actualOutgoingId,
                    "stage", repairedStage,
                    "status", repairedStatus,
                    "destination", destination,
                    "requeuedStaleFailure", false,
                    "outgoingConfirmations", outgoingDepth,
                    "reconciledLegacyFailure", true,
                    "reconciledAt", System.currentTimeMillis(),
                    "previousFailureStatus", row.optString("status", ""));
            try {
                Coin received = incoming.getValueSentToMe(wallet);
                if (received != null && received.isPositive()) changes.put("received", received.value);
                org.bitcoinj.core.TransactionConfidence incomingConfidence = incoming.getConfidence();
                int incomingDepth = incomingConfidence == null ? 0
                        : Math.max(0, incomingConfidence.getDepthInBlocks());
                changes.put("currentConfirmations", incomingDepth);
            } catch (Exception ignored) { }
            if (paysSavedDestination) {
                try { changes.put("amount", amountToDestination); }
                catch (org.json.JSONException ignored) { }
            }
            try {
                Coin fee = outgoing.getFee();
                if (fee != null && fee.value >= 0L) {
                    try { changes.put("fee", fee.value); }
                    catch (org.json.JSONException ignored) { }
                }
            } catch (RuntimeException ignored) { }
            try {
                int vsize = outgoing.getVsize();
                if (vsize > 0) {
                    changes.put("vsize", vsize);
                    Coin fee = outgoing.getFee();
                    if (fee != null && fee.value >= 0L) {
                        changes.put("effectiveRate", (int) Math.min(Integer.MAX_VALUE,
                                (fee.value + vsize - 1L) / vsize));
                    }
                }
            } catch (Exception ignored) { }

            updateForwardingLedgerEntry(prefs, incomingTxId, changes);
            // If the transaction is in the wallet but cannot yet be shown as relayed or confirmed,
            // the ordinary recovery loop may rebroadcast this exact TXID. It will never sign a
            // replacement merely because the old ledger said "failed".
        }
    }

    private String forwardingConfigFingerprint(android.content.SharedPreferences prefs) {
        if (prefs == null) return "";
        return NetworkConfig.storageName(NetworkConfig.get(applicationContext)) + "|"
                + prefs.getString(FORWARDING_ADDRESS, "").trim() + "|"
                + prefs.getString(FORWARDING_MODE, "sweep") + "|"
                + prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L) + "|"
                + prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5) + "|"
                + Math.max(1, prefs.getInt(FORWARDING_CONFIRMATIONS, 1));
    }

    private static boolean isForwardingContextChangeFailure(String status) {
        if (status == null || status.trim().isEmpty()) return false;
        String normalized = status.toLowerCase(java.util.Locale.ROOT);
        return (normalized.contains("wallet or network") && normalized.contains("before broadcast"))
                || (normalized.contains("ví hoặc mạng")
                        && normalized.contains("trước khi phát giao dịch"));
    }

    private boolean hasPositiveValueSentFromWallet(Wallet wallet, Transaction transaction) {
        if (wallet == null || transaction == null) return true;
        try {
            Coin sent = transaction.getValueSentFromMe(wallet);
            return sent != null && sent.isPositive();
        } catch (RuntimeException incompleteWalletView) {
            return true;
        }
    }

    private boolean hasSpendableOutputFromIncoming(Wallet wallet, Transaction incoming) {
        if (wallet == null || incoming == null) return false;
        try {
            for (TransactionOutput output : incoming.getOutputs()) {
                if (output != null && output.isAvailableForSpending()
                        && output.isMine(wallet)
                        && output.getParentTransactionHash() != null
                        && output.getParentTransactionHash().equals(incoming.getTxId())) {
                    return true;
                }
            }
        } catch (RuntimeException walletViewIncomplete) {
            return false;
        }
        return false;
    }

    /** Finds a real spend of the exact receipt whose destination metadata was lost by old versions. */
    private Transaction findHistoricalExternalSpend(
            Wallet wallet, List<Transaction> transactions, String incomingTxId) {
        if (wallet == null || transactions == null || incomingTxId == null || incomingTxId.isEmpty()) {
            return null;
        }
        Transaction best = null;
        int bestDepth = -1;
        for (Transaction candidate : transactions) {
            if (candidate == null || incomingTxId.equals(candidate.getTxId().toString())
                    || !forwardingTransactionSpends(candidate, incomingTxId)) continue;
            org.bitcoinj.core.TransactionConfidence confidence;
            try { confidence = candidate.getConfidence(); }
            catch (RuntimeException ignored) { continue; }
            if (confidence != null && confidence.getConfidenceType()
                    == org.bitcoinj.core.TransactionConfidence.ConfidenceType.DEAD) continue;
            String externalDestination = forwardingExternalDestination(candidate, wallet);
            if (externalDestination.isEmpty()) continue;
            int depth = confidence == null ? 0 : Math.max(0, confidence.getDepthInBlocks());
            if (best == null || depth > bestDepth) {
                best = candidate;
                bestDepth = depth;
            }
        }
        return best;
    }

    /** Returns the largest positive output address not owned by this wallet. */
    private String forwardingExternalDestination(Transaction transaction, Wallet wallet) {
        if (transaction == null || wallet == null) return "";
        long largestValue = 0L;
        String largestAddress = "";
        try {
            for (TransactionOutput output : transaction.getOutputs()) {
                if (output == null || output.getValue() == null
                        || output.getValue().value <= 0L || output.getScriptPubKey() == null) continue;
                try {
                    Address address = output.getScriptPubKey().getToAddress(wallet.getParams());
                    if (address == null || wallet.isAddressMine(address)) continue;
                    if (output.getValue().value > largestValue) {
                        largestValue = output.getValue().value;
                        largestAddress = address.toString();
                    }
                } catch (Exception ignored) { }
            }
        } catch (RuntimeException ignored) { }
        return largestAddress;
    }

    private Transaction findHistoricalForwardingSpend(Wallet wallet, List<Transaction> transactions,
            String incomingTxId, String destination) {
        if (wallet == null || transactions == null || incomingTxId == null
                || incomingTxId.isEmpty() || destination == null || destination.isEmpty()) return null;
        Transaction best = null;
        int bestDepth = -1;
        for (Transaction candidate : transactions) {
            if (candidate == null || candidate.getTxId().toString().equals(incomingTxId)
                    || !forwardingTransactionSpends(candidate, incomingTxId)
                    || forwardingValuePaidToAddress(candidate, wallet, destination) <= 0L) continue;
            org.bitcoinj.core.TransactionConfidence confidence;
            try { confidence = candidate.getConfidence(); }
            catch (RuntimeException ignored) { continue; }
            if (confidence != null && confidence.getConfidenceType()
                    == org.bitcoinj.core.TransactionConfidence.ConfidenceType.DEAD) continue;
            int depth = confidence == null ? 0 : Math.max(0, confidence.getDepthInBlocks());
            if (best == null || depth > bestDepth) {
                best = candidate;
                bestDepth = depth;
            }
        }
        return best;
    }

    private boolean forwardingTransactionSpends(Transaction transaction, String incomingTxId) {
        if (transaction == null || incomingTxId == null || incomingTxId.isEmpty()) return false;
        try {
            for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
                org.bitcoinj.core.TransactionOutPoint outpoint = input.getOutpoint();
                if (outpoint != null && outpoint.hash() != null
                        && incomingTxId.equals(outpoint.hash().toString())) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private long forwardingValuePaidToAddress(Transaction transaction, Wallet wallet, String address) {
        if (transaction == null || wallet == null || address == null || address.trim().isEmpty()) return 0L;
        long total = 0L;
        try {
            for (TransactionOutput output : transaction.getOutputs()) {
                if (output == null || output.getScriptPubKey() == null) continue;
                try {
                    String outputAddress = output.getScriptPubKey().getToAddress(wallet.getParams()).toString();
                    if (address.trim().equals(outputAddress)) {
                        long value = output.getValue().value;
                        if (value > 0L && Long.MAX_VALUE - total >= value) total += value;
                    }
                } catch (Exception ignored) {
                    // Non-address outputs (e.g. OP_RETURN) are not destination payments.
                }
            }
        } catch (RuntimeException ignored) { }
        return total;
    }

    private static final int FORWARDING_FAILED_RECONCILIATION_BATCH = 10;

    /** Returns a snapshot of ledger rows that still need reconciliation or recovery. */
    private List<org.json.JSONObject> getForwardingRecoveryRows(
            android.content.SharedPreferences prefs) {
        List<org.json.JSONObject> rows = new ArrayList<>();
        if (prefs == null) return rows;
        try {
            org.json.JSONArray ledger = new org.json.JSONArray(
                    prefs.getString(FORWARDING_ACTIVITY_LEDGER, "[]"));
            int failedRowsIncluded = 0;
            long now = System.currentTimeMillis();
            String activeNetwork = NetworkConfig.storageName(NetworkConfig.get(applicationContext));
            for (int i = 0; i < ledger.length(); i++) {
                org.json.JSONObject row = ledger.optJSONObject(i);
                if (row == null) continue;
                String rowNetwork = row.optString("network", "").trim();
                if (!rowNetwork.isEmpty() && !activeNetwork.equalsIgnoreCase(rowNetwork)) continue;
                String stage = row.optString("stage", "").trim();
                if ("failed".equals(stage)) {
                    if (row.optLong("nextFailureReconcileAt", 0L) > now
                            || failedRowsIncluded >= FORWARDING_FAILED_RECONCILIATION_BATCH) {
                        continue;
                    }
                    failedRowsIncluded++;
                }
                if ("waiting".equals(stage) || "failed".equals(stage)
                        || "preparing".equals(stage) || "broadcasting".equals(stage)
                        || "relayed".equals(stage)) {
                    rows.add(new org.json.JSONObject(row.toString()));
                }
            }
        } catch (org.json.JSONException ignored) { }
        return rows;
    }

    private void addForwardingRecoveryTransaction(
            java.util.Map<String, Transaction> byId, Transaction tx) {
        if (byId == null || tx == null) return;
        byId.put(tx.getTxId().toString(), tx);
    }

    /**
     * Loads only transactions related to unfinished forwarding rows during normal recovery.
     * The complete wallet transaction set is enumerated only once, when enabled forwarding
     * first needs to catch up across the activation boundary. Newly synced receipts are handled
     * by the wallet event listener installed before SPV synchronization starts.
     */
    private List<Transaction> loadForwardingRecoveryTransactions(
            Wallet wallet, boolean bootstrapScan, List<org.json.JSONObject> recoveryRows) {
        java.util.LinkedHashMap<String, Transaction> byId = new java.util.LinkedHashMap<>();
        if (bootstrapScan) {
            for (Transaction tx : wallet.getTransactions(true)) addForwardingRecoveryTransaction(byId, tx);
        } else {
            for (org.json.JSONObject row : recoveryRows) {
                if (row == null) continue;
                String incomingId = row.optString("incoming", "").trim();
                String outgoingId = row.optString("outgoing", "").trim();
                if (!incomingId.isEmpty()) {
                    try {
                        addForwardingRecoveryTransaction(byId,
                                wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(incomingId)));
                    } catch (RuntimeException ignored) { }
                }
                if (!outgoingId.isEmpty()) {
                    try {
                        addForwardingRecoveryTransaction(byId,
                                wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(outgoingId)));
                    } catch (RuntimeException ignored) { }
                }
            }
            // Use bitcoinj's connected output->spent-input graph to discover an existing spend
            // without walking every transaction in wallet history for each 15-second tick.
            List<Transaction> receipts = new ArrayList<>(byId.values());
            for (Transaction receipt : receipts) {
                if (receipt == null) continue;
                try {
                    for (TransactionOutput output : receipt.getOutputs()) {
                        org.bitcoinj.core.TransactionInput spentBy = output.getSpentBy();
                        if (spentBy != null) addForwardingRecoveryTransaction(
                                byId, spentBy.getParentTransaction());
                    }
                } catch (RuntimeException ignored) { }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Reconciles saved forwarding work without rescanning the entire wallet history on every
     * recovery tick. A one-time activation-gap pass discovers older untracked receipts; thereafter
     * wallet receipt/confidence listeners discover new work, while only unfinished ledger rows
     * are checked here. Completed history remains durable and is not repeatedly traversed.
     */
    private void recoverForwardingWork(WalletAppKit ownerKit, Wallet wallet) {
        if (ownerKit == null || wallet == null || !isCurrentKit(ownerKit)
                || getActiveWallet() != wallet
                || !forwardingRecoveryScanInProgress.compareAndSet(false, true)) return;
        try {
            android.content.SharedPreferences prefs = applicationContext.getSharedPreferences(
                    forwardingPreferencesName(applicationContext), android.content.Context.MODE_PRIVATE);
            if (!wallet.network().equals(NetworkConfig.get(applicationContext))) return;

            boolean forwardingEnabled = prefs.getBoolean(FORWARDING_ENABLED, false);
            boolean restoreMergePending = pendingMnemonicBackupFile != null
                    && pendingMnemonicBackupFile.exists();
            boolean bootstrapScan = forwardingEnabled && !restoreMergePending
                    && !prefs.getBoolean(FORWARDING_RECOVERY_INCREMENTAL_BOOTSTRAP_DONE, false);
            if (forwardingEnabled) ensureForwardingEnabledAt(prefs, wallet);

            List<org.json.JSONObject> recoveryRows = getForwardingRecoveryRows(prefs);
            List<Transaction> transactions = loadForwardingRecoveryTransactions(
                    wallet, bootstrapScan, recoveryRows);
            if (bootstrapScan) {
                transactions.sort((left, right) -> {
                    Instant leftTime = left == null ? null : left.updateTime().orElse(null);
                    Instant rightTime = right == null ? null : right.updateTime().orElse(null);
                    if (leftTime == null && rightTime == null) return 0;
                    if (leftTime == null) return -1;
                    if (rightTime == null) return 1;
                    return leftTime.compareTo(rightTime);
                });
            }

            // Repair legacy false-failure rows before the dispatcher skips terminal entries.
            // For routine ticks, 'transactions' contains only transactions linked to unfinished
            // rows, not the full wallet history.
            reconcileFailedForwardingLedgerEntries(ownerKit, wallet, prefs, transactions);
            if (!forwardingEnabled) return;

            ensureForwardingEnabledAt(prefs, wallet);
            List<String> incomingIds = new ArrayList<>();
            if (bootstrapScan) {
                for (Transaction tx : transactions) {
                    if (tx != null) incomingIds.add(tx.getTxId().toString());
                }
            } else {
                long now = System.currentTimeMillis();
                for (org.json.JSONObject row : recoveryRows) {
                    String incomingId = row.optString("incoming", "").trim();
                    String rowStage = row.optString("stage", "").trim();
                    if ("failed".equals(rowStage)
                            && row.optLong("nextFailureReconcileAt", 0L) > now) continue;
                    if (!incomingId.isEmpty() && !incomingIds.contains(incomingId)) incomingIds.add(incomingId);
                }
            }

            java.util.Map<String, Transaction> transactionById = new java.util.HashMap<>();
            for (Transaction tx : transactions) {
                if (tx != null) transactionById.put(tx.getTxId().toString(), tx);
            }
            for (String incomingTxId : incomingIds) {
                if (!isCurrentKit(ownerKit) || getActiveWallet() != wallet) return;
                Transaction tx = transactionById.get(incomingTxId);
                if (tx == null) {
                    try { tx = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(incomingTxId)); }
                    catch (RuntimeException ignored) { }
                }
                if (tx == null) continue;
                String stage = findForwardingLedgerStage(prefs, incomingTxId);
                if ("failed".equals(stage)) {
                    org.json.JSONObject saved = getForwardingLedgerEntry(prefs, incomingTxId);
                    if (saved == null) continue;
                    // This incoming ID was selected from the due set at the start of this pass.
                    // Reconciliation may already have advanced nextFailureReconcileAt, so do not
                    // re-check that newly written timestamp here and accidentally skip this pass.
                    String outgoingId = saved.optString("outgoing", "").trim();
                    String raw = saved.optString("raw", "").trim();
                    // Durable outgoing TXID/raw bytes always take the recovery path, never the
                    // new-signer path. This is throttled because failed rows enter this dispatcher
                    // only when their persisted reconciliation deadline is due.
                    if (!outgoingId.isEmpty() || !raw.isEmpty()) {
                        recoverInFlightForwarding(wallet, prefs, tx, "broadcasting");
                        continue;
                    }

                    String status = saved.optString("status", "").trim();
                    String previousStatus = saved.optString("previousFailureStatus", "").trim();
                    boolean configChangedSinceFailure = saved.has("failureConfigFingerprint")
                            && !saved.optString("failureConfigFingerprint", "").equals(
                                    forwardingConfigFingerprint(prefs));
                    boolean knownTransient = saved.optBoolean("retryableFailure", false)
                            || (!saved.has("retryableFailure")
                                    && (saved.optBoolean("requeuedStaleFailure", false)
                                        || isForwardingContextChangeFailure(status)
                                        || isForwardingContextChangeFailure(previousStatus)))
                            || configChangedSinceFailure;
                    // Ordinary permanent failures (invalid address, fee exceeds amount, no
                    // spendable output, etc.) stay failed until a relevant configuration/user
                    // action changes the situation. They are not auto-sent every 15 seconds.
                    if (!knownTransient || !forwardingEnabled) continue;
                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                            "stage", "waiting",
                            "previousFailureStatus", previousStatus.isEmpty() ? status : previousStatus,
                            "requeuedStaleFailure", true,
                            "retryableFailure", true,
                            "status", applicationContext.getString(
                                    R.string.forwarding_status_waiting_conditions, incomingTxId),
                            "lastRecoveryChecked", System.currentTimeMillis()));
                    refreshAndRecoverWaitingForwardingEntry(ownerKit, wallet, prefs, tx);
                    continue;
                }
                if ("waiting".equals(stage)) {
                    refreshAndRecoverWaitingForwardingEntry(ownerKit, wallet, prefs, tx);
                    continue;
                }
                if ("preparing".equals(stage) || "broadcasting".equals(stage)) {
                    recoverInFlightForwarding(wallet, prefs, tx, stage);
                    continue;
                }
                if ("relayed".equals(stage)) {
                    refreshForwardingConfirmation(wallet, prefs, incomingTxId);
                    String refreshedStage = findForwardingLedgerStage(prefs, incomingTxId);
                    String outgoingId = findForwardingLedgerOutgoing(prefs, incomingTxId);
                    Transaction knownOutgoing = null;
                    if (!outgoingId.isEmpty()) {
                        try { knownOutgoing = wallet.getTransaction(
                                org.bitcoinj.base.Sha256Hash.wrap(outgoingId)); }
                        catch (RuntimeException ignored) { }
                    }
                    if ("broadcasting".equals(refreshedStage)
                            || (knownOutgoing == null && !outgoingId.isEmpty())) {
                        recoverInFlightForwarding(wallet, prefs, tx, "broadcasting");
                    }
                    continue;
                }
                if (!bootstrapScan || !stage.isEmpty()) continue;
                if (tx.getPurpose() != Transaction.Purpose.UNKNOWN
                        || forwardingTransactions.contains(incomingTxId)
                        || isPreviouslyForwardedOutputFromThisWallet(wallet, prefs, tx)
                        || !isReceiptEligibleSinceActivation(prefs, wallet, tx)) continue;
                try {
                    Coin received = tx.getValueSentToMe(wallet);
                    if (received != null && received.isPositive()) maybeForwardIncoming(wallet, tx);
                } catch (RuntimeException ignored) {
                    // An incomplete wallet view is retried through the receipt listener or ledger.
                }
            }

            if (bootstrapScan) {
                prefs.edit().putBoolean(FORWARDING_RECOVERY_INCREMENTAL_BOOTSTRAP_DONE, true)
                        .putLong(FORWARDING_RECOVERY_LAST_SCAN_MS, System.currentTimeMillis()).apply();
            } else {
                prefs.edit().putLong(FORWARDING_RECOVERY_LAST_SCAN_MS, System.currentTimeMillis()).apply();
            }
        } catch (RuntimeException ignored) {
            // An incomplete wallet view is not a failed forwarding operation; retry next tick.
        } finally {
            forwardingRecoveryScanInProgress.set(false);
        }
    }

    /**
     * A waiting row is not allowed to remain stale forever. Re-read its actual receipt,
     * confirmation depth, saved configuration and durable outgoing transaction on every
     * recovery pass. Retry only when the receipt satisfies the configured confirmation
     * threshold; temporary lock/network/readiness problems remain waiting and are retried.
     */
    private void refreshAndRecoverWaitingForwardingEntry(
            WalletAppKit ownerKit, Wallet wallet,
            android.content.SharedPreferences prefs, Transaction incomingTx) {
        if (ownerKit == null || wallet == null || prefs == null || incomingTx == null
                || !isCurrentKit(ownerKit) || getActiveWallet() != wallet) return;

        String incomingTxId = incomingTx.getTxId().toString();
        org.json.JSONObject row = getForwardingLedgerEntry(prefs, incomingTxId);
        String savedRaw = row == null ? "" : row.optString("raw", "").trim();
        String outgoingId = row == null ? "" : row.optString("outgoing", "").trim();

        // If a previous attempt already persisted a signed transaction, recover that exact
        // transaction instead of re-entering the signer or waiting on receipt confirmations.
        if (!savedRaw.isEmpty() || !outgoingId.isEmpty()) {
            recoverInFlightForwarding(wallet, prefs, incomingTx, "broadcasting");
            return;
        }

        int required = Math.max(1, Math.min(100,
                row == null
                        ? prefs.getInt(FORWARDING_CONFIRMATIONS, 1)
                        : row.optInt("requiredConfirmations",
                                prefs.getInt(FORWARDING_CONFIRMATIONS, 1))));
        int depth = 0;
        long receivedSats = 0L;
        try {
            org.bitcoinj.core.TransactionConfidence confidence = incomingTx.getConfidence();
            depth = confidence == null ? 0 : Math.max(0, confidence.getDepthInBlocks());
            Coin received = incomingTx.getValueSentToMe(wallet);
            if (received != null && received.isPositive()) receivedSats = received.value;
        } catch (RuntimeException walletNotReady) {
            // The wallet may still be loading. Preserve the row and retry on the next scan.
            return;
        }

        String destination = row == null ? "" : row.optString("destination", "").trim();
        if (destination.isEmpty()) destination = prefs.getString(FORWARDING_ADDRESS, "").trim();
        String mode = row == null ? "" : row.optString("mode", "").trim();
        if (mode.isEmpty()) mode = prefs.getString(FORWARDING_MODE, "sweep");
        long fixedAmount = row == null
                ? prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L)
                : row.optLong("fixedAmount",
                        prefs.getLong(FORWARDING_FIXED_AMOUNT_SATS, 0L));
        int feeRate = Math.max(1, Math.min(10, row == null
                ? prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5)
                : row.optInt("feeRate", prefs.getInt(FORWARDING_FEE_RATE_SAT_VB, 5))));

        boolean needsMetadata = row == null
                || row.optString("destination", "").trim().isEmpty()
                || row.optString("mode", "").trim().isEmpty()
                || !row.has("received")
                || !row.has("currentConfirmations")
                || !row.has("requiredConfirmations");
        if (needsMetadata || row.optInt("currentConfirmations", -1) != depth) {
            org.json.JSONObject fields = forwardingFields(
                    "currentConfirmations", depth,
                    "requiredConfirmations", required,
                    "received", receivedSats);
            if (!destination.isEmpty()) fields = mergeForwardingFields(fields,
                    forwardingFields("destination", destination));
            if (!mode.isEmpty()) fields = mergeForwardingFields(fields,
                    forwardingFields("mode", mode));
            fields = mergeForwardingFields(fields, forwardingFields(
                    "fixedAmount", fixedAmount, "feeRate", feeRate,
                    "network", NetworkConfig.storageName(NetworkConfig.get(applicationContext))));
            updateForwardingLedgerEntry(prefs, incomingTxId, fields);
        }

        // Do not retry early: a genuine confirmation deficit is a valid waiting condition.
        if (depth < required) return;

        // Retry only a saved waiting job whose confirmation gate is satisfied. Retryable
        // failures are throttled below; permanent failures stay failed unless relevant settings
        // changed or a legacy transient failure was explicitly requeued.
        boolean hasDurableOutgoing = row != null
                && (!row.optString("raw", "").trim().isEmpty()
                    || !row.optString("outgoing", "").trim().isEmpty());

        // If a lease survived a lost callback, wait two minutes from the attempt start.
        // Never release a recent lease: concurrent senders could select the same UTXO.
        long attemptStartedAt = row == null ? 0L
                : row.optLong("attemptStarted", row.optLong("created", 0L));
        long staleAfterMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(2L);
        if (forwardingTransactions.contains(incomingTxId)) {
            if (attemptStartedAt > 0L
                    && System.currentTimeMillis() - attemptStartedAt >= staleAfterMs
                    && !hasDurableOutgoing) {
                forwardingTransactions.remove(incomingTxId);
            } else {
                return;
            }
        }

        // Retryable failures use persisted exponential backoff. Confirmation metadata may still
        // be refreshed each tick, but do not try signing/broadcasting again before nextRetryAt.
        if (row != null && row.optBoolean("retryableFailure", false)
                && row.optLong("nextRetryAt", 0L) > System.currentTimeMillis()) return;

        // This is an explicit saved waiting job, not a new receive notification. Once its
        // confirmation gate is satisfied, retry it if the exact output remains spendable.
        // If there is neither a recorded outgoing transaction nor a spendable input, it cannot
        // ever progress and must not remain labelled as waiting forever.
        if (!hasDurableOutgoing
                && (!hasSpendableOutputFromIncoming(wallet, incomingTx)
                    || hasPositiveValueSentFromWallet(wallet, incomingTx))) {
            updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                    "stage", "failed",
                    "status", applicationContext.getString(R.string.forwarding_error_output_unavailable),
                    "requeuedStaleFailure", false,
                    "retryableFailure", false,
                    "failureConfigFingerprint", forwardingConfigFingerprint(prefs),
                    "lastPermanentFailureAt", System.currentTimeMillis(),
                    "lastRecoveryChecked", System.currentTimeMillis()));
            return;
        }
        maybeForwardIncoming(wallet, incomingTx, true);
    }

    private static org.json.JSONObject mergeForwardingFields(
            org.json.JSONObject base, org.json.JSONObject extra) {
        if (base == null) base = new org.json.JSONObject();
        if (extra == null) return base;
        java.util.Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try { base.put(key, extra.opt(key)); }
            catch (org.json.JSONException ignored) { }
        }
        return base;
    }

    private static String forwardingTransactionToHex(Transaction transaction) {
        byte[] bytes = transaction.serialize();
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static Transaction forwardingTransactionFromHex(String hex) throws Exception {
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("forwarding_saved_transaction_invalid");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("forwarding_saved_transaction_invalid");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return Transaction.read(java.nio.ByteBuffer.wrap(bytes));
    }

    /** True only when another wallet transaction spends the same exact outpoint. */
    private boolean hasWalletSpendConflict(Wallet wallet, Transaction proposed) {
        if (wallet == null || proposed == null) {
            throw new IllegalStateException("wallet_not_ready");
        }
        try {
            java.util.Set<String> proposedInputs = new java.util.HashSet<>();
            for (org.bitcoinj.core.TransactionInput input : proposed.getInputs()) {
                org.bitcoinj.core.TransactionOutPoint outpoint = input.getOutpoint();
                if (outpoint != null) proposedInputs.add(
                        outpoint.hash().toString() + ":" + outpoint.index());
            }
            if (proposedInputs.isEmpty()) return true;
            for (Transaction candidate : wallet.getTransactions(true)) {
                if (candidate == null || candidate.getTxId().equals(proposed.getTxId())) continue;
                for (org.bitcoinj.core.TransactionInput input : candidate.getInputs()) {
                    org.bitcoinj.core.TransactionOutPoint outpoint = input.getOutpoint();
                    if (outpoint != null && proposedInputs.contains(
                            outpoint.hash().toString() + ":" + outpoint.index())) return true;
                }
            }
        } catch (RuntimeException error) {
            // A missing/incomplete wallet view is not a confirmed conflict. Defer and retry later.
            throw new IllegalStateException("wallet_not_ready", error);
        }
        return false;
    }

    /** Re-adds a previously signed transaction to this same wallet without signing a replacement. */
    private Transaction restoreSavedForwardingTransaction(Wallet expectedWallet, Transaction saved)
            throws Exception {
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null || !presenter.walletReady || expectedWallet == null || saved == null) {
            throw new IllegalStateException("wallet_not_ready");
        }
        synchronized (presenter.kitLock) {
            WalletAppKit currentKit = presenter.walletAppKit;
            if (presenter.shuttingDown || currentKit == null || !currentKit.isRunning()) {
                throw new IllegalStateException("wallet_not_ready");
            }
            Wallet currentWallet;
            try { currentWallet = currentKit.wallet(); }
            catch (IllegalStateException error) {
                throw new IllegalStateException("wallet_not_ready", error);
            }
            if (currentWallet != expectedWallet || !currentWallet.network().equals(
                    NetworkConfig.get(applicationContext))) {
                throw new IllegalStateException("wallet_not_ready");
            }
            android.content.SharedPreferences prefs = applicationContext.getSharedPreferences(
                    forwardingPreferencesName(applicationContext), android.content.Context.MODE_PRIVATE);
            if (!prefs.getBoolean(FORWARDING_ENABLED, false)) {
                throw new IllegalStateException("forwarding_disabled");
            }
            Transaction existing = currentWallet.getTransaction(saved.getTxId());
            if (existing != null) return existing;
            if (hasWalletSpendConflict(currentWallet, saved)) {
                throw new IllegalStateException("forwarding_recovery_conflict");
            }
            if (presenter.parameters != null) Context.propagate(Context.getOrCreate(presenter.parameters));
            currentWallet.commitTx(saved);
            Transaction restored = currentWallet.getTransaction(saved.getTxId());
            if (restored == null) throw new IOException("forwarding_saved_transaction_not_committed");
            return restored;
        }
    }

    private void markForwardingRecoveryBlocked(
            android.content.SharedPreferences prefs, String incomingTxId, Wallet wallet) {
        boolean incomingSpent = false;
        try { incomingSpent = hasWalletSpendOfIncoming(wallet,
                org.bitcoinj.base.Sha256Hash.wrap(incomingTxId)); }
        catch (RuntimeException ignored) { }
        int statusResource = incomingSpent
                ? R.string.forwarding_status_retry_blocked
                : R.string.forwarding_status_recovery_blocked;
        updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                "stage", "failed",
                "status", applicationContext.getString(statusResource, incomingTxId),
                "retryableFailure", false,
                "failureConfigFingerprint", forwardingConfigFingerprint(prefs),
                "lastPermanentFailureAt", System.currentTimeMillis()));
        forwardingTransactions.remove(incomingTxId);
    }

    private void recoverInFlightForwarding(Wallet wallet,
            android.content.SharedPreferences prefs, Transaction incomingTx, String stage) {
        String incomingTxId = incomingTx.getTxId().toString();
        if ("preparing".equals(stage) && forwardingTransactions.contains(incomingTxId)) return;
        org.json.JSONObject savedRow = getForwardingLedgerEntry(prefs, incomingTxId);
        String outgoingTxId = savedRow == null ? "" : savedRow.optString("outgoing", "");
        String savedRaw = savedRow == null ? "" : savedRow.optString("raw", "");
        Transaction outgoingTx = null;
        if (!outgoingTxId.isEmpty()) {
            try {
                outgoingTx = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(outgoingTxId));
            } catch (RuntimeException ignored) { }
        }
        if (outgoingTx != null) {
            // A crash can happen after commitTx() but before the state update is flushed.
            if ("preparing".equals(stage) || "waiting".equals(stage)) {
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "stage", "broadcasting",
                        "status", applicationContext.getString(
                                R.string.forwarding_status_broadcasting, incomingTxId)));
            }
            refreshForwardingConfirmation(wallet, prefs, incomingTxId);
            String refreshedStage = findForwardingLedgerStage(prefs, incomingTxId);
            if ("broadcasting".equals(refreshedStage)) {
                rebroadcastExistingForwardingTransaction(wallet, prefs, incomingTxId, outgoingTx);
            }
            return;
        }

        // New versions persist the signed raw TX before commitTx(). If the wallet lost the
        // transaction across a crash, recover and rebroadcast this exact TXID rather than signing
        // a competing transaction from the same inputs. A raw proposal from a generation that was
        // explicitly disabled must not be revived if the user re-enables forwarding later.
        if (!savedRaw.isEmpty()) {
            long savedGeneration = savedRow == null
                    ? prefs.getLong(FORWARDING_GENERATION, 0L)
                    : savedRow.optLong("generation", prefs.getLong(FORWARDING_GENERATION, 0L));
            if (savedGeneration != prefs.getLong(FORWARDING_GENERATION, 0L)) {
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "stage", "disabled", "status", applicationContext.getString(
                                R.string.forwarding_activity_disabled_detail)));
                forwardingTransactions.remove(incomingTxId);
                return;
            }
            Transaction savedOutgoing;
            try {
                savedOutgoing = forwardingTransactionFromHex(savedRaw);
            } catch (Exception invalidRaw) {
                markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
                return;
            }
            if (!outgoingTxId.isEmpty() && !outgoingTxId.equals(savedOutgoing.getTxId().toString())) {
                markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
                return;
            }
            try {
                if (hasWalletSpendConflict(wallet, savedOutgoing)) {
                    markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
                    return;
                }
                Transaction restored = restoreSavedForwardingTransaction(wallet, savedOutgoing);
                String restoredTxId = restored.getTxId().toString();
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "outgoing", restoredTxId,
                        "stage", "broadcasting",
                        "status", applicationContext.getString(
                                R.string.forwarding_status_broadcasting, incomingTxId)));
                rebroadcastExistingForwardingTransaction(wallet, prefs, incomingTxId, restored);
            } catch (Exception error) {
                if ("forwarding_disabled".equals(String.valueOf(error.getMessage()))) {
                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                            "stage", "disabled",
                            "status", applicationContext.getString(
                                    R.string.forwarding_activity_disabled_detail)));
                } else if ("forwarding_recovery_conflict".equals(String.valueOf(error.getMessage()))) {
                    markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
                }
                // For temporary wallet/network problems, keep the exact signed bytes and retry on
                // the next recovery pass. Do not construct a new outgoing transaction.
            }
            return;
        }

        // A legacy broadcasting row with only a TXID cannot be safely reconstructed. If any
        // candidate may already have been sent, stop rather than generate a different TX.
        if (!outgoingTxId.isEmpty() || "broadcasting".equals(stage)) {
            markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
            return;
        }
        if (hasWalletSpendOfIncoming(wallet, incomingTx.getTxId())) {
            markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
            return;
        }
        // No outgoing TXID was ever recorded and no wallet transaction spends the receipt. This
        // was interrupted before a signed transaction existed, so rebuilding from the saved config
        // is safe and does not duplicate a broadcast.
        updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                "stage", "waiting", "outgoing", "", "status",
                applicationContext.getString(R.string.forwarding_status_waiting_conditions, incomingTxId)));
        forwardingTransactions.remove(incomingTxId);
        maybeForwardIncoming(wallet, incomingTx);
    }

    private boolean hasWalletSpendOfIncoming(Wallet wallet, org.bitcoinj.base.Sha256Hash incomingTxId) {
        if (wallet == null || incomingTxId == null) {
            throw new IllegalStateException("wallet_not_ready");
        }
        try {
            Transaction incoming = wallet.getTransaction(incomingTxId);
            if (incoming == null) throw new IllegalStateException("wallet_not_ready");
            for (TransactionOutput output : incoming.getOutputs()) {
                org.bitcoinj.core.TransactionInput spentBy = output.getSpentBy();
                if (spentBy == null) continue;
                Transaction spender = spentBy.getParentTransaction();
                if (spender != null && !spender.getTxId().equals(incomingTxId)) return true;
            }
            return false;
        } catch (RuntimeException error) {
            // If the wallet cannot resolve its connected outpoint graph, do not guess that an
            // input is unspent; leave recovery blocked and retry when the wallet is ready.
            throw new IllegalStateException("wallet_not_ready", error);
        }
    }

    private void refreshForwardingConfirmation(Wallet wallet,
            android.content.SharedPreferences prefs, String incomingTxId) {
        String outgoingTxId = findForwardingLedgerOutgoing(prefs, incomingTxId);
        if (outgoingTxId.isEmpty()) return;
        try {
            Transaction outgoing = wallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(outgoingTxId));
            if (outgoing == null) return;
            org.bitcoinj.core.TransactionConfidence confidence = outgoing.getConfidence();
            int depth = confidence == null ? 0 : Math.max(0, confidence.getDepthInBlocks());
            String stage = findForwardingLedgerStage(prefs, incomingTxId);
            if (depth > 0) {
                updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                        "stage", "confirmed", "outgoingConfirmations", depth,
                        "status", applicationContext.getString(
                                R.string.forwarding_status_confirmed, outgoingTxId)));
                return;
            }
            boolean pending = confidence != null
                    && confidence.getConfidenceType()
                            == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING;
            boolean peersHaveTransaction = pending && confidence.numBroadcastPeers() > 0;
            if (peersHaveTransaction) {
                if ("broadcasting".equals(stage) || "preparing".equals(stage)) {
                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                            "stage", "relayed", "outgoingConfirmations", 0,
                            "status", applicationContext.getString(
                                    R.string.forwarding_status_forwarded, incomingTxId, outgoingTxId)));
                }
                return;
            }
            if ("relayed".equals(stage)) {
                if (confidence != null && confidence.getConfidenceType()
                        == org.bitcoinj.core.TransactionConfidence.ConfidenceType.DEAD) {
                    markForwardingRecoveryBlocked(prefs, incomingTxId, wallet);
                } else {
                    // The node/wallet no longer reports this pending TX as relayed. Keep its raw
                    // signed bytes and retry the exact same TXID rather than rebuilding it.
                    updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                            "stage", "broadcasting",
                            "status", applicationContext.getString(
                                    R.string.forwarding_status_broadcasting, incomingTxId)));
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private void rebroadcastExistingForwardingTransaction(Wallet wallet,
            android.content.SharedPreferences prefs, String incomingTxId, Transaction outgoing) {
        String outgoingTxId = outgoing.getTxId().toString();
        if (!forwardingBroadcastRetries.add(outgoingTxId)) return;
        FORWARDING_EXECUTOR.execute(() -> {
            try {
                if (!prefs.getBoolean(FORWARDING_ENABLED, false)
                        || getActiveWallet() != wallet
                        || !wallet.network().equals(NetworkConfig.get(applicationContext))) {
                    forwardingBroadcastRetries.remove(outgoingTxId);
                    return;
                }
                TransactionBroadcast broadcast = broadcastExistingForwardingTransaction(wallet, outgoing);
                java.util.concurrent.ScheduledFuture<?> timeout = FORWARDING_STATUS_EXECUTOR.schedule(
                        () -> forwardingBroadcastRetries.remove(outgoingTxId), 25L, TimeUnit.SECONDS);
                broadcast.awaitRelayed().whenComplete((relayed, error) -> {
                    try {
                        if (error == null && "broadcasting".equals(
                                findForwardingLedgerStage(prefs, incomingTxId))) {
                            updateForwardingLedgerEntry(prefs, incomingTxId, forwardingFields(
                                    "stage", "relayed", "outgoingConfirmations", 0,
                                    "status", applicationContext.getString(
                                            R.string.forwarding_status_forwarded, incomingTxId, outgoingTxId)));
                            invalidateHistoryCache(wallet);
                            refresh();
                            notifyWalletUpdated();
                        }
                        forwardingTransactions.remove(incomingTxId);
                        forwardingTransactions.remove(outgoingTxId);
                    } finally {
                        timeout.cancel(false);
                        forwardingBroadcastRetries.remove(outgoingTxId);
                    }
                });
            } catch (Exception ignored) {
                forwardingBroadcastRetries.remove(outgoingTxId);
            }
        });
    }

    private static TransactionBroadcast broadcastExistingForwardingTransaction(
            Wallet expectedWallet, Transaction transaction) throws Exception {
        if (expectedWallet == null || transaction == null) {
            throw new IllegalStateException("wallet_not_ready");
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
            Wallet currentWallet;
            try {
                currentWallet = kit.wallet();
            } catch (IllegalStateException error) {
                throw new IllegalStateException("wallet_not_ready", error);
            }
            if (currentWallet != expectedWallet) throw new IllegalStateException("wallet_not_ready");
            if (presenter.parameters != null) Context.propagate(Context.getOrCreate(presenter.parameters));
            TransactionBroadcast broadcast = kit.peerGroup().broadcastTransaction(transaction);
            broadcast.broadcast();
            return broadcast;
        }
    }

    private void setupWalletListeners(
            WalletAppKit ownerKit,
            Wallet wallet) {

        // A new wallet kit invalidates only in-memory work leases. The durable ledger and saved
        // signed transactions remain authoritative and are reconciled after all listeners attach.
        synchronized (forwardingRecoveryLock) {
            if (forwardingRecoveryOwnerKit != ownerKit) {
                forwardingTransactions.clear();
                forwardingRecoveryOwnerKit = ownerKit;
            }
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

        // Resume saved waiting/prepared work now, then continue reconciling periodically so a
        // temporary network loss or a receive missed while the app was stopped is not forgotten.
        scheduleForwardingRecovery(ownerKit, wallet);
        recoverForwardingWork(ownerKit, wallet);
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
    private Wallet safeWalletFromKit(WalletAppKit kit) {
        if (kit == null) return null;
        synchronized (kitLock) {
            if (shuttingDown || walletAppKit != kit || !kit.isRunning()) return null;
            try { return kit.wallet(); }
            catch (IllegalStateException ignored) { return null; }
        }
    }

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
                Wallet active = getActiveWallet();
                if (active != null && active == safeWalletFromKit(kit)) {
                    recoverForwardingWork(kit, active);
                }
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
    /** Current height known to the already-running active wallet kit, or -1 while unavailable. */
    public static int getActiveBestChainHeight() {
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null) return -1;
        WalletAppKit kit = presenter.walletAppKit;
        if (kit == null) return -1;
        try {
            return presenter.safeChainHeight(kit);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

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
            long expectedForwardingGeneration,
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
            android.content.SharedPreferences forwardingPrefs = presenter.applicationContext.getSharedPreferences(
                    forwardingPreferencesName(presenter.applicationContext), android.content.Context.MODE_PRIVATE);
            synchronized (FORWARDING_COMMIT_LOCK) {
                if (!forwardingPrefs.getBoolean(FORWARDING_ENABLED, false)
                        || forwardingPrefs.getLong(FORWARDING_GENERATION, 0L) != expectedForwardingGeneration) {
                    throw new IllegalStateException("forwarding_disabled");
                }
                currentWallet.commitTx(transaction);
                TransactionBroadcast broadcast = kit.peerGroup().broadcastTransaction(transaction);
                broadcast.broadcast();
                return broadcast;
            }
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
