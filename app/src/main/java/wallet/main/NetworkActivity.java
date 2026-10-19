package wallet.main;

import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import org.bitcoinj.base.BitcoinNetwork;

/** Allows the user to select the active Bitcoin network. */
public class NetworkActivity extends BaseActivity {
    private RadioGroup networkGroup;
    private RadioButton mainnetOption;
    private RadioButton signetOption;
    private boolean updatingSelection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_network);
        Toolbar toolbar = findViewById(R.id.toolbar_network);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.network_settings_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        networkGroup = findViewById(R.id.networkOptionsGroup);
        mainnetOption = findViewById(R.id.networkMainnetOption);
        signetOption = findViewById(R.id.networkSignetOption);
        mainnetOption.setOnClickListener(v -> requestNetworkChange(BitcoinNetwork.MAINNET));
        signetOption.setOnClickListener(v -> requestNetworkChange(BitcoinNetwork.SIGNET));
        renderSelection();
    }

    private void renderSelection() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        BitcoinNetwork selected = presenter == null ? NetworkConfig.get(this) : presenter.getActiveNetwork();
        boolean switching = presenter != null && presenter.isNetworkSwitching();
        updatingSelection = true;
        mainnetOption.setChecked(selected == BitcoinNetwork.MAINNET);
        signetOption.setChecked(selected == BitcoinNetwork.SIGNET);
        networkGroup.setEnabled(!switching);
        mainnetOption.setEnabled(!switching);
        signetOption.setEnabled(!switching);
        updatingSelection = false;
    }

    private void requestNetworkChange(BitcoinNetwork target) {
        if (updatingSelection) return;
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null || presenter.getActiveNetwork() == target || presenter.isNetworkSwitching()) {
            renderSelection();
            return;
        }
        renderSelection();
        new AlertDialog.Builder(this)
            .setTitle(R.string.sync_network_switch_title)
            .setMessage(getString(R.string.sync_network_switch_message, NetworkConfig.displayName(this, target)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sync_network_switch_action, (dialog, which) -> {
                presenter.switchNetwork(target);
                renderSelection();
            })
            .show();
    }

    @Override protected void onResume() { super.onResume(); if (networkGroup != null) renderSelection(); }
    @Override public boolean onSupportNavigateUp() { finish(); return true; }
}
