package wallet.forwarding;

import wallet.ui.BaseActivity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Coin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import wallet.util.MoneyFormatter;
import wallet.main.R;

/** Read-only details for one forwarding activity ledger entry. */
public final class ForwardingTransactionDetailActivity extends BaseActivity {
    public static final String EXTRA_PREFS_NAME = "forwarding_prefs_name";
    public static final String EXTRA_INCOMING_TXID = "forwarding_incoming_txid";
    private static final String ACTIVITY_LEDGER = "activity_ledger_json";

    private String prefsName;
    private String incomingTxId;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;

    private TextView stage;
    private TextView statusMessage;
    private TextView network;
    private TextView created;
    private TextView updated;
    private TextView incomingTx;
    private TextView outgoingTx;
    private View outgoingTxContainer;
    private TextView destination;
    private TextView mode;
    private TextView incomingConfirmations;
    private TextView outgoingConfirmations;
    private TextView received;
    private TextView amount;
    private TextView fee;
    private TextView feeRate;
    private TextView effectiveFeeRate;
    private TextView vsize;
    private TextView fixedAmount;
    private View fixedAmountRow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_forwarding_transaction_detail);

        prefsName = getIntent().getStringExtra(EXTRA_PREFS_NAME);
        incomingTxId = getIntent().getStringExtra(EXTRA_INCOMING_TXID);
        if (prefsName == null || !prefsName.startsWith("forwarding_service_")
                || incomingTxId == null || incomingTxId.trim().isEmpty()) {
            finish();
            return;
        }
        preferences = getSharedPreferences(prefsName, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_forwarding_transaction_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.forwarding_detail_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        stage = findViewById(R.id.forwardingDetailStage);
        statusMessage = findViewById(R.id.forwardingDetailStatusMessage);
        network = findViewById(R.id.forwardingDetailNetwork);
        created = findViewById(R.id.forwardingDetailCreated);
        updated = findViewById(R.id.forwardingDetailUpdated);
        incomingTx = findViewById(R.id.forwardingDetailIncomingTx);
        outgoingTx = findViewById(R.id.forwardingDetailOutgoingTx);
        outgoingTxContainer = findViewById(R.id.forwardingDetailOutgoingTxContainer);
        destination = findViewById(R.id.forwardingDetailDestination);
        mode = findViewById(R.id.forwardingDetailMode);
        incomingConfirmations = findViewById(R.id.forwardingDetailIncomingConfirmations);
        outgoingConfirmations = findViewById(R.id.forwardingDetailOutgoingConfirmations);
        received = findViewById(R.id.forwardingDetailReceived);
        amount = findViewById(R.id.forwardingDetailAmount);
        fee = findViewById(R.id.forwardingDetailFee);
        feeRate = findViewById(R.id.forwardingDetailFeeRate);
        effectiveFeeRate = findViewById(R.id.forwardingDetailEffectiveFeeRate);
        vsize = findViewById(R.id.forwardingDetailVsize);
        fixedAmount = findViewById(R.id.forwardingDetailFixedAmount);
        fixedAmountRow = findViewById(R.id.forwardingDetailFixedAmountRow);

        Button copyIncoming = findViewById(R.id.forwardingDetailCopyIncoming);
        Button copyOutgoing = findViewById(R.id.forwardingDetailCopyOutgoing);
        copyIncoming.setOnClickListener(v -> copyTxid(incomingTxId));
        copyOutgoing.setOnClickListener(v -> copyTxid(outgoingTx.getText().toString()));
        incomingTx.setTextIsSelectable(true);
        outgoingTx.setTextIsSelectable(true);
        destination.setTextIsSelectable(true);

        preferencesListener = (prefs, key) -> {
            if (ACTIVITY_LEDGER.equals(key)) runOnUiThread(this::renderRecord);
        };
        renderRecord();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences != null && preferencesListener != null) {
            preferences.registerOnSharedPreferenceChangeListener(preferencesListener);
            renderRecord();
        }
    }

    @Override
    protected void onPause() {
        if (preferences != null && preferencesListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener);
        }
        super.onPause();
    }

    private void renderRecord() {
        if (preferences == null || incomingTxId == null) return;
        JSONObject record = findRecord(preferences.getString(ACTIVITY_LEDGER, "[]"), incomingTxId);
        if (record == null) {
            stage.setText(R.string.forwarding_activity_history_empty);
            statusMessage.setText(R.string.forwarding_detail_record_unavailable);
            return;
        }

        String stageCode = record.optString("stage", "");
        stage.setText(stageLabel(stageCode));
        String storedStatus = record.optString("status", "").trim();
        if (storedStatus.isEmpty()) {
            statusMessage.setText(stageDetail(stageCode));
        } else {
            statusMessage.setText(storedStatus);
        }

        String networkValue = prefsName.substring("forwarding_service_".length()).trim();
        if (networkValue.isEmpty()) networkValue = record.optString("network", "").trim();
        if (networkValue.isEmpty()) networkValue = getString(R.string.forwarding_value_unavailable);
        network.setText(getString(R.string.forwarding_detail_network_value, networkValue));
        created.setText(getString(R.string.forwarding_detail_created_value,
                formatTimestamp(record.optLong("created", record.optLong("updated", 0L)))));
        updated.setText(getString(R.string.forwarding_detail_updated_value,
                formatTimestamp(record.optLong("updated", 0L))));

        String incoming = record.optString("incoming", incomingTxId);
        incomingTx.setText(incoming);
        String outgoing = record.optString("outgoing", "");
        outgoingTxContainer.setVisibility(outgoing.isEmpty() ? View.GONE : View.VISIBLE);
        outgoingTx.setText(outgoing);

        String target = record.optString("destination", "").trim();
        destination.setText(target.isEmpty() ? getString(R.string.forwarding_value_unavailable) : target);
        String modeCode = record.optString("mode", "sweep");
        mode.setText(modeLabel(modeCode));
        fixedAmountRow.setVisibility("fixed".equals(modeCode) ? View.VISIBLE : View.GONE);

        long receivedSats = record.optLong("received", 0L);
        long amountSats = record.optLong("amount", 0L);
        long feeSats = record.optLong("fee", -1L);
        long configuredFixedSats = record.optLong("fixedAmount", 0L);
        fixedAmount.setText(configuredFixedSats > 0L ? formatAmount(configuredFixedSats)
                : getString(R.string.forwarding_value_unavailable));
        received.setText(receivedSats > 0L ? formatAmount(receivedSats)
                : getString(R.string.forwarding_value_unavailable));
        amount.setText(amountSats > 0L ? formatAmount(amountSats)
                : getString(R.string.forwarding_value_unavailable));
        fee.setText(feeSats > 0L ? formatAmount(feeSats)
                : getString(R.string.forwarding_value_unavailable));

        int current = record.optInt("currentConfirmations", -1);
        int required = record.optInt("requiredConfirmations", -1);
        incomingConfirmations.setText(current >= 0 && required > 0
                ? getString(R.string.forwarding_activity_confirmation_count, current, required)
                : getString(R.string.forwarding_value_unavailable));
        int outgoingCurrent = record.optInt("outgoingConfirmations", -1);
        outgoingConfirmations.setText(outgoingCurrent >= 0
                ? getString(R.string.forwarding_detail_outgoing_confirmation_value, outgoingCurrent)
                : getString(R.string.forwarding_value_unavailable));

        int rateValue = record.optInt("rate", 0);
        int effectiveRateValue = record.optInt("effectiveRate", 0);
        int vsizeValue = record.optInt("vsize", 0);
        feeRate.setText(rateValue > 0
                ? getString(R.string.forwarding_detail_rate_value, rateValue)
                : getString(R.string.forwarding_value_unavailable));
        effectiveFeeRate.setText(effectiveRateValue > 0
                ? getString(R.string.forwarding_detail_effective_rate_value, effectiveRateValue)
                : getString(R.string.forwarding_value_unavailable));
        vsize.setText(vsizeValue > 0
                ? getString(R.string.forwarding_detail_vsize_value, vsizeValue)
                : getString(R.string.forwarding_value_unavailable));
    }

    private JSONObject findRecord(String raw, String wantedIncoming) {
        try {
            JSONArray entries = new JSONArray(raw);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && wantedIncoming.equals(entry.optString("incoming", ""))) {
                    return entry;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private int stageLabel(String value) {
        switch (value) {
            case "waiting": return R.string.forwarding_activity_waiting_title;
            case "preparing": return R.string.forwarding_activity_preparing_title;
            case "broadcasting": return R.string.forwarding_activity_broadcasting_title;
            case "relayed": return R.string.forwarding_activity_relayed_title;
            case "confirmed": return R.string.forwarding_activity_confirmed_title;
            case "failed": return R.string.forwarding_activity_failed_title;
            case "disabled": return R.string.forwarding_activity_disabled_title;
            default: return R.string.forwarding_activity_idle_title;
        }
    }

    private int stageDetail(String value) {
        switch (value) {
            case "waiting": return R.string.forwarding_activity_waiting_detail;
            case "preparing": return R.string.forwarding_activity_preparing_detail;
            case "broadcasting": return R.string.forwarding_activity_broadcasting_detail;
            case "relayed": return R.string.forwarding_activity_relayed_detail;
            case "confirmed": return R.string.forwarding_activity_confirmed_detail;
            case "failed": return R.string.forwarding_activity_failed_detail;
            case "disabled": return R.string.forwarding_activity_disabled_detail;
            default: return R.string.forwarding_detail_record_unavailable;
        }
    }

    private int modeLabel(String value) {
        switch (value) {
            case "fixed": return R.string.forwarding_mode_fixed;
            case "wallet_sweep": return R.string.forwarding_mode_wallet_sweep;
            default: return R.string.forwarding_mode_sweep;
        }
    }

    private String formatAmount(long sats) {
        return MoneyFormatter.format(this, Coin.valueOf(Math.max(0L, sats)));
    }

    private String formatTimestamp(long millis) {
        if (millis <= 0L) return getString(R.string.forwarding_value_unavailable);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    private void copyTxid(String txid) {
        if (txid == null || txid.trim().isEmpty()) {
            Toast.makeText(this, R.string.forwarding_no_txid_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.bitcoin_transaction_id_clip_label), txid));
            Toast.makeText(this, R.string.forwarding_txid_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
