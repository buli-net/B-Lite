package wallet.tools;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.bitcoinj.base.Address;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WatchOnlyWalletActivity extends AppCompatActivity {
    private EditText watchAddressInput; private EditText watchDateInput; private Button addWatchAddressButton; private Button rescanWatchedButton; private Button deleteWatchedButton; private LinearLayout watchedAddressList; private TextView watchedBalanceSummary; private Wallet watchedWallet; private WalletChangeEventListener walletChangeListener; private final Set<Script> selectedWatchedScripts = new HashSet<>();
    private MainActivityPresenter syncPresenter;
    private final android.os.Handler watchRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watchRefreshQueued;
    private static final long WATCH_REFRESH_DEBOUNCE_MS = 500L;
    private final Runnable syncStateListener = () -> runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        attachWalletListener();
        scheduleWatchedRefresh();
    });

    private Wallet getWallet(){
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null || !presenter.isWalletReady()) {
            return null;
        }
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            return null;
        }
        try {
            return kit.wallet();
        } catch (IllegalStateException ignored) {
            // WalletAppKit exposes wallet() only after startup has completed. During a
            // rescan the old kit is stopped and the replacement kit starts asynchronously.
            return null;
        }
    }
    private NetworkParameters getParameters(){ return MainActivityPresenter.getActiveParameters(); }
    private void show(int messageId){ Toast.makeText(this,messageId,Toast.LENGTH_LONG).show(); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_watch_only_wallet); Toolbar toolbar=findViewById(R.id.toolbar_watch_only); setSupportActionBar(toolbar);
        if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.watch_only_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);}
        toolbar.setNavigationOnClickListener(v->finish());
        syncPresenter = MainActivityPresenter.getActivePresenter();
        if (syncPresenter != null) {
            syncPresenter.addSyncStateListener(syncStateListener);
        }
        watchAddressInput=findViewById(R.id.watchAddressInput); watchDateInput=findViewById(R.id.watchDateInput); addWatchAddressButton=findViewById(R.id.addWatchAddressButton); rescanWatchedButton=findViewById(R.id.rescanWatchedButton); deleteWatchedButton=findViewById(R.id.deleteWatchedButton); watchedBalanceSummary=findViewById(R.id.watchedBalanceSummary); watchedAddressList=findViewById(R.id.watchedAddressList);
        addWatchAddressButton.setOnClickListener(v->addWatchAddress()); rescanWatchedButton.setOnClickListener(v->rescanWatchedAddresses()); deleteWatchedButton.setOnClickListener(v->deleteSelectedWatchedAddresses()); attachWalletListener(); refreshWatchedAddresses();
    }
    @Override protected void onResume(){
        super.onResume();
        if (syncPresenter == null) {
            syncPresenter = MainActivityPresenter.getActivePresenter();
        }
        if (syncPresenter != null) {
            syncPresenter.addSyncStateListener(syncStateListener);
        }
        attachWalletListener();
        refreshWatchedAddresses();
    }
    @Override protected void onPause(){
        if (syncPresenter != null) {
            syncPresenter.removeSyncStateListener(syncStateListener);
        }
        detachWalletListener();
        watchRefreshHandler.removeCallbacksAndMessages(null);
        watchRefreshQueued = false;
        super.onPause();
    }
    @Override protected void onDestroy(){
        if (syncPresenter != null) {
            syncPresenter.removeSyncStateListener(syncStateListener);
        }
        detachWalletListener();
        watchRefreshHandler.removeCallbacksAndMessages(null);
        watchRefreshQueued = false;
        super.onDestroy();
    }

    private void addWatchAddress() {
        String encoded = watchAddressInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.watch_address_required);
            return;
        }

        Wallet wallet = getWallet();
        NetworkParameters parameters = getParameters();
        if (wallet == null || parameters == null) {
            show(R.string.wallet_not_ready);
            return;
        }

        final Address address;
        try {
            address = Address.fromString(parameters, encoded);
        } catch (Exception error) {
            show(R.string.watch_address_invalid);
            return;
        }

        Instant creationTime;
        try {
            creationTime = parseDate(watchDateInput.getText().toString().trim());
        } catch (Exception error) {
            show(R.string.invalid_wallet_birthday);
            return;
        }

        addWatchAddressButton.setEnabled(false);
        new Thread(() -> {
            try {
                boolean added = creationTime == null
                        ? wallet.addWatchedAddress(address)
                        : wallet.addWatchedAddress(address, creationTime);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                }
                runOnUiThread(() -> {
                    addWatchAddressButton.setEnabled(true);
                    if (added) {
                        watchAddressInput.setText("");
                        String scanDate = watchDateInput.getText().toString().trim();
                        watchDateInput.setText("");
                        refreshWatchedAddresses();
                        Toast.makeText(this, R.string.watch_address_added, Toast.LENGTH_LONG).show();
                        // A rescan replaces WalletAppKit and the SPV chain. Do not start that
                        // replacement in the same UI turn as the wallet mutation: on a fresh
                        // install the wallet, presenter, and watchdog can still be settling.
                        // If normal sync is active, leave the explicit Rescan action to the user.
                        MainActivityPresenter activePresenter = MainActivityPresenter.getActivePresenter();
                        if (activePresenter != null && !activePresenter.isSyncing()) {
                            Instant scanFrom = null;
                            try {
                                scanFrom = TextUtils.isEmpty(scanDate)
                                        ? Instant.ofEpochSecond(1231006505L)
                                        : parseDate(scanDate);
                            } catch (Exception ignored) {
                                // The date was already validated above.
                            }
                            if (scanFrom != null) {
                                final Instant finalScanFrom = scanFrom;
                                new android.os.Handler(getMainLooper()).postDelayed(
                                        () -> rescanWatchedAddresses(finalScanFrom, false),
                                        1000L);
                            }
                        }
                    } else {
                        refreshWatchedAddresses();
                        Toast.makeText(this, R.string.watch_address_already_present, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    addWatchAddressButton.setEnabled(true);
                    Toast.makeText(this, getString(
                            R.string.watch_address_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-watch-address").start();
    }
    private void rescanWatchedAddresses() {
        Wallet wallet = getWallet();
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (wallet == null || presenter == null) {
            show(R.string.wallet_not_ready);
            return;
        }

        String dateText = watchDateInput.getText().toString().trim();
        final Instant scanFrom;
        try {
            scanFrom = TextUtils.isEmpty(dateText)
                    ? Instant.ofEpochSecond(1231006505L)
                    : parseDate(dateText);
        } catch (Exception error) {
            show(R.string.invalid_wallet_birthday);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.watch_rescan_title)
                .setMessage(getString(R.string.watch_rescan_message, scanFrom.toString().substring(0, 10)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.watch_rescan_button, (dialog, which) -> {
                    rescanWatchedAddresses(scanFrom, true);
                })
                .show();
    }
    private void rescanWatchedAddresses(final Instant scanFrom, boolean showDialogButtonState) {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        if (showDialogButtonState) {
            rescanWatchedButton.setEnabled(false);
        }
        presenter.rescanWatchedAddresses(scanFrom, error -> runOnUiThread(() -> {
            if (showDialogButtonState) {
                rescanWatchedButton.setEnabled(true);
            }
            refreshWatchedAddresses();
            if (error != null) {
                Toast.makeText(this, getString(
                        R.string.watch_rescan_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.watch_rescan_started, Toast.LENGTH_LONG).show();
            }
        }));
    }
    private void deleteSelectedWatchedAddresses() {
        if (selectedWatchedScripts.isEmpty()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.watch_delete_title)
                .setMessage(R.string.watch_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.watch_delete_button, (dialog, which) -> {
                    Wallet wallet = getWallet();
                    if (wallet == null) {
                        show(R.string.wallet_not_ready);
                        return;
                    }
                    Set<Script> toDelete = new HashSet<>(selectedWatchedScripts);
                    new Thread(() -> {
                        try {
                            wallet.removeWatchedScripts(new ArrayList<>(toDelete));
                            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                            if (presenter != null) {
                                presenter.saveWalletNow();
                            }
                            runOnUiThread(() -> {
                                selectedWatchedScripts.clear();
                                refreshWatchedAddresses();
                                Toast.makeText(this, R.string.watch_deleted, Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> Toast.makeText(this, getString(
                                    R.string.watch_delete_failed,
                                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                                    Toast.LENGTH_LONG).show());
                        }
                    }, "wallet-delete-watched").start();
                })
                .show();
    }
    private void attachWalletListener() {
        Wallet wallet = getWallet();
        if (wallet == watchedWallet) {
            return;
        }

        detachWalletListener();
        if (wallet == null) {
            return;
        }

        watchedWallet = wallet;
        walletChangeListener = changedWallet -> scheduleWatchedRefresh();
        wallet.addChangeEventListener(walletChangeListener);
    }
    private void detachWalletListener() {
        if (watchedWallet != null && walletChangeListener != null) {
            watchedWallet.removeChangeEventListener(walletChangeListener);
        }
        watchedWallet = null;
        walletChangeListener = null;
    }
    private void scheduleWatchedRefresh() {
        if (isFinishing() || isDestroyed() || watchedAddressList == null) {
            return;
        }
        if (watchRefreshQueued) {
            return;
        }
        watchRefreshQueued = true;
        watchRefreshHandler.postDelayed(() -> {
            watchRefreshQueued = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            refreshWatchedAddresses();
        }, WATCH_REFRESH_DEBOUNCE_MS);
    }

    private void refreshWatchedAddresses() {
        Wallet wallet = getWallet();
        if (wallet == null || watchedAddressList == null) {
            return;
        }

        watchedAddressList.removeAllViews();

        List<Script> scripts = wallet.getWatchedScripts();
        selectedWatchedScripts.retainAll(scripts);

        if (scripts == null || scripts.isEmpty()) {
            if (watchedBalanceSummary != null) {
                watchedBalanceSummary.setText(getWatchSummary(
                        org.bitcoinj.base.Coin.ZERO.toFriendlyString()));
            }
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, watchedAddressList, false);
            empty.setText(R.string.watch_address_empty);
            watchedAddressList.addView(empty);
            updateDeleteButton();
            return;
        }

        NetworkParameters parameters = getParameters();
        // Use bitcoinj's dedicated watched-output API. It returns the currently unspent
        // outputs whose script exactly matches an address/script registered as watched.
        // This keeps normal wallet UTXOs out of the watch-only totals.
        List<TransactionOutput> outputs = wallet.getWatchedOutputs(false);
        Map<Script, WatchBalance> balances = new HashMap<>();
        for (Script script : scripts) {
            balances.put(script, new WatchBalance());
        }

        for (TransactionOutput output : outputs) {
            if (!output.isAvailableForSpending()) {
                continue;
            }
            Script outputScript = output.getScriptPubKey();
            WatchBalance balance = balances.get(outputScript);
            if (balance == null) {
                continue;
            }
            long value = output.getValue().value;
            if (output.getParentTransactionDepthInBlocks() > 0) {
                balance.confirmed += value;
            } else {
                balance.pending += value;
            }
        }

        long watchedTotal = 0L;
        for (Script script : scripts) {
            WatchBalance balance = balances.get(script);
            watchedTotal += balance.total();

            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_watch_address, watchedAddressList, false);

            CheckBox checkBox = row.findViewById(R.id.watchAddressCheckBox);
            TextView typeView = row.findViewById(R.id.watchAddressType);
            TextView addressView = row.findViewById(R.id.watchAddressValue);
            TextView confirmedView = row.findViewById(R.id.watchAddressConfirmed);
            TextView pendingView = row.findViewById(R.id.watchAddressPending);
            TextView totalView = row.findViewById(R.id.watchAddressTotal);

            String type = script.getScriptType() == null
                    ? "UNKNOWN"
                    : script.getScriptType().name();
            String address;
            try {
                address = script.getToAddress(parameters).toString();
            } catch (Exception error) {
                address = script.toString();
            }

            typeView.setText(type);
            addressView.setText(address);
            confirmedView.setText(getString(
                    R.string.watch_confirmed_value,
                    org.bitcoinj.base.Coin.valueOf(balance.confirmed).toFriendlyString()));
            pendingView.setText(getString(
                    R.string.watch_pending_value,
                    org.bitcoinj.base.Coin.valueOf(balance.pending).toFriendlyString()));
            totalView.setText(org.bitcoinj.base.Coin.valueOf(balance.total()).toFriendlyString());

            checkBox.setChecked(selectedWatchedScripts.contains(script));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedWatchedScripts.add(script);
                } else {
                    selectedWatchedScripts.remove(script);
                }
                updateDeleteButton();
            });

            watchedAddressList.addView(row);
        }

        if (watchedBalanceSummary != null) {
            watchedBalanceSummary.setText(getWatchSummary(
                    org.bitcoinj.base.Coin.valueOf(watchedTotal).toFriendlyString()));
        }
        updateDeleteButton();
    }
    private String getWatchSummary(String total) {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        int lastScanned = presenter == null ? 0 : presenter.getWalletLastSeenHeight();
        return getString(R.string.watch_balance_total_with_scan, total, lastScanned);
    }
    private void updateDeleteButton() {
        if (deleteWatchedButton != null) {
            deleteWatchedButton.setEnabled(!selectedWatchedScripts.isEmpty());
        }
    }
    private Instant parseDate(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return LocalDate.parse(value)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }
    private static final class WatchBalance { private long confirmed; private long pending; private long total(){return confirmed+pending;} }
}
