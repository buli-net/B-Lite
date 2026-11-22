package wallet.sync;

import android.os.Bundle;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.net.TrafficStats;
import android.os.Process;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import wallet.ui.TextViewUtils;
import org.bitcoinj.base.BitcoinNetwork;
import wallet.ui.BaseActivity;
import wallet.ui.LocalizedErrors;
import wallet.network.NetworkConfig;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
/** Live blockchain and BitcoinJ synchronization monitor. */
public class SyncActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Toolbar toolbar;
    private ProgressBar progress;
    private TextView status;
    private TextView percent;
    private TextView walletBlock;
    private TextView networkBlock;
    private TextView bestChain;
    private TextView blocksBehind;
    private TextView chainHash;
    private TextView lastBlockTime;
    private TextView syncRate;
    private TextView syncRefreshTiming;
    private TextView syncTargetInline;
    private TextView syncBlockAge;
    private TextView pendingPeers;
    private TextView peers;
    private TextView activeNetworkName;
    private TextView activeNetworkTitle;
    private TextView engine;
    private TextView restarts;
    private TextView liveNote;
    private TextView peerList;
    private TextView networkCapabilities;
    private TextView chainTechnical;
    private TextView merkleRoot;
    private TextView walletChainState;
    private Button refresh;
    private Button reconnect;
    private Button rescan;
    private Button allowBackgroundSync;
    private TextView backgroundSyncStatus;
    private SyncLineChartView chainChart;
    private SyncLineChartView progressChart;
    private SyncLineChartView blockAgeChart;
    private SyncLineChartView pingChart;
    private SyncLineChartView rateChart;
    private SyncLineChartView peerChart;
    private SyncLineChartView behindChart;
    private SyncLineChartView trafficChart;
    private SyncLineChartView refreshChart;
    private final ArrayDeque<SyncSample> chartSamples = new ArrayDeque<>();
    private long previousRxBytes = -1L;
    private long previousTxBytes = -1L;
    private long previousTrafficAt;


    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sync);
        bindViews();
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sync_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        BitcoinSyncService.start(this);
        refresh.setOnClickListener(v -> render());
        reconnect.setOnClickListener(v -> {
            MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
            if (active != null) {
                active.reconnectNow();
                render();
            }
        });
        rescan.setOnClickListener(v -> showRescanDialog());
        allowBackgroundSync.setOnClickListener(v -> {
            boolean unrestricted = BitcoinSyncService.isBatteryOptimizationIgnored(this);
            if (unrestricted) {
                showCancelBackgroundSyncDialog();
                return;
            }

            boolean opened = BitcoinSyncService.requestBatteryOptimizationExemption(this);
            if (!opened) {
                Toast.makeText(this, R.string.sync_background_settings_failed, Toast.LENGTH_SHORT).show();
            }
        });
        handler.post(updater);
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_sync);
        progress = findViewById(R.id.syncPageProgress);
        status = findViewById(R.id.syncPageStatus);
        percent = findViewById(R.id.syncPagePercent);
        walletBlock = findViewById(R.id.syncWalletBlock);
        networkBlock = findViewById(R.id.syncNetworkBlock);
        bestChain = findViewById(R.id.syncBestChain);
        blocksBehind = findViewById(R.id.syncBlocksBehind);
        chainHash = findViewById(R.id.syncChainHash);
        lastBlockTime = findViewById(R.id.syncLastBlockTime);
        syncRate = findViewById(R.id.syncRate);
        syncRefreshTiming = findViewById(R.id.syncRefreshTiming);
        syncTargetInline = findViewById(R.id.syncTargetInline);
        syncBlockAge = findViewById(R.id.syncBlockAge);
        pendingPeers = findViewById(R.id.syncPendingPeers);
        peers = findViewById(R.id.syncPeers);
        activeNetworkName = findViewById(R.id.syncActiveNetworkName);
        activeNetworkTitle = findViewById(R.id.syncActiveNetworkTitle);
        engine = findViewById(R.id.syncEngine);
        restarts = findViewById(R.id.syncRestarts);
        liveNote = findViewById(R.id.syncLiveNote);
        peerList = findViewById(R.id.syncPeerList);
        networkCapabilities = findViewById(R.id.syncNetworkCapabilities);
        chainTechnical = findViewById(R.id.syncChainTechnical);
        merkleRoot = findViewById(R.id.syncMerkleRoot);
        walletChainState = findViewById(R.id.syncWalletChainState);
        TextViewUtils.configureSelectableMiddleEllipsis(chainHash);
        TextViewUtils.configureSelectableMiddleEllipsis(merkleRoot);
        refresh = findViewById(R.id.syncRefreshButton);
        reconnect = findViewById(R.id.syncReconnectButton);
        rescan = findViewById(R.id.syncRescanButton);
        allowBackgroundSync = findViewById(R.id.syncAllowBackgroundButton);
        backgroundSyncStatus = findViewById(R.id.syncBackgroundStatus);
        chainChart = findViewById(R.id.syncChartChain);
        progressChart = findViewById(R.id.syncChartProgress);
        blockAgeChart = findViewById(R.id.syncChartBlockAge);
        pingChart = findViewById(R.id.syncChartPing);
        rateChart = findViewById(R.id.syncChartRate);
        peerChart = findViewById(R.id.syncChartPeers);
        behindChart = findViewById(R.id.syncChartBehind);
        trafficChart = findViewById(R.id.syncChartTraffic);
        refreshChart = findViewById(R.id.syncChartRefresh);
    }

    private void render() {
        BitcoinNetwork activeNetwork = NetworkConfig.get(this);
        int networkLabel = activeNetwork == BitcoinNetwork.SIGNET
                ? R.string.network_badge_signet
                : R.string.network_badge_mainnet;
        setTextIfChanged(activeNetworkTitle, getString(networkLabel));

        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null) {
            status.setText(R.string.sync_status_starting);
            progress.setIndeterminate(true);
            percent.setText(R.string.sync_percent_initial);
            return;
        }
        int pct = active.getSyncPercent();
        int current = active.getCurrentBlock();
        int target = active.getNetworkBlock();
        updateLiveCharts(active, current, target, pct);

        progress.setIndeterminate(false);
        progress.setProgress(pct);
        if (hasActiveSelection()) {
            reconnect.setEnabled(active.isWalletReady());
            renderBackgroundSyncState();
            return;
        }
        setTextIfChanged(percent, getString(R.string.percentage_display, pct));
        setTextIfChanged(walletBlock, String.valueOf(current));
        setTextIfChanged(networkBlock, String.valueOf(target));
        setTextIfChanged(syncTargetInline, String.valueOf(target));
        setTextIfChanged(bestChain, String.valueOf(active.getBestChainHeight()));
        setTextIfChanged(blocksBehind, String.valueOf(active.getBlocksBehind()));
        setTextIfChanged(peers, String.valueOf(active.getConnectedPeerCount()));
        setTextIfChanged(pendingPeers, String.valueOf(active.getPendingPeerCount()));
        String hash = active.getBestChainHash();
        setTextIfChanged(chainHash, hash.isEmpty() ? getString(R.string.sync_placeholder) : hash);

        long blockTime = active.getBestChainTimeSeconds();
        if (blockTime <= 0L) {
            setTextIfChanged(lastBlockTime, getString(R.string.sync_placeholder));
        } else {
            setTextIfChanged(lastBlockTime, new SimpleDateFormat(getString(R.string.date_time_pattern), Locale.getDefault())
                    .format(new Date(blockTime * 1000L)));
        }
        if (blockTime <= 0L) {
            setTextIfChanged(syncBlockAge, getString(R.string.sync_placeholder));
        } else {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() / 1000L) - blockTime);
            setTextIfChanged(syncBlockAge, formatAge(ageSeconds));
        }
        setTextIfChanged(syncRate, getString(R.string.sync_rate_value, active.getSyncBlocksPerSecond()));
        long refreshTotalMs = active.getLastRefreshTotalMs();
        long refreshProcessingMs = active.getLastRefreshProcessingMs();
        if (refreshTotalMs < 0L || refreshProcessingMs < 0L) {
            setTextIfChanged(syncRefreshTiming, getString(R.string.sync_refresh_timing_initial));
        } else {
            setTextIfChanged(syncRefreshTiming, getString(R.string.sync_refresh_timing,
                    refreshTotalMs, refreshProcessingMs));
        }
        setTextIfChanged(activeNetworkName, active.getNetworkName());
        setTextIfChanged(engine, getString(active.isWalletKitRunning()
                ? R.string.sync_engine_running : R.string.sync_engine_stopped));
        setTextIfChanged(restarts, String.valueOf(active.getAutoRestartCount()));
        String peerDetails = getString(R.string.sync_download_peer_value, active.getDownloadPeerDetails())
                + "\n\n" + active.getPeerGroupDetails();
        setTextIfChanged(peerList, peerDetails);
        setTextIfChanged(networkCapabilities, active.getNetworkCapabilities());
        setTextIfChanged(chainTechnical, active.getChainTechnicalDetails());
        setTextIfChanged(merkleRoot, active.getChainMerkleRoot());
        setTextIfChanged(walletChainState, active.getWalletChainState());
        long recoveryWaitSeconds = active.getAutomaticRecoveryWaitSeconds();
        if (recoveryWaitSeconds > 0L
                && (active.getConnectedPeerCount() == 0 || active.isSyncStalled())) {
            setTextIfChanged(status, getString(R.string.sync_status_recovery_wait,
                    recoveryWaitSeconds));
        } else {
            setTextIfChanged(status, getString(active.getSyncStatusResId()));
        }
        setTextIfChanged(liveNote, getString(R.string.sync_live_note));
        reconnect.setEnabled(active.isWalletReady());
        renderBackgroundSyncState();
    }


    private void updateLiveCharts(MainActivityPresenter active, int current, int target, int percent) {
        long now = System.currentTimeMillis();
        long rxBytes = TrafficStats.getUidRxBytes(Process.myUid());
        long txBytes = TrafficStats.getUidTxBytes(Process.myUid());
        float rxRate = Float.NaN;
        float txRate = Float.NaN;
        if (rxBytes >= 0L && txBytes >= 0L && previousRxBytes >= 0L
                && previousTxBytes >= 0L && previousTrafficAt > 0L && now > previousTrafficAt) {
            float seconds = (now - previousTrafficAt) / 1000f;
            rxRate = Math.max(0L, rxBytes - previousRxBytes) / 1024f / seconds;
            txRate = Math.max(0L, txBytes - previousTxBytes) / 1024f / seconds;
        }
        previousRxBytes = rxBytes;
        previousTxBytes = txBytes;
        previousTrafficAt = now;

        chartSamples.addLast(new SyncSample(
                current,
                target,
                percent,
                active.getBestChainTimeSeconds() <= 0L ? Float.NaN
                        : Math.max(0L, System.currentTimeMillis() / 1000L - active.getBestChainTimeSeconds()),
                (float) active.getSyncBlocksPerSecond(),
                active.getConnectedPeerCount(),
                active.getPendingPeerCount(),
                active.getAveragePeerPingMs() < 0L ? Float.NaN : active.getAveragePeerPingMs(),
                Math.max(0, target - current),
                rxRate,
                txRate,
                active.getLastRefreshTotalMs() < 0L ? Float.NaN : active.getLastRefreshTotalMs(),
                active.getLastRefreshProcessingMs() < 0L ? Float.NaN : active.getLastRefreshProcessingMs()));
        while (chartSamples.size() > 60) chartSamples.removeFirst();

        List<SyncSample> samples = new ArrayList<>(chartSamples);
        chainChart.setChartData(getString(R.string.sync_chart_chain_title),
                new String[]{getString(R.string.sync_chart_best_chain), getString(R.string.sync_chart_network_chain)},
                new float[][]{values(samples, 0), values(samples, 1)});
        progressChart.setChartData(getString(R.string.sync_chart_progress_title),
                new String[]{getString(R.string.sync_chart_progress_percent)},
                new float[][]{values(samples, 2)});
        blockAgeChart.setChartData(getString(R.string.sync_chart_block_age_title),
                new String[]{getString(R.string.sync_chart_block_age_seconds)},
                new float[][]{values(samples, 3)});
        rateChart.setChartData(getString(R.string.sync_chart_rate_title),
                new String[]{getString(R.string.sync_chart_blocks_per_second)},
                new float[][]{values(samples, 4)});
        peerChart.setChartData(getString(R.string.sync_chart_peers_title),
                new String[]{getString(R.string.sync_chart_connected), getString(R.string.sync_chart_pending)},
                new float[][]{values(samples, 5), values(samples, 6)});
        pingChart.setChartData(getString(R.string.sync_chart_ping_title),
                new String[]{getString(R.string.sync_chart_ping_ms)},
                new float[][]{values(samples, 7)});
        behindChart.setChartData(getString(R.string.sync_chart_behind_title),
                new String[]{getString(R.string.sync_chart_blocks_behind)},
                new float[][]{values(samples, 8)});
        boolean trafficAvailable = rxBytes >= 0L && txBytes >= 0L;
        trafficChart.setVisibility(trafficAvailable ? android.view.View.VISIBLE : android.view.View.GONE);
        if (trafficAvailable) {
            trafficChart.setChartData(getString(R.string.sync_chart_traffic_title),
                    new String[]{getString(R.string.sync_chart_received), getString(R.string.sync_chart_sent)},
                    new float[][]{values(samples, 9), values(samples, 10)});
        }
        refreshChart.setChartData(getString(R.string.sync_chart_refresh_title),
                new String[]{getString(R.string.sync_chart_total), getString(R.string.sync_chart_processing)},
                new float[][]{values(samples, 11), values(samples, 12)});
    }

    private float[] values(List<SyncSample> samples, int metric) {
        float[] result = new float[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            SyncSample sample = samples.get(i);
            switch (metric) {
                case 0: result[i] = sample.bestChain; break;
                case 1: result[i] = sample.networkChain; break;
                case 2: result[i] = sample.progressPercent; break;
                case 3: result[i] = sample.blockAgeSeconds; break;
                case 4: result[i] = sample.blocksPerSecond; break;
                case 5: result[i] = sample.connectedPeers; break;
                case 6: result[i] = sample.pendingPeers; break;
                case 7: result[i] = sample.averagePingMs; break;
                case 8: result[i] = sample.blocksBehind; break;
                case 9: result[i] = sample.receivedKbPerSecond; break;
                case 10: result[i] = sample.sentKbPerSecond; break;
                case 11: result[i] = sample.refreshTotalMs; break;
                default: result[i] = sample.refreshProcessingMs; break;
            }
        }
        return result;
    }

    private static final class SyncSample {
        final float bestChain;
        final float networkChain;
        final float progressPercent;
        final float blockAgeSeconds;
        final float blocksPerSecond;
        final float connectedPeers;
        final float pendingPeers;
        final float averagePingMs;
        final float blocksBehind;
        final float receivedKbPerSecond;
        final float sentKbPerSecond;
        final float refreshTotalMs;
        final float refreshProcessingMs;

        SyncSample(float bestChain, float networkChain, float progressPercent, float blockAgeSeconds,
                   float blocksPerSecond, float connectedPeers, float pendingPeers, float averagePingMs,
                   float blocksBehind, float receivedKbPerSecond, float sentKbPerSecond,
                   float refreshTotalMs, float refreshProcessingMs) {
            this.bestChain = bestChain;
            this.networkChain = networkChain;
            this.progressPercent = progressPercent;
            this.blockAgeSeconds = blockAgeSeconds;
            this.blocksPerSecond = blocksPerSecond;
            this.connectedPeers = connectedPeers;
            this.pendingPeers = pendingPeers;
            this.averagePingMs = averagePingMs;
            this.blocksBehind = blocksBehind;
            this.receivedKbPerSecond = receivedKbPerSecond;
            this.sentKbPerSecond = sentKbPerSecond;
            this.refreshTotalMs = refreshTotalMs;
            this.refreshProcessingMs = refreshProcessingMs;
        }
    }

    private boolean hasActiveSelection() {
        return hasSelection(peerList)
                || hasSelection(networkCapabilities)
                || hasSelection(chainTechnical)
                || hasSelection(merkleRoot)
                || hasSelection(chainHash)
                || hasSelection(walletChainState);
    }

    private boolean hasSelection(TextView view) {
        return view != null && view.hasSelection();
    }

    private void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null || view.hasSelection()) return;
        CharSequence current = view.getText();
        if (!TextUtils.equals(current, value)) {
            view.setText(value);
        }
    }

    private void showRescanDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_rescan_title)
                .setMessage(R.string.sync_rescan_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sync_rescan_button, (dialog, which) -> startRescan())
                .show();
    }

    private void startRescan() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null || !active.isWalletReady()) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        rescan.setEnabled(false);
        active.rescanWatchedAddresses(
                java.time.Instant.ofEpochSecond(1231006505L),
                error -> runOnUiThread(() -> {
                    rescan.setEnabled(true);
                    if (error != null) {
                        Toast.makeText(this, getString(
                                R.string.sync_rescan_failed,
                                LocalizedErrors.resolve(this, error)),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.sync_rescan_started, Toast.LENGTH_LONG).show();
                        render();
                    }
                }));
    }

    private void showCancelBackgroundSyncDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_background_cancel_title)
                .setMessage(R.string.sync_background_cancel_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sync_background_cancel_confirm, (dialog, which) ->
                        openBatteryOptimizationRevokeScreen())
                .show();
    }

    private void openBatteryOptimizationRevokeScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            renderBackgroundSyncState();
            return;
        }

        boolean opened = BitcoinSyncService.openBatteryOptimizationSettings(this);
        if (!opened) {
            Toast.makeText(this, R.string.sync_background_settings_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderBackgroundSyncState() {
        boolean unrestricted = BitcoinSyncService.isBatteryOptimizationIgnored(this);
        setTextIfChanged(backgroundSyncStatus, getString(unrestricted
                ? R.string.sync_background_allowed
                : R.string.sync_background_restricted));
        setTextIfChanged(allowBackgroundSync, getString(unrestricted
                ? R.string.sync_background_cancel_button
                : R.string.sync_background_allow_button));
        allowBackgroundSync.setEnabled(true);
    }

    private String formatAge(long seconds) {
        if (seconds < 60L) return getString(R.string.sync_block_age_seconds, seconds);
        if (seconds < 3600L) return getString(R.string.sync_block_age_minutes, seconds / 60L);
        if (seconds < 86400L) return getString(R.string.sync_block_age_hours, seconds / 3600L);
        return getString(R.string.sync_block_age_days, seconds / 86400L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(updater);
        chartSamples.clear();
        previousRxBytes = -1L;
        previousTxBytes = -1L;
        previousTrafficAt = 0L;
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
