package wallet.node;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.BitcoinNetwork;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wallet.main.R;
import wallet.network.NetworkConfig;
import wallet.ui.BaseActivity;

/** Status and lifecycle screen for the optional local Bitcoin Core pruned node. */
public class BitcoinCoreNodeActivity extends BaseActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private boolean requestInFlight;

    private TextView stateValue;
    private TextView networkValue;
    private TextView blocksValue;
    private TextView headersValue;
    private TextView peersValue;
    private TextView mempoolValue;
    private TextView verificationValue;
    private TextView pruningValue;
    private TextView storageValue;
    private TextView bestBlockValue;
    private TextView messageValue;
    private ProgressBar verificationProgress;
    private Button startButton;
    private Button stopButton;
    private Button refreshButton;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 6000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_bitcoin_core_node);

        Toolbar toolbar = findViewById(R.id.toolbarBitcoinCoreNode);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.core_node_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        stateValue = findViewById(R.id.coreNodeStateValue);
        networkValue = findViewById(R.id.coreNodeNetworkValue);
        blocksValue = findViewById(R.id.coreNodeBlocksValue);
        headersValue = findViewById(R.id.coreNodeHeadersValue);
        peersValue = findViewById(R.id.coreNodePeersValue);
        mempoolValue = findViewById(R.id.coreNodeMempoolValue);
        verificationValue = findViewById(R.id.coreNodeVerificationValue);
        pruningValue = findViewById(R.id.coreNodePruningValue);
        storageValue = findViewById(R.id.coreNodeStorageValue);
        bestBlockValue = findViewById(R.id.coreNodeBestBlockValue);
        messageValue = findViewById(R.id.coreNodeMessageValue);
        verificationProgress = findViewById(R.id.coreNodeVerificationProgress);
        startButton = findViewById(R.id.coreNodeStartButton);
        stopButton = findViewById(R.id.coreNodeStopButton);
        refreshButton = findViewById(R.id.coreNodeRefreshButton);

        startButton.setOnClickListener(v -> confirmStart());
        stopButton.setOnClickListener(v -> confirmStop());
        refreshButton.setOnClickListener(v -> refreshStatus());
        renderStoredState();
    }

    private void confirmStart() {
        if (!hasUsableBinary()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.core_node_binary_missing_title)
                    .setMessage(R.string.core_node_binary_missing_detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        BitcoinNetwork network = NetworkConfig.get(this);
        String networkName = NetworkConfig.displayName(this, network);
        new AlertDialog.Builder(this)
                .setTitle(R.string.core_node_start_confirm_title)
                .setMessage(getString(R.string.core_node_start_confirm_message,
                        networkName, BitcoinCoreConfig.PRUNE_TARGET_MIB))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.core_node_start, (dialog, which) -> {
                    BitcoinCoreNodeService.start(this, network);
                    renderStoredState();
                })
                .show();
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.core_node_stop_confirm_title)
                .setMessage(R.string.core_node_stop_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.core_node_stop, (dialog, which) -> {
                    BitcoinCoreNodeService.stop(this);
                    renderStoredState();
                })
                .show();
    }

    private void refreshStatus() {
        if (requestInFlight) return;
        requestInFlight = true;
        refreshButton.setEnabled(false);
        ioExecutor.execute(() -> {
            BitcoinNetwork network = activeNetwork();
            try {
                BitcoinCoreStatus status = new BitcoinCoreRpcClient(this, network).fetchStatus();
                long usable = BitcoinCoreConfig.dataDirectory(this, network).getUsableSpace();
                runOnUiThread(() -> renderStatus(network, status, usable));
            } catch (Exception exception) {
                String state = BitcoinCoreNodeService.state(this);
                String message;
                if (!hasUsableBinary()) {
                    message = getString(R.string.core_node_binary_missing_detail);
                } else if ("stopped".equals(state) || "missing_binary".equals(state)) {
                    message = getString(R.string.core_node_not_running_detail);
                } else {
                    message = getString(R.string.core_node_rpc_waiting_detail);
                    String serviceMessage = BitcoinCoreNodeService.message(this);
                    if (!TextUtils.isEmpty(serviceMessage) && "error".equals(state)) {
                        message = serviceMessage;
                    }
                }
                final String finalMessage = message;
                runOnUiThread(() -> renderUnavailable(network, finalMessage));
            } finally {
                runOnUiThread(() -> {
                    requestInFlight = false;
                    refreshButton.setEnabled(true);
                    renderButtons();
                });
            }
        });
    }

    private BitcoinNetwork activeNetwork() {
        String value = BitcoinCoreNodeService.networkName(this);
        try {
            BitcoinNetwork network = BitcoinNetwork.valueOf(value);
            if (BitcoinCoreConfig.isSupportedNetwork(network)
                    && BitcoinCoreNodeService.isActive(this)) return network;
        } catch (IllegalArgumentException ignored) {
            // Use the wallet's current network when the node has not selected one yet.
        }
        return NetworkConfig.get(this);
    }

    private void renderStoredState() {
        BitcoinNetwork network = activeNetwork();
        networkValue.setText(NetworkConfig.displayName(this, network));
        String state = BitcoinCoreNodeService.state(this);
        String status;
        if (!hasUsableBinary()) {
            status = getString(R.string.core_node_binary_missing_title);
        } else if ("starting".equals(state)) {
            status = getString(R.string.core_node_state_starting);
        } else if ("syncing".equals(state)) {
            status = getString(R.string.core_node_state_syncing);
        } else if ("running".equals(state)) {
            status = getString(R.string.core_node_state_running);
        } else if ("error".equals(state)) {
            status = getString(R.string.core_node_state_error);
        } else if ("stopping".equals(state)) {
            status = getString(R.string.core_node_stopping);
        } else {
            status = getString(R.string.core_node_state_stopped);
        }
        stateValue.setText(status);
        String serviceMessage = BitcoinCoreNodeService.message(this);
        if (TextUtils.isEmpty(serviceMessage)) {
            serviceMessage = hasUsableBinary()
                    ? getString(R.string.core_node_not_running_detail)
                    : getString(R.string.core_node_binary_missing_detail);
        }
        messageValue.setText(serviceMessage);
        renderButtons();
    }

    private void renderUnavailable(BitcoinNetwork network, String message) {
        stateValue.setText(!hasUsableBinary()
                ? getString(R.string.core_node_binary_missing_title)
                : getString(R.string.core_node_state_unavailable));
        networkValue.setText(NetworkConfig.displayName(this, network));
        blocksValue.setText(R.string.core_node_value_unavailable);
        headersValue.setText(R.string.core_node_value_unavailable);
        peersValue.setText(R.string.core_node_value_unavailable);
        mempoolValue.setText(R.string.core_node_value_unavailable);
        verificationValue.setText(R.string.core_node_value_unavailable);
        pruningValue.setText(R.string.core_node_value_unavailable);
        storageValue.setText(getString(R.string.core_node_storage_value,
                getString(R.string.core_node_value_unavailable),
                formatBytes(BitcoinCoreConfig.dataDirectory(this, network).getUsableSpace())));
        bestBlockValue.setText(R.string.core_node_value_unavailable);
        verificationProgress.setProgress(0);
        messageValue.setText(message);
        renderButtons();
    }

    private void renderStatus(BitcoinNetwork network, BitcoinCoreStatus status, long usableBytes) {
        stateValue.setText(status.initialBlockDownload
                ? getString(R.string.core_node_state_syncing)
                : getString(R.string.core_node_state_running));
        networkValue.setText(NetworkConfig.displayName(this, network));
        blocksValue.setText(NumberFormat.getIntegerInstance().format(status.blocks));
        headersValue.setText(NumberFormat.getIntegerInstance().format(status.headers));
        peersValue.setText(NumberFormat.getIntegerInstance().format(status.connections));
        mempoolValue.setText(NumberFormat.getIntegerInstance().format(status.mempoolTransactions));
        verificationValue.setText(getString(R.string.core_node_percent_value, status.verificationPercent()));
        verificationProgress.setProgress(status.verificationPercent());
        pruningValue.setText(getString(R.string.core_node_pruning_value,
                status.pruned ? getString(R.string.core_node_enabled) : getString(R.string.core_node_disabled),
                NumberFormat.getIntegerInstance().format(status.pruneHeight),
                formatBytes(status.pruneTargetBytes)));
        storageValue.setText(getString(R.string.core_node_storage_value,
                formatBytes(status.blockUndoSizeBytes), formatBytes(usableBytes)));
        bestBlockValue.setText(TextUtils.isEmpty(status.bestBlockHash)
                ? getString(R.string.core_node_value_unavailable) : status.bestBlockHash);
        messageValue.setText(status.initialBlockDownload
                ? getString(R.string.core_node_initial_sync_detail)
                : getString(R.string.core_node_live_detail));
        renderButtons();
    }

    private void renderButtons() {
        boolean binaryAvailable = hasUsableBinary();
        boolean active = BitcoinCoreNodeService.isActive(this);
        startButton.setEnabled(binaryAvailable && !active);
        stopButton.setEnabled(active);
        refreshButton.setEnabled(!requestInFlight);
    }


    private boolean hasUsableBinary() {
        java.io.File binary = BitcoinCoreConfig.binary(this);
        return binary.isFile() && binary.canExecute();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) bytes = 0L;
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int index = 0;
        while (value >= 1024.0d && index < units.length - 1) {
            value /= 1024.0d;
            index++;
        }
        return String.format(Locale.getDefault(), "%.2f %s", value, units[index]);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
