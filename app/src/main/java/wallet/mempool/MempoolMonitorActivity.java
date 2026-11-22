package wallet.mempool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;

import java.text.DateFormat;
import java.util.Date;

import wallet.main.R;
import wallet.network.NetworkConfig;
import wallet.ui.BaseActivity;
import wallet.util.MoneyFormatter;

/** Shows the transactions observed through the active wallet PeerGroup. */
public class MempoolMonitorActivity extends BaseActivity {
    private SwitchCompat monitorSwitch;
    private TextView statusText;
    private TextView networkText;
    private TextView totalCount;
    private TextView walletRelatedCount;
    private TextView okCount;
    private TextView nonFinalCount;
    private TextView nonStandardCount;
    private TextView errorCount;
    private TextView emptyText;
    private View clearButton;
    private MempoolTransactionAdapter adapter;
    private boolean updatingSwitch;

    private final MempoolMonitor.Observer observer = () -> runOnUiThread(this::render);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mempool_monitor);

        Toolbar toolbar = findViewById(R.id.toolbar_mempool_monitor);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.mempool_monitor_title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        monitorSwitch = findViewById(R.id.mempool_monitor_switch);
        statusText = findViewById(R.id.mempool_monitor_status);
        networkText = findViewById(R.id.mempool_monitor_network);
        totalCount = findViewById(R.id.mempool_stat_total);
        walletRelatedCount = findViewById(R.id.mempool_stat_wallet_related);
        okCount = findViewById(R.id.mempool_stat_ok);
        nonFinalCount = findViewById(R.id.mempool_stat_non_final);
        nonStandardCount = findViewById(R.id.mempool_stat_non_standard);
        errorCount = findViewById(R.id.mempool_stat_error);
        emptyText = findViewById(R.id.mempool_monitor_empty);
        clearButton = findViewById(R.id.mempool_monitor_clear);

        RecyclerView recyclerView = findViewById(R.id.mempool_monitor_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        adapter = new MempoolTransactionAdapter(this::showEntryDetails);
        recyclerView.setAdapter(adapter);

        monitorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitch) {
                MempoolMonitor.setEnabled(this, isChecked);
            }
        });
        clearButton.setOnClickListener(v -> confirmClearSession());
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MempoolMonitor.addObserver(observer);
        render();
    }

    @Override
    protected void onPause() {
        MempoolMonitor.removeObserver(observer);
        super.onPause();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        MempoolMonitor.Snapshot snapshot = MempoolMonitor.snapshot(this);

        updatingSwitch = true;
        monitorSwitch.setChecked(snapshot.enabled);
        updatingSwitch = false;

        if (!snapshot.enabled) {
            statusText.setText(R.string.mempool_status_paused);
        } else if (!snapshot.attached) {
            statusText.setText(R.string.mempool_status_waiting_service);
        } else if (snapshot.connectedPeers == 0) {
            statusText.setText(R.string.mempool_status_waiting_peers);
        } else {
            statusText.setText(R.string.mempool_status_active);
        }

        BitcoinNetwork network = snapshot.attached && snapshot.network != null
                ? snapshot.network : NetworkConfig.get(this);
        networkText.setText(getString(R.string.mempool_network_peers,
                NetworkConfig.displayName(this, network), snapshot.connectedPeers));

        totalCount.setText(String.valueOf(snapshot.totalTransactions));
        walletRelatedCount.setText(String.valueOf(snapshot.walletRelatedCount));
        okCount.setText(String.valueOf(snapshot.okCount));
        nonFinalCount.setText(String.valueOf(snapshot.nonFinalCount));
        nonStandardCount.setText(String.valueOf(snapshot.nonStandardCount));
        errorCount.setText(String.valueOf(snapshot.errorCount));

        adapter.setEntries(snapshot.recentTransactions);
        boolean empty = snapshot.recentTransactions.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.mempool_monitor_list).setVisibility(empty ? View.GONE : View.VISIBLE);
        clearButton.setEnabled(snapshot.totalTransactions > 0 || snapshot.totalBroadcastEvents > 0);
    }

    private void showEntryDetails(MempoolMonitor.Entry entry) {
        if (entry == null) {
            return;
        }
        String result = getString(resultLabel(entry.riskStatus));
        String explanation = getString(resultDescription(entry.riskStatus));
        String message = getString(R.string.mempool_detail_message,
                entry.txId,
                result,
                DateFormat.getDateTimeInstance().format(new Date(entry.observedAtMillis)),
                entry.inputCount,
                entry.outputCount,
                entry.sizeBytes,
                MoneyFormatter.format(this, Coin.valueOf(entry.outputValueSatoshis)),
                entry.observationCount,
                getString(entry.walletRelated ? R.string.sync_yes : R.string.sync_no),
                explanation);

        new AlertDialog.Builder(this)
                .setTitle(R.string.mempool_detail_title)
                .setMessage(message)
                .setNeutralButton(R.string.mempool_detail_copy_txid,
                        (dialog, which) -> copyTransactionId(entry.txId))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private int resultLabel(MempoolMonitor.RiskStatus status) {
        if (status == MempoolMonitor.RiskStatus.NON_FINAL) {
            return R.string.mempool_result_non_final;
        }
        if (status == MempoolMonitor.RiskStatus.NON_STANDARD) {
            return R.string.mempool_result_non_standard;
        }
        if (status == MempoolMonitor.RiskStatus.ERROR) {
            return R.string.mempool_result_error;
        }
        return R.string.mempool_result_ok;
    }

    private int resultDescription(MempoolMonitor.RiskStatus status) {
        if (status == MempoolMonitor.RiskStatus.NON_FINAL) {
            return R.string.mempool_result_non_final_description;
        }
        if (status == MempoolMonitor.RiskStatus.NON_STANDARD) {
            return R.string.mempool_result_non_standard_description;
        }
        if (status == MempoolMonitor.RiskStatus.ERROR) {
            return R.string.mempool_result_error_description;
        }
        return R.string.mempool_result_ok_description;
    }

    private void copyTransactionId(String txId) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.mempool_txid_clip_label), txId));
            Toast.makeText(this, R.string.mempool_txid_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearSession() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.mempool_clear_title)
                .setMessage(R.string.mempool_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.mempool_clear_action,
                        (dialog, which) -> MempoolMonitor.clearSessionData())
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
