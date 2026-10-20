package wallet.main;

import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import org.bitcoinj.base.BitcoinNetwork;

/** Network selection screen. Uses the existing wallet/network switching implementation. */
public class NetworkActivity extends BaseActivity {
    private RadioGroup selectionGroup;
    private RadioButton mainnet;
    private RadioButton signet;
    private boolean updating;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_network);
        Toolbar toolbar = findViewById(R.id.toolbar_network);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.network_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        selectionGroup = findViewById(R.id.networkChoiceGroup);
        mainnet = findViewById(R.id.networkMainnetOption);
        signet = findViewById(R.id.networkSignetOption);
        mainnet.setOnClickListener(v -> requestNetworkSwitch(BitcoinNetwork.MAINNET));
        signet.setOnClickListener(v -> requestNetworkSwitch(BitcoinNetwork.SIGNET));
        renderSelection();
    }

    @Override protected void onResume() { super.onResume(); renderSelection(); }

    private void renderSelection() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        BitcoinNetwork selected = active != null ? active.getActiveNetwork() : NetworkConfig.get(this);
        updating = true;
        mainnet.setChecked(selected == BitcoinNetwork.MAINNET);
        signet.setChecked(selected == BitcoinNetwork.SIGNET);
        boolean switching = active != null && (active.isNetworkSwitching() || active.isSyncModeSwitching());
        selectionGroup.setEnabled(!switching);
        mainnet.setEnabled(!switching);
        signet.setEnabled(!switching);
        updating = false;
    }

    private void requestNetworkSwitch(BitcoinNetwork target) {
        if (updating) return;
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null || active.getActiveNetwork() == target || active.isNetworkSwitching()) {
            renderSelection();
            return;
        }
        renderSelection();
        new AlertDialog.Builder(this)
            .setTitle(R.string.sync_network_switch_title)
            .setMessage(getString(R.string.sync_network_switch_message, NetworkConfig.displayName(this, target)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sync_network_switch_action, (dialog, which) -> {
                active.switchNetwork(target);
                renderSelection();
            }).show();
    }
}
