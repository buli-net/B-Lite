package wallet.watchonly;

import wallet.ui.LocalizedErrors;

import wallet.util.MoneyFormatter;
import wallet.ui.BaseActivity;

import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import androidx.appcompat.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.wallet.Wallet;
import wallet.main.MainActivityPresenter;
import wallet.core.WalletSelection;
import wallet.main.R;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.BitcoinNetwork;
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
import wallet.ui.TextViewUtils;
import wallet.security.WalletSecurity;
import org.bitcoinj.crypto.AesKey;
import wallet.network.NetworkConfig;
import wallet.model.TransactionItem;
import wallet.transaction.TransactionMapper;
import wallet.transaction.TransactionDetailActivity;

public final class WatchOnlyWalletActivity extends BaseActivity {
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (!TextUtils.isEmpty(result.getContents())) {
                    EditText input = findViewById(R.id.watchAddressInput);
                    input.setText(result.getContents().trim());
                    input.setSelection(input.length());
                }
            });
    private EditText watchAddressInput;
    private EditText watchDateInput;
    private Button addWatchAddressButton;
    private TextView watchedAddressEmpty;
    private Button deleteWatchedButton;
    private LinearLayout watchedAddressList;
    private TextView watchedBalanceSummary;
    private EditText accountXpubInput;
    private Spinner accountXpubFormat;
    private TextView accountXpubCount;
    private TextView accountXpubListEmpty;
    private LinearLayout accountXpubList;
    private BitcoinNetwork displayedAccountXpubNetwork;
    private Button importAccountXpubButton;
    private Button exportAccountXpubButton;
    private View accountXpubTransactionsPanel;
    private TextView accountXpubTransactionsEmpty;
    private LinearLayout accountXpubTransactionList;
    private String lastAccountXpubHistoryKey;
    private volatile Instant pendingAccountXpubRescanFrom;
    private boolean accountXpubRescanScheduled;
    private Wallet watchedWallet;
    private WalletChangeEventListener walletChangeListener;
    private final Set<Script> selectedWatchedScripts = new HashSet<>();
    private final Map<String, View> watchedRows = new HashMap<>();
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
        maybeStartPendingAccountXpubRescan();
    });

    private Wallet getWallet() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null || !presenter.isWalletReady()) {
            return null;
        }
        // wallet() is acquired atomically with the WalletAppKit lifecycle check.
        // During a rescan/restart this simply returns null until the replacement kit is ready.
        return MainActivityPresenter.getActiveWallet();
    }
    private NetworkParameters getParameters() {
        return MainActivityPresenter.getActiveParameters();
    }

    private void show(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_watch_only_wallet);
        Toolbar toolbar = findViewById(R.id.toolbar_watch_only);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.watch_only_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        syncPresenter = MainActivityPresenter.getActivePresenter();
        if (syncPresenter != null) {
            syncPresenter.addSyncStateListener(syncStateListener);
        }
        watchAddressInput = findViewById(R.id.watchAddressInput);
        watchDateInput = findViewById(R.id.watchDateInput);
        addWatchAddressButton = findViewById(R.id.addWatchAddressButton);
        deleteWatchedButton = findViewById(R.id.deleteWatchedButton);
        watchedBalanceSummary = findViewById(R.id.watchedBalanceSummary);
        watchedAddressList = findViewById(R.id.watchedAddressList);
        watchedAddressEmpty = findViewById(R.id.watchedAddressEmpty);
        accountXpubInput = findViewById(R.id.accountXpubInput);
        accountXpubFormat = findViewById(R.id.accountXpubFormat);
        accountXpubCount = findViewById(R.id.accountXpubCount);
        accountXpubListEmpty = findViewById(R.id.accountXpubListEmpty);
        accountXpubList = findViewById(R.id.accountXpubList);
        importAccountXpubButton = findViewById(R.id.importAccountXpubButton);
        exportAccountXpubButton = findViewById(R.id.exportAccountXpubButton);
        accountXpubTransactionsPanel = findViewById(R.id.accountXpubTransactionsPanel);
        accountXpubTransactionsEmpty = findViewById(R.id.accountXpubTransactionsEmpty);
        accountXpubTransactionList = findViewById(R.id.accountXpubTransactionList);
        accountXpubInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                selectAccountXpubFormatForInput(text);
            }

            @Override
            public void afterTextChanged(Editable editable) { }
        });
        updateAccountXpubNetworkUi();
        importAccountXpubButton.setOnClickListener(v -> importAccountXpub());
        exportAccountXpubButton.setOnClickListener(v -> requestAccountXpubExport());
        updateAccountXpubCount();
        refreshAccountXpubList();
        Button scanWatchAddressQrButton = findViewById(R.id.scanWatchAddressQrButton);
        addWatchAddressButton.setOnClickListener(v -> addWatchAddress());
        scanWatchAddressQrButton.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions()
                    .setPrompt(getString(R.string.scan_watch_address_qr));
            barcodeLauncher.launch(options);
        });
        deleteWatchedButton.setOnClickListener(v -> deleteSelectedWatchedAddresses());
        attachWalletListener();
        refreshWatchedAddresses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (syncPresenter == null) {
            syncPresenter = MainActivityPresenter.getActivePresenter();
        }
        if (syncPresenter != null) {
            syncPresenter.addSyncStateListener(syncStateListener);
        }
        updateAccountXpubNetworkUi();
        attachWalletListener();
        updateAccountXpubCount();
        refreshAccountXpubList();
        refreshWatchedAddresses();
        maybeStartPendingAccountXpubRescan();
    }
    @Override
    protected void onPause() {
        if (syncPresenter != null) {
            syncPresenter.removeSyncStateListener(syncStateListener);
        }
        detachWalletListener();
        watchRefreshHandler.removeCallbacksAndMessages(null);
        watchRefreshQueued = false;
        super.onPause();
    }
    @Override
    protected void onDestroy() {
        if (syncPresenter != null) {
            syncPresenter.removeSyncStateListener(syncStateListener);
        }
        detachWalletListener();
        watchRefreshHandler.removeCallbacksAndMessages(null);
        watchRefreshQueued = false;
        super.onDestroy();
    }

    private void updateAccountXpubNetworkUi() {
        if (accountXpubFormat == null || accountXpubInput == null) {
            return;
        }
        BitcoinNetwork network = NetworkConfig.get(this);
        if (network == displayedAccountXpubNetwork) {
            return;
        }

        boolean isMainnet = network == BitcoinNetwork.MAINNET;
        int selectedFormat = displayedAccountXpubNetwork == null
                ? 2 : Math.max(0, accountXpubFormat.getSelectedItemPosition());
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                isMainnet ? R.array.account_xpub_formats_mainnet
                        : R.array.account_xpub_formats_signet,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        accountXpubFormat.setAdapter(adapter);
        accountXpubFormat.setSelection(Math.min(selectedFormat, 2));
        accountXpubInput.setHint(isMainnet
                ? R.string.account_xpub_input_hint_mainnet
                : R.string.account_xpub_input_hint_signet);
        displayedAccountXpubNetwork = network;
        selectAccountXpubFormatForInput(accountXpubInput.getText());
    }

    private void selectAccountXpubFormatForInput(CharSequence input) {
        if (input == null || accountXpubFormat == null || accountXpubFormat.getAdapter() == null) {
            return;
        }
        String key = input.toString().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        int formatIndex = -1;
        if (key.startsWith("xpub") || key.startsWith("tpub")) {
            formatIndex = 0;
        } else if (key.startsWith("ypub") || key.startsWith("upub")) {
            formatIndex = 1;
        } else if (key.startsWith("zpub") || key.startsWith("vpub")) {
            formatIndex = 2;
        }
        if (formatIndex >= 0 && accountXpubFormat.getSelectedItemPosition() != formatIndex) {
            accountXpubFormat.setSelection(formatIndex);
        }
    }

    private void updateAccountXpubCount() {
        if (accountXpubCount != null) {
            accountXpubCount.setText(getString(R.string.account_xpub_count,
                    AccountXpubStore.importedAccountCount(this)));
        }
    }

    private void refreshAccountXpubList() {
        if (accountXpubList == null) return;
        List<AccountXpubStore.AccountInfo> accounts = AccountXpubStore.importedAccounts(this);
        accountXpubList.removeAllViews();
        boolean empty = accounts.isEmpty();
        if (accountXpubListEmpty != null) {
            accountXpubListEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        for (AccountXpubStore.AccountInfo account : accounts) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_account_xpub, accountXpubList, false);
            TextView label = row.findViewById(R.id.accountXpubItemLabel);
            TextView key = row.findViewById(R.id.accountXpubItemKey);
            Button delete = row.findViewById(R.id.accountXpubDeleteButton);
            label.setText(getString(R.string.account_xpub_item_title_format,
                    account.type.toUpperCase(java.util.Locale.ROOT)));
            key.setText(account.preview);
            delete.setOnClickListener(v -> confirmDeleteAccountXpub(account));
            accountXpubList.addView(row);
        }
    }

    private void confirmDeleteAccountXpub(AccountXpubStore.AccountInfo account) {
        if (account == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.account_xpub_delete_title)
                .setMessage(getString(R.string.account_xpub_delete_message, account.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.account_xpub_delete, (dialog, which) ->
                        deleteAccountXpub(account))
                .show();
    }

    private void deleteAccountXpub(AccountXpubStore.AccountInfo account) {
        final Wallet wallet = getWallet();
        if (wallet == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        new Thread(() -> {
            try {
                AccountXpubStore.removeAccountKey(this, wallet, account.key);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) presenter.saveWalletNow();
                runOnUiThread(() -> {
                    // Clear a selected watch address if the deleted key owned it.
                    WalletSelection.findSelectedScript(this, wallet);
                    updateAccountXpubCount();
                    refreshAccountXpubList();
                    refreshWatchedAddresses();
                    show(R.string.account_xpub_deleted);
                });
            } catch (Exception error) {
                runOnUiThread(() -> show(R.string.account_xpub_delete_failed));
            }
        }, "wallet-account-xpub-delete").start();
    }

    private void importAccountXpub() {
        String encoded = accountXpubInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.account_xpub_invalid);
            return;
        }
        final Wallet wallet = getWallet();
        if (wallet == null || getParameters() == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        final Instant birthday;
        try {
            birthday = parseDate(watchDateInput.getText().toString().trim());
        } catch (Exception error) {
            show(R.string.invalid_wallet_birthday);
            return;
        }
        importAccountXpubButton.setEnabled(false);
        new Thread(() -> {
            try {
                int added = AccountXpubStore.importAccountKey(this, wallet, encoded,
                        birthday == null ? 1231006505L : birthday.getEpochSecond());
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) presenter.saveWalletNow();
                runOnUiThread(() -> {
                    importAccountXpubButton.setEnabled(true);
                    accountXpubInput.setText("");
                    updateAccountXpubCount();
                    refreshAccountXpubList();
                    refreshWatchedAddresses();
                    show(added < 0 ? R.string.account_xpub_duplicate : R.string.account_xpub_imported);
                    if (added >= 0) {
                        long oldestBirthday = AccountXpubStore.oldestCreationTime(this);
                        pendingAccountXpubRescanFrom = Instant.ofEpochSecond(
                                oldestBirthday > 0 ? oldestBirthday : 1231006505L);
                        maybeStartPendingAccountXpubRescan();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    importAccountXpubButton.setEnabled(true);
                    show(R.string.account_xpub_import_failed);
                });
            }
        }, "wallet-account-xpub-import").start();
    }

    private void requestAccountXpubExport() {
        final Wallet wallet = getWallet();
        if (wallet == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        if (!WalletSecurity.isEncrypted(wallet) || WalletSecurity.isSessionValid(wallet)) {
            exportAccountXpub(wallet, null);
            return;
        }
        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText password = passwordView.findViewById(R.id.dialogPasswordInput);
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_unlock_title)
                .setMessage(R.string.unlock_before_backup)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.account_xpub_export, (dialog, which) ->
                        exportAccountXpub(wallet, password.getText().toString()))
                .show();
    }

    private void exportAccountXpub(Wallet wallet, String password) {
        final AccountXpubStore.Format format = AccountXpubStore.formatAt(
                accountXpubFormat.getSelectedItemPosition());
        exportAccountXpubButton.setEnabled(false);
        new Thread(() -> {
            try {
                AesKey authorizationKey = WalletSecurity.getSessionKey();
                if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
                    authorizationKey = WalletSecurity.verifyPassword(wallet, password == null ? "" : password);
                    if (authorizationKey == null) {
                        runOnUiThread(() -> {
                            exportAccountXpubButton.setEnabled(true);
                            show(R.string.wrong_password);
                        });
                        return;
                    }
                }
                String exported = AccountXpubStore.exportAccountKey(
                        wallet, authorizationKey, NetworkConfig.get(this), format);
                runOnUiThread(() -> {
                    exportAccountXpubButton.setEnabled(true);
                    showExportedAccountXpub(exported);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    exportAccountXpubButton.setEnabled(true);
                    show(R.string.account_xpub_export_failed);
                });
            }
        }, "wallet-account-xpub-export").start();
    }

    private void showExportedAccountXpub(String encoded) {
        View exportView = getLayoutInflater().inflate(R.layout.dialog_account_xpub_export, null);
        TextView value = exportView.findViewById(R.id.accountXpubExportValue);
        value.setText(encoded);
        new AlertDialog.Builder(this)
                .setTitle(R.string.account_xpub_export_title)
                .setMessage(R.string.account_xpub_export_message)
                .setView(exportView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.account_xpub_copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.account_xpub_title), encoded));
                        show(R.string.account_xpub_copied);
                    }
                })
                .show();
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
                                        () -> rescanWatchedAddresses(finalScanFrom),
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
                            LocalizedErrors.resolve(this, error)),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-watch-address").start();
    }
    private void maybeStartPendingAccountXpubRescan() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        Instant scanFrom = pendingAccountXpubRescanFrom;
        if (scanFrom == null || presenter == null || presenter.isSyncing()
                || accountXpubRescanScheduled || isFinishing() || isDestroyed()) return;
        accountXpubRescanScheduled = true;
        pendingAccountXpubRescanFrom = null;
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            accountXpubRescanScheduled = false;
            if (!isFinishing() && !isDestroyed()) rescanWatchedAddresses(scanFrom);
        }, 1000L);
    }

    private void rescanWatchedAddresses(final Instant scanFrom) {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        presenter.rescanWatchedAddresses(scanFrom, error -> runOnUiThread(() -> {
            refreshWatchedAddresses();
            if (error != null) {
                Toast.makeText(this, getString(
                        R.string.watch_rescan_failed,
                        LocalizedErrors.resolve(this, error)),
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
                                    LocalizedErrors.resolve(this, error)),
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

        List<Script> scripts = new ArrayList<>();
        java.util.Set<Script> requestNestedScripts =
                WalletSelection.getRequestNestedScripts(this, wallet);
        for (Script script : wallet.getWatchedScripts()) {
            if (!requestNestedScripts.contains(script)) {
                scripts.add(script);
            }
        }
        Set<Script> accountXpubScripts;
        try {
            accountXpubScripts = AccountXpubStore.importedAccountScripts(this, wallet);
        } catch (Exception ignored) {
            accountXpubScripts = java.util.Collections.emptySet();
        }
        // Keep account-derived receive/change scripts in their own account panel
        // instead of flooding the manually watched-address list with 200+ rows.
        scripts.removeAll(accountXpubScripts);
        selectedWatchedScripts.retainAll(scripts);
        refreshAccountXpubTransactions(wallet, accountXpubScripts);

        if (scripts.isEmpty()) {
            watchedRows.clear();
            if (watchedAddressList.getChildCount() != 0) {
                watchedAddressList.removeAllViews();
            }
            if (watchedAddressEmpty != null) {
                watchedAddressEmpty.setVisibility(View.VISIBLE);
            }
            TextViewUtils.setTextIfChanged(watchedBalanceSummary, getWatchSummary(
                    MoneyFormatter.format(this, org.bitcoinj.base.Coin.ZERO)));
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, watchedAddressList, false);
            empty.setText(R.string.watch_address_empty);
            watchedAddressList.addView(empty);
            updateDeleteButton();
            return;
        }

        if (watchedAddressEmpty != null) {
            watchedAddressEmpty.setVisibility(View.GONE);
        }

        for (int i = watchedAddressList.getChildCount() - 1; i >= 0; i--) {
            View child = watchedAddressList.getChildAt(i);
            if (!(child.getTag() instanceof String)) {
                watchedAddressList.removeViewAt(i);
            }
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
        Set<String> currentKeys = new HashSet<>();
        for (int index = 0; index < scripts.size(); index++) {
            Script script = scripts.get(index);
            WatchBalance balance = balances.get(script);
            watchedTotal += balance.total();

            String key = script.toString();
            currentKeys.add(key);
            View row = watchedRows.get(key);
            if (row == null) {
                row = LayoutInflater.from(this)
                        .inflate(R.layout.item_watch_address, watchedAddressList, false);
                bindWatchedRow(row, script, false);
                watchedRows.put(key, row);
            }

            updateWatchedRow(row, script, parameters, balance);
            row.findViewById(R.id.watchAddressCheckBox)
                    .setEnabled(true);

            // Existing rows stay in place while sync advances. Only add/reorder when
            // the actual watched-script set/order changed, so a changing scan height
            // does not recreate every address card and its ellipsis/selection state.
            if (index >= watchedAddressList.getChildCount()
                    || watchedAddressList.getChildAt(index) != row) {
                int currentIndex = watchedAddressList.indexOfChild(row);
                if (currentIndex >= 0) {
                    watchedAddressList.removeViewAt(currentIndex);
                }
                watchedAddressList.addView(row, Math.min(index, watchedAddressList.getChildCount()));
            }
        }

        for (int i = watchedAddressList.getChildCount() - 1; i >= 0; i--) {
            View child = watchedAddressList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && !currentKeys.contains((String) tag)) {
                watchedAddressList.removeViewAt(i);
            }
        }
        watchedRows.keySet().retainAll(currentKeys);

        TextViewUtils.setTextIfChanged(watchedBalanceSummary, getWatchSummary(
                MoneyFormatter.format(this, org.bitcoinj.base.Coin.valueOf(watchedTotal))));
        updateDeleteButton();
    }

    private void refreshAccountXpubTransactions(Wallet wallet, Set<Script> accountScripts) {
        if (accountXpubTransactionsPanel == null || accountXpubTransactionList == null) return;
        boolean hasAccounts = AccountXpubStore.importedAccountCount(this) > 0;
        accountXpubTransactionsPanel.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);
        if (!hasAccounts) {
            lastAccountXpubHistoryKey = null;
            accountXpubTransactionList.removeAllViews();
            return;
        }

        long trackedBalance = 0L;
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (output.isAvailableForSpending()
                    && accountScripts.contains(output.getScriptPubKey())) {
                trackedBalance += output.getValue().value;
            }
        }
        TextView accountBalance = findViewById(R.id.accountXpubBalance);
        accountBalance.setText(getString(R.string.account_xpub_balance,
                MoneyFormatter.format(this, org.bitcoinj.base.Coin.valueOf(trackedBalance))));
        List<TransactionItem> items = TransactionMapper.mapForAccountXpubs(
                this, wallet, accountScripts);
        StringBuilder key = new StringBuilder();
        for (TransactionItem item : items) {
            key.append(item.txid).append(':')
                    .append(item.rawAmount == null ? item.amount : item.rawAmount.value)
                    .append(':').append(item.state).append(';');
        }
        String historyKey = key.toString();
        if (historyKey.equals(lastAccountXpubHistoryKey)) return;
        lastAccountXpubHistoryKey = historyKey;

        accountXpubTransactionList.removeAllViews();
        accountXpubTransactionsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (TransactionItem item : items) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_transaction, accountXpubTransactionList, false);
            ((TextView) row.findViewById(R.id.tvTxType)).setText(item.type);
            String formattedAmount = item.rawAmount == null ? item.amount
                    : (item.rawAmount.isPositive() ? "+" : "")
                    + MoneyFormatter.format(this, item.rawAmount);
            ((TextView) row.findViewById(R.id.tvTxAmount)).setText(formattedAmount);
            ((TextView) row.findViewById(R.id.tvTxTime)).setText(item.time);
            ((TextView) row.findViewById(R.id.tvTxConf)).setText(item.confirmations);
            ((TextView) row.findViewById(R.id.tvTxId)).setText(item.txid);
            TextView counterparty = row.findViewById(R.id.tvTxCounterparty);
            counterparty.setText(item.counterparty == null ? "" : item.counterparty);
            counterparty.setVisibility(TextUtils.isEmpty(item.counterparty) ? View.GONE : View.VISIBLE);
            ((TextView) row.findViewById(R.id.tvTxPeers)).setText(item.peers);
            ((TextView) row.findViewById(R.id.tvTxState)).setText(item.state);
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, TransactionDetailActivity.class);
                intent.putExtra(TransactionDetailActivity.EXTRA_TXID, item.txid);
                startActivity(intent);
            });
            accountXpubTransactionList.addView(row);
        }
    }

    private void bindWatchedRow(View row, Script script, boolean accountXpubScript) {
        row.setTag(script.toString());
        CheckBox checkBox = row.findViewById(R.id.watchAddressCheckBox);
        checkBox.setEnabled(!accountXpubScript);
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                selectedWatchedScripts.add(script);
            } else {
                selectedWatchedScripts.remove(script);
            }
            updateDeleteButton();
        });

        TextView addressView = row.findViewById(R.id.watchAddressValue);
        TextViewUtils.configureSelectableMiddleEllipsis(addressView);
    }

    private void updateWatchedRow(View row, Script script, NetworkParameters parameters,
                                  WatchBalance balance) {
        CheckBox checkBox = row.findViewById(R.id.watchAddressCheckBox);
        TextView typeView = row.findViewById(R.id.watchAddressType);
        TextView addressView = row.findViewById(R.id.watchAddressValue);
        TextView confirmedView = row.findViewById(R.id.watchAddressConfirmed);
        TextView pendingView = row.findViewById(R.id.watchAddressPending);
        TextView totalView = row.findViewById(R.id.watchAddressTotal);

        String type = script.getScriptType() == null
                ? getString(R.string.watch_script_type_unknown)
                : script.getScriptType().name();
        String address;
        try {
            address = script.getToAddress(parameters).toString();
        } catch (Exception error) {
            address = script.toString();
        }

        TextViewUtils.setTextIfChanged(typeView, type);
        TextViewUtils.setTextIfChanged(addressView, address);
        TextViewUtils.setTextIfChanged(confirmedView, getString(
                R.string.watch_confirmed_value,
                MoneyFormatter.format(this, org.bitcoinj.base.Coin.valueOf(balance.confirmed))));
        TextViewUtils.setTextIfChanged(pendingView, getString(
                R.string.watch_pending_value,
                MoneyFormatter.format(this, org.bitcoinj.base.Coin.valueOf(balance.pending))));
        TextViewUtils.setTextIfChanged(totalView,
                MoneyFormatter.format(this, org.bitcoinj.base.Coin.valueOf(balance.total())));

        boolean selected = selectedWatchedScripts.contains(script);
        if (checkBox.isChecked() != selected) {
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(selected);
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedWatchedScripts.add(script);
                } else {
                    selectedWatchedScripts.remove(script);
                }
                updateDeleteButton();
            });
        }
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
    private static final class WatchBalance {
        private long confirmed;
        private long pending;

        private long total() {
            return confirmed + pending;
        }
    }
}
