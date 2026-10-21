package wallet.network;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import android.widget.RadioButton;
import android.widget.RadioGroup;

import org.bitcoinj.base.BitcoinNetwork;
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
        super.onPause();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
