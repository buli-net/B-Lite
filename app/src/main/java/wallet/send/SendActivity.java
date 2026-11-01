package wallet.send;

import wallet.ui.LocalizedErrors;

import wallet.util.MoneyFormatter;
import wallet.ui.BaseActivity;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.protobuf.payments.Protos;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.protocols.payments.PaymentSession;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.core.Transaction;

import wallet.contacts.AddressBookActivity;
import wallet.main.MainActivityPresenter;
import wallet.network.NetworkConfig;
import wallet.main.R;
import wallet.core.WalletSelection;
import wallet.storage.ImportedWalletStore;
import wallet.core.WalletAddressType;
import wallet.qr.QrCodeGenerator;
import wallet.nfc.Nfc;
import wallet.qr.ReceiveQrDialog;
import wallet.security.WalletSecurity;
import wallet.nfc.NfcHardwareSecurity;
import wallet.offline.BluetoothPaymentSender;
import wallet.offline.BluetoothSupport;
import wallet.ui.TextViewUtils;

/** Bitcoin send screen. */

public class SendActivity extends BaseActivity implements SendPresenter.View {

    public static final String EXTRA_RECIPIENT = "recipient";

    private static final long SAFETY_DELAY_MS = 60_000L;
    private static final long COUNTDOWN_INTERVAL_MS = 1_000L;

    private EditText recipient;
    private EditText amount;
    private SeekBar fee;
    private TextView feeLabel;
    private TextView feeValue;
    private TextView balanceValue;
    private TextView networkBadge;
    private TextView availableBalanceText;
    private TextView pendingBalanceText;
    private LinearLayout walletLoadingStatus;
    private ProgressBar walletLoadingProgress;
    private TextView walletLoadingText;
    private View watchCoinsPanel;
    private LinearLayout watchCoinsContainer;
    /**
     * Reuse the existing Coin rows across wallet/block refreshes. Recreating
     * the TextViews on every confirmation update causes the long TXID ellipsis
     * span to be recalculated and visibly flicker.
     */
    private final Map<String, View> watchCoinRows = new HashMap<>();
    private ImageView receiveQr;
    private String lastReceiveQrAddress;
    private int receiveQrGeneration;
    private ImageButton walletSelectorButton;
    private TextView walletTypeText;
    private TextView addressText;
    private ImageView copyAddress;
    private View sendFormPanel;
    private View importedTypeUnsupportedNotice;
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
    private Button send;
    private Button cancelSend;
    private Button sendNow;
    private Button nfcReceiveButton;
    private TextView nfcReceiveStatus;

    private CountDownTimer countdownTimer;
    private SendPresenter presenter;
    private NfcAdapter nfcAdapter;
    private BluetoothAdapter bluetoothAdapter;
    private String pendingBluetoothMac;
    private boolean applyingIncomingPaymentData;
    private Transaction pendingBluetoothTransaction;
    private Coin pendingBluetoothAmount;
    private boolean bluetoothPaymentWaitingForEnable;
    private PendingIntent nfcReceivePendingIntent;
    private IntentFilter[] nfcReceiveIntentFilters;
    private String[][] nfcReceiveTechLists;
    private boolean nfcReceiveModeActive;
    private final AtomicBoolean nfcReadInProgress = new AtomicBoolean(false);
    private volatile boolean protectedNfcPromptOpen;
    private boolean updatingAmount;
    private final Handler walletLoadingHandler = new Handler(Looper.getMainLooper());
    private int walletSummaryRenderGeneration;
    private int walletLoadingCycle;
    private int walletLoadingMessageResId = R.string.wallet_status_loading_data;
    private boolean walletSummaryLoading;
    private boolean walletSummaryLoadFailed;
    private Runnable deferredWalletLoadingDisplay;
    private Runnable walletLoadingTimeout;
    private final Runnable walletUpdateCallback = this::onWalletUpdated;

    private static final long WALLET_LOADING_DISPLAY_DELAY_MS = 350L;
    private static final long WALLET_LOADING_TIMEOUT_MS = 30_000L;


