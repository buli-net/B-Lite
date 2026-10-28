package wallet.settings;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import wallet.main.R;
import wallet.offline.BluetoothSupport;
import wallet.ui.BaseActivity;

/** Merchant name and Bluetooth address used for offline payment requests. */
public final class BluetoothMerchantSettingsActivity extends BaseActivity {
    private EditText ownName;
    private EditText bluetoothAddress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_bluetooth_merchant_settings);
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.bluetooth_merchant_settings_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        ownName = findViewById(R.id.bluetoothMerchantOwnName);
        bluetoothAddress = findViewById(R.id.bluetoothMerchantAddress);
        ownName.setText(BluetoothSupport.getOwnName(this));
        String address = BluetoothSupport.getConfiguredAddress(this);
        bluetoothAddress.setText(BluetoothSupport.formatMac(address));
        Button save = findViewById(R.id.bluetoothMerchantSave);
        save.setOnClickListener(v -> save());
    }

    private void save() {
        String name = ownName.getText().toString().trim();
        if (name.length() > 32) {
            ownName.setError(getString(R.string.bluetooth_merchant_name_too_long));
            return;
        }
        String rawAddress = bluetoothAddress.getText().toString().trim();
        String normalized = TextUtils.isEmpty(rawAddress) ? null : BluetoothSupport.normalizeMac(rawAddress);
        if (!TextUtils.isEmpty(rawAddress) && normalized == null) {
            bluetoothAddress.setError(getString(R.string.bluetooth_merchant_address_invalid));
            return;
        }
        getApplicationContext().getSharedPreferences(BluetoothSupport.PREFS, MODE_PRIVATE).edit()
                .putString(BluetoothSupport.KEY_OWN_NAME, name)
                .putString(BluetoothSupport.KEY_BLUETOOTH_ADDRESS, normalized == null ? "" : normalized)
                .apply();
        Toast.makeText(this, R.string.bluetooth_merchant_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
