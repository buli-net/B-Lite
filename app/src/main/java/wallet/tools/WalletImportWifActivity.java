package wallet.tools;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;
import java.util.Collections;
import wallet.Constants;
import wallet.security.WalletSecurity;

public final class WalletImportWifActivity extends AppCompatActivity {

    private Wallet getWallet() { WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit(); return kit == null ? null : kit.wallet(); }
    private NetworkParameters getParameters() { return MainActivityPresenter.getActiveParameters(); }
    private void show(int messageId) { Toast.makeText(this, messageId, Toast.LENGTH_LONG).show(); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_wallet_import_wif);
        Toolbar toolbar = findViewById(R.id.toolbar_wallet_import_wif); setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) { getSupportActionBar().setTitle(R.string.wallet_import_wif_title); getSupportActionBar().setDisplayHomeAsUpEnabled(true); }
        toolbar.setNavigationOnClickListener(v -> finish());
        EditText input = findViewById(R.id.privateKeyInput); Button importButton = findViewById(R.id.importPrivateKeyButton);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        importButton.setOnClickListener(v -> importPrivateKey(input, importButton));
        findViewById(R.id.wifInfoButton).setOnClickListener(v -> showWifInfo());
    }

    private void importPrivateKey(EditText privateKeyInput, Button importPrivateKeyButton) {
        String encoded = privateKeyInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.private_key_required);
            return;
        }

        Wallet wallet = getWallet();
        NetworkParameters parameters = getParameters();
        if (wallet == null || parameters == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        if (WalletSecurity.isEncrypted(wallet) && WalletSecurity.getSessionKey() == null) {
            show(R.string.wallet_locked);
            return;
        }

        importPrivateKeyButton.setEnabled(false);
        new Thread(() -> {
            try {
                ECKey key = DumpedPrivateKey.fromBase58(
                        Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                        encoded).getKey();
                int added = WalletSecurity.isEncrypted(wallet)
                        ? wallet.importKeysAndEncrypt(
                                Collections.singletonList(key),
                                WalletSecurity.getSessionKey())
                        : (wallet.importKey(key) ? 1 : 0);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                    presenter.refresh();
                }
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    privateKeyInput.setText("");
                    Toast.makeText(
                            this,
                            getString(added == 0 ? R.string.private_key_already_present : R.string.private_key_imported),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, getString(
                            R.string.private_key_import_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-import-key").start();
    }
    private void showWifInfo() {
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(R.string.wif_info_message);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.what_is_wif)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
