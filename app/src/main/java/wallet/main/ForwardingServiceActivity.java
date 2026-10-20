package wallet.main;

import android.os.Bundle;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;

/** Configuration screen for opt-in automatic forwarding from the active wallet. */
public class ForwardingServiceActivity extends BaseActivity {
    private static final String PREFS = "forwarding_service";
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
        feePreview = findViewById(R.id.forwardingFeePreview);
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
        getSharedPreferences(PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesListener);
        if (status != null) updateState();
    }

    @Override
    protected void onPause() {
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesListener);
        super.onPause();
    }

    private void loadSettings() {
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        destination.setText(preferences.getString(DESTINATION, ""));
        confirmations.setText(String.valueOf(preferences.getInt(CONFIRMATIONS, 1)));
        String mode = preferences.getString(MODE, "sweep");
        modeChoice.check("fixed".equals(mode) ? R.id.forwardingModeFixed : R.id.forwardingModeSweep);
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
                throw new NumberFormatException("confirmation count out of range");
            }
        } catch (NumberFormatException error) {
            confirmations.setError(getString(R.string.forwarding_invalid_confirmations));
            Toast.makeText(this, R.string.forwarding_invalid_confirmations, Toast.LENGTH_LONG).show();
            return false;
        }

        String selectedMode = modeChoice.getCheckedRadioButtonId() == R.id.forwardingModeFixed
                ? "fixed" : "sweep";
        long fixedAmountSats = 0L;
        if ("fixed".equals(selectedMode)) {
            try {
                Coin amount = MoneyFormatter.parse(this, fixedAmount.getText().toString());
                if (amount.value <= 0L) throw new IllegalArgumentException("amount must be positive");
                fixedAmountSats = amount.value;
            } catch (Exception error) {
                fixedAmount.setError(getString(R.string.forwarding_invalid_fixed_amount));
                Toast.makeText(this, R.string.forwarding_invalid_fixed_amount, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        int feeRateSatVb = feeRate.getProgress() + 1;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
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
        fixedAmountContainer.setVisibility(modeChoice.getCheckedRadioButtonId() == R.id.forwardingModeFixed
                ? View.VISIBLE : View.GONE);
    }

    private void updateFeeRateLabel() {
        if (feeRateLabel != null && feeRate != null) {
            feeRateLabel.setText(getString(R.string.forwarding_fee_rate_value, feeRate.getProgress() + 1));
        }
    }

    private void toggleForwarding() {
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (preferences.getBoolean(ENABLED, false)) {
            preferences.edit()
                    .putBoolean(ENABLED, false)
                    .putString(STATUS, "")
                    .apply();
            updateState();
            return;
        }

        if (MainActivityPresenter.getActiveWallet() == null) {
            Toast.makeText(this, R.string.forwarding_wallet_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!saveSettings()) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.forwarding_confirm_title)
                .setMessage(R.string.forwarding_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forwarding_enable, (dialog, which) -> {
                    preferences.edit()
                            .putBoolean(ENABLED, true)
                            .putString(STATUS, "")
                            .apply();
                    updateState();
                })
                .show();
    }

    private void updateState() {
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = preferences.getBoolean(ENABLED, false);
        String lastStatus = preferences.getString(STATUS, "");
        BitcoinNetwork currentNetwork = NetworkConfig.get(this);
        network.setText(getString(R.string.forwarding_network_value, currentNetwork.toString()));
        status.setText((enabled
                ? getString(R.string.forwarding_status_enabled)
                : getString(R.string.forwarding_status_off))
                + (lastStatus.isEmpty() ? "" : "\n" + lastStatus));
        long feeSats = preferences.getLong(LAST_FEE_SATS, -1L);
        long amountSats = preferences.getLong(LAST_AMOUNT_SATS, -1L);
        int rate = preferences.getInt(LAST_RATE_SAT_VB, -1);
        int effectiveRate = preferences.getInt(LAST_EFFECTIVE_RATE_SAT_VB, -1);
        int vsize = preferences.getInt(LAST_VSIZE, 0);
        String incomingTx = preferences.getString(LAST_INCOMING_TX, "");
        if (feeSats >= 0L && amountSats > 0L && rate > 0) {
            feePreview.setText(getString(R.string.forwarding_preview_values,
                    MoneyFormatter.format(this, Coin.valueOf(feeSats)),
                    rate,
                    effectiveRate,
                    MoneyFormatter.format(this, Coin.valueOf(amountSats)),
                    vsize,
                    incomingTx));
        } else {
            feePreview.setText(R.string.forwarding_preview_empty);
        }
        toggle.setText(enabled ? R.string.forwarding_disable : R.string.forwarding_enable);
    }

}
