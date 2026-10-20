package wallet.main;

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
import android.view.ViewGroup;
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
    private int historyLimit = -1;
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
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;

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
        fixedAmount = findViewById(R.id.forwardingFixedAmount);
        modeChoice = findViewById(R.id.forwardingModeChoice);
        fixedAmountContainer = findViewById(R.id.forwardingFixedAmountContainer);
        feeRate = findViewById(R.id.forwardingFeeRate);
        feeRateLabel = findViewById(R.id.forwardingFeeRateLabel);
        feePreview = findViewById(R.id.forwardingActivityFeeSummary);
        activityHistory = findViewById(R.id.forwardingActivityHistory);
        historyLimitButton = findViewById(R.id.forwardingHistoryLimitButton);
        historyLimit = getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).getInt(HISTORY_LIMIT, -1);
        updateHistoryLimitButton();
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
        if (status != null) updateState();
    }

    @Override
    protected void onPause() {
        getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesListener);
        super.onPause();
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
        View sweepWalletNow = findViewById(R.id.forwardingSweepWalletNowButton);
        if (sweepWalletNow != null) {
            sweepWalletNow.setVisibility(selected == R.id.forwardingModeWalletSweep ? View.VISIBLE : View.GONE);
        }
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
        EditText passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint(R.string.password_hint);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        passwordInput.setPadding(padding, padding / 2, padding, padding / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.forwarding_unlock_title)
                .setMessage(R.string.forwarding_unlock_message)
                .setView(passwordInput)
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
        getSharedPreferences(forwardingPrefsName(), MODE_PRIVATE).edit()
                .putBoolean(ENABLED, true)
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

        String stage = preferences.getString(LAST_STAGE, "");
        String incomingTx = preferences.getString(LAST_INCOMING_TX, "");
        String outgoingTx = preferences.getString(LAST_OUTGOING_TX, "");
        boolean trackingOutgoingConfirmation = "relayed".equals(stage) || "confirmed".equals(stage);
        int required = trackingOutgoingConfirmation ? 1 : Math.max(1,
                preferences.getInt(LAST_REQUIRED_CONFIRMATIONS, preferences.getInt(CONFIRMATIONS, 1)));
        int current = trackingOutgoingConfirmation
                ? Math.max(0, Math.min(1, preferences.getInt(LAST_OUTGOING_CONFIRMATIONS, 0)))
                : Math.max(0, Math.min(required,
                        preferences.getInt(LAST_CURRENT_CONFIRMATIONS, 0)));

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
        } else if ("relayed".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_relayed_title);
            activityDetail.setText(R.string.forwarding_activity_relayed_detail);
        } else if ("confirmed".equals(stage)) {
            activityTitle.setText(R.string.forwarding_activity_confirmed_title);
            activityDetail.setText(R.string.forwarding_activity_confirmed_detail);
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
                || "broadcasting".equals(stage) || "relayed".equals(stage) || "confirmed".equals(stage);
        confirmationsRow.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            activityConfirmations.setText(getString(trackingOutgoingConfirmation
                            ? R.string.forwarding_activity_outgoing_confirmation_count
                            : R.string.forwarding_activity_confirmation_count,
                    current, required));
            confirmationProgress.setMax(required);
            confirmationProgress.setProgress(current);
        }

        long receivedSats = preferences.getLong(LAST_RECEIVED_SATS, -1L);
        long feeSats = preferences.getLong(LAST_FEE_SATS, -1L);
        long amountSats = preferences.getLong(LAST_AMOUNT_SATS, -1L);
        int rate = preferences.getInt(LAST_RATE_SAT_VB, -1);
        int effectiveRate = preferences.getInt(LAST_EFFECTIVE_RATE_SAT_VB, -1);
        int vsize = preferences.getInt(LAST_VSIZE, 0);
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

    private void renderActivityHistory(SharedPreferences preferences) {
        if (activityHistory == null) return;
        activityHistory.removeAllViews();
        String raw = preferences.getString("activity_ledger_json", "[]");
        try {
            org.json.JSONArray entries = new org.json.JSONArray(raw);
            int rendered = 0;
            for (int i = 0; i < entries.length() && (historyLimit < 0 || rendered < historyLimit); i++) {
                org.json.JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackgroundResource(R.drawable.bg_card);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardParams.topMargin = dp(8);
                activityHistory.addView(card, cardParams);
                String incoming = entry.optString("incoming", "");
                if (!incoming.isEmpty()) {
                    card.setClickable(true);
                    card.setFocusable(true);
                    android.util.TypedValue ripple = new android.util.TypedValue();
                    if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                            && ripple.resourceId != 0) {
                        card.setForeground(androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                this, ripple.resourceId));
                    }
                    card.setOnClickListener(v -> openForwardingDetail(incoming));
                }

                String stage = entry.optString("stage", "").trim();
                TextView stageView = historyText(getHistoryStageLabel(stage), 14, true, true);
                card.addView(stageView);

                if (!incoming.isEmpty()) addHistoryDetail(card,
                        getString(R.string.forwarding_activity_incoming_tx), compactTxId(incoming));
                String outgoing = entry.optString("outgoing", "");
                if (!outgoing.isEmpty()) addHistoryDetail(card,
                        getString(R.string.forwarding_activity_outgoing_tx), compactTxId(outgoing));
                long received = entry.optLong("received", 0L);
                long amount = entry.optLong("amount", 0L);
                long fee = entry.optLong("fee", 0L);
                if (received > 0) addHistoryDetail(card, getString(R.string.forwarding_activity_history_received,
                        MoneyFormatter.format(this, Coin.valueOf(received))), null);
                if (amount > 0) addHistoryDetail(card, getString(R.string.forwarding_activity_history_sent,
                        MoneyFormatter.format(this, Coin.valueOf(amount))), null);
                if (fee > 0) addHistoryDetail(card, getString(R.string.forwarding_activity_history_fee,
                        MoneyFormatter.format(this, Coin.valueOf(fee))), null);
                rendered++;
            }
            if (rendered == 0) {
                TextView empty = historyText(getString(R.string.forwarding_activity_history_empty), 13, false, false);
                activityHistory.addView(empty);
            }
        } catch (Exception ignored) {
            activityHistory.addView(historyText(getString(R.string.forwarding_activity_history_empty), 13, false, false));
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
    }

    private String getHistoryStageLabel(String stage) {
        switch (stage) {
            case "waiting": return getString(R.string.forwarding_activity_waiting_title);
            case "preparing": return getString(R.string.forwarding_activity_preparing_title);
            case "broadcasting": return getString(R.string.forwarding_activity_broadcasting_title);
            case "relayed": return getString(R.string.forwarding_activity_relayed_title);
            case "confirmed": return getString(R.string.forwarding_activity_confirmed_title);
            case "failed": return getString(R.string.forwarding_activity_failed_title);
            case "disabled": return getString(R.string.forwarding_activity_disabled_title);
            default: return getString(R.string.forwarding_activity_idle_title);
        }
    }

    private void addHistoryDetail(LinearLayout card, String label, String value) {
        TextView detail = historyText(value == null ? label : getString(
                R.string.forwarding_history_label_value, label, value), 13, false, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        card.addView(detail, params);
    }

    private TextView historyText(String text, int sizeSp, boolean primary, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        int textColorAttribute = primary
                ? android.R.attr.textColorPrimary
                : android.R.attr.textColorSecondary;
        android.content.res.TypedArray textColors = obtainStyledAttributes(
                new int[] { textColorAttribute });
        try {
            android.content.res.ColorStateList resolvedColor = textColors.getColorStateList(0);
            if (resolvedColor != null) {
                view.setTextColor(resolvedColor);
            }
        } finally {
            textColors.recycle();
        }
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setTextIsSelectable(false);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String compactTxId(String txId) {
        if (txId == null || txId.length() <= 24) return txId == null ? "" : txId;
        return txId.substring(0, 12) + "…" + txId.substring(txId.length() - 12);
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
