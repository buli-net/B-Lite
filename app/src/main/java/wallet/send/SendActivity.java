package wallet.send;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.bitcoinj.base.Coin;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;

import wallet.contacts.AddressBookActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletSelection;
import wallet.qr.QrCodeGenerator;
import wallet.qr.ReceiveQrDialog;
import wallet.tools.CoinControlActivity;

/** Bitcoin send screen. */
public class SendActivity extends AppCompatActivity implements SendPresenter.View {

    public static final String EXTRA_RECIPIENT = "recipient";

    private static final long SAFETY_DELAY_MS = 60_000L;
    private static final long COUNTDOWN_INTERVAL_MS = 1_000L;

    private EditText recipient;
    private EditText amount;
    private SeekBar fee;
    private SwitchCompat rbfSwitch;
    private TextView feeLabel;
    private TextView feeValue;
    private TextView balanceValue;
    private TextView availableBalanceText;
    private TextView pendingBalanceText;
    private View watchCoinsPanel;
    private LinearLayout watchCoinsContainer;
    private ImageView receiveQr;
    private ImageButton walletSelectorButton;
    private TextView walletTypeText;
    private TextView addressText;
    private ImageView copyAddress;
    private View sendFormPanel;
    private View syncBlockNotice;
    private ProgressBar sendSyncProgress;
    private TextView sendSyncProgressText;
    private TextView totalValue;
    private TextView remainingValue;
    private View processPanel;
    private TextView processStatus;
    private TextView processCountdown;
    private ProgressBar processProgress;
    private TextView processRecipient;
    private TextView processBalance;
    private TextView processAmount;
    private TextView processFee;
    private TextView processFeeRate;
    private TextView processSize;
    private TextView processTotal;
    private TextView processRemaining;
    private TextView processRbf;
    private Button send;
    private Button cancelSend;
    private Button sendNow;