    private final ActivityResultLauncher<String> bluetoothPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) continueBluetoothPayment();
                else failPendingBluetoothPayment(R.string.bluetooth_payment_permission_denied);
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bluetoothAdapter != null && result.getResultCode() == RESULT_OK) continueBluetoothPayment();
                else failPendingBluetoothPayment(R.string.bluetooth_payment_enable_bluetooth);
            });

    private final ActivityResultLauncher<Intent> addressBookLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String selectedAddress = result.getData().getStringExtra(
                            AddressBookActivity.EXTRA_SELECTED_ADDRESS);
                    if (!TextUtils.isEmpty(selectedAddress)) {
                        recipient.setText(selectedAddress);
                        recipient.selectAll();
                    }
                }
            });

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                final String scanned = result.getContents();
                if (TextUtils.isEmpty(scanned)) return;
                if (scanned.regionMatches(true, 0, "bitcoin:", 0, "bitcoin:".length())) {
                    handleBitcoinUriString(scanned.trim());
                } else {
                    recipient.setText(scanned.trim());
                    recipient.selectAll();
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_send);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        configureNfcReceiveDispatch();
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
        beginWalletSummaryLoading(R.string.wallet_status_loading_data, false);
        renderSelectedWalletSummary();
        updateNetworkBadge();

        String passed = getIntent().getStringExtra(EXTRA_RECIPIENT);
        if (!TextUtils.isEmpty(passed)) {
            recipient.setText(passed);
            recipient.selectAll();
        }

        if (state == null) {
            resetProcessPanel();
            if (!handleIncomingIntent(getIntent(), true)) {
                if (!isWatchOnlySelected()) {
                    presenter.refreshWalletSummary();
                }
            }
        }
    }

    private boolean isWatchOnlySelected() {
        Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            return false;
        }
        try {
            Script selected = WalletSelection.findSelectedScript(this, wallet);
            return selected != null
                    && !WalletSelection.isRequestNestedScript(this, wallet, selected);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void configureSelectedWalletMode() {
        boolean watchOnly = isWatchOnlySelected();
        String selectedImported = WalletSelection.getSelectedImportedAddress(this);
        boolean importedTypeUnsupported = selectedImported != null
                && !WalletAddressType.isNativelySpendable(
                ImportedWalletStore.getAddressType(this, selectedImported));
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        boolean syncing = active == null || active.isSyncing();
        syncBlockNotice.setVisibility(syncing ? View.VISIBLE : View.GONE);
        importedTypeUnsupportedNotice.setVisibility(!syncing && !watchOnly && importedTypeUnsupported
                ? View.VISIBLE : View.GONE);
        if (syncing) {
            int pct = active == null ? 0 : active.getSyncPercent();
            sendSyncProgress.setIndeterminate(false);
            sendSyncProgress.setProgress(pct);
            sendSyncProgressText.setText(getString(R.string.percentage_display, pct));
        } else {
            sendSyncProgress.setIndeterminate(false);
            sendSyncProgress.setProgress(100);
            sendSyncProgressText.setText(getString(R.string.percentage_display, 100));
        }

        boolean blockSending = watchOnly || syncing || importedTypeUnsupported;
        sendFormPanel.setVisibility(blockSending ? View.GONE : View.VISIBLE);

        if (watchOnly || syncing || importedTypeUnsupported) {
            stopCountdown();
            processPanel.setVisibility(View.GONE);
            send.setVisibility(View.GONE);
        } else if (processPanel.getVisibility() != View.VISIBLE) {
            send.setVisibility(View.VISIBLE);
        }
        applyWalletLoadingControls();
    }

    private void renderSelectedWalletSummary() {
        final int renderGeneration = ++walletSummaryRenderGeneration;
        final MainActivityPresenter activePresenter = MainActivityPresenter.getActivePresenter();
        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            if (activePresenter != null && !activePresenter.isWalletReady()) {
                if (!walletSummaryLoading) {
                    beginWalletSummaryLoading(R.string.wallet_status_opening, true);
                } else {
                    setWalletLoadingMessage(R.string.wallet_status_opening);
                }
            } else {
                if (!walletSummaryLoading) {
                    beginWalletSummaryLoading(R.string.wallet_status_opening, true);
                }
                showWalletSummaryLoadFailure();
            }
            return;
        }

        final String renderedWatchSelection = WalletSelection.getSelectedWatchAddress(this);
        final String renderedImportedSelection = WalletSelection.getSelectedImportedAddress(this);
        new Thread(() -> {
            try {
                Script selected = WalletSelection.findSelectedScript(this, wallet);
                Script imported = selected == null
                        ? WalletSelection.findSelectedImportedScript(this, wallet) : null;
                Coin balance;
                Coin available;
                Coin pending;
                String address;
                boolean requestNestedSelected = selected != null
                        && WalletSelection.isRequestNestedScript(this, wallet, selected);
                boolean watchOnly = selected != null && !requestNestedSelected;
                boolean importedSelected = !watchOnly && imported != null;

                if (watchOnly || importedSelected || requestNestedSelected) {
                    long confirmedSat = 0L;
                    long pendingSat = 0L;
                    Iterable<TransactionOutput> outputs = importedSelected
                            ? (WalletSelection.selectedImportedUsesWatchedOutputs(this)
                            ? wallet.getWatchedOutputs(false) : wallet.getUnspents())
                            : wallet.getWatchedOutputs(false);
                    for (TransactionOutput output : outputs) {
                        if (!output.isAvailableForSpending()
                                || !(importedSelected ? imported : selected).equals(output.getScriptPubKey())) {
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
                    address = WalletSelection.addressForScript(
                            importedSelected ? imported : selected, wallet.getParams());
                } else {
                    balance = WalletSelection.mainEstimatedBalance(this, wallet);
                    available = WalletSelection.mainAvailableBalance(this, wallet);
                    pending = balance.subtract(available);
                    address = wallet.currentReceiveAddress().toString();
                }

                final Coin finalBalance = balance;
                final Coin finalAvailable = available;
                final Coin finalPending = pending;
                final String finalAddress = address;
                final boolean finalWatchOnly = watchOnly;
                final boolean finalImportedSelected = importedSelected;
                final boolean finalRequestNestedSelected = requestNestedSelected;
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

                final String importedType = finalImportedSelected
                        ? ImportedWalletStore.getAddressType(this, WalletSelection.getSelectedImportedAddress(this))
                        : null;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (renderGeneration != walletSummaryRenderGeneration) {
                        return;
                    }
                    if (MainActivityPresenter.getActiveWallet() != wallet
                            || !java.util.Objects.equals(renderedWatchSelection,
                            WalletSelection.getSelectedWatchAddress(this))
                            || !java.util.Objects.equals(renderedImportedSelection,
                            WalletSelection.getSelectedImportedAddress(this))) {
                        renderSelectedWalletSummary();
                        return;
                    }
                    configureSelectedWalletMode();
                    if (finalWatchOnly) {
                        walletTypeText.setText(R.string.wallet_type_watch);
                    } else if (finalRequestNestedSelected) {
                        walletTypeText.setText(getString(R.string.wallet_type_label_format,
                                getString(R.string.wallet_type_main),
                                WalletAddressType.label(this, WalletAddressType.P2SH_P2WPKH)));
                    } else if (finalImportedSelected) {
                        walletTypeText.setText(getString(R.string.wallet_type_label_format,
                                getString(R.string.wallet_type_imported),
                                WalletAddressType.label(this, importedType)));
                    } else {
                        walletTypeText.setText(R.string.wallet_type_main);
                    }
                    balanceValue.setText(MoneyFormatter.format(this, finalBalance));
                    availableBalanceText.setText(getString(
                            R.string.available_balance, MoneyFormatter.format(this, finalAvailable)));
                    pendingBalanceText.setText(getString(
                            R.string.pending_balance, MoneyFormatter.format(this, finalPending)));
                    renderWatchCoins(finalWatchOnly, finalWatchCoins);
                    if (!TextUtils.isEmpty(finalAddress)) {
                        TextViewUtils.setTextIfChanged(addressText, finalAddress);
                        updateReceiveQr(finalAddress);
                    }
                    finishWalletSummaryLoading();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (renderGeneration != walletSummaryRenderGeneration) {
                        return;
                    }
                    balanceValue.setText(R.string.loading);
                    availableBalanceText.setText(R.string.available_balance_loading);
                    pendingBalanceText.setText(R.string.pending_balance_loading);
                    if (walletSummaryLoading) {
                        showWalletSummaryLoadFailure();
                    }
                });
            }
        }, "send-wallet-summary").start();
    }

    /**
     * Shows the same theme-aware wallet status used on the main screen. The
     * indicator is delayed for ordinary initial renders to avoid flicker, but
     * wallet scope changes show it immediately so a slow switch is visible.
     */
    private void beginWalletSummaryLoading(int messageResId, boolean immediate) {
        walletSummaryLoading = true;
        walletSummaryLoadFailed = false;
        walletLoadingMessageResId = messageResId;
        final int cycle = ++walletLoadingCycle;
        cancelWalletLoadingCallbacks();
        clearWalletSummaryForLoading();
        applyWalletLoadingControls();

        if (immediate) {
            showWalletLoadingStatus();
        } else {
            deferredWalletLoadingDisplay = () -> {
                if (walletSummaryLoading && cycle == walletLoadingCycle) {
                    showWalletLoadingStatus();
                }
            };
            walletLoadingHandler.postDelayed(
                    deferredWalletLoadingDisplay, WALLET_LOADING_DISPLAY_DELAY_MS);
        }

        walletLoadingTimeout = () -> {
            if (walletSummaryLoading && cycle == walletLoadingCycle) {
                showWalletSummaryLoadFailure();
            }
        };
        walletLoadingHandler.postDelayed(walletLoadingTimeout, WALLET_LOADING_TIMEOUT_MS);
    }

    private void clearWalletSummaryForLoading() {
        if (balanceValue != null) balanceValue.setText(R.string.loading);
        if (availableBalanceText != null) {
            availableBalanceText.setText(R.string.available_balance_loading);
        }
        if (pendingBalanceText != null) {
            pendingBalanceText.setText(R.string.pending_balance_loading);
        }
        if (walletTypeText != null) walletTypeText.setText(R.string.loading);
        if (addressText != null) addressText.setText(R.string.loading);
        if (receiveQr != null) receiveQr.setImageDrawable(null);
        lastReceiveQrAddress = null;
        receiveQrGeneration++;
        if (watchCoinsPanel != null) watchCoinsPanel.setVisibility(View.GONE);
        if (watchCoinsContainer != null) watchCoinsContainer.removeAllViews();
        watchCoinRows.clear();
        if (totalValue != null) totalValue.setText(R.string.send_calculated_on_send);
        if (remainingValue != null) remainingValue.setText(R.string.send_calculated_on_send);
    }

    private void setWalletLoadingMessage(int messageResId) {
        walletLoadingMessageResId = messageResId;
        if (walletLoadingText != null && walletLoadingStatus != null
                && walletLoadingStatus.getVisibility() == View.VISIBLE
                && !walletSummaryLoadFailed) {
            walletLoadingText.setText(messageResId);
        }
    }

    private void showWalletLoadingStatus() {
        if (walletLoadingStatus == null || walletLoadingText == null
                || walletLoadingProgress == null) {
            return;
        }
        walletLoadingText.setText(walletSummaryLoadFailed
                ? R.string.wallet_status_tap_to_retry : walletLoadingMessageResId);
        walletLoadingStatus.setVisibility(View.VISIBLE);
        walletLoadingProgress.setVisibility(walletSummaryLoading ? View.VISIBLE : View.GONE);
    }

    private void finishWalletSummaryLoading() {
        if (!walletSummaryLoading && !walletSummaryLoadFailed) {
            return;
        }
        walletSummaryLoading = false;
        walletSummaryLoadFailed = false;
        walletLoadingCycle++;
        cancelWalletLoadingCallbacks();
        if (walletLoadingStatus != null) {
            walletLoadingStatus.setVisibility(View.GONE);
        }
        if (walletLoadingProgress != null) {
            walletLoadingProgress.setVisibility(View.GONE);
        }
        applyWalletLoadingControls();
    }

    private void showWalletSummaryLoadFailure() {
        walletSummaryLoading = false;
        walletSummaryLoadFailed = true;
        walletLoadingCycle++;
        cancelWalletLoadingCallbacks();
        if (walletLoadingStatus != null) {
            walletLoadingStatus.setOnClickListener(v -> retryWalletSummaryLoad());
        }
        showWalletLoadingStatus();
        applyWalletLoadingControls();
    }

    private void retryWalletSummaryLoad() {
        if (!walletSummaryLoadFailed) {
            return;
        }
        beginWalletSummaryLoading(R.string.wallet_status_loading_data, true);
        renderSelectedWalletSummary();
        if (!isWatchOnlySelected() && presenter != null) {
            presenter.refreshWalletSummary();
        }
    }

    private boolean ensureWalletSummaryReady() {
        if (walletSummaryLoading || walletSummaryLoadFailed) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_SHORT).show();
            return false;
        }
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (MainActivityPresenter.getActiveWallet() == null
                || active == null || !active.isWalletReady()) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_SHORT).show();
            beginWalletSummaryLoading(R.string.wallet_status_opening, true);
            renderSelectedWalletSummary();
            return false;
        }
        return true;
    }

    private void applyWalletLoadingControls() {
        boolean loading = walletSummaryLoading;
        boolean blockActions = loading || walletSummaryLoadFailed;
        if (walletSelectorButton != null) {
            // A failed load still permits selecting another wallet or tapping
            // the status row to retry; only an active switch locks the selector.
            walletSelectorButton.setEnabled(!loading);
        }
        if (blockActions) {
            setWalletActionControlsEnabled(false);
            return;
        }
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        boolean walletReady = active != null && active.isWalletReady()
                && MainActivityPresenter.getActiveWallet() != null;
        boolean selectedReadOnly = isWatchOnlySelected();
        String imported = WalletSelection.getSelectedImportedAddress(this);
        boolean importedUnsupported = imported != null
                && !WalletAddressType.isNativelySpendable(
                ImportedWalletStore.getAddressType(this, imported));
        boolean canSend = walletReady && !selectedReadOnly && !importedUnsupported
                && !active.isSyncing()
                && processPanel != null && processPanel.getVisibility() != View.VISIBLE;
        View maxButton = findViewById(R.id.maxButton);
        View coinControlButton = findViewById(R.id.coinControlButton);
        if (send != null) send.setEnabled(canSend);
        if (maxButton != null) maxButton.setEnabled(canSend);
        if (coinControlButton != null) coinControlButton.setEnabled(canSend);
        if (sendNow != null) {
            boolean processActive = processPanel != null
                    && processPanel.getVisibility() == View.VISIBLE;
            sendNow.setEnabled(walletReady && processActive
                    && sendNow.getVisibility() == View.VISIBLE);
        }
        if (copyAddress != null) copyAddress.setEnabled(walletReady);
        if (receiveQr != null) receiveQr.setEnabled(walletReady);
    }

    private void setWalletActionControlsEnabled(boolean enabled) {
        if (send != null) send.setEnabled(enabled);
        View maxButton = findViewById(R.id.maxButton);
        if (maxButton != null) maxButton.setEnabled(enabled);
        View coinControlButton = findViewById(R.id.coinControlButton);
        if (coinControlButton != null) coinControlButton.setEnabled(enabled);
        if (sendNow != null) sendNow.setEnabled(enabled);
        if (copyAddress != null) copyAddress.setEnabled(enabled);
        if (receiveQr != null) receiveQr.setEnabled(enabled);
    }

    private void cancelWalletLoadingCallbacks() {
        if (deferredWalletLoadingDisplay != null) {
            walletLoadingHandler.removeCallbacks(deferredWalletLoadingDisplay);
            deferredWalletLoadingDisplay = null;
        }
        if (walletLoadingTimeout != null) {
            walletLoadingHandler.removeCallbacks(walletLoadingTimeout);
            walletLoadingTimeout = null;
        }
    }

    private void renderWatchCoins(boolean watchOnly, java.util.List<TransactionOutput> outputs) {
        if (watchCoinsPanel == null || watchCoinsContainer == null) {
            return;
        }

        watchCoinsPanel.setVisibility(watchOnly ? View.VISIBLE : View.GONE);
        if (!watchOnly) {
            watchCoinsContainer.removeAllViews();
            watchCoinRows.clear();
            return;
        }

        if (outputs == null || outputs.isEmpty()) {
            watchCoinsContainer.removeAllViews();
            watchCoinRows.clear();

            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, watchCoinsContainer, false);
            empty.setText(R.string.watch_send_no_coins);
            watchCoinsContainer.addView(empty);
            return;
        }

        /*
         * Keep the actual row/TextView instances alive while confirmations
         * change. The TXID is therefore not torn down and rebuilt on every
         * wallet update, so its middle-ellipsis rendering stays stable.
         */
        Map<String, View> activeRows = new HashMap<>();

        for (TransactionOutput output : outputs) {
            String rowKey = output.getParentTransactionHash().toString()
                    + ":" + output.getIndex();

            View row = watchCoinRows.get(rowKey);
            if (row == null) {
                row = getLayoutInflater().inflate(
                        R.layout.item_watch_coin, watchCoinsContainer, false);

                TextView txId = row.findViewById(R.id.tvCoinTxId);
                TextViewUtils.configureSelectableMiddleEllipsis(txId);
                watchCoinRows.put(rowKey, row);
            }

            TextView txId = row.findViewById(R.id.tvCoinTxId);
            TextView outputIndex = row.findViewById(R.id.tvCoinOutput);
            TextView confirmations = row.findViewById(R.id.tvCoinConf);
            TextView value = row.findViewById(R.id.tvCoinAmount);

            TextViewUtils.setTextIfChanged(
                    txId, output.getParentTransactionHash().toString());
            TextViewUtils.setTextIfChanged(
                    outputIndex,
                    getString(R.string.watch_send_coin_output, output.getIndex()));
            TextViewUtils.setTextIfChanged(
                    confirmations,
                    getString(
                            R.string.watch_send_coin_confirmations,
                            Math.max(0, output.getParentTransactionDepthInBlocks())));
            TextViewUtils.setTextIfChanged(value, MoneyFormatter.format(this, output.getValue()));

            activeRows.put(rowKey, row);
        }

        // Remove rows for UTXOs that disappeared, without rebuilding rows that
        // are still present.
        java.util.Iterator<Map.Entry<String, View>> iterator =
                watchCoinRows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, View> entry = iterator.next();
            if (!activeRows.containsKey(entry.getKey())) {
                watchCoinsContainer.removeView(entry.getValue());
                iterator.remove();
            }
        }

        // Keep the same row instances and only touch the container when the
        // UTXO order actually changed. Confirmation-only updates therefore do
        // not trigger a remove/re-add layout pass on the TXID TextViews.
        boolean orderChanged = watchCoinsContainer.getChildCount() != outputs.size();
        if (!orderChanged) {
            for (int i = 0; i < outputs.size(); i++) {
                TransactionOutput output = outputs.get(i);
                String rowKey = output.getParentTransactionHash().toString()
                        + ":" + output.getIndex();
                View expected = activeRows.get(rowKey);
                if (watchCoinsContainer.getChildAt(i) != expected) {
                    orderChanged = true;
                    break;
                }
            }
        }

        if (orderChanged) {
            watchCoinsContainer.removeAllViews();
            for (TransactionOutput output : outputs) {
                String rowKey = output.getParentTransactionHash().toString()
                        + ":" + output.getIndex();
                View row = activeRows.get(rowKey);
                if (row != null) {
                    watchCoinsContainer.addView(row);
                }
            }
        }
    }

    private final Object walletUpdateLock = new Object();
    private boolean walletUpdatePosted;
    private boolean walletUpdatePending;

    private void onWalletUpdated() {
        boolean postUpdate = false;
        synchronized (walletUpdateLock) {
            walletUpdatePending = true;
            if (!walletUpdatePosted) {
                walletUpdatePosted = true;
                postUpdate = true;
            }
        }
        if (postUpdate) {
            runOnUiThread(this::drainWalletUpdates);
        }
    }

    private void drainWalletUpdates() {
        synchronized (walletUpdateLock) {
            if (isFinishing()) {
                walletUpdatePending = false;
                walletUpdatePosted = false;
                return;
            }
            walletUpdatePending = false;
        }

        configureSelectedWalletMode();
        renderSelectedWalletSummary();

        boolean postAgain;
        synchronized (walletUpdateLock) {
            if (isFinishing()) {
                walletUpdatePending = false;
                walletUpdatePosted = false;
                return;
            }
            postAgain = walletUpdatePending;
            if (!postAgain) {
                walletUpdatePosted = false;
            }
        }

        if (postAgain) {
            runOnUiThread(this::drainWalletUpdates);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView amountUnitLabel = findViewById(R.id.amountUnitLabel);
        if (amountUnitLabel != null) {
            amountUnitLabel.setText(MoneyFormatter.getUnit(this));
        }
        if (walletSelectorButton != null) {
            configureSelectedWalletMode();
            renderSelectedWalletSummary();
            updateNetworkBadge();
        }
        if (nfcReceiveModeActive) {
            enableNfcReceiveDispatch();
        }
    }

    @Override
    protected void onPause() {
        disableNfcReceiveDispatch();
        super.onPause();
    }

    /**
     * Prepare explicit foreground dispatch for the manual Receive NFC mode. On some devices,
     * recognized NDEF records can otherwise fall through to the system app resolver. Foreground
     * dispatch gives this foreground activity priority and routes the discovered Tag back
     * to SendActivity instead of showing the system chooser while waiting to receive.
     */
    private void configureNfcReceiveDispatch() {
        if (nfcAdapter == null) return;
        final Intent target = new Intent(this, SendActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        nfcReceivePendingIntent = PendingIntent.getActivity(this, 7314, target, pendingFlags);

        final java.util.ArrayList<IntentFilter> filters = new java.util.ArrayList<>();
        try {
            // Bitcoin URI tags must be claimed before another wallet's URI handler.
            final IntentFilter bitcoinUri = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
            bitcoinUri.addDataScheme("bitcoin");
            filters.add(bitcoinUri);

            // Also accept MIME NDEF records, including Bitcoin PaymentRequest and text records.
            final IntentFilter ndefMime = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
            ndefMime.addDataType("*/*");
            filters.add(ndefMime);
        } catch (IntentFilter.MalformedMimeTypeException malformedMimeType) {
            // The wildcard above is a platform-supported MIME pattern; if unavailable, URI
            // handling and the technology filters below still provide safe receive paths.
        }
        nfcReceiveIntentFilters = filters.toArray(new IntentFilter[0]);

        // Each entry is an OR match. A single technology in an entry is intentionally used
        // to accept phones exposing IsoDep/HCE and common physical NDEF tag families.
        nfcReceiveTechLists = new String[][]{
                {"android.nfc.tech.IsoDep"},
                {"android.nfc.tech.NfcA"},
                {"android.nfc.tech.NfcB"},
                {"android.nfc.tech.NfcF"},
                {"android.nfc.tech.NfcV"},
                {"android.nfc.tech.MifareUltralight"},
                {"android.nfc.tech.MifareClassic"},
                {"android.nfc.tech.Ndef"},
                {"android.nfc.tech.NdefFormatable"}
        };
    }

    private boolean enableNfcReceiveDispatch() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT
                || nfcAdapter == null || !nfcAdapter.isEnabled()
                || nfcReceivePendingIntent == null) return false;
        try {
            // The manual flow uses foreground dispatch to give SendActivity explicit
            // priority over the system tag-dispatch resolver.
            nfcAdapter.enableForegroundDispatch(this, nfcReceivePendingIntent,
                    nfcReceiveIntentFilters, nfcReceiveTechLists);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void disableNfcReceiveDispatch() {
        if (nfcAdapter == null) return;
        try { nfcAdapter.disableForegroundDispatch(this); } catch (RuntimeException ignored) { }
    }

    private void readNfcTagInReceiveMode(final Tag tag) {
        if (!nfcReceiveModeActive || protectedNfcPromptOpen
                || !nfcReadInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                if (!nfcReceiveModeActive) return;

                // Phone-to-phone HCE has no physical tag password; preserve its existing path.
                try {
                    final byte[] paymentRequest = Nfc.readHcePaymentRequest(tag);
                    runOnUiThread(() -> {
                        if (!nfcReceiveModeActive) return;
                        completeNfcReceiveMode();
                        handleNfcPaymentRequest(paymentRequest);
                    });
                    return;
                } catch (Exception notBriteHce) {
                    // Not our HCE service; continue with the same NDEF path used for a tag.
                }

                // Add password authentication before reading protected NDEF. Once authenticated,
                // the decoded records go through processNfcRequestMessage(), the same handler
                // used for non-password cards and HCE/Android-dispatched requests.
                if (NfcHardwareSecurity.requiresPasswordForNdefRead(tag)) {
                    runOnUiThread(() -> {
                        if (!nfcReceiveModeActive) return;
                        promptForProtectedBitcoinRequest(tag, true, false);
                    });
                    return;
                }

                final NdefMessage message = Nfc.readNdefMessage(tag);
                runOnUiThread(() -> {
                    if (!nfcReceiveModeActive) return;
                    processNfcRequestMessage(message, true, false);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (nfcReceiveModeActive) {
                        updateNfcReceiveStatus(R.string.send_receive_nfc_unrecognized);
                    }
                });
            } finally {
                nfcReadInProgress.set(false);
            }
        }, "nfc-payment-request-reader").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Foreground dispatch supplies the Tag explicitly. While manual receive is active,
        // consume it through the exact same HCE/password/NDEF pipeline as the former reader
        // callback instead of letting the generic app-link path compete with receive mode.
        if (nfcReceiveModeActive && intent != null) {
            final Tag dispatchedTag = getTagExtra(intent);
            if (dispatchedTag != null) {
                readNfcTagInReceiveMode(dispatchedTag);
                return;
            }
        }
        handleIncomingIntent(intent, false);
    }

    /**
     * Routes Android NFC dispatch and app links through the same Request handlers.
     * NDEF discovery is inspected for the card's hardware-password state before its
     * already-decoded URI/MIME payload is consumed. Read-protected tags arrive through
     * TECH_DISCOVERED and are decoded after the user supplies the password.
     */
    private boolean handleIncomingIntent(final Intent intent, final boolean fromActivityStart) {
        if (intent == null) return false;

        final String action = intent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            final Tag tag = getTagExtra(intent);
            if (tag == null) return false;
            handleIncomingTechDiscovered(tag, fromActivityStart);
            return true;
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            final Tag tag = getTagExtra(intent);
            if (tag != null) {
                inspectNdefTagBeforeDispatch(tag, intent, fromActivityStart);
                return true;
            }
        }

        return handleStandardIncomingIntent(intent, fromActivityStart);
    }

    private Tag getTagExtra(final Intent intent) {
        try {
            return (Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Check card security off the UI thread without changing the normal no-password dispatch. */
    private void inspectNdefTagBeforeDispatch(final Tag tag, final Intent intent,
                                             final boolean fromActivityStart) {
        if (!nfcReadInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                final boolean passwordRequired = NfcHardwareSecurity.requiresPasswordForNdefRead(tag);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (passwordRequired) {
                        promptForProtectedBitcoinRequest(tag, false, fromActivityStart);
                    } else {
                        // Exact original URI/MIME path for public tags.
                        handleStandardIncomingIntent(intent, fromActivityStart);
                    }
                });
            } catch (RuntimeException inspectFailed) {
                // If chip metadata cannot be inspected, preserve Android's original NDEF path.
                runOnUiThread(() -> {
                    if (!isFinishing()) handleStandardIncomingIntent(intent, fromActivityStart);
                });
            } finally {
                nfcReadInProgress.set(false);
            }
        }, "nfc-ndef-password-check").start();
    }

    /** A read-protected NDEF card cannot produce ACTION_NDEF_DISCOVERED; catch its tech event. */
    private void handleIncomingTechDiscovered(final Tag tag, final boolean fromActivityStart) {
        if (!nfcReadInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                if (NfcHardwareSecurity.requiresPasswordForNdefRead(tag)) {
                    runOnUiThread(() -> promptForProtectedBitcoinRequest(
                            tag, false, fromActivityStart));
                    return;
                }
                // TECH_DISCOVERED is a fallback only. Ordinary Bitcoin NDEF tags continue to
                // be handled by Android's more specific NDEF filters above.
                final NdefMessage message = Nfc.readNdefMessage(tag);
                runOnUiThread(() -> processNfcRequestMessage(
                        message, false, fromActivityStart));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (fromActivityStart && !isFinishing()) {
                        finish();
                    } else if (!isFinishing()) {
                        Toast.makeText(this, R.string.send_receive_nfc_unrecognized,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                nfcReadInProgress.set(false);
            }
        }, "nfc-tech-dispatch-reader").start();
    }

    /**
     * The only extra step for a protected card: authenticate and then pass its NDEF
     * records into the shared URI/PaymentRequest handlers. This does not create/import
     * a wallet or introduce a separate Request workflow.
     */
    private void promptForProtectedBitcoinRequest(final Tag tag, final boolean receiveMode,
                                                  final boolean finishIfCancelled) {
        if (isFinishing() || (receiveMode && !nfcReceiveModeActive)
                || protectedNfcPromptOpen) return;

        protectedNfcPromptOpen = true;
        if (receiveMode) disableNfcReceiveDispatch();

        final View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        final EditText passwordInput = passwordView.findViewById(R.id.dialogPasswordInput);
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint(R.string.nfc_hardware_password_hint);
        passwordInput.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(128)
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_reader_protected_prompt_title)
                .setMessage(R.string.send_receive_nfc_password_message)
                .setView(passwordView)
                .setNegativeButton(android.R.string.cancel,
                        (ignored, which) -> cancelProtectedNfcPrompt(receiveMode, finishIfCancelled))
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnCancelListener(ignored -> cancelProtectedNfcPrompt(receiveMode, finishIfCancelled));
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String password = passwordInput.getText().toString();
                    if (password.trim().length() < 4) {
                        passwordInput.setError(getString(R.string.nfc_reader_protected_bad_password_format));
                        return;
                    }
                    dialog.dismiss();
                    protectedNfcPromptOpen = false;
                    readProtectedBitcoinRequest(tag, password, receiveMode, finishIfCancelled);
                }));
        dialog.show();
    }

    private void cancelProtectedNfcPrompt(final boolean receiveMode, final boolean finishIfCancelled) {
        protectedNfcPromptOpen = false;
        if (receiveMode && nfcReceiveModeActive) {
            updateNfcReceiveStatus(R.string.send_receive_nfc_waiting);
            enableNfcReceiveDispatch();
        } else if (finishIfCancelled && !isFinishing()) {
            finish();
        }
    }

    private void readProtectedBitcoinRequest(final Tag tag, final String password,
                                             final boolean receiveMode,
                                             final boolean finishIfUnrecognized) {
        if (receiveMode && !nfcReceiveModeActive) return;
        if (!nfcReadInProgress.compareAndSet(false, true)) {
            if (receiveMode && nfcReceiveModeActive) enableNfcReceiveDispatch();
            return;
        }

        new Thread(() -> {
            byte[] encodedNdef = null;
            try {
                encodedNdef = NfcHardwareSecurity.readProtectedNdef(tag, password);
                if (encodedNdef == null || encodedNdef.length == 0) {
                    throw new LocalizedErrors.IllegalArgumentException(R.string.error_nfc_protected_empty_ndef);
                }
                final NdefMessage message = new NdefMessage(encodedNdef);
                runOnUiThread(() -> {
                    if (receiveMode && !nfcReceiveModeActive) return;
                    // Android's NDEF resolver must run BEFORE this activity is selected.
                    // Once the user chose this wallet and supplied the card password, consume the
                    // authenticated payload here. Re-dispatching it would show a second chooser.
                    processNfcRequestMessage(message, receiveMode, finishIfUnrecognized);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (receiveMode) {
                        if (nfcReceiveModeActive) {
                            updateNfcReceiveStatus(R.string.send_receive_nfc_unrecognized);
                            enableNfcReceiveDispatch();
                        }
                        if (!isFinishing()) {
                            Toast.makeText(this, R.string.nfc_security_operation_failed_generic,
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (!isFinishing()) {
                        Toast.makeText(this, R.string.nfc_security_operation_failed_generic,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                if (encodedNdef != null) Arrays.fill(encodedNdef, (byte) 0);
                nfcReadInProgress.set(false);
            }
        }, "nfc-protected-bitcoin-request").start();
    }

    /** Shared destination for plain and password-protected NDEF Bitcoin requests. */
    private boolean processNfcRequestMessage(final NdefMessage message, final boolean receiveMode,
                                             final boolean finishIfUnrecognized) {
        final byte[] paymentRequest = Nfc.extractMimePayload(
                PaymentProtocol.MIMETYPE_PAYMENTREQUEST, message);
        if (paymentRequest != null && paymentRequest.length > 0) {
            if (receiveMode) completeNfcReceiveMode();
            handleNfcPaymentRequest(paymentRequest);
            return true;
        }

        final String bitcoinUri = Nfc.extractBitcoinUri(message);
        if (!TextUtils.isEmpty(bitcoinUri)) {
            if (receiveMode) completeNfcReceiveMode();
            handleBitcoinUriString(bitcoinUri);
            return true;
        }

        if (receiveMode && nfcReceiveModeActive) {
            updateNfcReceiveStatus(R.string.send_receive_nfc_unrecognized);
            enableNfcReceiveDispatch();
        } else if (finishIfUnrecognized && !isFinishing()) {
            finish();
        } else if (!isFinishing()) {
            Toast.makeText(this, R.string.send_receive_nfc_unrecognized, Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /** Keep the app's original behavior for ACTION_VIEW and normal public NDEF records. */
    private boolean handleStandardIncomingIntent(final Intent intent,
                                                 final boolean fromActivityStart) {
        if (intent == null) return false;
        final String action = intent.getAction();
        final Uri data = intent.getData();

        if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                && data != null && "bitcoin".equalsIgnoreCase(data.getScheme())) {
            return handleBitcoinUri(intent);
        }

        if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(intent.getType())) {
            return handleNfcPaymentRequest(intent);
        }

        // Bluetooth/nearby transfers may arrive as plain text or an HTML file.
        // Accept only a bounded payload containing a Bitcoin URI; never import keys
        // or treat arbitrary file contents as a payment request.
        final String incomingType = intent.getType();
        final boolean supportedRequestText = "text/plain".equalsIgnoreCase(incomingType)
                || "text/html".equalsIgnoreCase(incomingType);
        if (supportedRequestText
                && (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action))) {
            String requestText = null;
            if (Intent.ACTION_SEND.equals(action)) {
                // Prefer the shared file stream; EXTRA_TEXT is a compatibility fallback.
                final Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) requestText = readSharedRequestText(stream);
                if (TextUtils.isEmpty(requestText)) {
                    requestText = intent.getStringExtra(Intent.EXTRA_TEXT);
                }
            } else if (data != null) {
                // ACTION_VIEW may provide the received file as a content:// URI.
                requestText = readSharedRequestText(data);
            }
            final String requestUri = extractBitcoinRequestUri(requestText);
            if (requestUri != null) {
                handleBitcoinUriString(requestUri);
            } else {
                Toast.makeText(this, R.string.request_uri_invalid, Toast.LENGTH_LONG).show();
            }
            return true;
        }

        // Rare Android devices dispatch the NDEF message but omit a parsed URI/MIME data field.
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            final android.os.Parcelable[] parcels = intent.getParcelableArrayExtra(
                    NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (parcels != null && parcels.length > 0 && parcels[0] instanceof NdefMessage) {
                return processNfcRequestMessage((NdefMessage) parcels[0], false, fromActivityStart);
            }
        }
        return false;
    }

    private String readSharedRequestText(final Uri source) {
        if (source == null) return null;
        try (java.io.InputStream input = getContentResolver().openInputStream(source);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            if (input == null) return null;
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 8192) return null;
                output.write(buffer, 0, read);
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name()).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractBitcoinRequestUri(final String text) {
        if (TextUtils.isEmpty(text) || text.length() > 8192) return null;
        String candidate = text.trim()
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&#x26;", "&")
                .replace("&#X26;", "&");

        // A receiver may store the URI as the whole file or wrap it in HTML markup.
        // Extract only the Bitcoin URI, not the surrounding markup or other file content.
        if (!candidate.regionMatches(true, 0, "bitcoin:", 0, 8)) {
            final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?i)bitcoin:[a-z0-9]{20,100}(?:\\?[^\\s<>]*)?")
                    .matcher(candidate);
            if (!matcher.find()) return null;
            candidate = matcher.group();
        }
        while (!candidate.isEmpty() && (candidate.endsWith(".") || candidate.endsWith(",")
                || candidate.endsWith(";") || candidate.endsWith(")")
                || candidate.endsWith("\"") || candidate.endsWith("'"))) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.length() > 4096 || candidate.indexOf('\n') >= 0
                || candidate.indexOf('\r') >= 0
                || !candidate.regionMatches(true, 0, "bitcoin:", 0, 8)) return null;
        return candidate;
    }

    private boolean handleBitcoinUri(final Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        final Uri data = intent.getData();
        if (!"bitcoin".equalsIgnoreCase(data.getScheme())) {
            return false;
        }
        handleBitcoinUriString(data.toString());
        return true;
    }

    private boolean handleNfcPaymentRequest(final Intent intent) {
        try {
            final byte[] payload = extractPaymentRequest(intent);
            if (payload == null || payload.length == 0) {
                Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
                return true;
            }
            return handleNfcPaymentRequest(payload);
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
            return true;
        }
    }

    private boolean handleNfcPaymentRequest(final byte[] payload) {
        try {
            if (payload == null || payload.length == 0) {
                Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
                return true;
            }

            final Protos.PaymentRequest paymentRequest = Protos.PaymentRequest.parseFrom(payload);
            final PaymentSession session = PaymentProtocol.parsePaymentRequest(paymentRequest);

            if (session.isExpired()) {
                Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
                return true;
            }

            if (!session.getNetworkParameters().equals(NetworkConfig.parameters(NetworkConfig.get(this)))) {
                Toast.makeText(this, R.string.request_network_mismatch, Toast.LENGTH_LONG).show();
                return true;
            }

            if (session.getOutputs() == null || session.getOutputs().size() != 1) {
                Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
                return true;
            }

            final PaymentProtocol.Output output = session.getOutputs().get(0);
            final Script script = Script.parse(output.scriptData);
            final Address address = script.getToAddress(NetworkConfig.parameters(NetworkConfig.get(this)));
            if (address == null) {
                Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
                return true;
            }

            pendingBluetoothMac = BluetoothSupport.getBluetoothMacFromUrl(session.getPaymentUrl());
            applyingIncomingPaymentData = true;
            recipient.setText(address.toString());
            recipient.selectAll();

            if (output.amount != null) {
                amount.setText(MoneyFormatter.formatInput(this, output.amount));
                amount.selectAll();
            } else {
                amount.setText("");
            }

            applyingIncomingPaymentData = false;
            final String memo = session.getMemo();
            if (!TextUtils.isEmpty(memo)) {
                Toast.makeText(this, memo, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.request_nfc_received, Toast.LENGTH_SHORT).show();
            }

            return true;
        } catch (PaymentProtocolException | RuntimeException exception) {
            applyingIncomingPaymentData = false;
            pendingBluetoothMac = null;
            Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception exception) {
            applyingIncomingPaymentData = false;
            pendingBluetoothMac = null;
            Toast.makeText(this, R.string.request_nfc_invalid, Toast.LENGTH_LONG).show();
            return true;
        }
    }

    private static String getBitcoinUriParameter(final String rawUri, final String parameterName) {
        if (TextUtils.isEmpty(rawUri) || parameterName == null
                || !rawUri.regionMatches(true, 0, "bitcoin:", 0, 8)) return null;
        final int queryStart = rawUri.indexOf('?', 8);
        if (queryStart < 0 || queryStart == rawUri.length() - 1) return null;
        final String query = rawUri.substring(queryStart + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            final int equals = pair.indexOf('=');
            final String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (parameterName.equalsIgnoreCase(Uri.decode(rawName))) {
                return equals < 0 ? "" : Uri.decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    private void handleBitcoinUriString(final String uri) {
        try {
            // build flavor. The app switches networks at runtime, so propagate the
            // selected network context before bitcoinj parses an external BIP21 URI.
            final org.bitcoinj.core.NetworkParameters networkParameters =
                    NetworkConfig.parameters(NetworkConfig.get(this));
            org.bitcoinj.core.Context.propagate(
                    org.bitcoinj.core.Context.getOrCreate(networkParameters));
            // BitcoinURI.of(String) can infer the generic Testnet network from tb1,
            // which is shared by Signet; use the selected network explicitly instead.
            final BitcoinURI bitcoinUri = new BitcoinURI(networkParameters, uri);
            final Address address = bitcoinUri.getAddress();
            if (address == null || !isUriForCurrentNetwork(address)) {
                Toast.makeText(this, R.string.request_network_mismatch, Toast.LENGTH_LONG).show();
                return;
            }

            // BIP21 bitcoin: URIs are opaque URIs on Android. Uri.getQueryParameter()
            // only works reliably for hierarchical URIs, and can therefore miss the
            // bt endpoint even when the raw Request URI is valid.
            String suppliedMac = getBitcoinUriParameter(uri, BluetoothSupport.MAC_URI_PARAM);
            pendingBluetoothMac = BluetoothSupport.normalizeMac(suppliedMac);
            applyingIncomingPaymentData = true;
            recipient.setText(address.toString());
            recipient.selectAll();

            final Coin requestedAmount = bitcoinUri.getAmount();
            if (requestedAmount != null) {
                amount.setText(MoneyFormatter.formatInput(this, requestedAmount));
                amount.selectAll();
            } else {
                amount.setText("");
            }
            applyingIncomingPaymentData = false;

            final String label = bitcoinUri.getLabel();
            if (!TextUtils.isEmpty(label)) {
                Toast.makeText(this, label, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.request_uri_received, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception exception) {
            applyingIncomingPaymentData = false;
            pendingBluetoothMac = null;
            // BitcoinURI.of() declares BitcoinURIParseException in bitcoinj 0.17.1.
            // Treat malformed external/NFC URIs as invalid input instead of allowing
            // the checked parser exception to escape the Android activity callback.
            Toast.makeText(this, R.string.request_uri_invalid, Toast.LENGTH_LONG).show();
        }
    }

    private byte[] extractPaymentRequest(final Intent intent) {
        final String mimeType = intent.getType();
        if (!PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
            return null;
        }

        // Bluetooth direct request transfers the same protobuf bytes that NFC stores in
        // the application/bitcoin-paymentrequest NDEF MIME record. Route both through
        // handleNfcPaymentRequest(byte[]) so validation and form population stay identical.
        final byte[] directPayload = intent.getByteArrayExtra(
                BluetoothSupport.EXTRA_DIRECT_PAYMENT_REQUEST);
        if (directPayload != null && directPayload.length > 0
                && directPayload.length <= wallet.nfc.Nfc.HCE_MAX_PAYLOAD) {
            return directPayload;
        }

        final android.os.Parcelable[] messages = intent.getParcelableArrayExtra(
                NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (messages == null || messages.length == 0 || !(messages[0] instanceof NdefMessage)) {
            return null;
        }

        final NdefMessage message = (NdefMessage) messages[0];
        return wallet.nfc.Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, message);
    }

    private boolean isUriForCurrentNetwork(Address address) {
        BitcoinNetwork current = NetworkConfig.get(this);
        Network uriNetwork = address.network();
        if (current == BitcoinNetwork.MAINNET) {
            return uriNetwork == BitcoinNetwork.MAINNET;
        }
        // bitcoinj exposes Signet addresses as the TESTNET-compatible network.
        return uriNetwork == BitcoinNetwork.TESTNET;
    }

    private void bindViews() {
        syncBlockNotice = findViewById(R.id.syncBlockNotice);
        sendSyncProgress = findViewById(R.id.sendSyncProgress);
        sendSyncProgressText = findViewById(R.id.sendSyncProgressText);
        walletLoadingStatus = findViewById(R.id.sendWalletLoadingStatus);
        walletLoadingProgress = findViewById(R.id.sendWalletLoadingProgress);
        walletLoadingText = findViewById(R.id.sendWalletLoadingText);
        if (walletLoadingStatus != null) {
            walletLoadingStatus.setOnClickListener(v -> retryWalletSummaryLoad());
        }
        nfcReceiveButton = findViewById(R.id.sendNfcReceiveButton);
        nfcReceiveStatus = findViewById(R.id.sendNfcReceiveStatus);
        recipient = findViewById(R.id.recipientInput);
        amount = findViewById(R.id.amountInput);
        fee = findViewById(R.id.feeSeekBar);
        feeLabel = findViewById(R.id.feeRateText);
        feeValue = findViewById(R.id.estimatedFeeText);
        balanceValue = findViewById(R.id.balanceValue);
        networkBadge = findViewById(R.id.networkBadge);
        availableBalanceText = findViewById(R.id.availableBalanceText);
        pendingBalanceText = findViewById(R.id.pendingBalanceText);
        watchCoinsPanel = findViewById(R.id.watchCoinsPanel);
        watchCoinsContainer = findViewById(R.id.watchCoinsContainer);
        receiveQr = findViewById(R.id.receiveQr);
        walletSelectorButton = findViewById(R.id.walletSelectorButton);
        walletTypeText = findViewById(R.id.walletTypeText);
        addressText = findViewById(R.id.addressText);
        TextViewUtils.configureSelectableMiddleEllipsis(addressText);
        copyAddress = findViewById(R.id.copyAddress);
        sendFormPanel = findViewById(R.id.sendFormPanel);
        importedTypeUnsupportedNotice = findViewById(R.id.importedTypeUnsupportedNotice);
        totalValue = findViewById(R.id.totalValue);
        remainingValue = findViewById(R.id.remainingValue);
        processPanel = findViewById(R.id.sendProcessPanel);
        processStatus = findViewById(R.id.processStatus);
        processCountdown = findViewById(R.id.processCountdown);
        processProgress = findViewById(R.id.processProgress);
        processRecipient = findViewById(R.id.processRecipient);
        TextViewUtils.configureSelectableMiddleEllipsis(processRecipient);
        processBalance = findViewById(R.id.processBalance);
        processAmount = findViewById(R.id.processAmount);
        processFee = findViewById(R.id.processFee);
        processFeeRate = findViewById(R.id.processFeeRate);
        processSize = findViewById(R.id.processSize);
        processTotal = findViewById(R.id.processTotal);
        processRemaining = findViewById(R.id.processRemaining);
        send = findViewById(R.id.sendButton);
        cancelSend = findViewById(R.id.cancelSendButton);
        sendNow = findViewById(R.id.sendNowButton);
    }

    private void updateNetworkBadge() {
        if (networkBadge == null) {
            return;
        }
        org.bitcoinj.base.BitcoinNetwork network = NetworkConfig.get(this);
        networkBadge.setText(network == org.bitcoinj.base.BitcoinNetwork.SIGNET
                ? R.string.network_badge_signet
                : R.string.network_badge_mainnet);
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

        nfcReceiveButton.setOnClickListener(v -> {
            if (nfcReceiveModeActive) {
                stopNfcReceiveMode(R.string.send_receive_nfc_cancelled);
            } else {
                startNfcReceiveMode();
            }
        });

        copyAddress.setOnClickListener(v -> copyAddress());
        receiveQr.setOnClickListener(v -> showReceiveQr());
        walletSelectorButton.setOnClickListener(v -> showWalletSelector());

        amount.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && getString(R.string.amount_default).equals(amount.getText().toString())) {
                amount.selectAll();
            }
        });

        Button addressBook = findViewById(R.id.addressBookButton);
        addressBook.setOnClickListener(v -> addressBookLauncher.launch(
                new Intent(this, AddressBookActivity.class)));

        scan.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions()
                    .setPrompt(getString(R.string.scan_recipient_address));
            barcodeLauncher.launch(options);
        });

        max.setOnClickListener(v -> {
            if (ensureWalletSummaryReady()) presenter.fillMax();
        });
        coinControl.setOnClickListener(v -> {
            if (ensureWalletSummaryReady()) {
                startActivity(new Intent(this, CoinControlActivity.class));
            }
        });
        send.setOnClickListener(v -> {
            if (ensureWalletSummaryReady()) presenter.prepareSend();
        });
        cancelSend.setOnClickListener(v -> cancelPendingSend());
        sendNow.setOnClickListener(v -> {
            if (!ensureWalletSummaryReady()) return;
            stopCountdown();
            presenter.confirmSend();
        });

        TextWatcher recipientWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                // The BT endpoint belongs to the received request. Keep it when the
                // payer adjusts the amount; clear it only if they replace the payee.
                if (!applyingIncomingPaymentData) pendingBluetoothMac = null;
                if (!updatingAmount) presenter.refreshWalletSummary();
            }
        };
        TextWatcher amountWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                if (!updatingAmount) presenter.refreshWalletSummary();
            }
        };
        recipient.addTextChangedListener(recipientWatcher);
        amount.addTextChangedListener(amountWatcher);

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
        if (TextUtils.isEmpty(address) || receiveQr == null) {
            return;
        }
        if (address.equals(lastReceiveQrAddress)) {
            return;
        }

        lastReceiveQrAddress = address;
        final int generation = ++receiveQrGeneration;
        new Thread(() -> {
            try {
                final android.graphics.Bitmap qr = QrCodeGenerator.generate(address, 300);
                runOnUiThread(() -> {
                    if (!isFinishing() && generation == receiveQrGeneration) {
                        receiveQr.setImageBitmap(qr);
                    }
                });
            } catch (Exception ignored) {
                // QR display failure does not affect wallet operation.
            }
        }, "send-receive-qr-generator").start();
    }

    private void showWalletSelector() {
        if (walletSummaryLoading) {
            return;
        }
        if (processPanel != null && processPanel.getVisibility() == View.VISIBLE) {
            Toast.makeText(this, R.string.send_already_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            class Entry {
                final int kind; // 0 = main, 1 = imported key, 2 = read-only address
                final String address;
                Entry(int kind, String address) {
                    this.kind = kind;
                    this.address = address;
                }
            }

            java.util.List<Entry> entries = new java.util.ArrayList<>();
            entries.add(new Entry(0, null));

            // Keep the same wallet list used by the Main screen. The Send screen
            // only changes its presentation according to the selected wallet type.
            for (String address : WalletSelection.getImportedAddresses(this, wallet)) {
                entries.add(new Entry(1, address));
            }
            java.util.Set<Script> requestNestedScripts =
                    WalletSelection.getRequestNestedScripts(this, wallet);
            for (Script script : wallet.getWatchedScripts()) {
                try {
                    // Request Nested SegWit outputs belong to the main wallet scope.
                    if (requestNestedScripts.contains(script)) continue;
                    entries.add(new Entry(2, script.getToAddress(wallet.getParams()).toString()));
                } catch (Exception ignored) {
                    // Ignore watched scripts that cannot be rendered as an address.
                }
            }

            WalletSelection.findSelectedScript(this, wallet);
            String selectedImported = WalletSelection.getSelectedImportedAddress(this);
            String selectedWatch = WalletSelection.getSelectedWatchAddress(this);
            int checked = 0;
            for (int i = 1; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if ((entry.kind == 1 && entry.address.equals(selectedImported))
                        || (entry.kind == 2 && entry.address.equals(selectedWatch))) {
                    checked = i;
                    break;
                }
            }

            View selectorContent = getLayoutInflater().inflate(R.layout.dialog_wallet_selector, null);
            LinearLayout container = selectorContent.findViewById(R.id.walletSelectorList);

            final AlertDialog[] dialogHolder = new AlertDialog[1];

            for (int i = 0; i < entries.size(); i++) {
                final int position = i;
                final Entry entry = entries.get(i);

                View row = getLayoutInflater().inflate(
                        R.layout.item_wallet_selector, container, false);
                android.widget.RadioButton radio =
                        row.findViewById(R.id.walletSelectorRadio);
                TextView type = row.findViewById(R.id.walletSelectorType);
                TextView addressView = row.findViewById(R.id.walletSelectorAddress);
                TextViewUtils.configureSelectableMiddleEllipsis(addressView);

                radio.setChecked(position == checked);
                if (entry.kind == 0) {
                    type.setText(R.string.wallet_type_main);
                    TextViewUtils.setTextIfChanged(addressView, wallet.currentReceiveAddress().toString());
                } else if (entry.kind == 1) {
                    type.setText(getString(R.string.wallet_type_label_format,
                                getString(R.string.wallet_type_imported),
                                WalletAddressType.label(this, ImportedWalletStore.getAddressType(this, entry.address))));
                    TextViewUtils.setTextIfChanged(addressView, entry.address);
                } else {
                    type.setText(R.string.wallet_type_watch);
                    TextViewUtils.setTextIfChanged(addressView, entry.address);
                }

                row.setOnClickListener(v -> {
                    String previousImported = WalletSelection.getSelectedImportedAddress(this);
                    String previousWatch = WalletSelection.getSelectedWatchAddress(this);
                    boolean sameSelection = entry.kind == 0
                            ? previousImported == null && previousWatch == null
                            : entry.kind == 1
                                    ? entry.address.equals(previousImported)
                                    : entry.address.equals(previousWatch);
                    if (sameSelection) {
                        dialogHolder[0].dismiss();
                        return;
                    }

                    beginWalletSummaryLoading(R.string.wallet_status_switching, true);
                    if (entry.kind == 0) {
                        WalletSelection.selectMain(this);
                    } else if (entry.kind == 1) {
                        WalletSelection.selectImportedAddress(this, entry.address);
                    } else {
                        // Keep the existing Watch-only selection mechanism unchanged.
                        WalletSelection.selectWatchAddress(this, entry.address);
                    }
                    dialogHolder[0].dismiss();
                    configureSelectedWalletMode();
                    renderSelectedWalletSummary();
                    if (!isWatchOnlySelected()) {
                        presenter.refreshWalletSummary();
                    }
                });

                container.addView(row);
                if (i < entries.size() - 1) {
                    View divider = getLayoutInflater().inflate(
                            R.layout.item_wallet_selector_divider, container, false);
                    LinearLayout.LayoutParams dividerParams =
                            (LinearLayout.LayoutParams) divider.getLayoutParams();
                    dividerParams.height = dp(i == 0 ? 2 : 1);
                    dividerParams.setMargins(dp(12), dp(4), dp(12), dp(4));
                    container.addView(divider, dividerParams);
                }
            }

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.wallet_selector_title)
                    .setView(selectorContent)
                    .setNegativeButton(R.string.close, null)
                    .create();
            dialogHolder[0] = dialog;
            dialog.show();
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.wallet_selector_failed,
                    LocalizedErrors.resolve(this, error)), Toast.LENGTH_LONG).show();
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
        applyWalletLoadingControls();
    }

    private void startNfcReceiveMode() {
        if (nfcAdapter == null) {
            updateNfcReceiveStatus(R.string.send_receive_nfc_unavailable);
            Toast.makeText(this, R.string.send_receive_nfc_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            updateNfcReceiveStatus(R.string.send_receive_nfc_enable_device);
            Toast.makeText(this, R.string.send_receive_nfc_enable_device, Toast.LENGTH_LONG).show();
            return;
        }
        nfcReceiveModeActive = true;
        updateNfcReceiveStatus(R.string.send_receive_nfc_waiting);
        nfcReceiveButton.setText(R.string.send_receive_nfc_cancel_button);
        if (!enableNfcReceiveDispatch()) {
            nfcReceiveModeActive = false;
            nfcReceiveButton.setText(R.string.send_receive_nfc_button);
            updateNfcReceiveStatus(R.string.send_receive_nfc_unavailable);
            Toast.makeText(this, R.string.send_receive_nfc_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void stopNfcReceiveMode(final int statusString) {
        nfcReceiveModeActive = false;
        disableNfcReceiveDispatch();
        if (nfcReceiveButton != null) {
            nfcReceiveButton.setText(R.string.send_receive_nfc_button);
        }
        updateNfcReceiveStatus(statusString);
    }

    private void completeNfcReceiveMode() {
        stopNfcReceiveMode(R.string.send_receive_nfc_read_complete);
    }

    private void updateNfcReceiveStatus(final int statusString) {
        if (nfcReceiveStatus == null) return;
        nfcReceiveStatus.setText(statusString);
        nfcReceiveStatus.setVisibility(View.VISIBLE);
    }

    private void setFormEnabled(boolean enabled) {
        recipient.setEnabled(enabled);
        amount.setEnabled(enabled);
        fee.setEnabled(enabled);
        findViewById(R.id.scanButton).setEnabled(enabled);
        findViewById(R.id.maxButton).setEnabled(enabled);
    }

    private void showProcessPanel() {
        if (nfcReceiveModeActive) {
            stopNfcReceiveMode(R.string.send_receive_nfc_cancelled);
        }
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
    public void requestMaxUnlock(Wallet wallet) {
        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        final EditText password = passwordView.findViewById(R.id.dialogPasswordInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.send_unlock_title)
                .setMessage(R.string.unlock_before_send)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.send_button, (dialog, which) -> {
                    final String enteredPassword = password.getText().toString();
                    new Thread(() -> {
                        try {
                            if (!WalletSecurity.unlock(wallet, enteredPassword)) {
                                showMessage(getString(R.string.wrong_password));
                                return;
                            }
                            runOnUiThread(() -> presenter.fillMax());
                        } catch (Exception error) {
                            showMessage(getString(
                                    R.string.security_operation_failed,
                                    LocalizedErrors.resolve(this, error)));
                        }
                    }, "max-wallet-unlock").start();
                })
                .show();
    }

    @Override
    public void requestSendUnlock(Wallet wallet) {
        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        final EditText password = passwordView.findViewById(R.id.dialogPasswordInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.send_unlock_title)
                .setMessage(R.string.unlock_before_send)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.send_button, (dialog, which) -> {
                    final String enteredPassword = password.getText().toString();
                    new Thread(() -> {
                        try {
                            if (!WalletSecurity.unlock(wallet, enteredPassword)) {
                                showMessage(getString(R.string.wrong_password));
                                return;
                            }
                            runOnUiThread(() -> presenter.prepareSend());
                        } catch (Exception error) {
                            showMessage(getString(
                                    R.string.security_operation_failed,
                                    LocalizedErrors.resolve(this, error)));
                        }
                    }, "send-wallet-unlock").start();
                })
                .show();
    }

    @Override
    public void onTransactionBroadcast(final Transaction transaction, final Coin paymentAmount) {
        runOnUiThread(() -> {
            if (TextUtils.isEmpty(pendingBluetoothMac)) return;
            pendingBluetoothTransaction = transaction;
            pendingBluetoothAmount = paymentAmount;
            bluetoothPaymentWaitingForEnable = true;
            continueBluetoothPayment();
        });
    }

    private void continueBluetoothPayment() {
        if (!bluetoothPaymentWaitingForEnable || pendingBluetoothTransaction == null
                || TextUtils.isEmpty(pendingBluetoothMac)) return;
        if (bluetoothAdapter == null) {
            failPendingBluetoothPayment(R.string.bluetooth_payment_bluetooth_unavailable);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        final Transaction transaction = pendingBluetoothTransaction;
        final Coin paymentAmount = pendingBluetoothAmount;
        final String mac = pendingBluetoothMac;
        bluetoothPaymentWaitingForEnable = false;
        pendingBluetoothTransaction = null;
        pendingBluetoothAmount = null;
        BluetoothPaymentSender.send(bluetoothAdapter, mac, transaction, paymentAmount, (acknowledged, error) ->
                runOnUiThread(() -> {
                    if (acknowledged) {
                        Toast.makeText(this, R.string.bluetooth_payment_sent_ack, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, getString(error == null
                                ? R.string.bluetooth_payment_sent_nack
                                : R.string.bluetooth_payment_send_failed, error == null ? new Object[0] : new Object[]{error}),
                                Toast.LENGTH_LONG).show();
                    }
                }));
    }

    private void failPendingBluetoothPayment(int messageResId) {
        bluetoothPaymentWaitingForEnable = false;
        pendingBluetoothTransaction = null;
        pendingBluetoothAmount = null;
        Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
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
            TextViewUtils.setTextIfChanged(processRecipient, preview.recipient());
            processBalance.setText(MoneyFormatter.format(this, preview.balance()));
            processAmount.setText(MoneyFormatter.format(this, preview.amount()));
            processFee.setText(MoneyFormatter.format(this, preview.fee()));
            processFeeRate.setText(getString(R.string.fee_rate_value, preview.feeRateSatVb()));
            processSize.setText(getString(R.string.transaction_size_value, preview.sizeBytes()));
            processTotal.setText(MoneyFormatter.format(this, preview.totalDebit()));
            processRemaining.setText(MoneyFormatter.format(this, preview.remainingBalance()));

            feeValue.setText(getString(
                    R.string.actual_fee_value,
                    MoneyFormatter.format(this, preview.fee())));
            balanceValue.setText(MoneyFormatter.format(this, preview.balance()));
            totalValue.setText(MoneyFormatter.format(this, preview.totalDebit()));
            remainingValue.setText(MoneyFormatter.format(this, preview.remainingBalance()));
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
            feeValue.setText(R.string.estimated_fee_pending);
        });
    }

    @Override
    public void showWalletBalance(Coin balance, Coin available, Coin pending) {
        runOnUiThread(() -> {
            balanceValue.setText(MoneyFormatter.format(this, balance));
            availableBalanceText.setText(getString(
                    R.string.available_balance, MoneyFormatter.format(this, available)));
            pendingBalanceText.setText(getString(
                    R.string.pending_balance, MoneyFormatter.format(this, pending)));
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
                balanceValue.setText(MoneyFormatter.format(this, balance));
            }
            totalValue.setText(R.string.send_calculated_on_send);
            remainingValue.setText(R.string.send_calculated_on_send);
        });
    }

    @Override
    public void showMaxAmount(Coin maxAmount) {
        runOnUiThread(() -> {
            updatingAmount = true;
            amount.setText(MoneyFormatter.formatInput(this, maxAmount));
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
    protected void onDestroy() {
        stopCountdown();
        walletLoadingCycle++;
        walletSummaryRenderGeneration++;
        cancelWalletLoadingCallbacks();
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
