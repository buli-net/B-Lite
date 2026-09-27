package wallet.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.transaction.TransactionMapper;
import wallet.security.WalletSecurity;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.crypto.MnemonicCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the persistent bitcoinj wallet and sync lifecycle. */
public class MainActivityPresenter
        implements MainActivityContract.MainActivityPresenter {

    private static volatile MainActivityPresenter activePresenter;

    private static final String TAG = "BitcoinWalletSync";

    private static final int MAX_CONNECTIONS = 8;

    private static final long STALL_TIMEOUT_MS = 90_000L;

    private static final int MAX_AUTO_RESTARTS = 8;

    private MainActivityContract.MainActivityView view;

    private final android.content.Context applicationContext;

    private final File walletDir;

    private NetworkParameters parameters;

    private volatile WalletAppKit walletAppKit;

    private final File walletFile;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Object kitLock = new Object();

    private final AtomicBoolean restartInProgress =
            new AtomicBoolean(false);

    private volatile boolean walletReady = false;

    private volatile boolean downloadFinished = false;

    private volatile boolean startupInProgress = false;

    private volatile boolean shuttingDown = false;

    private volatile int lastPercent = -1;

    private volatile int lastChainHeight = -1;

    private volatile long lastProgressAt = 0L;

    private volatile int autoRestartCount = 0;

    private volatile double syncBlocksPerSecond = 0.0;
    private volatile long lastBlockEventAt = 0L;
    private volatile int lastRateHeight = -1;
    private volatile String bestChainHash = "";
    private volatile long bestChainTimeSeconds = 0L;

    private volatile DeterministicSeed pendingRestoreSeed;
    private volatile File pendingMnemonicBackupFile;

    private ScheduledExecutorService watchdog;

    /** UI clients that need an immediate refresh when the wallet changes while they are visible. */
    private final CopyOnWriteArrayList<Runnable> walletUpdateListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Runnable> syncStateListeners =
            new CopyOnWriteArrayList<>();

    public MainActivityPresenter(
            MainActivityContract.MainActivityView view,
            File walletDir) {

        this.view = view;
        this.applicationContext = view.getActivityContext().getApplicationContext();
        this.walletDir = walletDir;
        activePresenter = this;

        this.walletFile =
                new File(
                        walletDir,
                        Constants.WALLET_NAME + ".wallet"
                );

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
                view.displayDownloadContent(!downloadFinished);

                if (!downloadFinished && lastPercent >= 0) {
                    view.displayPercentage(lastPercent);
                    view.displayProgress(lastPercent);
                }

                refresh();
                return;
            }

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

        parameters =
                Constants.IS_PRODUCTION
                        ? MainNetParams.get()
                        : TestNet3Params.get();

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

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

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

                    final WalletAppKit newKit =
                            new WalletAppKit(
                                    Constants.IS_PRODUCTION
                                            ? BitcoinNetwork.MAINNET
                                            : BitcoinNetwork.TESTNET,
                                    ScriptType.P2WPKH,
                                    KeyChainGroupStructure.BIP43,
                                    walletDir,
                                    Constants.WALLET_NAME
                            ) {

                                @Override
                                protected void onSetupCompleted() {

                                    try {

                                        peerGroup()
                                                .setMaxConnections(
                                                        MAX_CONNECTIONS
                                                );

                                        peerGroup()
                                                .setMaxPeersToDiscoverCount(
                                                        100
                                                );

                                    } catch (Exception e) {

                                        Log.w(
                                                TAG,
                                                "PeerGroup tuning failed; "
                                                        + "using defaults",
                                                e
                                        );
                                    }

                                    setupWalletListeners(wallet());
                                    setupPeerListeners(peerGroup());

                                    File restoreBackup = pendingMnemonicBackupFile;
                                    if (restoreBackup != null && restoreBackup.exists()) {
                                        wallet().isConsistentOrThrow();
                                        if (!wallet().getParams().equals(parameters)) {
                                            throw new IllegalStateException(
                                                    "Restored wallet network does not match the application network");
                                        }
                                        restoreBackup.delete();
                                        pendingMnemonicBackupFile = null;
                                    }

                                    walletReady = true;
                    notifySyncStateChanged();

                                    int height =
                                            safeChainHeight(this);

                                    lastChainHeight = height;

                                    touchProgress();

                                    runOnUi(() -> {

                                        view.displayDownloadContent(true);

                                        refresh();
                                    });

                                }
                            };

                    newKit.setDownloadListener(
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

                                    int percentage =
                                            (int) Math.round(
                                                    pct * 1.0
                                            );

                                    if (percentage < 0) {
                                        percentage = 0;
                                    }

                                    if (percentage > 100) {
                                        percentage = 100;
                                    }

                                    int chainHeight =
                                            safeChainHeight(newKit);

                                    lastPercent = percentage;
                                    notifySyncStateChanged();

                                    if (chainHeight >
                                            lastChainHeight) {

                                        lastChainHeight =
                                                chainHeight;
                                    }

                                    touchProgress();

                                    final int uiPercent =
                                            percentage;

                                    BitcoinSyncService.updateSyncNotification(
                                            applicationContext,
                                            uiPercent,
                                            chainHeight,
                                            safePeerHeight(newKit),
                                            true);

                                    runOnUi(() -> {

                                        view.displayDownloadContent(
                                                true
                                        );

                                        view.displayPercentage(
                                                uiPercent
                                        );

                                        view.displayProgress(
                                                uiPercent
                                        );
                                    });
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

                                    lastProgressAt =
                                            System.currentTimeMillis();

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
                            }
                    );

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
                lastRateHeight = lastChainHeight;
                lastBlockEventAt = 0L;
                syncBlocksPerSecond = 0.0;
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

                startupInProgress = false;

            } catch (Exception e) {

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

                    }
                }

                if (failureCause != null) {

                    rootCause =
                            findRootCause(
                                    failureCause
                            );
                }

                Log.e(
                        TAG,
                        "================================"
                );

                Log.e(
                        TAG,
                        "WalletAppKit FAILED",
                        e
                );

                Log.e(
                        TAG,
                        "WalletAppKit failureCause",
                        failureCause
                );

                Log.e(
                        TAG,
                        "WalletAppKit rootCause",
                        rootCause
                );

                Log.e(
                        TAG,
                        "================================"
                );

                final String errorText =
                        buildFailureMessage(
                                e,
                                rootCause
                        );

                if (!shuttingDown) {

                    runOnUi(() ->
                            view.showToastMessage(
                                    text(R.string.sync_error, errorText)
                            )
                    );

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
                            || downloadFinished
                            || restartInProgress.get()) {

                        return;
                    }

                    WalletAppKit kit =
                            walletAppKit;

                    if (kit == null) {
                        return;
                    }

                    if (!kit.isRunning()) {
                        return;
                    }

                    int chainHeight =
                            safeChainHeight(kit);

                    int peerHeight =
                            safePeerHeight(kit);

                    int peers =
                            safePeerCount(kit);

                    BitcoinSyncService.updateSyncNotification(
                            applicationContext,
                            Math.max(0, lastPercent),
                            chainHeight,
                            peerHeight,
                            true);

                    if (chainHeight >
                            lastChainHeight) {

                        lastChainHeight =
                                chainHeight;

                        touchProgress();

                        return;
                    }

                    long stalledFor =
                            System.currentTimeMillis()
                                    - lastProgressAt;

                    if (stalledFor <
                            STALL_TIMEOUT_MS) {

                        return;
                    }

                    Log.w(
                            TAG,
                            "SYNC STALLED for "
                                    + (stalledFor / 1000)
                                    + "s: percent="
                                    + lastPercent
                                    + " chain="
                                    + chainHeight
                                    + " peerHeight="
                                    + peerHeight
                                    + " peers="
                                    + peers
                    );

                    if (autoRestartCount >=
                            MAX_AUTO_RESTARTS) {

                        Log.e(
                                TAG,
                                "Maximum automatic sync "
                                        + "restarts reached."
                        );

                        runOnUi(() ->
                                view.showToastMessage(
                                        text(R.string.sync_waiting_network)
                                )
                        );

                        touchProgress();

                        return;
                    }

                    restartWalletKit(
                            "download stalled"
                    );

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

            Log.e(
                    TAG,
                    "Maximum automatic startup "
                            + "restarts reached."
            );

            runOnUi(() ->
                    view.showToastMessage(
                            text(R.string.wallet_start_failed_detailed, TAG)
                    )
            );

            return;
        }

        watchdog.schedule(

                () -> {

                    if (!shuttingDown &&
                            !restartInProgress.get()) {

                        restartWalletKit(
                                "WalletAppKit startup failed"
                        );
                    }

                },

                10,
                TimeUnit.SECONDS
        );
    }

    private void restartWalletKit(
            String reason) {

        if (shuttingDown) {
            return;
        }

        if (!restartInProgress.compareAndSet(
                false,
                true)) {

            return;
        }

        autoRestartCount++;

        walletReady = false;

        downloadFinished = false;
        notifySyncStateChanged();

        Log.w(
                TAG,
                "Restarting WalletAppKit (#"
                        + autoRestartCount
                        + "): "
                        + reason
        );

        runOnUi(() -> {

            view.displayDownloadContent(true);

            view.showToastMessage(
                    text(R.string.blockchain_reconnecting)
            );
        });

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

                    } catch (Exception stopError) {

                        Log.w(
                                TAG,
                                "Error stopping old "
                                        + "WalletAppKit",
                                stopError
                        );
                    }
                }

                if (!shuttingDown) {

                    lastPercent = -1;

                    lastChainHeight = -1;

                    touchProgress();

                    startWalletKit();
                }

            } finally {

                restartInProgress.set(false);
            }

        }, "bitcoinj-reconnect").start();
    }

    private void touchProgress() {

        lastProgressAt =
                System.currentTimeMillis();
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
                cause.getMessage();

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
                e.getMessage();

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

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Error stopping WalletAppKit",
                        e
                );
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

        if (!walletReady || kit == null) {
            return;
        }

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            try {
                renderSelectedWallet(kit.wallet());
            } catch (Exception e) {
                Log.w(TAG, "Refresh failed", e);
            }
        }, "bitcoinj-refresh").start();
    }

    private void renderSelectedWallet(Wallet wallet) {
        android.content.Context context = view.getActivityContext();
        Script selectedScript = WalletSelection.findSelectedScript(context, wallet);

        Coin balance;
        Coin available;
        Coin pending;
        String address;
        String walletType;
        List<TransactionItem> transactions;

        if (selectedScript != null) {
            long confirmedSat = 0L;
            long pendingSat = 0L;
            for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
                if (!output.isAvailableForSpending()
                        || !selectedScript.equals(output.getScriptPubKey())) {
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
            address = WalletSelection.addressForScript(selectedScript, parameters);
            walletType = text(R.string.wallet_type_watch);
            transactions = TransactionMapper.mapForWatchedScript(
                    context, wallet, selectedScript);
        } else {
            // Main wallet must exclude all explicitly watched outputs.
            balance = WalletSelection.mainEstimatedBalance(wallet);
            available = WalletSelection.mainAvailableBalance(wallet);
            pending = balance.subtract(available);
            address = wallet.currentReceiveAddress().toString();
            walletType = text(R.string.wallet_type_main);
            transactions = TransactionMapper.mapForMainWallet(context, wallet);
        }

        final String balanceText = balance.toFriendlyString();
        final String availableText = text(R.string.available_balance,
                available.toFriendlyString());
        final String pendingText = text(R.string.pending_balance,
                pending.toFriendlyString());
        final String selectedAddress = address;
        final String selectedWalletType = walletType;
        final List<TransactionItem> selectedTransactions = transactions;

        runOnUi(() -> {
            view.displayWalletType(selectedWalletType);
            view.displayMyBalance(balanceText);
            view.displayBalanceState(availableText, pendingText);
            if (!TextUtils.isEmpty(selectedAddress)) {
                view.displayMyAddress(selectedAddress);
            }
            view.displayTransactions(selectedTransactions);
        });
    }

    @Override
    public void restoreWallet(final android.net.Uri backupUri) {

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
                            walletDir,
                            Constants.WALLET_NAME + ".restore.tmp"
                    );
            File backupOfCurrent =
                    new File(
                            walletDir,
                            Constants.WALLET_NAME + ".before-restore.wallet"
                    );

            WalletAppKit oldKit;

            try {

                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

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
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception stopError) {
                        synchronized (kitLock) {
                            walletAppKit = oldKit;
                        }
                        walletReady = false;
                        startWatchdog();
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

                if (backupOfCurrent.exists()) {
                    backupOfCurrent.delete();
                }

                autoRestartCount = 0;
                lastPercent = -1;
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

                Log.e(
                        TAG,
                        "Wallet restore failed",
                        e
                );

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
                            walletDir,
                            Constants.WALLET_NAME + ".before-mnemonic-restore.wallet");
            File chainFile =
                    new File(
                            walletDir,
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
                lastPercent = -1;
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
                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.mnemonic_restore_failed, safeMessage(error))));
            }
        }, "bitcoinj-mnemonic-restore").start();
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
            try {
                Context.propagate(Context.getOrCreate(parameters));

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                    downloadFinished = false;
                    lastPercent = -1;
                    lastChainHeight = -1;
                }

                if (oldKit == null) {
                    throw new IOException("WalletAppKit is not running");
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
                                walletDir,
                                Constants.WALLET_NAME + ".spvchain");
                if (chainFile.exists() && !chainFile.delete()) {
                    throw new IOException("Unable to reset SPV chain file");
                }

                shuttingDown = false;
                autoRestartCount = 0;
                startWalletKit();
                startWatchdog();
            } catch (Exception error) {
                failure = error;
                if (safetyCopy != null && safetyCopy.exists()) {
                    try {
                        copyFile(safetyCopy, walletFile);
                    } catch (Exception restoreError) {
                        Log.e(TAG, "Unable to restore watch-rescan safety copy", restoreError);
                    }
                }
                Log.e(TAG, "Watch-only rescan failed", error);
                synchronized (kitLock) {
                    walletAppKit = null;
                    walletReady = false;
                }
                if (!shuttingDown) {
                    try {
                        startWalletKit();
                    } catch (Exception restartError) {
                        Log.e(TAG, "Unable to restart WalletAppKit after watch-only rescan failure", restartError);
                    }
                }
            } finally {
                final Exception result = failure;
                if (finished != null) {
                    runOnUi(() -> finished.accept(result));
                }
            }
        }, "bitcoinj-watch-rescan").start();
    }

    private File createWalletSafetyCopy(String reason) throws IOException {
        if (!walletFile.exists()) {
            throw new IOException("Wallet file does not exist");
        }
        File dir = new File(walletDir, "backup-safety");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create wallet safety directory");
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

    public void saveWalletNow() throws IOException {
        WalletAppKit kit = walletAppKit;
        if (kit == null || kit.wallet() == null) {
            throw new IOException(text(R.string.wallet_not_ready));
        }
        kit.wallet().saveToFile(walletFile);
    }

    private void setupWalletListeners(
            Wallet wallet) {

        wallet.addCoinsReceivedEventListener(
                (wallet1, tx, prevBalance, newBalance) -> {
                    refresh();
                    notifyWalletUpdated();
                    if (tx.getPurpose() == Transaction.Purpose.UNKNOWN) {
                        try {
                            Coin received = newBalance.minus(prevBalance);
                            BitcoinSyncService.notifyReceived(
                                    applicationContext, received, tx);
                            runOnUi(() -> view.showToastMessage(
                                    text(R.string.receive_message,
                                            received.toFriendlyString())));
                        } catch (Exception ignored) {
                            // UI refresh remains the important part of the event.
                        }
                    }
                }
        );

        wallet.addCoinsSentEventListener(
                (wallet1, tx, prevBalance, newBalance) -> {
                    refresh();
                    notifyWalletUpdated();
                    try {
                        Coin sent = prevBalance.minus(newBalance);
                        if (sent.isPositive()) {
                            BitcoinSyncService.notifySent(
                                    applicationContext, sent, tx);
                        }
                    } catch (Exception ignored) {
                        // The wallet refresh remains the important part of the event.
                    }
                }
        );

        wallet.addTransactionConfidenceEventListener(
                (wallet1, tx) -> {
                    refresh();
                    notifyWalletUpdated();
                }
        );

        wallet.addChangeEventListener(
                wallet1 -> {
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
                Log.w(TAG, "Sync state listener failed", e);
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
        return walletReady && walletAppKit != null;
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
        if (kit == null || kit.peerGroup() == null) {
            return "—";
        }
        try {
            PeerGroup group = kit.peerGroup();
            List<Peer> connected = group.getConnectedPeers();
            if (connected.isEmpty()) {
                return "No connected peers";
            }
            StringBuilder out = new StringBuilder();
            Peer downloadPeer = group.getDownloadPeer();
            for (int i = 0; i < connected.size(); i++) {
                Peer peer = connected.get(i);
                if (i > 0) out.append("\n\n");
                out.append(i + 1).append("  ")
                        .append(peer.getAddress() == null ? "unknown peer" : peer.getAddress().toString());
                out.append("\nHeight: ").append(peer.getBestHeight());
                out.append("  Behind/ahead: ").append(peer.getPeerBlockHeightDifference());
                out.append("\nDownload: ").append(peer.isDownloadData() ? "yes" : "no");
                try {
                    if (peer.pingInterval().isPresent()) {
                        out.append("  Ping: ").append(peer.pingInterval().get().toMillis()).append(" ms");
                    } else {
                        out.append("  Ping: —");
                    }
                } catch (Exception ignored) {
                    out.append("  Ping: —");
                }
                try {
                    if (peer.getFeeFilter() != null) {
                        out.append("\nFee filter: ").append(peer.getFeeFilter().toFriendlyString());
                    }
                } catch (Exception ignored) {
                }
                if (peer == downloadPeer) {
                    out.append("\nRole: BLOCK DOWNLOAD PEER");
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "Unavailable";
        }
    }

    public String getNetworkCapabilities() {
        WalletAppKit kit = walletAppKit;
        if (kit == null || kit.peerGroup() == null) return "—";
        try {
            PeerGroup group = kit.peerGroup();
            return "Max connections: " + group.getMaxConnections()
                    + "\nMin broadcast connections: " + group.getMinBroadcastConnections()
                    + "\nMin protocol version: " + group.getMinRequiredProtocolVersion()
                    + "\nPing interval: " + group.getPingIntervalMsec() + " ms"
                    + "\nBloom filtering: " + (group.isBloomFilteringEnabled() ? "enabled" : "disabled")
                    + "\nFast catch-up: " + group.getFastCatchupTime();
        } catch (Exception e) {
            return "Unavailable";
        }
    }

    public String getChainTechnicalDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head == null || head.getHeader() == null) return "—";
            org.bitcoinj.core.Block header = head.getHeader();
            return "Chain work: " + head.getChainWork()
                    + "\nHeader version: " + header.getVersion()
                    + "\nNonce: " + header.getNonce()
                    + "\nDifficulty target: " + Long.toUnsignedString(header.getDifficultyTarget())
                    + "\nMerkle root: " + header.getMerkleRoot().toString();
        } catch (Exception e) {
            return "Unavailable";
        }
    }

    public String getWalletChainState() {
        Wallet wallet = walletAppKit == null ? null : walletAppKit.wallet();
        if (wallet == null) return "—";
        try {
            return "Wallet last-seen height: " + wallet.getLastBlockSeenHeight()
                    + "\nPending wallet transactions: " + wallet.getPendingTransactions().size();
        } catch (Exception e) {
            return "Unavailable";
        }
    }

    public int getWalletLastSeenHeight() {
        Wallet wallet = walletAppKit == null ? null : walletAppKit.wallet();
        if (wallet == null) {
            return 0;
        }
        try {
            return wallet.getLastBlockSeenHeight();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public String getDownloadPeerDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null || kit.peerGroup() == null) {
            return "—";
        }
        try {
            Peer peer = kit.peerGroup().getDownloadPeer();
            if (peer == null) {
                return "None";
            }
            String address = peer.getAddress() == null
                    ? "unknown peer"
                    : peer.getAddress().toString();
            return address + "  •  height " + peer.getBestHeight();
        } catch (Exception e) {
            return "Unavailable";
        }
    }

    public String getWalletHealthReport() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return "ERROR\n\nWallet engine is not running.";
        }
        Wallet wallet;
        try {
            wallet = kit.wallet();
        } catch (Exception error) {
            return "ERROR\n\nUnable to access wallet: " + safeMessage(error);
        }

        boolean consistent = true;
        String consistency = "OK";
        try {
            wallet.isConsistentOrThrow();
        } catch (Exception error) {
            consistent = false;
            consistency = "ERROR: " + safeMessage(error);
        }

        int peers = getConnectedPeerCount();
        int chain = getBestChainHeight();
        int network = getNetworkBlock();
        int behind = getBlocksBehind();
        boolean ready = isWalletReady();
        boolean syncing = isSyncing();
        boolean stalled = isSyncStalled();

        String overall = !consistent ? "ERROR"
                : !ready ? "WARNING"
                : peers == 0 ? "WARNING"
                : stalled ? "WARNING"
                : "OK";

        StringBuilder out = new StringBuilder();
        out.append(overall).append("\n\n");
        out.append("Engine: ").append(ready ? "RUNNING" : "STOPPED").append("\n");
        out.append("Sync: ").append(syncing ? (stalled ? "STALLED" : "SYNCING") : "LIVE").append("\n");
        out.append("Network: ").append(getNetworkName()).append("\n");
        out.append("Peers: ").append(peers).append("\n");
        out.append("Wallet block: ").append(chain).append("\n");
        out.append("Network block: ").append(network).append("\n");
        out.append("Blocks behind: ").append(behind).append("\n");
        out.append("Transactions: ").append(wallet.getTransactions(true).size()).append("\n");
        out.append("UTXOs: ").append(wallet.getUnspents().size()).append("\n");
        out.append("Watched scripts: ").append(wallet.getWatchedScripts().size()).append("\n");
        out.append("Consistency: ").append(consistency).append("\n");
        out.append("Wallet file: ").append(walletFile.length()).append(" bytes");
        return out.toString();
    }

    public String getWalletDiagnostics() {
        WalletAppKit kit = walletAppKit;
        Wallet wallet = kit == null ? null : kit.wallet();
        StringBuilder out = new StringBuilder();
        out.append("Network: ").append(getNetworkName());
        out.append("\nEngine: ").append(isWalletKitRunning() ? "Running" : "Stopped");
        out.append("\nSync: ").append(isSyncing() ? "Synchronizing" : "Live");
        out.append("\nWallet block: ").append(getCurrentBlock());
        out.append("\nNetwork block: ").append(getNetworkBlock());
        out.append("\nConnected peers: ").append(getConnectedPeerCount());
        out.append("\nPending peers: ").append(getPendingPeerCount());
        out.append("\nAutomatic reconnects: ").append(autoRestartCount);
        out.append("\nDownload peer: ").append(getDownloadPeerDetails());
        out.append("\nBest chain tip: ").append(getBestChainHash().isEmpty() ? "—" : getBestChainHash());
        out.append("\nLast scanned block: ").append(getWalletLastSeenHeight());

        if (wallet == null) {
            out.append("\nWallet: not loaded");
            return out.toString();
        }

        try {
            out.append("\nWallet file: ").append(walletFile.exists() ? walletFile.length() + " bytes" : "missing");
            out.append("\nEncrypted: ").append(WalletSecurity.isEncrypted(wallet) ? "yes" : "no");
            out.append("\nTransactions: ").append(wallet.getTransactions(true).size());
            int mainUtxos = 0;
            for (TransactionOutput output : wallet.getUnspents()) {
                if (!WalletSelection.isWatchedOutput(wallet, output)) {
                    mainUtxos++;
                }
            }
            out.append("\nMain UTXOs: ").append(mainUtxos);
            out.append("\nWatched scripts: ").append(wallet.getWatchedScripts().size());
            out.append("\nWatched UTXOs: ").append(wallet.getWatchedOutputs(false).size());
            wallet.isConsistentOrThrow();
            out.append("\nConsistency: OK");
        } catch (Exception e) {
            out.append("\nConsistency: ERROR");
        }
        return out.toString();
    }

    public int getAutoRestartCount() {
        return autoRestartCount;
    }

    public String getNetworkName() {
        return Constants.IS_PRODUCTION ? "Bitcoin Mainnet" : "Bitcoin Testnet";
    }

    public void reconnectNow() {
        restartWalletKit("manual reconnect");
    }

    private void notifyWalletUpdated() {
        for (Runnable listener : walletUpdateListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                Log.w(TAG, "Wallet UI listener failed", e);
            }
        }
    }

    /**
     * Listens to blocks arriving after the initial download as well as during
     * catch-up. This is the important realtime path: WalletAppKit continues
     * receiving blocks even when MainActivity is not visible.
     */
    private void setupPeerListeners(PeerGroup peerGroup) {
        if (peerGroup == null) {
            return;
        }

        peerGroup.addBlocksDownloadedEventListener(
                (peer, block, filteredBlock, blocksLeft) -> {
                    WalletAppKit kit = walletAppKit;
                    if (kit == null || shuttingDown) {
                        return;
                    }

                    int chainHeight = safeChainHeight(kit);
                    int peerHeight = safePeerHeight(kit);

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
                        org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
                        if (head != null && head.getHeader() != null) {
                            bestChainHash = head.getHeader().getHashAsString();
                            bestChainTimeSeconds = head.getHeader().getTimeSeconds();
                        }
                    } catch (Exception ignored) {
                    }
                    touchProgress();

                    final boolean liveSynced = downloadFinished;
                    int percent = liveSynced ? 100 : Math.max(0, lastPercent);
                    if (!liveSynced && peerHeight > 0 && chainHeight > 0 && lastPercent < 0) {
                        percent = Math.min(100, Math.max(0,
                                (int) Math.round((chainHeight * 100.0) / peerHeight)));
                    }

                    BitcoinSyncService.updateSyncNotification(
                            applicationContext,
                            percent,
                            chainHeight,
                            peerHeight,
                            !liveSynced);

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
        @Override public void displayTransactions(List<TransactionItem> transactions) { }
        @Override public void showToastMessage(String message) { }
        @Override public android.content.Context getActivityContext() { return context; }
    }

    public static MainActivityPresenter getActivePresenter() {
        return activePresenter;
    }

    public static WalletAppKit getActiveWalletAppKit() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.walletAppKit;
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