    private CountDownTimer countdownTimer;
    private SendPresenter presenter;
    private boolean updatingAmount;
    private final Runnable walletUpdateCallback = this::onWalletUpdated;
    private static final int ADDRESS_BOOK_REQUEST = 4101;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_send);

        bindViews();
        setupToolbar();
        setupFeeControls();
        setupActions();

        presenter = new SendPresenter(this);
        MainActivityPresenter activePresenter = MainActivityPresenter.getActivePresenter();
        if (activePresenter != null) {
            activePresenter.addWalletUpdateListener(walletUpdateCallback);
            activePresenter.addSyncStateListener(walletUpdateCallback);
        }
        configureSelectedWalletMode();
        renderSelectedWalletSummary();

        String passed = getIntent().getStringExtra(EXTRA_RECIPIENT);
        if (!TextUtils.isEmpty(passed)) {
            recipient.setText(passed);
            recipient.selectAll();
        }

        if (state == null) {
            resetProcessPanel();
            if (!isWatchOnlySelected()) {
                presenter.refreshWalletSummary();
            }
        }
    }

    private boolean isWatchOnlySelected() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            return false;
        }
        try {
            return WalletSelection.findSelectedScript(this, kit.wallet()) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void configureSelectedWalletMode() {
        boolean watchOnly = isWatchOnlySelected();
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        boolean syncing = active == null || active.isSyncing();
        syncBlockNotice.setVisibility(syncing ? View.VISIBLE : View.GONE);
        if (syncing) {
            int pct = active == null ? 0 : active.getSyncPercent();
            sendSyncProgress.setIndeterminate(false);
            sendSyncProgress.setProgress(pct);
            sendSyncProgressText.setText(getString(R.string.percentage_display, pct));
        }

        sendFormPanel.setVisibility(watchOnly || syncing ? View.GONE : View.VISIBLE);

        if (watchOnly || syncing) {
            stopCountdown();
            processPanel.setVisibility(View.GONE);
            send.setVisibility(View.GONE);
        } else if (processPanel.getVisibility() != View.VISIBLE) {
            send.setVisibility(View.VISIBLE);
        }
    }

    private void renderSelectedWalletSummary() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            return;
        }

        new Thread(() -> {
            try {
                Wallet wallet = kit.wallet();
                Script selected = WalletSelection.findSelectedScript(this, wallet);
                Coin balance;
                Coin available;
                Coin pending;
                String address;
                boolean watchOnly = selected != null;

                if (watchOnly) {
                    long confirmedSat = 0L;
                    long pendingSat = 0L;
                    for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
                        if (!output.isAvailableForSpending()
                                || !selected.equals(output.getScriptPubKey())) {
                            continue;
                        }
                        if (output.getParentTransactionDepthInBlocks() > 0) {
                            confirmedSat += output.getValue().value;
                        } else {
                            pendingSat += output.getValue().value;
                        }
                    }
                    available = Coin.valueOf(confirmedSat);
                    pending = Coin.valueOf(pendingSat);
                    balance = available.add(pending);
                    address = WalletSelection.addressForScript(selected, wallet.getParams());
                } else {
                    balance = WalletSelection.mainEstimatedBalance(wallet);
                    available = WalletSelection.mainAvailableBalance(wallet);
                    pending = balance.subtract(available);
                    address = wallet.currentReceiveAddress().toString();
                }

                final Coin finalBalance = balance;
                final Coin finalAvailable = available;
                final Coin finalPending = pending;
                final String finalAddress = address;
                final boolean finalWatchOnly = watchOnly;
                final Script finalSelected = selected;
                final java.util.ArrayList<TransactionOutput> finalWatchCoins =
                        new java.util.ArrayList<>();
                if (watchOnly) {
                    for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
                        if (output.isAvailableForSpending()
                                && selected.equals(output.getScriptPubKey())) {
                            finalWatchCoins.add(output);
                        }
                    }
                }

                runOnUiThread(() -> {
                    configureSelectedWalletMode();
                    walletTypeText.setText(finalWatchOnly
                            ? R.string.wallet_type_watch
                            : R.string.wallet_type_main);
                    balanceValue.setText(finalBalance.toFriendlyString());
                    availableBalanceText.setText(getString(
                            R.string.available_balance, finalAvailable.toFriendlyString()));
                    pendingBalanceText.setText(getString(
                            R.string.pending_balance, finalPending.toFriendlyString()));
                    renderWatchCoins(finalWatchOnly, finalWatchCoins);
                    if (!TextUtils.isEmpty(finalAddress)) {
                        addressText.setText(finalAddress);
                        updateReceiveQr(finalAddress);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    balanceValue.setText(R.string.loading);
                    availableBalanceText.setText(R.string.available_balance_loading);
                    pendingBalanceText.setText(R.string.pending_balance_loading);
                });
            }
        }, "send-wallet-summary").start();
    }

    private void renderWatchCoins(boolean watchOnly, java.util.List<TransactionOutput> outputs) {
        if (watchCoinsPanel == null || watchCoinsContainer == null) {
            return;
        }

        watchCoinsPanel.setVisibility(watchOnly ? View.VISIBLE : View.GONE);
        watchCoinsContainer.removeAllViews();
        if (!watchOnly) {
            return;
        }

        if (outputs == null || outputs.isEmpty()) {
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, watchCoinsContainer, false);
            empty.setText(R.string.watch_send_no_coins);
            watchCoinsContainer.addView(empty);
            return;
        }

        for (TransactionOutput output : outputs) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_watch_coin, watchCoinsContainer, false);
            TextView txId = row.findViewById(R.id.tvCoinTxId);
            TextView outputIndex = row.findViewById(R.id.tvCoinOutput);
            TextView confirmations = row.findViewById(R.id.tvCoinConf);
            TextView value = row.findViewById(R.id.tvCoinAmount);

            txId.setText(output.getParentTransactionHash().toString());
            outputIndex.setText(getString(
                    R.string.watch_send_coin_output, output.getIndex()));
            confirmations.setText(getString(
                    R.string.watch_send_coin_confirmations,
                    Math.max(0, output.getParentTransactionDepthInBlocks())));
            value.setText(output.getValue().toFriendlyString());
            watchCoinsContainer.addView(row);
        }
    }

    private void onWalletUpdated() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                configureSelectedWalletMode();
                renderSelectedWalletSummary();
                if (!isWatchOnlySelected()) {
                    presenter.refreshWalletSummary();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (walletSelectorButton != null) {
            configureSelectedWalletMode();
            renderSelectedWalletSummary();
        }
    }

    private void bindViews() {
        syncBlockNotice = findViewById(R.id.syncBlockNotice);
        sendSyncProgress = findViewById(R.id.sendSyncProgress);
        sendSyncProgressText = findViewById(R.id.sendSyncProgressText);
        recipient = findViewById(R.id.recipientInput);
        amount = findViewById(R.id.amountInput);
        fee = findViewById(R.id.feeSeekBar);
        rbfSwitch = findViewById(R.id.rbfSwitch);
        feeLabel = findViewById(R.id.feeRateText);
        feeValue = findViewById(R.id.estimatedFeeText);
        balanceValue = findViewById(R.id.balanceValue);
        availableBalanceText = findViewById(R.id.availableBalanceText);
        pendingBalanceText = findViewById(R.id.pendingBalanceText);
        watchCoinsPanel = findViewById(R.id.watchCoinsPanel);
        watchCoinsContainer = findViewById(R.id.watchCoinsContainer);
        receiveQr = findViewById(R.id.receiveQr);
        walletSelectorButton = findViewById(R.id.walletSelectorButton);
        walletTypeText = findViewById(R.id.walletTypeText);
        addressText = findViewById(R.id.addressText);
        copyAddress = findViewById(R.id.copyAddress);
        sendFormPanel = findViewById(R.id.sendFormPanel);
        totalValue = findViewById(R.id.totalValue);
        remainingValue = findViewById(R.id.remainingValue);
        processPanel = findViewById(R.id.sendProcessPanel);
        processStatus = findViewById(R.id.processStatus);
        processCountdown = findViewById(R.id.processCountdown);
        processProgress = findViewById(R.id.processProgress);
        processRecipient = findViewById(R.id.processRecipient);
        processBalance = findViewById(R.id.processBalance);
        processAmount = findViewById(R.id.processAmount);
        processFee = findViewById(R.id.processFee);
        processFeeRate = findViewById(R.id.processFeeRate);
        processSize = findViewById(R.id.processSize);
        processTotal = findViewById(R.id.processTotal);
        processRemaining = findViewById(R.id.processRemaining);
        processRbf = findViewById(R.id.processRbf);
        send = findViewById(R.id.sendButton);
        cancelSend = findViewById(R.id.cancelSendButton);
        sendNow = findViewById(R.id.sendNowButton);
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar_send);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.send_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupFeeControls() {
        fee.setMax(SendPresenter.MAX_FEE_SAT_VB - SendPresenter.MIN_FEE_SAT_VB);
        fee.setProgress(4);
        fee.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateFeeLabel();
                presenter.refreshWalletSummary();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        updateFeeLabel();
    }

    private void setupActions() {
        Button scan = findViewById(R.id.scanButton);
        Button max = findViewById(R.id.maxButton);
        Button coinControl = findViewById(R.id.coinControlButton);

        copyAddress.setOnClickListener(v -> copyAddress());
        receiveQr.setOnClickListener(v -> showReceiveQr());
        walletSelectorButton.setOnClickListener(v -> showWalletSelector());

        amount.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && getString(R.string.amount_default).equals(amount.getText().toString())) {
                amount.selectAll();
            }
        });

        Button addressBook = findViewById(R.id.addressBookButton);
        addressBook.setOnClickListener(v -> startActivityForResult(
                new Intent(this, AddressBookActivity.class), ADDRESS_BOOK_REQUEST));

        scan.setOnClickListener(v -> new IntentIntegrator(this)
                .setPrompt(getString(R.string.scan_recipient_address))
                .initiateScan());

        max.setOnClickListener(v -> presenter.fillMax());
        coinControl.setOnClickListener(v -> startActivity(new Intent(this, CoinControlActivity.class)));
        send.setOnClickListener(v -> presenter.prepareSend());
        cancelSend.setOnClickListener(v -> cancelPendingSend());
        sendNow.setOnClickListener(v -> {
            stopCountdown();
            presenter.confirmSend();
        });

        TextWatcher formWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!updatingAmount) {
                    presenter.refreshWalletSummary();
                }
            }
        };
        recipient.addTextChangedListener(formWatcher);
        amount.addTextChangedListener(formWatcher);

        rbfSwitch.setOnCheckedChangeListener((button, checked) -> presenter.refreshWalletSummary());
    }

    private void copyAddress() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            Toast.makeText(this, R.string.wallet_address_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                getString(R.string.bitcoin_address_clip_label), address));
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show();
    }

    private void showReceiveQr() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            Toast.makeText(this, R.string.wallet_address_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        ReceiveQrDialog.show(this, address);
    }

    private void updateReceiveQr(String address) {
        new Thread(() -> {
            try {
                final android.graphics.Bitmap qr = QrCodeGenerator.generate(address, 300);
                runOnUiThread(() -> receiveQr.setImageBitmap(qr));
            } catch (Exception ignored) {
                // QR display failure does not affect wallet operation.
            }
        }, "send-receive-qr-generator").start();
    }

    private void showWalletSelector() {
        final WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Wallet wallet = kit.wallet();
            java.util.List<String> addresses = new java.util.ArrayList<>();
            addresses.add(null);

            for (Script script : wallet.getWatchedScripts()) {
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
                    configureSelectedWalletMode();
                    renderSelectedWalletSummary();
                    if (!isWatchOnlySelected()) {
                        presenter.refreshWalletSummary();
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
            Toast.makeText(this, getString(
                    R.string.wallet_selector_failed,
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateFeeLabel() {
        int satVb = selectedFeeSatVb();
        feeLabel.setText(getString(R.string.fee_rate_label, satVb));
        feeValue.setText(R.string.estimated_fee_pending);
    }

    private int selectedFeeSatVb() {
        return fee.getProgress() + SendPresenter.MIN_FEE_SAT_VB;
    }

    private void cancelPendingSend() {
        stopCountdown();
        presenter.cancelPendingSend();
        resetProcessPanel();
        presenter.refreshWalletSummary();
    }

    private void startCountdown(long remainingMs) {
        stopCountdown();
        long duration = Math.max(0L, Math.min(SAFETY_DELAY_MS, remainingMs));
        sendNow.setEnabled(true);
        processCountdown.setText(getString(
                R.string.send_countdown,
                Math.max(0L, (duration + 999L) / 1000L)));
        processProgress.setIndeterminate(false);
        processProgress.setMax((int) SAFETY_DELAY_MS);
        processProgress.setProgress((int) duration);

        if (duration == 0L) {
            processCountdown.setText(R.string.send_ready_to_send);
            presenter.confirmSend();
            return;
        }

        countdownTimer = new CountDownTimer(duration, COUNTDOWN_INTERVAL_MS) {
            @Override
            public void onTick(long remaining) {
                processCountdown.setText(getString(
                        R.string.send_countdown,
                        Math.max(1L, (remaining + 999L) / 1000L)));
                processProgress.setProgress((int) remaining);
            }

            @Override
            public void onFinish() {
                processProgress.setProgress(0);
                processCountdown.setText(R.string.send_ready_to_send);
                presenter.confirmSend();
            }
        }.start();
    }

    private void stopCountdown() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    private void resetProcessPanel() {
        stopCountdown();
        setFormEnabled(true);
        processPanel.setVisibility(View.GONE);
        boolean watchOnly = isWatchOnlySelected();
        send.setVisibility(watchOnly ? View.GONE : View.VISIBLE);
        send.setEnabled(!watchOnly);
        send.setText(R.string.send_button);
        cancelSend.setVisibility(View.GONE);
        cancelSend.setEnabled(false);
        sendNow.setVisibility(View.GONE);
        sendNow.setEnabled(false);
        processStatus.setText(R.string.send_process_preparing);
        processCountdown.setText("");
        processProgress.setIndeterminate(false);
        processProgress.setProgress(0);
    }

    private void setFormEnabled(boolean enabled) {
        recipient.setEnabled(enabled);
        amount.setEnabled(enabled);
        fee.setEnabled(enabled);
        rbfSwitch.setEnabled(enabled);
        findViewById(R.id.scanButton).setEnabled(enabled);
        findViewById(R.id.maxButton).setEnabled(enabled);
    }

    private void showProcessPanel() {
        processPanel.setVisibility(View.VISIBLE);
        send.setVisibility(View.GONE);
        cancelSend.setVisibility(View.VISIBLE);
        sendNow.setVisibility(View.VISIBLE);
    }

    @Override
    public String recipient() {
        return recipient.getText().toString();
    }

    @Override
    public String amount() {
        return amount.getText().toString();
    }

    @Override
    public int feeSatVb() {
        return selectedFeeSatVb();
    }

    @Override
    public boolean replaceByFee() {
        return rbfSwitch.isChecked();
    }

    @Override
    public android.content.Context getActivityContext() {
        return this;
    }

    @Override
    public String getStringResource(int resId, Object... formatArgs) {
        return formatArgs == null || formatArgs.length == 0
                ? getString(resId)
                : getString(resId, formatArgs);
    }

    @Override
    public void showPreparing(boolean preparing) {
        runOnUiThread(() -> {
            if (preparing) {
                showProcessPanel();
                setFormEnabled(false);
                processStatus.setText(R.string.send_process_preparing);
                processCountdown.setText(R.string.send_process_calculating);
                processProgress.setIndeterminate(true);
                sendNow.setVisibility(View.VISIBLE);
                cancelSend.setVisibility(View.VISIBLE);
                sendNow.setEnabled(false);
                cancelSend.setEnabled(true);
                return;
            }

            sendNow.setEnabled(false);
        });
    }

    @Override
    public void showReview(SendTransactionPreview preview, long remainingMs) {
        runOnUiThread(() -> {
            showProcessPanel();
            setFormEnabled(false);
            processStatus.setText(R.string.send_process_waiting);
            processProgress.setIndeterminate(false);
            processRecipient.setText(preview.recipient());
            processBalance.setText(preview.balance().toFriendlyString());
            processAmount.setText(preview.amount().toFriendlyString());
            processFee.setText(preview.fee().toFriendlyString());
            processFeeRate.setText(getString(R.string.fee_rate_value, preview.feeRateSatVb()));
            processSize.setText(getString(R.string.transaction_size_value, preview.sizeBytes()));
            processTotal.setText(preview.totalDebit().toFriendlyString());
            processRemaining.setText(preview.remainingBalance().toFriendlyString());
            processRbf.setText(preview.replaceByFee()
                    ? R.string.rbf_enabled
                    : R.string.rbf_disabled);

            feeValue.setText(getString(
                    R.string.actual_fee_value,
                    preview.fee().toFriendlyString()));
            balanceValue.setText(preview.balance().toFriendlyString());
            totalValue.setText(preview.totalDebit().toFriendlyString());
            remainingValue.setText(preview.remainingBalance().toFriendlyString());
            startCountdown(remainingMs);
        });
    }

    @Override
    public void showSending(boolean sending) {
        runOnUiThread(() -> {
            stopCountdown();
            if (sending) {
                processStatus.setText(R.string.broadcasting_transaction);
                processCountdown.setText(R.string.send_process_broadcasting);
                processProgress.setIndeterminate(true);
                cancelSend.setVisibility(View.GONE);
                sendNow.setVisibility(View.GONE);
                send.setVisibility(View.GONE);
                processPanel.setVisibility(View.VISIBLE);
                return;
            }

            resetProcessPanel();
            amount.setText(R.string.amount_default);
            recipient.setText("");
            rbfSwitch.setChecked(true);
            feeValue.setText(R.string.estimated_fee_pending);
        });
    }

    @Override
    public void showWalletBalance(Coin balance) {
        runOnUiThread(() -> {
            balanceValue.setText(balance.toFriendlyString());
            totalValue.setText(R.string.send_calculated_on_send);
            remainingValue.setText(R.string.send_calculated_on_send);
        });
    }

    @Override
    public void showSummaryPending(Coin balance) {
        runOnUiThread(() -> {
            if (balance == null) {
                balanceValue.setText(R.string.loading);
            } else {
                balanceValue.setText(balance.toFriendlyString());
            }
            totalValue.setText(R.string.send_calculated_on_send);
            remainingValue.setText(R.string.send_calculated_on_send);
        });
    }

    @Override
    public void showMaxAmount(Coin maxAmount) {
        runOnUiThread(() -> {
            updatingAmount = true;
            amount.setText(maxAmount.toPlainString());
            amount.selectAll();
            updatingAmount = false;
            presenter.refreshWalletSummary();
        });
    }

    @Override
    public void showMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ADDRESS_BOOK_REQUEST && resultCode == RESULT_OK && data != null) {
            String selectedAddress = data.getStringExtra(AddressBookActivity.EXTRA_SELECTED_ADDRESS);
            if (!TextUtils.isEmpty(selectedAddress)) {
                recipient.setText(selectedAddress);
                recipient.selectAll();
            }
            return;
        }

        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            recipient.setText(result.getContents());
            recipient.selectAll();
        }
    }

    @Override
    protected void onDestroy() {
        stopCountdown();
        MainActivityPresenter activePresenter = MainActivityPresenter.getActivePresenter();
        if (activePresenter != null) {
            activePresenter.removeWalletUpdateListener(walletUpdateCallback);
            activePresenter.removeSyncStateListener(walletUpdateCallback);
        }
        if (presenter != null) {
            presenter.onViewDestroyed(this, isChangingConfigurations());
        }
        super.onDestroy();
    }
}
