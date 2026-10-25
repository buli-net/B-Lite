package wallet.network;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.widget.Button;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import android.widget.RadioButton;
import android.widget.RadioGroup;

import org.bitcoinj.base.BitcoinNetwork;
import com.google.common.net.HostAndPort;
import java.util.List;
import wallet.ui.BaseActivity;
import wallet.sync.BitcoinSyncService;
import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Network and synchronization-mode preferences. */
public class NetworkActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RadioGroup networkSelectionGroup;
    private RadioButton mainnetOption;
    private RadioButton signetOption;
    private RadioGroup syncModeSelectionGroup;
    private RadioButton liteModeOption;
    private RadioButton fullModeOption;
    private EditText trustedPeerInput;
    private CheckBox trustedPeerOnlyOption;
    private Button trustedPeerApplyButton;
    private TextView trustedPeerStatus;
    private TextView trustedPeerTestResult;
    private Button trustedPeerTestButton;
    private final ExecutorService peerTestExecutor = Executors.newSingleThreadExecutor();
    private int peerTestGeneration = 0;
    private BitcoinNetwork trustedPeerScreenNetwork;
    private boolean networkSelectionUpdating;
    private boolean syncModeSelectionUpdating;

    private final Runnable selectionUpdater = new Runnable() {
        @Override public void run() {
            renderSelections();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_network);

        Toolbar toolbar = findViewById(R.id.toolbar_network);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.network_settings_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        networkSelectionGroup = findViewById(R.id.networkSelectionGroup);
        mainnetOption = findViewById(R.id.networkMainnetOption);
        signetOption = findViewById(R.id.networkSignetOption);
        syncModeSelectionGroup = findViewById(R.id.networkSyncModeSelectionGroup);
        liteModeOption = findViewById(R.id.networkSyncModeLiteOption);
        fullModeOption = findViewById(R.id.networkSyncModeFullOption);
        trustedPeerInput = findViewById(R.id.networkTrustedPeerInput);
        trustedPeerOnlyOption = findViewById(R.id.networkTrustedPeerOnly);
        trustedPeerApplyButton = findViewById(R.id.networkTrustedPeerSave);
        trustedPeerStatus = findViewById(R.id.networkTrustedPeerStatus);
        trustedPeerTestResult = findViewById(R.id.networkTrustedPeerTestResult);
        trustedPeerTestButton = findViewById(R.id.networkTrustedPeerTest);

        trustedPeerInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTrustedPeerControlState();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        trustedPeerApplyButton.setOnClickListener(v -> saveTrustedPeerSettings());
        trustedPeerTestButton.setOnClickListener(v -> testTrustedPeers());

        BitcoinSyncService.start(this);
        mainnetOption.setOnClickListener(v -> requestNetworkSwitch(BitcoinNetwork.MAINNET));
        signetOption.setOnClickListener(v -> requestNetworkSwitch(BitcoinNetwork.SIGNET));
        liteModeOption.setOnClickListener(v -> requestSyncModeSwitch(NetworkConfig.SyncMode.LITE));
        fullModeOption.setOnClickListener(v -> requestSyncModeSwitch(NetworkConfig.SyncMode.FULL));
        renderSelections();
    }

    private void renderSelections() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        BitcoinNetwork selectedNetwork = active == null ? NetworkConfig.get(this) : active.getActiveNetwork();
        NetworkConfig.SyncMode selectedMode = active == null
                ? NetworkConfig.getSyncMode(this, selectedNetwork)
                : active.getSyncMode();
        boolean switching = active != null && (active.isNetworkSwitching() || active.isSyncModeSwitching());

        networkSelectionUpdating = true;
        mainnetOption.setChecked(selectedNetwork == BitcoinNetwork.MAINNET);
        signetOption.setChecked(selectedNetwork == BitcoinNetwork.SIGNET);
        networkSelectionUpdating = false;
        networkSelectionGroup.setEnabled(!switching);
        mainnetOption.setEnabled(!switching);
        signetOption.setEnabled(!switching);

        syncModeSelectionUpdating = true;
        liteModeOption.setChecked(selectedMode == NetworkConfig.SyncMode.LITE);
        fullModeOption.setChecked(selectedMode == NetworkConfig.SyncMode.FULL);
        syncModeSelectionUpdating = false;
        syncModeSelectionGroup.setEnabled(!switching);
        liteModeOption.setEnabled(!switching);
        fullModeOption.setEnabled(!switching);
        renderTrustedPeerSettings(selectedNetwork, switching);
    }

    private void renderTrustedPeerSettings(BitcoinNetwork network, boolean switching) {
        if (network == null) {
            return;
        }
        if (trustedPeerScreenNetwork != network) {
            trustedPeerInput.setText(NetworkConfig.getTrustedPeerInput(this, network));
            trustedPeerOnlyOption.setChecked(NetworkConfig.isTrustedPeersOnly(this, network));
            trustedPeerScreenNetwork = network;
            trustedPeerInput.setHint(getString(R.string.network_trusted_peer_hint,
                    NetworkConfig.parameters(network).getPort()));
        }
        updateTrustedPeerControlState(switching);

        String configured = NetworkConfig.getTrustedPeerInput(this, network).trim();
        if (configured.isEmpty()) {
            trustedPeerStatus.setText(R.string.network_trusted_peer_status_discovery);
        } else if (NetworkConfig.isTrustedPeersOnly(this, network)) {
            trustedPeerStatus.setText(getString(R.string.network_trusted_peer_status_exclusive,
                    NetworkConfig.displayName(this, network), NetworkConfig.parameters(network).getPort()));
        } else {
            trustedPeerStatus.setText(getString(R.string.network_trusted_peer_status_configured,
                    NetworkConfig.displayName(this, network), NetworkConfig.parameters(network).getPort()));
        }
    }

    private void updateTrustedPeerControlState() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        boolean switching = active != null
                && (active.isNetworkSwitching() || active.isSyncModeSwitching());
        updateTrustedPeerControlState(switching);
    }

    private void updateTrustedPeerControlState(boolean switching) {
        if (trustedPeerInput == null || trustedPeerOnlyOption == null || trustedPeerApplyButton == null) {
            return;
        }
        boolean hasPeer = !TextUtils.isEmpty(trustedPeerInput.getText().toString().trim());
        trustedPeerInput.setEnabled(!switching);
        trustedPeerOnlyOption.setEnabled(!switching && hasPeer);
        trustedPeerApplyButton.setEnabled(!switching);
        if (trustedPeerTestButton != null) trustedPeerTestButton.setEnabled(!switching && hasPeer);
    }

    /** Tests TCP reachability only; bitcoinj's live peer connection remains the authority for protocol/network validity. */
    private void testTrustedPeers() {
        final BitcoinNetwork target = trustedPeerScreenNetwork == null
                ? NetworkConfig.get(this) : trustedPeerScreenNetwork;
        final List<HostAndPort> peers;
        try {
            peers = NetworkConfig.parseTrustedPeerInput(trustedPeerInput.getText().toString().trim(), target);
        } catch (IllegalArgumentException invalid) {
            trustedPeerInput.setError(getString(R.string.network_trusted_peer_invalid));
            return;
        }
        if (peers.isEmpty()) {
            trustedPeerInput.setError(getString(R.string.network_trusted_peer_invalid));
            return;
        }
        final int generation = ++peerTestGeneration;
        trustedPeerTestButton.setEnabled(false);
        trustedPeerTestResult.setText(R.string.network_trusted_peer_test_running);
        peerTestExecutor.execute(() -> {
            StringBuilder result = new StringBuilder();
            for (HostAndPort endpoint : peers) {
                String host = endpoint.getHost();
                int port = endpoint.hasPort() ? endpoint.getPort() : NetworkConfig.parameters(target).getPort();
                long started = android.os.SystemClock.elapsedRealtime();
                String state;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 4000);
                    long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                    state = getString(R.string.network_trusted_peer_test_tcp_ok, host, port, elapsed);
                } catch (java.net.SocketTimeoutException timeout) {
                    state = getString(R.string.network_trusted_peer_test_timeout, host, port);
                } catch (java.net.UnknownHostException unknown) {
                    state = getString(R.string.network_trusted_peer_test_dns_failed, host);
                } catch (IOException failure) {
                    String detail = failure.getMessage();
                    state = getString(R.string.network_trusted_peer_test_failed, host, port,
                            detail == null ? failure.getClass().getSimpleName() : detail);
                } catch (RuntimeException failure) {
                    state = getString(R.string.network_trusted_peer_test_failed, host, port,
                            failure.getClass().getSimpleName());
                }
                if (result.length() > 0) result.append('\n');
                result.append(state);
            }
            final String report = result.toString();
            handler.post(() -> {
                if (isFinishing() || generation != peerTestGeneration) return;
                trustedPeerTestResult.setText(report + "\n" + getString(R.string.network_trusted_peer_test_caveat));
                trustedPeerTestButton.setEnabled(true);
            });
        });
    }

    private void saveTrustedPeerSettings() {
        BitcoinNetwork target = trustedPeerScreenNetwork;
        if (target == null) {
            target = NetworkConfig.get(this);
        }

        String rawInput = trustedPeerInput.getText().toString().trim();
        trustedPeerInput.setError(null);
        final List<HostAndPort> parsedPeers;
        try {
            parsedPeers = NetworkConfig.parseTrustedPeerInput(rawInput, target);
        } catch (IllegalArgumentException invalid) {
            trustedPeerInput.setError(getString(R.string.network_trusted_peer_invalid));
            trustedPeerInput.requestFocus();
            return;
        }

        String oldInput = NetworkConfig.getTrustedPeerInput(this, target).trim();
        boolean oldOnly = NetworkConfig.isTrustedPeersOnly(this, target);
        boolean newOnly = trustedPeerOnlyOption.isChecked() && !parsedPeers.isEmpty();
        if (rawInput.equals(oldInput) && newOnly == oldOnly) {
            Toast.makeText(this, R.string.network_trusted_peer_no_changes, Toast.LENGTH_SHORT).show();
            return;
        }

        NetworkConfig.setTrustedPeerSettings(this, target, rawInput, newOnly);
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active != null && active.getActiveNetwork() == target) {
            boolean restartStarted = active.restartForNetworkSettings();
            Toast.makeText(this, restartStarted
                    ? R.string.network_trusted_peer_saved_reconnecting
                    : R.string.network_trusted_peer_saved_next_restart,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.network_trusted_peer_saved_inactive_network,
                    NetworkConfig.displayName(this, target)), Toast.LENGTH_LONG).show();
        }
        renderTrustedPeerSettings(target, false);
    }

    private void requestNetworkSwitch(BitcoinNetwork target) {
        if (networkSelectionUpdating) return;
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null || active.getActiveNetwork() == target
                || active.isNetworkSwitching() || active.isSyncModeSwitching()) {
            renderSelections();
            return;
        }
        networkSelectionUpdating = true;
        mainnetOption.setChecked(active.getActiveNetwork() == BitcoinNetwork.MAINNET);
        signetOption.setChecked(active.getActiveNetwork() == BitcoinNetwork.SIGNET);
        networkSelectionUpdating = false;

        new AlertDialog.Builder(this)
                .setTitle(R.string.network_switch_title)
                .setMessage(getString(R.string.network_switch_message, NetworkConfig.displayName(this, target)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> renderSelections())
                .setPositiveButton(R.string.network_switch_action, (dialog, which) -> {
                    active.switchNetwork(target);
                    renderSelections();
                })
                .setOnCancelListener(dialog -> renderSelections())
                .show();
    }

    private void requestSyncModeSwitch(NetworkConfig.SyncMode target) {
        if (syncModeSelectionUpdating) return;
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null || active.getSyncMode() == target
                || active.isNetworkSwitching() || active.isSyncModeSwitching()) {
            renderSelections();
            return;
        }
        syncModeSelectionUpdating = true;
        liteModeOption.setChecked(active.getSyncMode() == NetworkConfig.SyncMode.LITE);
        fullModeOption.setChecked(active.getSyncMode() == NetworkConfig.SyncMode.FULL);
        syncModeSelectionUpdating = false;

        new AlertDialog.Builder(this)
                .setTitle(R.string.network_sync_mode_switch_title)
                .setMessage(R.string.network_sync_mode_switch_message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> renderSelections())
                .setPositiveButton(R.string.network_sync_mode_switch_action, (dialog, which) -> {
                    active.switchSyncMode(target);
                    renderSelections();
                })
                .setOnCancelListener(dialog -> renderSelections())
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(selectionUpdater);
        handler.post(selectionUpdater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(selectionUpdater);
        peerTestGeneration++;
        super.onPause();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
