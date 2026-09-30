package wallet.main;

import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Live blockchain and BitcoinJ synchronization monitor. */
public class SyncActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MainActivityPresenter presenter;
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
    private TextView syncTargetInline;
    private TextView syncBlockAge;
    private TextView pendingPeers;
    private TextView peers;
    private TextView network;
    private TextView engine;
    private TextView restarts;
    private TextView liveNote;
    private TextView peerList;
    private TextView networkCapabilities;
    private TextView chainTechnical;
    private TextView walletChainState;
    private Button refresh;
    private Button reconnect;
    private Button allowBackgroundSync;
    private TextView backgroundSyncStatus;

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sync);
        bindViews();
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sync_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        BitcoinSyncService.start(this);
        presenter = MainActivityPresenter.getActivePresenter();
        refresh.setOnClickListener(v -> render());
        reconnect.setOnClickListener(v -> {
            MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
            if (active != null) {
                active.reconnectNow();
                render();
            }
        });
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
        syncTargetInline = findViewById(R.id.syncTargetInline);
        syncBlockAge = findViewById(R.id.syncBlockAge);
        pendingPeers = findViewById(R.id.syncPendingPeers);
        peers = findViewById(R.id.syncPeers);
        network = findViewById(R.id.syncNetwork);
        engine = findViewById(R.id.syncEngine);
        restarts = findViewById(R.id.syncRestarts);
        liveNote = findViewById(R.id.syncLiveNote);
        peerList = findViewById(R.id.syncPeerList);
        networkCapabilities = findViewById(R.id.syncNetworkCapabilities);
        chainTechnical = findViewById(R.id.syncChainTechnical);
        walletChainState = findViewById(R.id.syncWalletChainState);
        refresh = findViewById(R.id.syncRefreshButton);
        reconnect = findViewById(R.id.syncReconnectButton);
        allowBackgroundSync = findViewById(R.id.syncAllowBackgroundButton);
        backgroundSyncStatus = findViewById(R.id.syncBackgroundStatus);
    }

    private void render() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null) {
            status.setText(R.string.sync_status_starting);
            progress.setIndeterminate(true);
            percent.setText("0 %");
            return;
        }
        presenter = active;
        int pct = active.getSyncPercent();
        int current = active.getCurrentBlock();
        int target = active.getNetworkBlock();

        progress.setIndeterminate(false);
        progress.setProgress(pct);
        percent.setText(getString(R.string.percentage_display, pct));
        walletBlock.setText(String.valueOf(current));
        networkBlock.setText(String.valueOf(target));
        syncTargetInline.setText(String.valueOf(target));
        bestChain.setText(String.valueOf(active.getBestChainHeight()));
        blocksBehind.setText(String.valueOf(active.getBlocksBehind()));
        peers.setText(String.valueOf(active.getConnectedPeerCount()));
        pendingPeers.setText(String.valueOf(active.getPendingPeerCount()));
        String hash = active.getBestChainHash();
        chainHash.setText(hash.isEmpty() ? "—" : hash);
        long blockTime = active.getBestChainTimeSeconds();
        lastBlockTime.setText(blockTime <= 0L
                ? "—"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(blockTime * 1000L)));
        if (blockTime <= 0L) {
            syncBlockAge.setText("—");
        } else {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() / 1000L) - blockTime);
            syncBlockAge.setText(formatAge(ageSeconds));
        }
        syncRate.setText(String.format(Locale.US, "%.2f blocks/s", active.getSyncBlocksPerSecond()));
        network.setText(active.getNetworkName());
        engine.setText(active.isWalletKitRunning()
                ? R.string.sync_engine_running : R.string.sync_engine_stopped);
        restarts.setText(String.valueOf(active.getAutoRestartCount()));
        peerList.setText(getString(R.string.sync_download_peer_value, active.getDownloadPeerDetails())
                + "\n\n" + active.getPeerGroupDetails());
        networkCapabilities.setText(active.getNetworkCapabilities());
        chainTechnical.setText(active.getChainTechnicalDetails());
        walletChainState.setText(active.getWalletChainState());
        status.setText(active.getSyncStatusResId());
        liveNote.setText(R.string.sync_live_note);
        reconnect.setEnabled(active.isWalletReady());
        renderBackgroundSyncState();
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
        backgroundSyncStatus.setText(unrestricted
                ? R.string.sync_background_allowed
                : R.string.sync_background_restricted);
        allowBackgroundSync.setText(unrestricted
                ? R.string.sync_background_cancel_button
                : R.string.sync_background_allow_button);
        allowBackgroundSync.setEnabled(true);
    }

    private String formatAge(long seconds) {
        if (seconds < 60L) return seconds + " s";
        if (seconds < 3600L) return (seconds / 60L) + " min";
        if (seconds < 86400L) return (seconds / 3600L) + " h";
        return (seconds / 86400L) + " d";
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
