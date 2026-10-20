package wallet.main;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.base.BitcoinNetwork;

/** Configuration screen for opt-in automatic forwarding from the active wallet. */
public class ForwardingServiceActivity extends BaseActivity {
    private static final String PREFS = "forwarding_service";
    private static final String ENABLED = "enabled";
    private static final String DESTINATION = "destination";
    private static final String CONFIRMATIONS = "confirmations";
    private static final String STATUS = "status";

    private EditText destination;
    private EditText confirmations;
    private TextView network;
    private TextView status;
    private Button toggle;

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
        Button save = findViewById(R.id.forwardingSaveButton);

        destination.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        confirmations.setInputType(InputType.TYPE_CLASS_NUMBER);
        save.setOnClickListener(v -> saveSettings());
        toggle.setOnClickListener(v -> toggleForwarding());
        loadSettings();
    }

    private void loadSettings() {
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        destination.setText(preferences.getString(DESTINATION, ""));
        confirmations.setText(String.valueOf(preferences.getInt(CONFIRMATIONS, 1)));
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

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(DESTINATION, parsed.toString())
                .putInt(CONFIRMATIONS, confirmationCount)
                .apply();
        destination.setError(null);
        confirmations.setError(null);
        Toast.makeText(this, R.string.forwarding_saved, Toast.LENGTH_SHORT).show();
        updateState();
        return true;
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
        toggle.setText(enabled ? R.string.forwarding_disable : R.string.forwarding_enable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) updateState();
    }
}
