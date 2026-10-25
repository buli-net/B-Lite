package wallet.forwarding;

import wallet.ui.BaseActivity;
import wallet.network.NetworkConfig;

import android.os.Bundle;
import android.content.SharedPreferences;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.wallet.Wallet;
import wallet.security.WalletSecurity;
import wallet.util.MoneyFormatter;
import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Configuration screen for opt-in automatic forwarding from the active wallet. */
public class ForwardingServiceActivity extends BaseActivity {
    private static final String PREFS = "forwarding_service";

    private String forwardingPrefsName() {
        return PREFS + "_" + NetworkConfig.storageName(NetworkConfig.get(this));
    }
    private static final String ENABLED = "enabled";
    private static final String DESTINATION = "destination";
    private static final String CONFIRMATIONS = "confirmations";
    private static final String STATUS = "status";
    private static final String MODE = "mode";
    private static final String FIXED_AMOUNT_SATS = "fixed_amount_sats";
    private static final String FEE_RATE_SAT_VB = "fee_rate_sat_vb";
    private static final String LAST_FEE_SATS = "last_fee_sats";
    private static final String LAST_AMOUNT_SATS = "last_amount_sats";
    private static final String LAST_RATE_SAT_VB = "last_rate_sat_vb";
    private static final String LAST_EFFECTIVE_RATE_SAT_VB = "last_effective_rate_sat_vb";
    private static final String LAST_VSIZE = "last_vsize";
    private static final String LAST_INCOMING_TX = "last_incoming_tx";
    private static final String LAST_OUTGOING_TX = "last_outgoing_tx";
    private static final String LAST_STAGE = "last_stage";
    private static final String LAST_RECEIVED_SATS = "last_received_sats";
    private static final String LAST_CURRENT_CONFIRMATIONS = "last_current_confirmations";
    private static final String LAST_REQUIRED_CONFIRMATIONS = "last_required_confirmations";
    private static final String LAST_OUTGOING_CONFIRMATIONS = "last_outgoing_confirmations";
    private static final String HISTORY_LIMIT = "history_limit";
    private static final String HISTORY_FILTER = "history_filter";
    private static final int HISTORY_FILTER_ALL = 0;
    private static final int HISTORY_FILTER_SUCCESS = 1;
    private static final int HISTORY_FILTER_PENDING = 2;
    private static final int HISTORY_FILTER_FAILED = 3;
    private static final String ENABLED_AT_MS = "enabled_at_ms";
    private static final String ENABLED_AT_HEIGHT = "enabled_at_height";
    private static final String PRE_ENABLE_PENDING_TXIDS = "pre_enable_pending_txids_json";

