package wallet.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.List;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.qr.QrCodeGenerator;
import wallet.qr.ReceiveQrDialog;
import wallet.transaction.TransactionAdapter;
import wallet.storage.WalletFileMigration;

/** Main wallet screen. UI structure lives in XML; this class handles wallet events. */
public class MainActivity extends AppCompatActivity
        implements MainActivityContract.MainActivityView {

    private static final String STATE_TRANSACTION_FILTER = "transaction_filter";
    private static final String STATE_TRANSACTION_LIMIT = "transaction_limit";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_SENT = 1;
    private static final int FILTER_RECEIVED = 2;
    private static final int ADDRESS_BOOK_REQUEST = 9201;

    private MainActivityContract.MainActivityPresenter presenter;

    private FrameLayout startupSplash;
    private Toolbar toolbar;
    private SwipeRefreshLayout swipeRefresh;
    private TextView balanceText;
    private TextView availableBalanceText;
    private TextView pendingBalanceText;
    private TextView addressText;
    private ImageView copyAddress;
    private ImageView receiveQr;
    private ImageButton walletSelectorButton;
    private TextView walletTypeText;

    private final List<TransactionItem> allTransactions = new ArrayList<>();
    private final List<TransactionItem> filteredTransactions = new ArrayList<>();

    private TransactionAdapter transactionAdapter;
    private TextView transactionsEmpty;
    private TextView transactionsCount;
    private ImageButton transactionsExpand;
    private android.widget.Button transactionsExport;
    private RadioGroup transactionFilters;
    private int transactionFilter = FILTER_ALL;
    /** -1 means all matching transactions. */
    private int transactionLimit = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        if (state != null) {
            transactionFilter = state.getInt(STATE_TRANSACTION_FILTER, FILTER_ALL);
            transactionLimit = state.getInt(STATE_TRANSACTION_LIMIT, -1);
        }

        bindViews();
        setupToolbar();
        setupTransactionList();
        setupTransactionFilters();
        setupActions();
        initPresenter();
        showStartupSplash();
    }

    private void bindViews() {
        startupSplash = findViewById(R.id.startupSplash);
        toolbar = findViewById(R.id.toolbar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        balanceText = findViewById(R.id.balanceText);
        availableBalanceText = findViewById(R.id.availableBalanceText);
        pendingBalanceText = findViewById(R.id.pendingBalanceText);
        addressText = findViewById(R.id.addressText);
        copyAddress = findViewById(R.id.copyAddress);
        receiveQr = findViewById(R.id.receiveQr);
        walletSelectorButton = findViewById(R.id.walletSelectorButton);
        walletTypeText = findViewById(R.id.walletTypeText);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_title);
        }
    }

    private void setupTransactionList() {
        RecyclerView list = findViewById(R.id.transactionsList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setNestedScrollingEnabled(false);
        transactionAdapter = new TransactionAdapter(filteredTransactions);
        list.setAdapter(transactionAdapter);
        transactionsEmpty = findViewById(R.id.transactionsEmpty);
        transactionsCount = findViewById(R.id.transactionsCount);
        transactionsExpand = findViewById(R.id.transactionsExpand);
        transactionsExport = findViewById(R.id.transactionsExport);
    }

    private void setupTransactionFilters() {
        transactionFilters = findViewById(R.id.transactionFilters);
        transactionFilters.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.filterSent) {
                selectTransactionFilter(FILTER_SENT);
            } else if (checkedId == R.id.filterReceived) {
                selectTransactionFilter(FILTER_RECEIVED);
            } else {
                selectTransactionFilter(FILTER_ALL);
            }
        });
        updateTransactionFilter();
        updateTransactionLimitLabel();
        transactionsExpand.setOnClickListener(v -> showTransactionLimitDialog());
        transactionsExport.setOnClickListener(v -> exportTransactions());
    }

    private void selectTransactionFilter(int selectedFilter) {
        transactionFilter = selectedFilter;
        applyTransactionFilter();
    }

    private void updateTransactionFilter() {
        int checkedId;
        if (transactionFilter == FILTER_SENT) {
            checkedId = R.id.filterSent;
        } else if (transactionFilter == FILTER_RECEIVED) {
            checkedId = R.id.filterReceived;
        } else {
            checkedId = R.id.filterAll;
        }
        transactionFilters.check(checkedId);
    }

    private void applyTransactionFilter() {
        filteredTransactions.clear();
        String sent = getString(R.string.transaction_sent);
        String received = getString(R.string.transaction_received);

        int sentCount = 0;
        int receivedCount = 0;
        int otherCount = 0;
        for (TransactionItem item : allTransactions) {
            boolean include = transactionFilter == FILTER_ALL
                    || (transactionFilter == FILTER_SENT && sent.equals(item.type))
                    || (transactionFilter == FILTER_RECEIVED && received.equals(item.type));
            if (!include) {
                continue;
            }

            if (transactionLimit >= 0) {
                if (sent.equals(item.type)) {
                    if (sentCount >= transactionLimit) continue;
                    sentCount++;
                } else if (received.equals(item.type)) {
                    if (receivedCount >= transactionLimit) continue;
                    receivedCount++;
                } else {
                    if (transactionFilter == FILTER_ALL && otherCount >= transactionLimit) continue;
                    otherCount++;
                }
            }
            filteredTransactions.add(item);
        }

        transactionAdapter.notifyDataSetChanged();
        transactionsEmpty.setVisibility(
                filteredTransactions.isEmpty() ? View.VISIBLE : View.GONE);
        updateTransactionLimitLabel();
    }

    private void updateTransactionLimitLabel() {
        if (transactionsCount == null) {
            return;
        }
        String filterLabel;
        if (transactionFilter == FILTER_SENT) {
            filterLabel = getString(R.string.filter_sent);
        } else if (transactionFilter == FILTER_RECEIVED) {
            filterLabel = getString(R.string.filter_received);
        } else {
            filterLabel = getString(R.string.filter_all);
        }
        String countLabel = transactionLimit < 0
                ? getString(R.string.transaction_limit_all)
                : getString(R.string.transaction_limit_per_type, transactionLimit);
        transactionsCount.setText(getString(
                R.string.transactions_summary,
                filterLabel,
                countLabel,
                filteredTransactions.size(),
                allTransactions.size()));
    }

    private void showTransactionLimitDialog() {
        final String[] labels = {
                getString(R.string.transaction_limit_all),
                getString(R.string.transaction_limit_10),
                getString(R.string.transaction_limit_20),
                getString(R.string.transaction_limit_30),
                getString(R.string.transaction_limit_50),
                getString(R.string.transaction_limit_100)
        };
        final int[] values = {-1, 10, 20, 30, 50, 100};
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == transactionLimit) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.transaction_limit_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    transactionLimit = values[which];
                    applyTransactionFilter();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setupActions() {

        swipeRefresh.setOnRefreshListener(() -> {
            if (presenter != null) {
                presenter.refresh();
            }
        });

        copyAddress.setOnClickListener(v -> copyAddress());
        addressText.setOnClickListener(v -> showAddressTools());
        receiveQr.setOnClickListener(v -> showReceiveQr());
        walletSelectorButton.setOnClickListener(v -> showWalletSelector());
    }

    private void initPresenter() {
        WalletFileMigration.migrate(this, Constants.WALLET_NAME);

        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active != null) {
            presenter = active;
            presenter.attachView(this);
        } else {
            presenter = new MainActivityPresenter(this, getFilesDir());
        }

        BitcoinSyncService.start(this);
        presenter.subscribe();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuScan:
                openScanner();
                return true;
            case R.id.menuSend:
                startActivity(new Intent(this, wallet.send.SendActivity.class));
                return true;
            case R.id.menuSecurity:
                startActivity(new Intent(this, wallet.security.SecurityActivity.class));
                return true;
            case R.id.menuAddressBook:
                startActivityForResult(
                        new Intent(this, wallet.contacts.AddressBookActivity.class),
                        ADDRESS_BOOK_REQUEST);
                return true;
            case R.id.menuImportWif:
                startActivity(new Intent(this, wallet.tools.WalletImportWifActivity.class));
                return true;
            case R.id.menuWatchOnlyWallet:
                startActivity(new Intent(this, wallet.tools.WatchOnlyWalletActivity.class));
                return true;
            case R.id.menuWalletUtility:
                startActivity(new Intent(this, wallet.tools.WalletUtilityActivity.class));
                return true;
            case R.id.menuAbout:
                startActivity(new Intent(this, wallet.about.AboutActivity.class));
                return true;
            case R.id.menuSync:
                startActivity(new Intent(this, wallet.main.SyncActivity.class));
                return true;
            case R.id.menuBackupRecovery:
                startActivity(new Intent(this, wallet.backup.BackupRecoveryActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void openScanner() {
        new IntentIntegrator(this)
                .setPrompt(getString(R.string.scan_bitcoin_address))
                .initiateScan();
    }

    private void copyAddress() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.bitcoin_address_clip_label), address));
        showToastMessage(getString(R.string.address_copied));
    }

    private void exportTransactions() {
        if (allTransactions.isEmpty()) {
            showToastMessage(getString(R.string.no_transactions_to_export));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "bitcoin-transactions.csv");
        startActivityForResult(intent, 9101);
    }

    private void showAddressTools() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.address_tools_title)
                .setMessage(getString(R.string.address_tools_message, address,
                        walletTypeText.getText().toString()))
                .setPositiveButton(R.string.copy_address, (dialog, which) -> copyAddress())
                .setNeutralButton(R.string.receive_qr_title, (dialog, which) -> showReceiveQr())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showWalletSelector() {
        final org.bitcoinj.kits.WalletAppKit kit =
                MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            showToastMessage(getString(R.string.wallet_not_ready));
            return;
        }

        try {
            org.bitcoinj.wallet.Wallet wallet = kit.wallet();
            java.util.List<String> addresses = new java.util.ArrayList<>();
            addresses.add(null);

            for (org.bitcoinj.script.Script script : wallet.getWatchedScripts()) {
                try {
                    addresses.add(script.getToAddress(wallet.getParams()).toString());
                } catch (Exception ignored) {
                    // Ignore watched scripts that cannot be rendered as an address.
                }
            }

            String selected = WalletSelection.getSelectedWatchAddress(this);
            int checked = 0;
            if (selected != null) {
                for (int i = 1; i < addresses.size(); i++) {
                    if (selected.equals(addresses.get(i))) {
                        checked = i;
                        break;
                    }
                }
            }

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            int horizontal = dp(8);
            container.setPadding(horizontal, 0, horizontal, dp(4));

            final AlertDialog[] dialogHolder = new AlertDialog[1];

            for (int i = 0; i < addresses.size(); i++) {
                final int position = i;
                final String address = addresses.get(i);

                View row = getLayoutInflater().inflate(
                        R.layout.item_wallet_selector, container, false);
                android.widget.RadioButton radio =
                        row.findViewById(R.id.walletSelectorRadio);
                TextView type = row.findViewById(R.id.walletSelectorType);
                TextView addressView = row.findViewById(R.id.walletSelectorAddress);

                radio.setChecked(position == checked);
                if (address == null) {
                    type.setText(R.string.wallet_type_main);
                    addressView.setText(wallet.currentReceiveAddress().toString());
                } else {
                    type.setText(R.string.wallet_type_watch);
                    addressView.setText(address);
                }

                row.setOnClickListener(v -> {
                    if (address == null) {
                        WalletSelection.selectMain(this);
                    } else {
                        WalletSelection.selectWatchAddress(this, address);
                    }
                    dialogHolder[0].dismiss();
                    if (presenter != null) {
                        presenter.refresh();
                    }
                });

                container.addView(row);

                if (i < addresses.size() - 1) {
                    View divider = new View(this);
                    divider.setBackgroundResource(R.drawable.bg_divider);
                    LinearLayout.LayoutParams dividerParams =
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(i == 0 ? 2 : 1));
                    dividerParams.setMargins(dp(12), dp(4), dp(12), dp(4));
                    container.addView(divider, dividerParams);
                }
            }

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.wallet_selector_title)
                    .setView(container)
                    .create();
            dialogHolder[0] = dialog;
            dialog.show();
        } catch (Exception error) {
            showToastMessage(getString(
                    R.string.wallet_selector_failed,
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage()));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showStartupSplash() {
        if (startupSplash == null) {
            return;
        }
        startupSplash.setVisibility(View.VISIBLE);
        startupSplash.postDelayed(() -> startupSplash.setVisibility(View.GONE), 900L);
    }

    private void showReceiveQr() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        ReceiveQrDialog.show(this, address);
    }

    @Override
    public void setPresenter(MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    public void displayDownloadContent(boolean shown) {
        // Sync is a background service concern. The wallet screen remains visible.
    }

    @Override
    public void displayProgress(int percent) {
        // Sync progress is shown on the Sync page and in the system notification.
    }

    @Override
    public void displayPercentage(int percent) {
        // Sync progress is shown on the Sync page and in the system notification.
    }

    @Override
    public void displayMyBalance(String balance) {
        balanceText.setText(balance);
    }

    @Override
    public void displayBalanceState(String available, String pending) {
        availableBalanceText.setText(available);
        pendingBalanceText.setText(pending);
    }

    @Override
    public void displayWalletType(String type) {
        walletTypeText.setText(type);
    }

    @Override
    public void displayMyAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            return;
        }

        addressText.setText(address);
        updateReceiveQr(address);

        if (swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
    }

    private void updateReceiveQr(String address) {
        new Thread(() -> {
            try {
                final android.graphics.Bitmap qr = QrCodeGenerator.generate(address, 300);
                runOnUiThread(() -> receiveQr.setImageBitmap(qr));
            } catch (Exception ignored) {
                // QR display failure does not affect wallet operation.
            }
        }, "receive-qr-generator").start();
    }

    @Override
    public void displayTransactions(List<TransactionItem> transactions) {
        allTransactions.clear();
        if (transactions != null) {
            allTransactions.addAll(transactions);
        }
        applyTransactionFilter();
    }

    @Override
    public Context getActivityContext() {
        return this;
    }

    @Override
    public void showToastMessage(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 9101 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportTransactionsTo(data.getData());
            return;
        }

        if (requestCode == ADDRESS_BOOK_REQUEST && resultCode == RESULT_OK && data != null) {
            String selectedAddress = data.getStringExtra(
                    wallet.contacts.AddressBookActivity.EXTRA_SELECTED_ADDRESS);
            if (!TextUtils.isEmpty(selectedAddress)) {
                Intent send = new Intent(this, wallet.send.SendActivity.class);
                send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, selectedAddress);
                startActivity(send);
            }
            return;
        }

        IntentResult result = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            Intent send = new Intent(this, wallet.send.SendActivity.class);
            send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, result.getContents());
            startActivity(send);
        }
    }

    private void exportTransactionsTo(android.net.Uri destination) {
        new Thread(() -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(destination);
                 java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8)) {
                if (output == null) throw new java.io.IOException("Unable to open destination");
                writer.write("type,amount,time,confirmations,state,peers,txid\n");
                for (TransactionItem item : allTransactions) {
                    writer.write(csv(item.type)); writer.write(",");
                    writer.write(csv(item.amount)); writer.write(",");
                    writer.write(csv(item.time)); writer.write(",");
                    writer.write(csv(item.confirmations)); writer.write(",");
                    writer.write(csv(item.state)); writer.write(",");
                    writer.write(csv(item.peers)); writer.write(",");
                    writer.write(csv(item.txid)); writer.write("\n");
                }
                writer.flush();
                runOnUiThread(() -> showToastMessage(getString(R.string.export_transactions_success)));
            } catch (Exception error) {
                runOnUiThread(() -> showToastMessage(getString(R.string.export_transactions_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())));
            }
        }, "transaction-export").start();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_TRANSACTION_FILTER, transactionFilter);
        outState.putInt(STATE_TRANSACTION_LIMIT, transactionLimit);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (presenter != null) {
            // The foreground sync service owns WalletAppKit. Destroying this
            // Activity must only detach the UI, not stop blockchain sync.
            presenter.detachView();
        }
    }
}
