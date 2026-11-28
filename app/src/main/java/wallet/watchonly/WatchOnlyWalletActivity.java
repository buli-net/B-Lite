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
import android.view.ViewGroup;
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
    private TextView accountXpubNetwork;
    private TextView accountXpubListEmpty;
    private LinearLayout accountXpubList;
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
    private final Map<String, TextView> watchedGroupHeaders = new java.util.LinkedHashMap<>();
    private MainActivityPresenter syncPresenter;
    private final android.os.Handler watchRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watchRefreshQueued;
    private static final long WATCH_REFRESH_DEBOUNCE_MS = 500L;
    private final Runnable syncStateListener = () -> runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        attachWalletListener();
        updateActiveNetworkLabel();
        updateAccountXpubCount();
        refreshAccountXpubList();
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

    private void show(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        accountXpubNetwork = findViewById(R.id.accountXpubNetwork);
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
        configureAccountXpubFormats();
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
        updateActiveNetworkLabel();
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
        attachWalletListener();
        updateActiveNetworkLabel();
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

    private void updateActiveNetworkLabel() {
        Wallet wallet = getWallet();
        if (wallet == null) return;
        org.bitcoinj.base.BitcoinNetwork network = AccountXpubStore.networkForWallet(wallet);
        String display = NetworkConfig.displayName(this, network);
        if (accountXpubNetwork != null) {
            accountXpubNetwork.setText(getString(R.string.account_xpub_active_network, display));
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.watch_only_title_network, display));
        }
    }

    /** One format selector for both supported networks; network differences are encoding only. */
    private void configureAccountXpubFormats() {
        if (accountXpubFormat == null || accountXpubInput == null) return;
        int selectedFormat = Math.max(0, accountXpubFormat.getSelectedItemPosition());
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.account_xpub_formats,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        accountXpubFormat.setAdapter(adapter);
        accountXpubFormat.setSelection(Math.min(selectedFormat, 2));
        accountXpubInput.setHint(R.string.account_xpub_input_hint);
        selectAccountXpubFormatForInput(accountXpubInput.getText());
    }

    private void selectAccountXpubFormatForInput(CharSequence input) {
        if (input == null || accountXpubFormat == null || accountXpubFormat.getAdapter() == null) {
            return;
        }
        String key = input.toString().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        // xpub/tpub are standard BIP32 prefixes and do not tell us whether the
        // account uses Legacy, Nested SegWit or Native SegWit. Preserve the user's
        // spinner choice for these ambiguous prefixes. SLIP-132 prefixes are explicit.
        int formatIndex = -1;
        if (key.startsWith("ypub") || key.startsWith("upub")) {
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
            Wallet wallet = getWallet();
            accountXpubCount.setText(getString(R.string.account_xpub_count,
                    AccountXpubStore.importedAccountCount(this, wallet)));
        }
    }

    private void refreshAccountXpubList() {
        if (accountXpubList == null) return;
        Wallet wallet = getWallet();
        List<AccountXpubStore.AccountInfo> accounts = AccountXpubStore.importedAccounts(this, wallet);
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
            Button addressesButton = row.findViewById(R.id.accountXpubAddressesButton);
            LinearLayout addressesContainer = row.findViewById(
                    R.id.accountXpubAddressesContainer);
            label.setText(getString(R.string.account_xpub_item_title_format,
                    account.network.name() + " · " + account.type.toUpperCase(java.util.Locale.ROOT)));
            key.setText(account.preview);
            delete.setOnClickListener(v -> confirmDeleteAccountXpub(account));
            addressesButton.setOnClickListener(v -> toggleAccountXpubAddresses(
                    account, addressesButton, addressesContainer));
            accountXpubList.addView(row);
        }
    }

    /** Expands one key into its own receive (m/0) and change (m/1) address groups. */
    private void toggleAccountXpubAddresses(AccountXpubStore.AccountInfo account,
                                            Button toggle, LinearLayout container) {
        if (account == null || toggle == null || container == null) return;
        if (container.getVisibility() == View.VISIBLE) {
            container.setVisibility(View.GONE);
            toggle.setText(R.string.account_xpub_show_addresses);
            return;
        }

        container.removeAllViews();
        try {
            Wallet wallet = getWallet();
            if (wallet == null) {
                show(R.string.wallet_not_ready);
                return;
            }
            List<AccountXpubStore.AccountAddressInfo> addresses =
                    AccountXpubStore.importedAccountAddresses(this, wallet, account.key);
            for (int branch = 0; branch <= 1; branch++) {
                final int branchNumber = branch;
                int count = 0;
                for (AccountXpubStore.AccountAddressInfo info : addresses) {
                    if (info.branch == branchNumber) count++;
                }
                TextView heading = new TextView(this);
                heading.setText(getString(branch == 0
                        ? R.string.account_xpub_receive_branch
                        : R.string.account_xpub_change_branch, count));
                heading.setTextSize(12);
                heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
                heading.setPadding(0, dp(8), 0, dp(4));
                container.addView(heading);

                for (AccountXpubStore.AccountAddressInfo info : addresses) {
                    if (info.branch != branchNumber) continue;
                    TextView addressRow = new TextView(this);
                    addressRow.setText(info.derivationPath() + "  " + info.address);
                    addressRow.setTextIsSelectable(true);
                    addressRow.setTextSize(11);
                    addressRow.setTypeface(android.graphics.Typeface.MONOSPACE);
                    addressRow.setPadding(dp(6), dp(5), dp(6), dp(5));
                    container.addView(addressRow);
                }
            }
            container.setVisibility(View.VISIBLE);
            toggle.setText(R.string.account_xpub_hide_addresses);
        } catch (Exception error) {
            container.removeAllViews();
            show(R.string.account_xpub_addresses_failed);
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
        if (wallet == null || wallet.getParams() == null) {
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
        final AccountXpubStore.Format selectedFormat = AccountXpubStore.formatAt(
                accountXpubFormat.getSelectedItemPosition());
        importAccountXpubButton.setEnabled(false);
        new Thread(() -> {
            try {
                int added = AccountXpubStore.importAccountKey(this, wallet, encoded,
                        birthday == null ? 1231006505L : birthday.getEpochSecond(),
                        selectedFormat);
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
                        long oldestBirthday = AccountXpubStore.oldestCreationTime(this, wallet);
                        pendingAccountXpubRescanFrom = Instant.ofEpochSecond(
                                oldestBirthday > 0 ? oldestBirthday : 1231006505L);
                        maybeStartPendingAccountXpubRescan();
                    }
                });
            } catch (AccountXpubStore.NetworkMismatchException mismatch) {
                runOnUiThread(() -> {
                    importAccountXpubButton.setEnabled(true);
                    show(getString(R.string.account_xpub_wrong_network,
                            NetworkConfig.displayName(this, mismatch.keyNetwork),
                            NetworkConfig.displayName(this, mismatch.walletNetwork)));
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
                        wallet, authorizationKey, AccountXpubStore.networkForWallet(wallet), format);
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
        if (wallet == null || wallet.getParams() == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        NetworkParameters parameters = wallet.getParams();

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

        // A network switch replaces the Wallet object. Do not carry selected rows,
        // account headers, or balances from the previous network into the new one.
        detachWalletListener();
        selectedWatchedScripts.clear();
        watchedRows.clear();
        watchedGroupHeaders.clear();
        if (watchedAddressList != null) watchedAddressList.removeAllViews();
        updateDeleteButton();
        lastAccountXpubHistoryKey = null;
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

        org.bitcoinj.base.BitcoinNetwork walletNetwork = AccountXpubStore.networkForWallet(wallet);
        String networkLabel = NetworkConfig.displayName(this, walletNetwork);
        List<Script> scripts = new ArrayList<>();
        java.util.Set<Script> requestNestedScripts =
                WalletSelection.getRequestNestedScripts(this, wallet);
        for (Script script : wallet.getWatchedScripts()) {
            if (!requestNestedScripts.contains(script)) {
                scripts.add(script);
            }
        }

        // Resolve ownership once, from the same wallet/network used to render addresses.
        // Mainnet and Signet go through the identical derivation path; only their key
        // version family and wallet address parameters differ.
        Map<Script, String> accountSources;
        try {
            accountSources = AccountXpubStore.accountLabelsByScript(this, wallet);
        } catch (Exception ignored) {
            accountSources = java.util.Collections.emptyMap();
        }
        Set<Script> accountXpubScripts = new HashSet<>(accountSources.keySet());
        selectedWatchedScripts.retainAll(scripts);
        selectedWatchedScripts.removeAll(accountXpubScripts);
        refreshAccountXpubTransactions(wallet, accountXpubScripts);

        if (scripts.isEmpty()) {
            watchedRows.clear();
            watchedGroupHeaders.clear();
            watchedAddressList.removeAllViews();
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

        NetworkParameters parameters = wallet.getParams();
        // Use bitcoinj's dedicated watched-output API, scoped to this wallet only.
        List<TransactionOutput> outputs = wallet.getWatchedOutputs(false);
        Map<Script, WatchBalance> balances = new HashMap<>();
        for (Script script : scripts) {
            balances.put(script, new WatchBalance());
        }
        for (TransactionOutput output : outputs) {
            if (!output.isAvailableForSpending()) continue;
            Script outputScript = output.getScriptPubKey();
            WatchBalance balance = balances.get(outputScript);
            if (balance == null) continue;
            long value = output.getValue().value;
            if (output.getParentTransactionDepthInBlocks() > 0) {
                balance.confirmed += value;
            } else {
                balance.pending += value;
            }
        }

        // Keep each imported account in its own section. Addresses added manually,
        // or left over from builds where their source key was not saved, are shown
        // under a separate network-labelled section and are never attributed blindly.
        Map<String, List<Script>> groupedScripts = new java.util.LinkedHashMap<>();
        Map<Script, String> sourceByScript = new HashMap<>();
        for (Script script : scripts) {
            String ownership = accountSources.get(script);
            String group;
            String source;
            if (ownership == null || ownership.trim().isEmpty()) {
                group = getString(R.string.watch_address_unlinked_group, networkLabel);
                source = getString(R.string.watch_address_source_unlinked, networkLabel);
            } else {
                int pathStart = ownership.lastIndexOf(" · m/");
                group = pathStart > 0 ? ownership.substring(0, pathStart) : ownership;
                source = pathStart > 0
                        ? getString(R.string.watch_address_derivation_path,
                                ownership.substring(pathStart + 3))
                        : ownership;
            }
            groupedScripts.computeIfAbsent(group, ignored -> new ArrayList<>()).add(script);
            sourceByScript.put(script, source);
        }

        long watchedTotal = 0L;
        Set<String> currentKeys = new HashSet<>();
        Set<String> currentGroups = new HashSet<>(groupedScripts.keySet());

        // Reuse existing row views but rebuild the ordering so section headers are
        // always immediately above the addresses owned by that account key.
        watchedAddressList.removeAllViews();
        for (Map.Entry<String, List<Script>> group : groupedScripts.entrySet()) {
            String groupKey = group.getKey();
            TextView header = watchedGroupHeaders.get(groupKey);
            if (header == null) {
                header = new TextView(this);
                header.setTextSize(12);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                header.setPadding(dp(4), dp(12), dp(4), dp(6));
                watchedGroupHeaders.put(groupKey, header);
            }
            TextViewUtils.setTextIfChanged(header, groupKey);
            watchedAddressList.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (Script script : group.getValue()) {
                WatchBalance balance = balances.get(script);
                watchedTotal += balance == null ? 0L : balance.total();
                String key = script.toString();
                currentKeys.add(key);
                boolean accountXpubScript = accountXpubScripts.contains(script);
                View row = watchedRows.get(key);
                if (row == null) {
                    row = LayoutInflater.from(this)
                            .inflate(R.layout.item_watch_address, watchedAddressList, false);
                    bindWatchedRow(row, script, accountXpubScript);
                    watchedRows.put(key, row);
                } else {
                    // Existing rows may move between the unlinked and account groups
                    // after an xpub is re-imported. Rebind the checkbox policy as well.
                    bindWatchedRow(row, script, accountXpubScript);
                }
                updateWatchedRow(row, script, parameters,
                        balance == null ? new WatchBalance() : balance,
                        sourceByScript.get(script), walletNetwork);
                row.findViewById(R.id.watchAddressCheckBox).setEnabled(!accountXpubScript);
                watchedAddressList.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        for (String staleKey : new ArrayList<>(watchedRows.keySet())) {
            if (!currentKeys.contains(staleKey)) watchedRows.remove(staleKey);
        }
        for (String staleGroup : new ArrayList<>(watchedGroupHeaders.keySet())) {
            if (!currentGroups.contains(staleGroup)) watchedGroupHeaders.remove(staleGroup);
        }

        TextViewUtils.setTextIfChanged(watchedBalanceSummary, getWatchSummary(
                MoneyFormatter.format(this, org.bitcoinj.base.Coin.valueOf(watchedTotal))));
        updateDeleteButton();
    }

    private void refreshAccountXpubTransactions(Wallet wallet, Set<Script> accountScripts) {
        if (accountXpubTransactionsPanel == null || accountXpubTransactionList == null) return;
        boolean hasAccounts = AccountXpubStore.importedAccountCount(this, wallet) > 0;
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
        key.append(AccountXpubStore.networkForWallet(wallet).name()).append('|');
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
        checkBox.setOnCheckedChangeListener(null);
        checkBox.setEnabled(!accountXpubScript);
        boolean selected = !accountXpubScript && selectedWatchedScripts.contains(script);
        checkBox.setChecked(selected);
        if (accountXpubScript) selectedWatchedScripts.remove(script);
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !accountXpubScript) {
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
                                  WatchBalance balance, String sourceLabel,
                                  org.bitcoinj.base.BitcoinNetwork walletNetwork) {
        CheckBox checkBox = row.findViewById(R.id.watchAddressCheckBox);
        TextView typeView = row.findViewById(R.id.watchAddressType);
        TextView addressView = row.findViewById(R.id.watchAddressValue);
        TextView confirmedView = row.findViewById(R.id.watchAddressConfirmed);
        TextView pendingView = row.findViewById(R.id.watchAddressPending);
        TextView totalView = row.findViewById(R.id.watchAddressTotal);
        TextView sourceView = row.findViewById(R.id.watchAddressSource);

        String type = script.getScriptType() == null
                ? getString(R.string.watch_script_type_unknown)
                : script.getScriptType().name();
        String address;
        try {
            address = script.getToAddress(parameters).toString();
        } catch (Exception error) {
            address = script.toString();
        }

        TextViewUtils.setTextIfChanged(typeView, getString(R.string.watch_address_type_network,
                NetworkConfig.displayName(this, walletNetwork), type));
        TextViewUtils.setTextIfChanged(sourceView, sourceLabel);
        sourceView.setVisibility(View.VISIBLE);
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