    private EditText destination;
    private EditText confirmations;
    private TextView network;
    private TextView status;
    private Button toggle;
    private EditText fixedAmount;
    private RadioGroup modeChoice;
    private SeekBar feeRate;
    private TextView feeRateLabel;
    private TextView feePreview;
    private LinearLayout activityHistory;
    private TextView historyLimitButton;
    private TextView historySummary;
    private RadioGroup historyFilters;
    private androidx.core.widget.NestedScrollView historyScroll;
    private int historyLimit = -1;
    private int historyFilter = HISTORY_FILTER_ALL;
    private TextView activityTitle;
    private TextView activityDetail;
    private TextView activityConfirmations;
    private TextView incomingTxValue;
    private TextView outgoingTxValue;
    private TextView amountSummary;
    private TextView copyIncomingTx;
    private TextView copyOutgoingTx;
    private View incomingTxRow;
    private View outgoingTxRow;
    private View confirmationsRow;
    private ProgressBar confirmationProgress;
    private View fixedAmountContainer;
    private View syncNotice;
    private TextView syncNoticeDetail;
    private MainActivityPresenter syncPresenter;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;
    private final Runnable syncStateListener = () -> runOnUiThread(() -> {
        if (!isFinishing()) updateSyncNotice();
    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_forwarding_service);

        Toolbar toolbar = findViewById(R.id.toolbar_forwarding_service);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.forwarding_service_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        destination = findViewById(R.id.forwardingDestination);
        confirmations = findViewById(R.id.forwardingConfirmations);
        network = findViewById(R.id.forwardingNetwork);
        status = findViewById(R.id.forwardingStatus);
        toggle = findViewById(R.id.forwardingToggleButton);
        syncNotice = findViewById(R.id.forwardingSyncNotice);
        syncNoticeDetail = findViewById(R.id.forwardingSyncNoticeDetail);
        fixedAmount = findViewById(R.id.forwardingFixedAmount);
        modeChoice = findViewById(R.id.forwardingModeChoice);
        fixedAmountContainer = findViewById(R.id.forwardingFixedAmountContainer);
        feeRate = findViewById(R.id.forwardingFeeRate);
        feeRateLabel = findViewById(R.id.forwardingFeeRateLabel);
        feePreview = findViewById(R.id.forwardingActivityFeeSummary);
        activityHistory = findViewById(R.id.forwardingActivityHistory);
        historyLimitButton = findViewById(R.id.forwardingHistoryLimitButton);
        SharedPreferences historyPreferences = getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE);
        historyLimit = historyPreferences.getInt(HISTORY_LIMIT, -1);
        int savedHistoryFilter = historyPreferences.getInt(HISTORY_FILTER, HISTORY_FILTER_ALL);
        historyFilter = savedHistoryFilter >= HISTORY_FILTER_ALL && savedHistoryFilter <= HISTORY_FILTER_FAILED
                ? savedHistoryFilter : HISTORY_FILTER_ALL;
        historySummary = findViewById(R.id.forwardingHistorySummary);
        historyFilters = findViewById(R.id.forwardingHistoryFilters);
        historyScroll = findViewById(R.id.forwardingHistoryScroll);
        updateHistoryLimitButton();
        updateHistoryFilterButtons();
        historyFilters.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.forwardingHistoryFilterSuccess) {
                historyFilter = HISTORY_FILTER_SUCCESS;
            } else if (checkedId == R.id.forwardingHistoryFilterPending) {
                historyFilter = HISTORY_FILTER_PENDING;
            } else if (checkedId == R.id.forwardingHistoryFilterFailed) {
                historyFilter = HISTORY_FILTER_FAILED;
            } else {
                historyFilter = HISTORY_FILTER_ALL;
            }
            historyPreferences.edit().putInt(HISTORY_FILTER, historyFilter).apply();
            renderActivityHistory(historyPreferences);
            if (historyScroll != null) historyScroll.post(() -> historyScroll.scrollTo(0, 0));
        });
        historyLimitButton.setOnClickListener(v -> showHistoryLimitDialog());
        activityTitle = findViewById(R.id.forwardingActivityTitle);
        activityDetail = findViewById(R.id.forwardingActivityDetail);
        activityConfirmations = findViewById(R.id.forwardingActivityConfirmations);
        incomingTxValue = findViewById(R.id.forwardingIncomingTxValue);
        outgoingTxValue = findViewById(R.id.forwardingOutgoingTxValue);
        amountSummary = findViewById(R.id.forwardingActivityAmountSummary);
        copyIncomingTx = findViewById(R.id.forwardingCopyIncomingTx);
        copyOutgoingTx = findViewById(R.id.forwardingCopyOutgoingTx);
        incomingTxRow = findViewById(R.id.forwardingIncomingTxRow);
        outgoingTxRow = findViewById(R.id.forwardingOutgoingTxRow);
        confirmationsRow = findViewById(R.id.forwardingConfirmationsRow);
        confirmationProgress = findViewById(R.id.forwardingConfirmationProgress);
        copyIncomingTx.setOnClickListener(v -> copyTransactionId(
                getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).getString(LAST_INCOMING_TX, "")));
        copyOutgoingTx.setOnClickListener(v -> copyTransactionId(
                getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).getString(LAST_OUTGOING_TX, "")));
        incomingTxValue.setOnClickListener(v -> copyTransactionId(
                getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).getString(LAST_INCOMING_TX, "")));
        outgoingTxValue.setOnClickListener(v -> copyTransactionId(
                getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).getString(LAST_OUTGOING_TX, "")));
        Button save = findViewById(R.id.forwardingSaveButton);

        destination.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        confirmations.setInputType(InputType.TYPE_CLASS_NUMBER);
        fixedAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        feeRate.setMax(9); // Send screen supports 1-10 sat/vB.
        feeRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateFeeRateLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        modeChoice.setOnCheckedChangeListener((group, checkedId) -> updateModeVisibility());
        save.setOnClickListener(v -> saveSettings());
        toggle.setOnClickListener(v -> toggleForwarding());
        preferencesListener = (sharedPreferences, key) -> {
            if (status != null && feePreview != null) updateState();
        };
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesListener);
        attachSyncStateListener();
        if (status != null) updateState();
    }

    @Override
    protected void onPause() {
        getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesListener);
        if (syncPresenter != null) syncPresenter.removeSyncStateListener(syncStateListener);
        super.onPause();
    }

    private void attachSyncStateListener() {
        MainActivityPresenter currentPresenter = MainActivityPresenter.getActivePresenter();
        if (syncPresenter != currentPresenter) {
            if (syncPresenter != null) syncPresenter.removeSyncStateListener(syncStateListener);
            syncPresenter = currentPresenter;
        }
        if (syncPresenter != null) syncPresenter.addSyncStateListener(syncStateListener);
        updateSyncNotice();
    }

    private void updateSyncNotice() {
        if (syncNotice == null || syncNoticeDetail == null) return;
        SharedPreferences preferences = getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE);
        boolean forwardingEnabled = preferences.getBoolean(ENABLED, false);
        MainActivityPresenter currentPresenter = MainActivityPresenter.getActivePresenter();
        boolean syncBlocked = currentPresenter == null
                || currentPresenter.isSyncing()
                || !currentPresenter.isWalletReady()
                || currentPresenter.isSyncModeSwitching()
                || MainActivityPresenter.getActiveWallet() == null;
        boolean showNotice = forwardingEnabled && syncBlocked;
        syncNotice.setVisibility(showNotice ? View.VISIBLE : View.GONE);
        if (!showNotice) return;
        int syncState = currentPresenter == null
                ? R.string.sync_status_starting : currentPresenter.getSyncStatusResId();
        syncNoticeDetail.setText(getString(R.string.forwarding_sync_paused_detail,
                getString(syncState)));
    }

    private void loadSettings() {
        android.content.SharedPreferences preferences = getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE);
        destination.setText(preferences.getString(DESTINATION, ""));
        confirmations.setText(String.valueOf(preferences.getInt(CONFIRMATIONS, 1)));
        String mode = preferences.getString(MODE, "sweep");
        modeChoice.check("fixed".equals(mode) ? R.id.forwardingModeFixed
                : ("wallet_sweep".equals(mode) ? R.id.forwardingModeWalletSweep : R.id.forwardingModeSweep));
        long fixedSats = preferences.getLong(FIXED_AMOUNT_SATS, 0L);
        fixedAmount.setText(fixedSats > 0L
                ? MoneyFormatter.formatInput(this, Coin.valueOf(fixedSats)) : "");
        feeRate.setProgress(Math.max(0, Math.min(9,
                preferences.getInt(FEE_RATE_SAT_VB, 5) - 1)));
        updateFeeRateLabel();
        updateModeVisibility();
        updateState();
    }

    private boolean saveSettings() {
        Address parsed;
        int confirmationCount;
        try {
            parsed = AddressParser.getDefault().parseAddress(destination.getText().toString().trim());
            NetworkConfig.get(this).checkAddress(parsed);
        } catch (Exception error) {
            destination.setError(getString(R.string.forwarding_invalid_address));
            Toast.makeText(this, R.string.forwarding_invalid_address, Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            confirmationCount = Integer.parseInt(confirmations.getText().toString().trim());
            if (confirmationCount < 1 || confirmationCount > 100) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            confirmations.setError(getString(R.string.forwarding_invalid_confirmations));
            Toast.makeText(this, R.string.forwarding_invalid_confirmations, Toast.LENGTH_LONG).show();
            return false;
        }

        int selectedModeId = modeChoice.getCheckedRadioButtonId();
        String selectedMode = selectedModeId == R.id.forwardingModeFixed ? "fixed"
                : (selectedModeId == R.id.forwardingModeWalletSweep ? "wallet_sweep" : "sweep");
        long fixedAmountSats = 0L;
        if ("fixed".equals(selectedMode)) {
            try {
                Coin amount = MoneyFormatter.parse(this, fixedAmount.getText().toString());
                if (amount.value <= 0L) throw new IllegalArgumentException(getString(R.string.forwarding_invalid_fixed_amount));
                fixedAmountSats = amount.value;
            } catch (Exception error) {
                fixedAmount.setError(getString(R.string.forwarding_invalid_fixed_amount));
                Toast.makeText(this, R.string.forwarding_invalid_fixed_amount, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        int feeRateSatVb = feeRate.getProgress() + 1;
        getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).edit()
                .putString(DESTINATION, parsed.toString())
                .putInt(CONFIRMATIONS, confirmationCount)
                .putString(MODE, selectedMode)
                .putLong(FIXED_AMOUNT_SATS, fixedAmountSats)
                .putInt(FEE_RATE_SAT_VB, feeRateSatVb)
                .apply();
        destination.setError(null);
        confirmations.setError(null);
        fixedAmount.setError(null);
        Toast.makeText(this, R.string.forwarding_saved, Toast.LENGTH_SHORT).show();
        updateState();
        return true;
    }

    private void updateModeVisibility() {
        if (fixedAmountContainer == null || modeChoice == null) return;
        int selected = modeChoice.getCheckedRadioButtonId();
        fixedAmountContainer.setVisibility(selected == R.id.forwardingModeFixed ? View.VISIBLE : View.GONE);
    }

    private void updateFeeRateLabel() {
        if (feeRateLabel != null && feeRate != null) {
            feeRateLabel.setText(getString(R.string.forwarding_fee_rate_value, feeRate.getProgress() + 1));
        }
    }

    private void toggleForwarding() {
        android.content.SharedPreferences preferences = getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE);
        if (preferences.getBoolean(ENABLED, false)) {
            android.content.SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(ENABLED, false)
                    .putString(STATUS, "");
            if ("waiting".equals(preferences.getString(LAST_STAGE, ""))) {
                editor.putString(LAST_STAGE, "disabled");
            }
            editor.apply();
            MainActivityPresenter.disablePendingForwardingJobs(this);
            updateState();
            return;
        }

        if (MainActivityPresenter.getActiveWallet() == null) {
            Toast.makeText(this, R.string.forwarding_wallet_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!saveSettings()) return;

        Wallet activeWallet = MainActivityPresenter.getActiveWallet();
        if (WalletSecurity.isEncrypted(activeWallet)
                && !WalletSecurity.isSessionValid(activeWallet)) {
            promptWalletPassword(activeWallet);
            return;
        }
        confirmEnableForwarding();
    }

    private void promptWalletPassword(Wallet wallet) {
        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText passwordInput = passwordView.findViewById(R.id.dialogPasswordInput);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint(R.string.password_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.forwarding_unlock_title)
                .setMessage(R.string.forwarding_unlock_message)
                .setView(passwordView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forwarding_unlock_continue, (dialog, which) -> {
                    String password = passwordInput.getText().toString();
                    if (password.isEmpty() || !WalletSecurity.unlock(wallet, password)) {
                        Toast.makeText(this, R.string.forwarding_unlock_wrong_password,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Do not persist the password. Unlock only through the existing wallet
                    // security session, then ask for explicit confirmation before enabling.
                    confirmEnableForwarding();
                })
                .show();
    }

    private void confirmEnableForwarding() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forwarding_confirm_title)
                .setMessage(R.string.forwarding_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forwarding_enable, (dialog, which) -> {
                    Wallet wallet = MainActivityPresenter.getActiveWallet();
                    if (wallet == null || (WalletSecurity.isEncrypted(wallet)
                            && !WalletSecurity.isSessionValid(wallet))) {
                        Toast.makeText(this, R.string.forwarding_unlock_required,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    preferencesEnableForwarding();
                })
                .show();
    }

    private void preferencesEnableForwarding() {
        long enabledAt = System.currentTimeMillis();
        int enabledAtHeight = Math.max(0, MainActivityPresenter.getActiveBestChainHeight());
        // A previous disabled session must not resume its old waiting work when re-enabled.
        MainActivityPresenter.disablePendingForwardingJobs(this);
        org.json.JSONArray pendingBeforeEnable = new org.json.JSONArray();
        Wallet activeWallet = MainActivityPresenter.getActiveWallet();
        if (activeWallet != null) {
            try {
                for (org.bitcoinj.core.Transaction tx : activeWallet.getTransactions(true)) {
                    if (tx == null || tx.getPurpose() != org.bitcoinj.core.Transaction.Purpose.UNKNOWN) continue;
                    org.bitcoinj.core.TransactionConfidence confidence = tx.getConfidence();
                    int depth = confidence == null ? 0 : Math.max(0, confidence.getDepthInBlocks());
                    if (depth == 0) {
                        // Snapshot all pre-existing unconfirmed wallet transactions. This is
                        // deliberately conservative and also handles receives whose block time
                        // predates activation even though the app only learns about them later.
                        pendingBeforeEnable.put(tx.getTxId().toString());
                    }
                }
            } catch (Exception ignored) {
                // The chain-height/time checkpoint remains a conservative fallback if the wallet
                // transaction list is temporarily unavailable during this one-time snapshot.
            }
        }
        getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).edit()
                .putBoolean(ENABLED, true)
                .putLong(ENABLED_AT_MS, enabledAt)
                .putInt(ENABLED_AT_HEIGHT, enabledAtHeight)
                .putString(PRE_ENABLE_PENDING_TXIDS, pendingBeforeEnable.toString())
                .putString(STATUS, "")
                .apply();
        updateState();
    }

    private void updateState() {
        android.content.SharedPreferences preferences = getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE);
        boolean enabled = preferences.getBoolean(ENABLED, false);
        String lastStatus = preferences.getString(STATUS, "");
        BitcoinNetwork currentNetwork = NetworkConfig.get(this);
        network.setText(getString(R.string.forwarding_network_value, currentNetwork.toString()));
        status.setText(enabled
                ? getString(R.string.forwarding_status_enabled)
                : getString(R.string.forwarding_status_off));
        updateSyncNotice();

        String stage = preferences.getString(LAST_STAGE, "");
        String incomingTx = preferences.getString(LAST_INCOMING_TX, "");
        String outgoingTx = preferences.getString(LAST_OUTGOING_TX, "");
        org.json.JSONObject summaryEntry = resolveSummaryLedgerEntry(preferences, incomingTx);
        if (summaryEntry != null) {
            stage = summaryEntry.optString("stage", stage);
            incomingTx = summaryEntry.optString("incoming", incomingTx);
            outgoingTx = summaryEntry.optString("outgoing", outgoingTx);
            lastStatus = summaryEntry.optString("status", lastStatus);
        }
        // Older versions called a peer-relayed transaction "relayed". It is still pending
        // until a block confirms it, so normalize the display without rewriting stored history.
        if ("relayed".equals(stage)) stage = "pending_confirmation";
        if (!outgoingTx.isEmpty() && "confirmed".equals(stage)) {
            try {
                MainActivityPresenter currentPresenter = MainActivityPresenter.getActivePresenter();
                Wallet activeWallet = MainActivityPresenter.getActiveWallet();
                org.bitcoinj.core.Transaction activeOutgoing = activeWallet == null ? null
                        : activeWallet.getTransaction(org.bitcoinj.base.Sha256Hash.wrap(outgoingTx));
                if (activeOutgoing != null && activeOutgoing.getConfidence() != null) {
                    org.bitcoinj.core.TransactionConfidence confidence = activeOutgoing.getConfidence();
                    if (confidence.getDepthInBlocks() <= 0) {
                        stage = confidence.getConfidenceType()
                                == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                                && confidence.numBroadcastPeers() > 0
                                ? "pending_confirmation" : "broadcasting";
                        if (summaryEntry != null) summaryEntry.put("outgoingConfirmations", 0);
                    }
                } else if (currentPresenter != null && currentPresenter.isSyncing()) {
                    // Absence from an incomplete wallet view during sync is uncertainty, not success.
                    stage = "pending_confirmation";
                    if (summaryEntry != null) summaryEntry.put("outgoingConfirmations", 0);
                }
            } catch (Exception ignored) { }
        }
        // Only use the outgoing-confirmation track when the chain has a concrete outgoing TXID.
        boolean trackingOutgoingConfirmation = !outgoingTx.isEmpty()
                && ("pending_confirmation".equals(stage) || "confirmed".equals(stage));
        if (outgoingTx.isEmpty() && ("pending_confirmation".equals(stage) || "confirmed".equals(stage))) {
            stage = "waiting";
        }
        int required = trackingOutgoingConfirmation ? 1 : Math.max(1,
                summaryEntry != null
                        ? summaryEntry.optInt("requiredConfirmations", preferences.getInt(CONFIRMATIONS, 1))
                        : preferences.getInt(LAST_REQUIRED_CONFIRMATIONS, preferences.getInt(CONFIRMATIONS, 1)));
        int current = trackingOutgoingConfirmation
                ? Math.max(0, Math.min(1, summaryEntry != null
                        ? summaryEntry.optInt("outgoingConfirmations", 0)
                        : preferences.getInt(LAST_OUTGOING_CONFIRMATIONS, 0)))
                : Math.max(0, Math.min(required, summaryEntry != null
                        ? summaryEntry.optInt("currentConfirmations", 0)
                        : preferences.getInt(LAST_CURRENT_CONFIRMATIONS, 0)));

        if (stage.isEmpty()) {
            activityTitle.setText(R.string.forwarding_activity_idle_title);
            activityDetail.setText(R.string.forwarding_activity_idle_detail);
        } else if ("waiting".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_waiting_title);
            activityDetail.setText(R.string.forwarding_activity_waiting_detail);
        } else if ("preparing".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_preparing_title);
            activityDetail.setText(R.string.forwarding_activity_preparing_detail);
        } else if ("broadcasting".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_broadcasting_title);
            activityDetail.setText(R.string.forwarding_activity_broadcasting_detail);
        } else if ("pending_confirmation".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_pending_confirmation_title);
            activityDetail.setText(R.string.forwarding_activity_pending_confirmation_detail);
        } else if ("confirmed".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_confirmed_title);
            activityDetail.setText(R.string.forwarding_activity_confirmed_detail);
        } else if ("skipped".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_skipped_title);
            String skippedDetail = lastStatus.isEmpty()
                    ? getString(R.string.forwarding_activity_skipped_detail) : lastStatus;
            if (!incomingTx.isEmpty()) skippedDetail = skippedDetail.replace(incomingTx, compactTxId(incomingTx));
            activityDetail.setText(skippedDetail);
        } else if ("failed".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_failed_title);
            String failureDetail = lastStatus.isEmpty()
                    ? getString(R.string.forwarding_activity_failed_detail) : lastStatus;
            if (!incomingTx.isEmpty()) failureDetail = failureDetail.replace(incomingTx, compactTxId(incomingTx));
            if (!outgoingTx.isEmpty()) failureDetail = failureDetail.replace(outgoingTx, compactTxId(outgoingTx));
            activityDetail.setText(failureDetail);
        } else if ("disabled".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_disabled_title);
            activityDetail.setText(R.string.forwarding_activity_disabled_detail);
        } else {
            activityTitle.setText(R.string.forwarding_activity_idle_title);
            activityDetail.setText(R.string.forwarding_activity_idle_detail);
        }

        boolean hasIncoming = !stage.isEmpty() && !incomingTx.isEmpty();
        boolean hasOutgoing = !stage.isEmpty() && !outgoingTx.isEmpty();
        incomingTxRow.setVisibility(hasIncoming ? View.VISIBLE : View.GONE);
        outgoingTxRow.setVisibility(hasOutgoing ? View.VISIBLE : View.GONE);
        incomingTxValue.setText(hasIncoming ? compactTxId(incomingTx) : "");
        outgoingTxValue.setText(hasOutgoing ? compactTxId(outgoingTx) : "");

        boolean showProgress = "waiting".equals(stage) || "preparing".equals(stage)
                || "broadcasting".equals(stage) || "pending_confirmation".equals(stage)
                || "confirmed".equals(stage);
        confirmationsRow.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            activityConfirmations.setText(getString(trackingOutgoingConfirmation
                            ? R.string.forwarding_activity_outgoing_confirmation_count
                            : R.string.forwarding_activity_confirmation_count,
                    current, required));
            confirmationProgress.setMax(required);
            confirmationProgress.setProgress(current);
        }

        long receivedSats = summaryEntry != null
                ? summaryEntry.optLong("received", -1L) : preferences.getLong(LAST_RECEIVED_SATS, -1L);
        long feeSats = summaryEntry != null
                ? summaryEntry.optLong("fee", -1L) : preferences.getLong(LAST_FEE_SATS, -1L);
        long amountSats = summaryEntry != null
                ? summaryEntry.optLong("amount", -1L) : preferences.getLong(LAST_AMOUNT_SATS, -1L);
        int rate = summaryEntry != null
                ? summaryEntry.optInt("rate", -1) : preferences.getInt(LAST_RATE_SAT_VB, -1);
        int effectiveRate = summaryEntry != null
                ? summaryEntry.optInt("effectiveRate", -1) : preferences.getInt(LAST_EFFECTIVE_RATE_SAT_VB, -1);
        int vsize = summaryEntry != null
                ? summaryEntry.optInt("vsize", 0) : preferences.getInt(LAST_VSIZE, 0);
        if (!stage.isEmpty() && (receivedSats >= 0L || amountSats > 0L)) {
            String received = receivedSats >= 0L
                    ? MoneyFormatter.format(this, Coin.valueOf(receivedSats))
                    : getString(R.string.forwarding_value_unavailable);
            String amount = amountSats > 0L
                    ? MoneyFormatter.format(this, Coin.valueOf(amountSats))
                    : getString(R.string.forwarding_value_unavailable);
            amountSummary.setText(getString(R.string.forwarding_activity_amounts, received, amount));
        } else {
            amountSummary.setText(R.string.forwarding_activity_amounts_empty);
        }
        if (!stage.isEmpty() && feeSats >= 0L && rate > 0) {
            feePreview.setText(getString(R.string.forwarding_activity_fee_summary,
                    MoneyFormatter.format(this, Coin.valueOf(feeSats)), rate, effectiveRate, vsize));
        } else {
            feePreview.setText(R.string.forwarding_activity_fee_empty);
        }
        toggle.setText(enabled ? R.string.forwarding_disable : R.string.forwarding_enable);
        renderActivityHistory(preferences);
    }

    private org.json.JSONObject resolveSummaryLedgerEntry(SharedPreferences preferences, String preferredIncoming) {
        try {
            org.json.JSONArray entries = new org.json.JSONArray(
                    preferences.getString("activity_ledger_json", "[]"));
            // Migrate legacy duplicate snapshots by selecting the newest state for each
            // incoming transaction. A TXID matching another row's outgoing TXID alone
            // must not hide a receipt from the history or summary.
            java.util.LinkedHashMap<String, org.json.JSONObject> latestByIncoming =
                    new java.util.LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                org.json.JSONObject row = entries.optJSONObject(i);
                if (row == null) continue;
                String incoming = row.optString("incoming", "");
                if (incoming.isEmpty()) continue;
                if (row.optLong("transactionTime", 0L) <= 0L) {
                    long knownTime = resolveHistoryTransactionTime(incoming);
                    if (knownTime > 0L) {
                        try { row.put("transactionTime", knownTime); }
                        catch (org.json.JSONException ignored) { }
                    }
                }
                org.json.JSONObject existing = latestByIncoming.get(incoming);
                if (existing == null) {
                    latestByIncoming.put(incoming, row);
                    continue;
                }
                long existingUpdated = existing.optLong("updated",
                        existing.optLong("created", 0L));
                long rowUpdated = row.optLong("updated", row.optLong("created", 0L));
                if (rowUpdated > existingUpdated) latestByIncoming.put(incoming, row);
            }
            org.json.JSONObject preferred = preferredIncoming == null
                    ? null : latestByIncoming.get(preferredIncoming);
            if (preferred != null) return new org.json.JSONObject(preferred.toString());

            org.json.JSONObject newest = null;
            long newestCreated = Long.MIN_VALUE;
            for (org.json.JSONObject row : latestByIncoming.values()) {
                long created = row.optLong("created", row.optLong("updated", 0L));
                if (created >= newestCreated) {
                    newestCreated = created;
                    newest = row;
                }
            }
            return newest == null ? null : new org.json.JSONObject(newest.toString());
        } catch (org.json.JSONException ignored) {
            return null;
        }
    }

    private long getHistoryOrderTime(org.json.JSONObject row) {
        if (row == null) return 0L;
        long transactionTime = row.optLong("transactionTime", 0L);
        return transactionTime > 0L
                ? transactionTime
                : row.optLong("created", row.optLong("updated", 0L));
    }

    private long resolveHistoryTransactionTime(String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) return 0L;
        try {
            Wallet wallet = MainActivityPresenter.getActiveWallet();
            if (wallet == null) return 0L;
            org.bitcoinj.core.Transaction transaction = wallet.getTransaction(
                    org.bitcoinj.base.Sha256Hash.wrap(transactionId));
            if (transaction == null) return 0L;
            java.util.Optional<java.time.Instant> time = transaction.updateTime();
            if (!time.isPresent()) return 0L;
            long millis = time.get().toEpochMilli();
            return millis > 0L ? millis : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private void renderActivityHistory(SharedPreferences preferences) {
        if (activityHistory == null) return;
        activityHistory.removeAllViews();
        String raw = preferences.getString("activity_ledger_json", "[]");
        try {
            org.json.JSONArray entries = new org.json.JSONArray(raw);
            java.util.LinkedHashMap<String, org.json.JSONObject> latestByIncoming =
                    new java.util.LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                org.json.JSONObject row = entries.optJSONObject(i);
                if (row == null) continue;
                String incoming = row.optString("incoming", "");
                if (incoming.isEmpty()) continue;
                org.json.JSONObject existing = latestByIncoming.get(incoming);
                if (existing == null) {
                    latestByIncoming.put(incoming, new org.json.JSONObject(row.toString()));
                    continue;
                }
                long existingUpdated = existing.optLong("updated",
                        existing.optLong("created", 0L));
                long rowUpdated = row.optLong("updated", row.optLong("created", 0L));
                if (rowUpdated > existingUpdated) {
                    org.json.JSONObject freshest = new org.json.JSONObject(row.toString());
                    freshest.put("created", Math.min(
                            existing.optLong("created", existingUpdated),
                            row.optLong("created", rowUpdated)));
                    if (freshest.optLong("transactionTime", 0L) <= 0L) {
                        long retainedTransactionTime = existing.optLong("transactionTime", 0L);
                        if (retainedTransactionTime <= 0L) {
                            retainedTransactionTime = row.optLong("transactionTime", 0L);
                        }
                        if (retainedTransactionTime > 0L) {
                            freshest.put("transactionTime", retainedTransactionTime);
                        }
                    }
                    latestByIncoming.put(incoming, freshest);
                }
            }
            java.util.ArrayList<org.json.JSONObject> ordered =
                    new java.util.ArrayList<>(latestByIncoming.values());
            java.util.Collections.sort(ordered, (a, b) -> {
                int byTransactionTime = Long.compare(getHistoryOrderTime(b), getHistoryOrderTime(a));
                if (byTransactionTime != 0) return byTransactionTime;
                int byCreated = Long.compare(
                        b.optLong("created", b.optLong("updated", 0L)),
                        a.optLong("created", a.optLong("updated", 0L)));
                if (byCreated != 0) return byCreated;
                return a.optString("incoming", "").compareTo(b.optString("incoming", ""));
            });
            // A forwarding operation is the whole connected chain, not one card per
            // intermediate transaction. Rows are connected when one row's outgoing
            // TXID is another row's incoming TXID. Build connected components so legacy
            // ordering, cycles, and intermediate change returns cannot create extra cards.
            java.util.HashMap<String, org.json.JSONObject> byIncoming = new java.util.HashMap<>();
            java.util.HashMap<String, java.util.ArrayList<String>> links = new java.util.HashMap<>();
            java.util.HashSet<String> allOutgoing = new java.util.HashSet<>();
            for (org.json.JSONObject row : ordered) {
                String in = row.optString("incoming", "").trim();
                String out = row.optString("outgoing", "").trim();
                if (in.isEmpty()) continue;
                byIncoming.put(in, row);
                links.putIfAbsent(in, new java.util.ArrayList<>());
                if (!out.isEmpty()) {
                    allOutgoing.add(out);
                    links.putIfAbsent(out, new java.util.ArrayList<>());
                    links.get(in).add(out);
                    links.get(out).add(in);
                }
            }
            java.util.ArrayList<org.json.JSONObject> chains = new java.util.ArrayList<>();
            java.util.HashSet<String> consumed = new java.util.HashSet<>();
            for (org.json.JSONObject candidate : ordered) {
                String candidateIn = candidate.optString("incoming", "").trim();
                if (candidateIn.isEmpty() || consumed.contains(candidateIn)) continue;
                java.util.ArrayList<String> component = new java.util.ArrayList<>();
                java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
                java.util.HashSet<String> componentIds = new java.util.HashSet<>();
                queue.add(candidateIn);
                while (!queue.isEmpty()) {
                    String id = queue.removeFirst();
                    if (!componentIds.add(id)) continue;
                    for (String linked : links.getOrDefault(id, new java.util.ArrayList<>())) {
                        if (!componentIds.contains(linked)) queue.addLast(linked);
                    }
                }
                for (String id : componentIds) if (byIncoming.containsKey(id)) {
                    component.add(id);
                    consumed.add(id);
                }
                if (component.isEmpty()) continue;
                // Prefer the actual chain root (an incoming TXID not produced by another
                // row). If legacy data is cyclic/incomplete, use the oldest row as fallback.
                String rootId = null;
                long rootCreated = Long.MAX_VALUE;
                for (String id : component) {
                    org.json.JSONObject row = byIncoming.get(id);
                    long created = getHistoryOrderTime(row);
                    if (!allOutgoing.contains(id)) {
                        if (rootId == null || created < rootCreated) {
                            rootId = id;
                            rootCreated = created;
                        }
                    }
                }
                if (rootId == null) {
                    for (String id : component) {
                        org.json.JSONObject row = byIncoming.get(id);
                        long created = getHistoryOrderTime(row);
                        if (rootId == null || created < rootCreated) {
                            rootId = id;
                            rootCreated = created;
                        }
                    }
                }
                org.json.JSONObject root = byIncoming.get(rootId);
                org.json.JSONObject cardEntry;
                try { cardEntry = new org.json.JSONObject(root.toString()); }
                catch (org.json.JSONException ex) { continue; }
                // Find the tail by following outgoing -> incoming links; its outcome is
                // the outcome of the whole forwarding operation shown on the single card.
                org.json.JSONObject tail = root;
                java.util.HashSet<String> path = new java.util.HashSet<>();
                while (true) {
                    String in = tail.optString("incoming", "").trim();
                    if (in.isEmpty() || !path.add(in)) break;
                    String out = tail.optString("outgoing", "").trim();
                    org.json.JSONObject next = byIncoming.get(out);
                    if (out.isEmpty() || next == null || !componentIds.contains(out) || path.contains(out)) break;
                    tail = next;
                }
                try {
                    String tailOutgoing = tail.optString("outgoing", "").trim();
                    cardEntry.put("outgoing", tailOutgoing);
                    cardEntry.put("stage", tail.optString("stage", cardEntry.optString("stage", "")));
                    cardEntry.put("status", tail.optString("status", cardEntry.optString("status", "")));
                    // Confirmation depth belongs to a specific outgoing TXID. If the last hop
                    // is still waiting and has no TXID, never inherit a stale count from an older leg.
                    cardEntry.put("outgoingConfirmations", tailOutgoing.isEmpty()
                            ? -1 : tail.optInt("outgoingConfirmations", -1));
                    cardEntry.put("updated", tail.optLong("updated", cardEntry.optLong("updated", 0L)));
                    long feeTotal = 0L;
                    for (String id : component) feeTotal += Math.max(0L, byIncoming.get(id).optLong("fee", 0L));
                    if (feeTotal > 0L) cardEntry.put("fee", feeTotal);
                } catch (org.json.JSONException ignored) { }
                chains.add(cardEntry);
            }
            // Components are discovered by traversing links, so the first row encountered
            // may be a newer intermediate hop rather than the operation's original row.
            // Sort the finished cards again by the root row's immutable creation time;
            // otherwise connected chains can jump around whenever ledger rows are updated.
            java.util.Collections.sort(chains, (a, b) -> {
                long aCreated = getHistoryOrderTime(a);
                long bCreated = getHistoryOrderTime(b);
                int byTransactionTime = Long.compare(bCreated, aCreated);
                if (byTransactionTime != 0) return byTransactionTime;
                int byLedgerCreated = Long.compare(
                        b.optLong("created", b.optLong("updated", 0L)),
                        a.optLong("created", a.optLong("updated", 0L)));
                if (byLedgerCreated != 0) return byLedgerCreated;
                int byUpdated = Long.compare(
                        b.optLong("updated", 0L), a.optLong("updated", 0L));
                if (byUpdated != 0) return byUpdated;
                return a.optString("incoming", "").compareTo(b.optString("incoming", ""));
            });
            ordered = chains;
            // Resolve each operation's real current state first, then filter, then apply the
            // requested display limit. Applying the limit before filtering hid valid matches.
            java.util.ArrayList<org.json.JSONObject> filteredEntries = new java.util.ArrayList<>();
            for (org.json.JSONObject entry : ordered) {
                String stage = resolveTransactionStage(entry);
                String outgoingForLookup = entry.optString("outgoing", "").trim();
                if (!outgoingForLookup.isEmpty()) {
                    try {
                        Wallet activeWallet = MainActivityPresenter.getActiveWallet();
                        if (activeWallet != null) {
                            org.bitcoinj.core.Transaction tx = activeWallet.getTransaction(
                                    org.bitcoinj.base.Sha256Hash.wrap(outgoingForLookup));
                            if (tx != null && tx.getConfidence() != null) {
                                org.bitcoinj.core.TransactionConfidence confidence = tx.getConfidence();
                                int depth = Math.max(0, confidence.getDepthInBlocks());
                                if (depth > 0) {
                                    stage = "confirmed";
                                    entry.put("outgoingConfirmations", depth);
                                } else if ("confirmed".equals(stage) || "relayed".equals(stage)
                                        || "pending_confirmation".equals(stage)) {
                                    // A reorg can remove a formerly confirmed TX from the active
                                    // chain. Show it as unfinished until recovery reconciles it.
                                    stage = confidence.getConfidenceType()
                                            == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING
                                            && confidence.numBroadcastPeers() > 0
                                            ? "pending_confirmation" : "broadcasting";
                                    entry.put("outgoingConfirmations", 0);
                                }
                            }
                        }
                    } catch (Exception ignored) { }
                }
                if ("confirmed".equals(stage)) {
                    MainActivityPresenter currentPresenter = MainActivityPresenter.getActivePresenter();
                    Wallet activeWallet = MainActivityPresenter.getActiveWallet();
                    if (currentPresenter != null && currentPresenter.isSyncing()) {
                        String liveOutgoingId = entry.optString("outgoing", "").trim();
                        try {
                            org.bitcoinj.core.Transaction liveOutgoing = activeWallet == null || liveOutgoingId.isEmpty()
                                    ? null : activeWallet.getTransaction(
                                            org.bitcoinj.base.Sha256Hash.wrap(liveOutgoingId));
                            if (liveOutgoing == null) stage = "pending_confirmation";
                        } catch (Exception ignored) {
                            stage = "pending_confirmation";
                        }
                    }
                }
                entry.put("_uiStage", stage);
                if (matchesHistoryFilter(stage)) filteredEntries.add(entry);
            }

            int rendered = 0;
            for (org.json.JSONObject entry : filteredEntries) {
                if (historyLimit >= 0 && rendered >= historyLimit) break;
                String incoming = entry.optString("incoming", "");
                String outgoing = entry.optString("outgoing", "").trim();
                String stage = entry.optString("_uiStage", "waiting");
                long received = Math.max(0L, entry.optLong("received", 0L));
                long amount = Math.max(0L, entry.optLong("amount", 0L));
                long fee = Math.max(0L, entry.optLong("fee", 0L));

                // Reuse the XML card template so spacing, theme colors and interaction states
                // stay consistent with the rest of the wallet's list UI.
                View card = getLayoutInflater().inflate(R.layout.item_forwarding_history, activityHistory, false);
                TextView stageView = card.findViewById(R.id.forwardingHistoryStage);
                stageView.setText(getHistoryStageLabel(stage));
                LinearLayout details = card.findViewById(R.id.forwardingHistoryDetails);
                LinearLayout metrics = card.findViewById(R.id.forwardingHistoryMetrics);
                activityHistory.addView(card);
                card.setOnClickListener(v -> openForwardingDetail(incoming));

                addCompactHistoryDetail(details,
                        getString(R.string.forwarding_history_incoming_short), compactTxId(incoming));
                if (!outgoing.isEmpty()) {
                    addCompactHistoryDetail(details,
                            getString(R.string.forwarding_history_outgoing_short), compactTxId(outgoing));
                }
                if (fee > 0L) {
                    addCompactHistoryDetail(details, getString(R.string.forwarding_history_fee_short,
                            MoneyFormatter.format(this, Coin.valueOf(fee))), null);
                }
                long timestamp = getHistoryOrderTime(entry);
                if (timestamp > 0L) {
                    java.util.Date date = new java.util.Date(timestamp);
                    String time = android.text.format.DateFormat.getDateFormat(this).format(date)
                            + " " + android.text.format.DateFormat.getTimeFormat(this).format(date);
                    TextView timeView = (TextView) getLayoutInflater().inflate(
                            R.layout.item_forwarding_history_detail, details, false);
                    timeView.setText(time);
                    timeView.setSingleLine(false);
                    timeView.setEllipsize(null);
                    details.addView(timeView);
                }

                if (received > 0L) {
                    TextView receivedAmountView = (TextView) getLayoutInflater().inflate(
                            R.layout.item_forwarding_history_metric, metrics, false);
                    receivedAmountView.setText("+" + MoneyFormatter.format(this, Coin.valueOf(received)));
                    metrics.addView(receivedAmountView);
                }
                if (amount > 0L) {
                    TextView sentAmountView = (TextView) getLayoutInflater().inflate(
                            R.layout.item_forwarding_history_metric, metrics, false);
                    sentAmountView.setText("-" + MoneyFormatter.format(this, Coin.valueOf(amount)));
                    LinearLayout.LayoutParams sentParams = (LinearLayout.LayoutParams) sentAmountView.getLayoutParams();
                    if (received > 0L) sentParams.topMargin = dp(4);
                    metrics.addView(sentAmountView, sentParams);
                }
                int outgoingConfirmations = entry.optInt("outgoingConfirmations", -1);
                String confirmationsLabel = outgoingConfirmations > 0
                        ? getString(R.string.forwarding_history_confirmations, outgoingConfirmations) : "";
                if (!confirmationsLabel.isEmpty()) {
                    TextView confirmationsView = (TextView) getLayoutInflater().inflate(
                            R.layout.item_forwarding_history_confirmations, metrics, false);
                    confirmationsView.setText(confirmationsLabel);
                    metrics.addView(confirmationsView);
                }
                rendered++;
            }
            updateHistorySummary(filteredEntries.size(), rendered);
            if (rendered == 0) {
                int emptyMessage = ordered.isEmpty()
                        ? R.string.forwarding_activity_history_empty
                        : R.string.forwarding_history_filter_empty;
                addHistoryEmptyMessage(emptyMessage);
            }
        } catch (Exception ignored) {
            addHistoryEmptyMessage(R.string.forwarding_activity_history_empty);
        }
    }

    private void openForwardingDetail(String incomingTxId) {
        Intent intent = new Intent(this, ForwardingTransactionDetailActivity.class);
        intent.putExtra(ForwardingTransactionDetailActivity.EXTRA_PREFS_NAME, forwardingPrefsName());
        intent.putExtra(ForwardingTransactionDetailActivity.EXTRA_INCOMING_TXID, incomingTxId);
        startActivity(intent);
    }

    private void showHistoryLimitDialog() {
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
            if (values[i] == historyLimit) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.transaction_limit_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    historyLimit = values[which];
                    getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).edit().putInt(HISTORY_LIMIT, historyLimit).apply();
                    updateHistoryLimitButton();
                    renderActivityHistory(getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE));
                    if (historyScroll != null) historyScroll.post(() -> historyScroll.scrollTo(0, 0));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateHistoryLimitButton() {
        if (historyLimitButton == null) return;
        String label = historyLimit < 0 ? getString(R.string.transaction_limit_all)
                : getString(R.string.transaction_limit_value, historyLimit);
        historyLimitButton.setText(getString(R.string.transaction_limit_all_with_arrow, label));
        historyLimitButton.setContentDescription(getString(R.string.transaction_limit_title) + ": " + label);
    }

    private void updateHistoryFilterButtons() {
        if (historyFilters == null) return;
        int checkedId;
        switch (historyFilter) {
            case HISTORY_FILTER_SUCCESS: checkedId = R.id.forwardingHistoryFilterSuccess; break;
            case HISTORY_FILTER_PENDING: checkedId = R.id.forwardingHistoryFilterPending; break;
            case HISTORY_FILTER_FAILED: checkedId = R.id.forwardingHistoryFilterFailed; break;
            default: checkedId = R.id.forwardingHistoryFilterAll; break;
        }
        if (historyFilters.getCheckedRadioButtonId() != checkedId) historyFilters.check(checkedId);
    }

    private boolean matchesHistoryFilter(String stage) {
        if (historyFilter == HISTORY_FILTER_ALL) return true;
        if (historyFilter == HISTORY_FILTER_SUCCESS) return "confirmed".equals(stage);
        if (historyFilter == HISTORY_FILTER_FAILED) return "failed".equals(stage);
        // Broadcast and peer relay remain pending until the transaction is in an active block.
        // Paused jobs are unfinished, not forwarding failures.
        return !"confirmed".equals(stage) && !"failed".equals(stage) && !"skipped".equals(stage);
    }

    private String getHistoryFilterLabel() {
        switch (historyFilter) {
            case HISTORY_FILTER_SUCCESS: return getString(R.string.forwarding_history_filter_success);
            case HISTORY_FILTER_PENDING: return getString(R.string.forwarding_history_filter_pending);
            case HISTORY_FILTER_FAILED: return getString(R.string.forwarding_history_filter_failed);
            default: return getString(R.string.forwarding_history_filter_all);
        }
    }

    private void updateHistorySummary(int matching, int rendered) {
        if (historySummary == null) return;
        historySummary.setText(getString(R.string.forwarding_history_summary,
                getHistoryFilterLabel(), rendered, matching));
        historySummary.setContentDescription(getString(R.string.forwarding_history_summary,
                getHistoryFilterLabel(), rendered, matching));
    }

    private void addCompactHistoryDetail(LinearLayout container, String label, String value) {
        TextView detail = (TextView) getLayoutInflater().inflate(
                R.layout.item_forwarding_history_detail, container, false);
        detail.setText(value == null ? label : getString(
                R.string.forwarding_history_label_value, label, value));
        container.addView(detail);
    }

    private void addHistoryEmptyMessage(int messageRes) {
        TextView empty = (TextView) getLayoutInflater().inflate(
                R.layout.item_forwarding_history_empty, activityHistory, false);
        empty.setText(messageRes);
        activityHistory.addView(empty);
    }

    /** Resolve saved forwarding state without promoting relay or stale confirmation metadata to success. */
    private String resolveTransactionStage(org.json.JSONObject entry) {
        String stage = entry.optString("stage", "").trim();
        String outgoing = entry.optString("outgoing", "").trim();
        int confirmations = entry.optInt("outgoingConfirmations", -1);

        // Explicit outcomes take precedence over stale confirmation metadata. Actual live wallet
        // confidence is checked by the caller and can independently prove a current confirmation.
        if ("failed".equals(stage) || "disabled".equals(stage) || "skipped".equals(stage)) return stage;
        if (outgoing.isEmpty()) {
            if ("preparing".equals(stage) || "broadcasting".equals(stage)) return stage;
            return "waiting";
        }
        // A peer relay is not a block confirmation. Keep all unconfirmed states out of Success.
        if ("relayed".equals(stage) || "pending_confirmation".equals(stage)) {
            return "pending_confirmation";
        }
        if ("confirmed".equals(stage)) {
            return confirmations > 0 ? "confirmed" : "broadcasting";
        }
        if ("broadcasting".equals(stage)) return "broadcasting";
        if ("waiting".equals(stage) || "preparing".equals(stage)) return stage;
        // Unknown legacy rows with an outgoing TXID need reconciliation, not success promotion.
        return "broadcasting";
    }

    private String getHistoryStageLabel(String stage) {
        switch (stage) {
            case "waiting": return getString(R.string.forwarding_activity_waiting_title);
            case "preparing": return getString(R.string.forwarding_activity_preparing_title);
            case "broadcasting": return getString(R.string.forwarding_activity_broadcasting_title);
            case "relayed":
            case "pending_confirmation": return getString(R.string.forwarding_activity_pending_confirmation_title);
            case "confirmed": return getString(R.string.forwarding_activity_confirmed_title);
            case "skipped": return getString(R.string.forwarding_activity_skipped_title);
            case "failed": return getString(R.string.forwarding_activity_failed_title);
            case "disabled": return getString(R.string.forwarding_activity_disabled_title);
            default: return getString(R.string.forwarding_activity_idle_title);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String compactTxId(String txId) {
        if (txId == null || txId.length() <= 24) return txId == null ? "" : txId;
        return txId.substring(0, 12) + getString(R.string.text_ellipsis) + txId.substring(txId.length() - 12);
    }

    private void copyTransactionId(String txId) {
        if (txId == null || txId.trim().isEmpty()) {
            Toast.makeText(this, R.string.forwarding_no_txid_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.bitcoin_transaction_id_clip_label), txId));
            Toast.makeText(this, R.string.forwarding_txid_copied, Toast.LENGTH_SHORT).show();
        }
    }

}
