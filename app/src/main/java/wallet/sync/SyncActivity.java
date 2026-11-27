package wallet.sync;

import android.os.Bundle;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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
    private Spinner chartTimeRange;
    private ScrollView syncScrollView;
    private boolean chartScrollActive;
    private boolean chartRefreshPending;
    private long lastRenderedChartSampleTime = -1L;
    private int lastRenderedChartRangeIndex = -1;
    private BitcoinNetwork lastRenderedChartNetwork;
    private boolean emptyChartsRendered;
    private static final long CHART_REFRESH_INTERVAL_MILLIS = 1000L;
    private static final long CHART_SCROLL_IDLE_DELAY_MILLIS = 350L;
    private SyncChartHistoryStore chartHistoryStore;
    private BitcoinNetwork chartHistoryNetwork;
    private int chartRangeIndex;
    private static final int[] CHART_RANGE_MINUTES = {1, 5, 15, 60, 360, 1440};
    private final int MAX_CHART_POINTS = 600;
    private final ArrayList<SyncChartHistoryStore.Sample> chartSamples = new ArrayList<>();
    private long lastChartReloadAt;


    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable chartUpdater = new Runnable() {
        @Override
        public void run() {
            requestChartRefresh();
            handler.postDelayed(this, CHART_REFRESH_INTERVAL_MILLIS);
        }
    };

    private final Runnable chartScrollIdleRunnable = new Runnable() {
        @Override
        public void run() {
            chartScrollActive = false;
            if (chartRefreshPending) {
                chartRefreshPending = false;
                lastChartReloadAt = 0L;
                renderCharts();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sync);
        bindViews();
        syncScrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            chartScrollActive = true;
            handler.removeCallbacks(chartScrollIdleRunnable);
            handler.postDelayed(chartScrollIdleRunnable, CHART_SCROLL_IDLE_DELAY_MILLIS);
        });
        ensureChartHistoryStoreForCurrentNetwork();
        chartRangeIndex = getSharedPreferences("sync_chart_preferences", MODE_PRIVATE)
                .getInt("time_range_index", 1);
        if (chartRangeIndex < 0 || chartRangeIndex >= CHART_RANGE_MINUTES.length) chartRangeIndex = 1;
        reloadChartHistory(true);
        setupChartTimeRange();
        renderCharts();
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
        handler.postDelayed(chartUpdater, CHART_REFRESH_INTERVAL_MILLIS);
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
        chartTimeRange = findViewById(R.id.syncChartTimeRange);
        syncScrollView = findViewById(R.id.syncScrollView);
    }

    private void render() {
        if (chartHistoryNetwork != getCurrentChartNetwork()) {
            requestChartRefresh();
        }
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


    private void setupChartTimeRange() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.sync_chart_time_ranges, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        chartTimeRange.setAdapter(adapter);
        chartTimeRange.setSelection(chartRangeIndex, false);
        chartTimeRange.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                                  int position, long id) {
                if (position < 0 || position >= CHART_RANGE_MINUTES.length) return;
                chartRangeIndex = position;
                getSharedPreferences("sync_chart_preferences", MODE_PRIVATE).edit()
                        .putInt("time_range_index", position).apply();
                requestChartRefresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void requestChartRefresh() {
        if (chartScrollActive) {
            chartRefreshPending = true;
            return;
        }
        renderCharts();
    }

    private void renderCharts() {
        ensureChartHistoryStoreForCurrentNetwork();
        reloadChartHistory(false);
        if (chartSamples.isEmpty()) {
            if (!emptyChartsRendered || lastRenderedChartNetwork != chartHistoryNetwork
                    || lastRenderedChartRangeIndex != chartRangeIndex) {
                clearCharts();
            }
            emptyChartsRendered = true;
            lastRenderedChartSampleTime = -1L;
            lastRenderedChartRangeIndex = chartRangeIndex;
            lastRenderedChartNetwork = chartHistoryNetwork;
            return;
        }

        long latestSampleTime = chartSamples.get(chartSamples.size() - 1).timestampMillis;
        if (!emptyChartsRendered && latestSampleTime == lastRenderedChartSampleTime
                && lastRenderedChartRangeIndex == chartRangeIndex
                && lastRenderedChartNetwork == chartHistoryNetwork) {
            return;
        }

        long now = System.currentTimeMillis();
        long rangeMillis = CHART_RANGE_MINUTES[chartRangeIndex] * 60_000L;
        long cutoff = now - rangeMillis;
        int firstVisible = firstSampleAtOrAfter(cutoff);
        if (firstVisible >= chartSamples.size()) {
            if (!emptyChartsRendered) clearCharts();
            emptyChartsRendered = true;
            lastRenderedChartSampleTime = latestSampleTime;
            lastRenderedChartRangeIndex = chartRangeIndex;
            lastRenderedChartNetwork = chartHistoryNetwork;
            return;
        }
        List<SyncChartHistoryStore.Sample> visible = chartSamples.subList(
                firstVisible, chartSamples.size());
        List<SyncChartHistoryStore.Sample> samples = downsample(visible, MAX_CHART_POINTS);
        long[] sampleTimes = new long[samples.size()];
        for (int i = 0; i < samples.size(); i++) sampleTimes[i] = samples.get(i).timestampMillis;
        chainChart.setChartData(getString(R.string.sync_chart_chain_title),
                new String[]{getString(R.string.sync_chart_best_chain), getString(R.string.sync_chart_network_chain)},
                new float[][]{values(samples, 0), values(samples, 1)}, getString(R.string.sync_chart_unit_blocks), sampleTimes, cutoff, now);
        progressChart.setChartData(getString(R.string.sync_chart_progress_title),
                new String[]{getString(R.string.sync_chart_progress_percent)},
                new float[][]{values(samples, 2)}, getString(R.string.sync_chart_unit_percent), sampleTimes, cutoff, now);
        blockAgeChart.setChartData(getString(R.string.sync_chart_block_age_title),
                new String[]{getString(R.string.sync_chart_block_age_seconds)},
                new float[][]{values(samples, 3)}, getString(R.string.sync_chart_unit_seconds), sampleTimes, cutoff, now);
        rateChart.setChartData(getString(R.string.sync_chart_rate_title),
                new String[]{getString(R.string.sync_chart_blocks_per_second)},
                new float[][]{values(samples, 4)}, getString(R.string.sync_chart_unit_blocks_per_second), sampleTimes, cutoff, now);
        peerChart.setChartData(getString(R.string.sync_chart_peers_title),
                new String[]{getString(R.string.sync_chart_connected), getString(R.string.sync_chart_pending)},
                new float[][]{values(samples, 5), values(samples, 6)}, getString(R.string.sync_chart_unit_peers), sampleTimes, cutoff, now);
        pingChart.setChartData(getString(R.string.sync_chart_ping_title),
                new String[]{getString(R.string.sync_chart_ping_ms)},
                new float[][]{values(samples, 7)}, getString(R.string.sync_chart_unit_ms), sampleTimes, cutoff, now);
        behindChart.setChartData(getString(R.string.sync_chart_behind_title),
                new String[]{getString(R.string.sync_chart_blocks_behind)},
                new float[][]{values(samples, 8)}, getString(R.string.sync_chart_unit_blocks), sampleTimes, cutoff, now);
        boolean trafficAvailable = hasFiniteValue(samples, 9) || hasFiniteValue(samples, 10);
        trafficChart.setVisibility(trafficAvailable ? android.view.View.VISIBLE : android.view.View.GONE);
        if (trafficAvailable) {
            trafficChart.setChartData(getString(R.string.sync_chart_traffic_title),
                    new String[]{getString(R.string.sync_chart_received), getString(R.string.sync_chart_sent)},
                    new float[][]{values(samples, 9), values(samples, 10)}, getString(R.string.sync_chart_unit_kb_per_second), sampleTimes, cutoff, now);
        }
        refreshChart.setChartData(getString(R.string.sync_chart_refresh_title),
                new String[]{getString(R.string.sync_chart_total), getString(R.string.sync_chart_processing)},
                new float[][]{values(samples, 11), values(samples, 12)}, getString(R.string.sync_chart_unit_ms), sampleTimes, cutoff, now);
        lastRenderedChartSampleTime = latestSampleTime;
        lastRenderedChartRangeIndex = chartRangeIndex;
        lastRenderedChartNetwork = chartHistoryNetwork;
        emptyChartsRendered = false;
    }


    private int firstSampleAtOrAfter(long timestampMillis) {
        int low = 0;
        int high = chartSamples.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (chartSamples.get(middle).timestampMillis < timestampMillis) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private boolean hasFiniteValue(List<SyncChartHistoryStore.Sample> samples, int metric) {
        for (SyncChartHistoryStore.Sample sample : samples) {
            float value;
            switch (metric) {
                case 9: value = sample.receivedKbPerSecond; break;
                case 10: value = sample.sentKbPerSecond; break;
                default: value = Float.NaN; break;
            }
            if (Float.isFinite(value)) return true;
        }
        return false;
    }

    private void clearCharts() {
        long[] noTimes = new long[0];
        chainChart.setChartData(getString(R.string.sync_chart_chain_title),
                new String[]{getString(R.string.sync_chart_best_chain), getString(R.string.sync_chart_network_chain)},
                new float[][]{new float[0], new float[0]}, getString(R.string.sync_chart_unit_blocks), noTimes);
        progressChart.setChartData(getString(R.string.sync_chart_progress_title),
                new String[]{getString(R.string.sync_chart_progress_percent)}, new float[][]{new float[0]},
                getString(R.string.sync_chart_unit_percent), noTimes);
        blockAgeChart.setChartData(getString(R.string.sync_chart_block_age_title),
                new String[]{getString(R.string.sync_chart_block_age_seconds)}, new float[][]{new float[0]},
                getString(R.string.sync_chart_unit_seconds), noTimes);
        rateChart.setChartData(getString(R.string.sync_chart_rate_title),
                new String[]{getString(R.string.sync_chart_blocks_per_second)}, new float[][]{new float[0]},
                getString(R.string.sync_chart_unit_blocks_per_second), noTimes);
        peerChart.setChartData(getString(R.string.sync_chart_peers_title),
                new String[]{getString(R.string.sync_chart_connected), getString(R.string.sync_chart_pending)},
                new float[][]{new float[0], new float[0]}, getString(R.string.sync_chart_unit_peers), noTimes);
        pingChart.setChartData(getString(R.string.sync_chart_ping_title),
                new String[]{getString(R.string.sync_chart_ping_ms)}, new float[][]{new float[0]},
                getString(R.string.sync_chart_unit_ms), noTimes);
        behindChart.setChartData(getString(R.string.sync_chart_behind_title),
                new String[]{getString(R.string.sync_chart_blocks_behind)}, new float[][]{new float[0]},
                getString(R.string.sync_chart_unit_blocks), noTimes);
        trafficChart.setChartData(getString(R.string.sync_chart_traffic_title),
                new String[]{getString(R.string.sync_chart_received), getString(R.string.sync_chart_sent)},
                new float[][]{new float[0], new float[0]}, getString(R.string.sync_chart_unit_kb_per_second), noTimes);
        refreshChart.setChartData(getString(R.string.sync_chart_refresh_title),
                new String[]{getString(R.string.sync_chart_total), getString(R.string.sync_chart_processing)},
                new float[][]{new float[0], new float[0]}, getString(R.string.sync_chart_unit_ms), noTimes);
    }

    private List<SyncChartHistoryStore.Sample> downsample(List<SyncChartHistoryStore.Sample> source, int maximum) {
        if (source.size() <= maximum) return source;
        List<SyncChartHistoryStore.Sample> result = new ArrayList<>(maximum);
        double step = (source.size() - 1d) / (maximum - 1d);
        for (int i = 0; i < maximum; i++) {
            result.add(source.get((int) Math.round(i * step)));
        }
        return result;
    }

    private float[] values(List<SyncChartHistoryStore.Sample> samples, int metric) {
        float[] result = new float[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            SyncChartHistoryStore.Sample sample = samples.get(i);
            switch (metric) {
                case 0: result[i] = sample.bestChain; break;
                case 1: result[i] = sample.networkChain; break;
                case 2: result[i] = sample.progressPercent; break;
                case 3: result[i] = sample.blockAgeSeconds; break;
                case 4: result[i] = sample.blocksPerSecond; break;
                case 5: result[i] = sample.connectedPeers; break;
                case 6: result[i] = sample.pendingPeers; break;
                case 7: result[i] = sample.averagePeerPingMs; break;
                case 8: result[i] = sample.blocksBehind; break;
                case 9: result[i] = sample.receivedKbPerSecond; break;
                case 10: result[i] = sample.sentKbPerSecond; break;
                case 11: result[i] = sample.refreshTotalMs; break;
                default: result[i] = sample.refreshProcessingMs; break;
            }
        }
        return result;
    }

    private BitcoinNetwork getCurrentChartNetwork() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        return active != null ? active.getActiveNetwork() : NetworkConfig.get(this);
    }

    private void ensureChartHistoryStoreForCurrentNetwork() {
        BitcoinNetwork selected = getCurrentChartNetwork();
        if (chartHistoryStore != null && chartHistoryNetwork == selected) return;

        SyncChartHistoryStore replacement = new SyncChartHistoryStore(
                getApplicationContext(), selected);
        if (chartHistoryStore != null) chartHistoryStore.close();
        chartHistoryStore = replacement;
        chartHistoryNetwork = selected;
        chartSamples.clear();
        lastChartReloadAt = 0L;
        lastRenderedChartSampleTime = -1L;
        lastRenderedChartRangeIndex = -1;
        lastRenderedChartNetwork = null;
        emptyChartsRendered = false;
    }

    private void reloadChartHistory(boolean force) {
        ensureChartHistoryStoreForCurrentNetwork();
        long now = System.currentTimeMillis();
        if (!force && now - lastChartReloadAt < CHART_REFRESH_INTERVAL_MILLIS) return;

        long retentionCutoff = now - 24L * 60L * 60L * 1000L;
        if (force || chartSamples.isEmpty()) {
            chartSamples.clear();
            chartSamples.addAll(chartHistoryStore.loadSince(retentionCutoff));
        } else {
            long lastTimestamp = chartSamples.get(chartSamples.size() - 1).timestampMillis;
            if (lastTimestamp < now) {
                chartSamples.addAll(chartHistoryStore.loadSince(
                        Math.max(retentionCutoff, lastTimestamp + 1L)));
            }
            int firstRetained = firstSampleAtOrAfter(retentionCutoff);
            if (firstRetained > 0) chartSamples.subList(0, firstRetained).clear();
        }
        lastChartReloadAt = now;
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
        handler.removeCallbacks(chartUpdater);
        handler.post(chartUpdater);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(updater);
        handler.removeCallbacks(chartUpdater);
        handler.removeCallbacks(chartScrollIdleRunnable);
        chartScrollActive = false;
        chartRefreshPending = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updater);
        handler.removeCallbacks(chartUpdater);
        handler.removeCallbacks(chartScrollIdleRunnable);
        if (chartHistoryStore != null) {
            chartHistoryStore.close();
            chartHistoryStore = null;
            chartHistoryNetwork = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
