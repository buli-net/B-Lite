package wallet.tools;

import wallet.main.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.base.Coin;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.View;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import androidx.appcompat.app.AlertDialog;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.LegacyAddress;
import wallet.main.WalletSelection;
import wallet.main.ImportedWalletStore;
import wallet.qr.ReceiveQrDialog;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.crypto.ECKey;
import java.util.Collections;
import wallet.Constants;
import wallet.security.WalletSecurity;

public final class WalletImportWifActivity extends BaseActivity {
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (!TextUtils.isEmpty(result.getContents())) {
                    EditText input = findViewById(R.id.privateKeyInput);
                    input.setText(result.getContents().trim());
                    input.setSelection(input.length());
                }
            });

    private Wallet getWallet() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        return kit == null ? null : kit.wallet();
    }

    private NetworkParameters getParameters() {
        return MainActivityPresenter.getActiveParameters();
    }
    private void show(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_wallet_import_wif);
        Toolbar toolbar = findViewById(R.id.toolbar_wallet_import_wif); setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) { getSupportActionBar().setTitle(R.string.wallet_import_wif_title); getSupportActionBar().setDisplayHomeAsUpEnabled(true); }
        toolbar.setNavigationOnClickListener(v -> finish());
        EditText input = findViewById(R.id.privateKeyInput); Button importButton = findViewById(R.id.importPrivateKeyButton);
        Button scanWifQrButton = findViewById(R.id.scanWifQrButton);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        importButton.setOnClickListener(v -> importPrivateKey(input, importButton));
        scanWifQrButton.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions()
                    .setPrompt(getString(R.string.scan_wif_qr));
            barcodeLauncher.launch(options);
        });
        findViewById(R.id.wifInfoButton).setOnClickListener(v -> showWifInfo());

        renderImportedWallets();
    }

    @Override protected void onResume() {
        super.onResume();
        renderImportedWallets();
    }

    private void importPrivateKey(EditText privateKeyInput, Button importPrivateKeyButton) {
        String encoded = privateKeyInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.private_key_required);
            return;
        }

        // BIP38 encrypted private keys use the 6P prefix. They cannot be
        // imported as ordinary WIF until the user's passphrase decrypts them.
        if (isBip38(encoded)) {
            promptForBip38Passphrase(privateKeyInput, importPrivateKeyButton, encoded);
            return;
        }

        final NetworkParameters parameters = getParameters();
        importPrivateKeyButton.setEnabled(false);
        new Thread(() -> {
            try {
                ECKey key = DumpedPrivateKey.fromBase58(
                        Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                        encoded).getKey();
                importDecodedPrivateKey(key, parameters, privateKeyInput, importPrivateKeyButton);
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

    private boolean isBip38(String value) {
        return value.length() == 58 && value.startsWith("6P");
    }

    private void promptForBip38Passphrase(EditText privateKeyInput,
                                           Button importPrivateKeyButton,
                                           String encryptedKey) {
        EditText passphraseInput = new EditText(this);
        passphraseInput.setSingleLine(true);
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphraseInput.setHint(R.string.bip38_passphrase_hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bip38_passphrase_title)
                .setMessage(R.string.bip38_passphrase_message)
                .setView(passphraseInput)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.bip38_unlock, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String passphrase = passphraseInput.getText().toString();
            if (passphrase.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }

            importPrivateKeyButton.setEnabled(false);
            dialog.dismiss();

            final NetworkParameters parameters = getParameters();
            new Thread(() -> {
                try {
                    BIP38PrivateKey bip38 = BIP38PrivateKey.fromBase58(
                            Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                            encryptedKey);
                    ECKey key = bip38.decrypt(passphrase);
                    // Do not retain the passphrase. It is only used during this
                    // decryption operation and is never written to preferences/files.
                    importDecodedPrivateKey(key, parameters, privateKeyInput, importPrivateKeyButton);
                } catch (BIP38PrivateKey.BadPassphraseException error) {
                    runOnUiThread(() -> {
                        importPrivateKeyButton.setEnabled(true);
                        Toast.makeText(this, R.string.bip38_wrong_passphrase, Toast.LENGTH_LONG).show();
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
            }, "wallet-bip38-decrypt").start();
        }));
        dialog.show();
    }

    private void importDecodedPrivateKey(ECKey key,
                                         NetworkParameters parameters,
                                         EditText privateKeyInput,
                                         Button importPrivateKeyButton) {
        try {
            NetworkParameters addressParameters = parameters;
            if (addressParameters == null) {
                addressParameters = Constants.IS_PRODUCTION
                        ? org.bitcoinj.params.MainNetParams.get()
                        : org.bitcoinj.params.TestNet3Params.get();
            }
            final String importedAddress = LegacyAddress.fromKey(addressParameters, key).toString();

            // Like Watch-only, persist and render the management entry before
            // touching WalletAppKit. This keeps this screen responsive.
            runOnUiThread(() -> {
                ImportedWalletStore.register(this, importedAddress);
                int index = ImportedWalletStore.getAddresses(this).indexOf(importedAddress) + 1;
                if (index < 1) index = 1;
                ImportedWalletStore.setName(this, importedAddress,
                        ImportedWalletStore.getName(this, importedAddress, index));
                privateKeyInput.setText("");
                renderImportedWallets();
            });

            Wallet wallet = getWalletSafely();
            if (wallet == null) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
                });
                return;
            }
            if (WalletSecurity.isEncrypted(wallet) && WalletSecurity.getSessionKey() == null) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, R.string.wallet_locked, Toast.LENGTH_LONG).show();
                });
                return;
            }

            int added = WalletSecurity.isEncrypted(wallet)
                    ? wallet.importKeysAndEncrypt(Collections.singletonList(key), WalletSecurity.getSessionKey())
                    : (wallet.importKey(key) ? 1 : 0);

            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) {
                try {
                    presenter.saveWalletNow();
                } catch (Exception ignored) {
                    // The management entry is already persisted independently.
                }
            }

            final int result = added;
            runOnUiThread(() -> {
                importPrivateKeyButton.setEnabled(true);
                new AlertDialog.Builder(this)
                        .setTitle(result == 0
                                ? R.string.private_key_already_present
                                : R.string.private_key_imported)
                        .setMessage(getString(R.string.private_key_import_address, importedAddress))
                        .setPositiveButton(R.string.close, null)
                        .show();
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
    }

    /**
     * WalletAppKit can be between stop/start during initial sync or rescan.
     * Never let that transient state crash the Import screen.
     */
    private Wallet getWalletSafely() {
        try {
            WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
            if (kit == null) return null;
            return kit.wallet();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }
    private void renderImportedWallets() {
        LinearLayout container = findViewById(R.id.importedWalletsContainer);
        TextView empty = findViewById(R.id.importedWalletsEmpty);
        if (container == null || empty == null) return;

        // IMPORTANT: this method is UI-only. Do not call WalletAppKit.wallet(),
        // getImportedKeys(), getUnspents(), or any bitcoinj balance calculation here.
        // Those operations were causing the Import screen to freeze.
        container.removeAllViews();
        java.util.List<String> addresses = ImportedWalletStore.getAddresses(this);
        empty.setVisibility(addresses.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < addresses.size(); i++) {
            addImportedWalletRow(container, addresses.get(i), i + 1);
        }
    }

    private void addImportedWalletRow(LinearLayout container, String address, int index) {
        View row = getLayoutInflater().inflate(R.layout.item_imported_wif_wallet, container, false);
        TextView name = row.findViewById(R.id.importedWalletName);
        TextView addressView = row.findViewById(R.id.importedWalletAddress);
        TextView balance = row.findViewById(R.id.importedWalletBalance);
        Button select = row.findViewById(R.id.importedWalletSelect);
        Button manage = row.findViewById(R.id.importedWalletManage);

        name.setText(ImportedWalletStore.getName(this, address, index));
        addressView.setText(address);
        balance.setText(getString(R.string.imported_wallet_balance, Coin.ZERO.toFriendlyString()));

        boolean selected = address.equals(WalletSelection.getSelectedImportedAddress(this));
        select.setText(selected ? R.string.imported_wallet_selected : R.string.imported_wallet_select);
        select.setEnabled(!selected);
        select.setOnClickListener(v -> {
            WalletSelection.selectImportedAddress(this, address);
            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) presenter.refresh();
            renderImportedWallets();
        });
        manage.setOnClickListener(v -> showImportedWalletManager(address));

        // The row is inflated with the container as parent but must still be
        // explicitly attached. Without this call the empty-state text hides
        // after import, while the wallet row remains invisible.
        container.addView(row);
    }

    private void showImportedWalletManager(String address) {
        int index = ImportedWalletStore.getAddresses(this).indexOf(address) + 1;
        String currentName = ImportedWalletStore.getName(this, address, Math.max(1, index));

        String[] actions = new String[] {
                getString(R.string.imported_wallet_show_address),
                getString(R.string.imported_wallet_show_qr),
                getString(R.string.imported_wallet_rename),
                getString(R.string.imported_wallet_remove)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.imported_wallet_manage_title) + "\n" + currentName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showImportedAddress(address);
                    else if (which == 1) ReceiveQrDialog.show(this, address);
                    else if (which == 2) renameImportedWallet(address, currentName);
                    else if (which == 3) confirmRemoveImportedWallet(address, currentName);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showImportedAddress(String address) {
        TextView content = new TextView(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        content.setText(address);
        content.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_address_title)
                .setView(content)
                .setPositiveButton(R.string.imported_wallet_copy_address, (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.imported_wallet_clipboard_label), address));
                        show(R.string.imported_wallet_address_copied);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void renameImportedWallet(String address, String currentName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelection(input.length());
        input.setHint(R.string.imported_wallet_name_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_rename_title)
                .setView(input)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.imported_wallet_rename, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        ImportedWalletStore.setName(this, address, value);
                        renderImportedWallets();
                        show(R.string.imported_wallet_renamed);
                    }
                })
                .show();
    }

    private void confirmRemoveImportedWallet(String address, String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_remove_title)
                .setMessage(R.string.imported_wallet_remove_message)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.imported_wallet_remove, (d, w) -> removeImportedWallet(address))
                .show();
    }

    private void removeImportedWallet(String address) {
        Wallet wallet = getWallet();
        if (wallet == null) return;
        try {
            org.bitcoinj.crypto.ECKey key = WalletSelection.findImportedKey(wallet, address);
            if (key == null) throw new IllegalStateException(getString(R.string.imported_wallet_not_found));
            wallet.removeKey(key);
            ImportedWalletStore.remove(this, address);
            if (address.equals(WalletSelection.getSelectedImportedAddress(this))) {
                WalletSelection.selectMain(this);
            }
            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) {
                presenter.saveWalletNow();
                presenter.refresh();
            }
            renderImportedWallets();
            show(R.string.imported_wallet_removed);
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.imported_wallet_remove_failed,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showWifInfo() {
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(R.string.wif_info_message);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.what_is_wif)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
