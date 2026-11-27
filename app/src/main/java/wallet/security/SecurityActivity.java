package wallet.security;

import wallet.ui.LocalizedErrors;

import wallet.ui.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.base.Coin;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.watchonly.AccountXpubExporter;

public class SecurityActivity extends BaseActivity {

    private EditText passwordInput;
    private EditText passwordConfirmationInput;
    private TextView statusView;
    private Button setPasswordButton;
    private Button unlockButton;
    private Button lockButton;
    private Button removePasswordButton;
    private EditText keyRotationDateInput;
    private TextView keyRotationStatusView;
    private Button setKeyRotationButton;
    private Button runKeyRotationButton;
    private Button clearKeyRotationButton;
    private Button exportAccountXpubButton;
    private boolean operationBusy;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                refreshStatus();
                statusHandler.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_security);

        Toolbar toolbar = findViewById(R.id.toolbar_security);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.security_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        passwordInput = findViewById(R.id.etSecurityPassword);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordConfirmationInput = findViewById(R.id.etSecurityPasswordConfirm);
        passwordConfirmationInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        statusView = findViewById(R.id.tvSecurityStatus);
        setPasswordButton = findViewById(R.id.btnSetPassword);
        unlockButton = findViewById(R.id.btnUnlockWallet);
        lockButton = findViewById(R.id.btnLockWallet);
        removePasswordButton = findViewById(R.id.btnRemovePassword);
        keyRotationDateInput = findViewById(R.id.etKeyRotationDate);
        keyRotationStatusView = findViewById(R.id.tvKeyRotationStatus);
        setKeyRotationButton = findViewById(R.id.btnSetKeyRotation);
        runKeyRotationButton = findViewById(R.id.btnRunKeyRotation);
        clearKeyRotationButton = findViewById(R.id.btnClearKeyRotation);
        exportAccountXpubButton = findViewById(R.id.btnExportAccountXpub);

        setKeyRotationButton.setOnClickListener(v -> setKeyRotationTime());
        runKeyRotationButton.setOnClickListener(v -> previewKeyRotation());
        clearKeyRotationButton.setOnClickListener(v -> clearKeyRotationTime());
        exportAccountXpubButton.setOnClickListener(v -> exportAccountXpub());

        setPasswordButton.setOnClickListener(v -> setPassword());
        unlockButton.setOnClickListener(v -> unlockWallet());
        lockButton.setOnClickListener(v -> lockWallet());
        removePasswordButton.setOnClickListener(v -> removePassword());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        statusHandler.removeCallbacks(statusRefreshTask);
        statusHandler.post(statusRefreshTask);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(statusRefreshTask);
        super.onPause();
    }

    private void refreshStatus() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            statusView.setText(R.string.wallet_not_ready);
            passwordInput.setEnabled(false);
            passwordConfirmationInput.setEnabled(false);
            passwordConfirmationInput.setVisibility(View.GONE);
            setPasswordButton.setVisibility(View.GONE);
            unlockButton.setVisibility(View.GONE);
            lockButton.setVisibility(View.GONE);
            removePasswordButton.setVisibility(View.GONE);
            setPasswordButton.setEnabled(false);
            unlockButton.setEnabled(false);
            lockButton.setEnabled(false);
            removePasswordButton.setEnabled(false);
            keyRotationDateInput.setEnabled(false);
            setKeyRotationButton.setEnabled(false);
            runKeyRotationButton.setEnabled(false);
            clearKeyRotationButton.setEnabled(false);
            exportAccountXpubButton.setEnabled(false);
            keyRotationStatusView.setText(R.string.wallet_not_ready);
            return;
        }

        boolean encrypted = WalletSecurity.isEncrypted(wallet);
        boolean unlocked = WalletSecurity.isSessionValid(wallet);
        statusView.setText(
                encrypted
                        ? (unlocked
                        ? R.string.wallet_status_unlocked
                        : R.string.wallet_status_locked)
                        : R.string.wallet_status_unencrypted);

        setPasswordButton.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        passwordConfirmationInput.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        unlockButton.setVisibility(encrypted && !unlocked ? View.VISIBLE : View.GONE);
        lockButton.setVisibility(encrypted && unlocked ? View.VISIBLE : View.GONE);
        removePasswordButton.setVisibility(encrypted && unlocked ? View.VISIBLE : View.GONE);
        passwordInput.setEnabled(!operationBusy);
        passwordConfirmationInput.setEnabled(!encrypted && !operationBusy);
        setPasswordButton.setEnabled(!operationBusy);
        unlockButton.setEnabled(!operationBusy);
        lockButton.setEnabled(!operationBusy);
        removePasswordButton.setEnabled(!operationBusy);
        keyRotationDateInput.setEnabled(!operationBusy);
        setKeyRotationButton.setEnabled(!operationBusy);
        runKeyRotationButton.setEnabled(!operationBusy && wallet.keyRotationTime().isPresent());
        clearKeyRotationButton.setEnabled(!operationBusy && wallet.keyRotationTime().isPresent());
        exportAccountXpubButton.setEnabled(!operationBusy);
        java.util.Optional<Instant> rotationTime = wallet.keyRotationTime();
        if (rotationTime.isPresent()) {
            keyRotationStatusView.setText(getString(R.string.key_rotation_status_active,
                    rotationTime.get().atZone(ZoneOffset.UTC).toLocalDate().toString()));
        } else {
            keyRotationStatusView.setText(R.string.key_rotation_status_inactive);
        }
    }

    private void setKeyRotationTime() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }
        final Instant rotationTime;
        try {
            String rawDate = keyRotationDateInput.getText().toString().trim();
            LocalDate date = LocalDate.parse(rawDate);
            rotationTime = date.atStartOfDay().toInstant(ZoneOffset.UTC);
            if (rotationTime.isAfter(Instant.now())) {
                showMessage(R.string.key_rotation_date_future);
                return;
            }
        } catch (Exception invalid) {
            keyRotationDateInput.setError(getString(R.string.key_rotation_date_invalid));
            return;
        }
        keyRotationDateInput.setError(null);
        new AlertDialog.Builder(this)
                .setTitle(R.string.key_rotation_confirm_title)
                .setMessage(R.string.key_rotation_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.key_rotation_set, (dialog, which) -> {
                    try {
                        wallet.setKeyRotationTime(rotationTime);
                        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                        if (presenter != null) presenter.saveWalletNow();
                        refreshStatus();
                        Toast.makeText(this, R.string.key_rotation_saved, Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        showError(error);
                    }
                }).show();
    }

    private void clearKeyRotationTime() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.key_rotation_clear_title)
                .setMessage(R.string.key_rotation_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.key_rotation_clear, (dialog, which) -> {
                    try {
                        wallet.setKeyRotationTime((java.time.Instant) null);
                        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                        if (presenter != null) presenter.saveWalletNow();
                        refreshStatus();
                        Toast.makeText(this, R.string.key_rotation_cleared, Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        showError(error);
                    }
                }).show();
    }

    private void previewKeyRotation() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }
        if (!wallet.keyRotationTime().isPresent()) {
            showMessage(R.string.key_rotation_not_configured);
            return;
        }
        if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
            showMessage(R.string.wallet_locked);
            passwordInput.requestFocus();
            return;
        }
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null || !presenter.isWalletReady()) {
            showMessage(R.string.wallet_not_ready);
            return;
        }
        setBusy(true);
        new Thread(() -> {
            try {
                List<Transaction> transactions = wallet.doMaintenance(
                        WalletSecurity.getSessionKey(), false).get(2, TimeUnit.MINUTES);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (transactions == null || transactions.isEmpty()) {
                        Toast.makeText(this, R.string.key_rotation_no_transactions, Toast.LENGTH_LONG).show();
                        return;
                    }
                    StringBuilder details = new StringBuilder();
                    long totalFees = 0L;
                    for (int i = 0; i < transactions.size(); i++) {
                        Transaction tx = transactions.get(i);
                        Coin fee = tx.getFee();
                        if (fee != null) totalFees += fee.value;
                        details.append(getString(R.string.key_rotation_preview_item, i + 1,
                                tx.getTxId().toString().substring(0, 12)));
                        if (fee != null) details.append(" — ").append(fee.toFriendlyString());
                        if (i + 1 < transactions.size()) details.append('\n');
                    }
                    details.append("\n\n").append(getString(R.string.key_rotation_preview_fee,
                            Coin.valueOf(totalFees).toFriendlyString()));
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.key_rotation_preview_title)
                            .setMessage(getString(R.string.key_rotation_preview_message,
                                    transactions.size(), details.toString()))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.key_rotation_broadcast, (dialog, which) -> broadcastKeyRotation())
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-key-rotation-preview").start();
    }

    private void broadcastKeyRotation() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }
        if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
            showMessage(R.string.wallet_locked);
            return;
        }
        setBusy(true);
        new Thread(() -> {
            try {
                List<Transaction> transactions = wallet.doMaintenance(
                        WalletSecurity.getSessionKey(), true).get(5, TimeUnit.MINUTES);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) presenter.saveWalletNow();
                runOnUiThread(() -> {
                    setBusy(false);
                    refreshStatus();
                    Toast.makeText(this, getString(R.string.key_rotation_broadcast_result,
                            transactions == null ? 0 : transactions.size()), Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-key-rotation-broadcast").start();
    }

    private void exportAccountXpub() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }
        try {
            AccountXpubExporter.ExportedAccountKey exported =
                    AccountXpubExporter.export(wallet);
            String details = getString(R.string.account_xpub_export_details,
                    exported.getDerivationPath(), exported.getEncodedKey());
            new AlertDialog.Builder(this)
                    .setTitle(R.string.account_xpub_export_title)
                    .setMessage(details)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.account_xpub_copy, (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                    getString(R.string.account_xpub_clip_label),
                                    exported.getEncodedKey()));
                            Toast.makeText(this, R.string.account_xpub_copied,
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Exception error) {
            showError(error);
        }
    }

    private Wallet getWallet() {
        return MainActivityPresenter.getActiveWallet();
    }

    private String password() {
        return passwordInput.getText().toString();
    }

    private boolean validPassword(String password) {
        if (password.length() < 8) {
            Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void setPassword() {
        String password = password();
        if (!validPassword(password)) {
            return;
        }
        String confirmation = passwordConfirmationInput.getText().toString();
        if (!password.equals(confirmation)) {
            passwordConfirmationInput.setError(getString(R.string.password_confirmation_mismatch));
            Toast.makeText(this, R.string.password_confirmation_mismatch, Toast.LENGTH_LONG).show();
            return;
        }
        passwordConfirmationInput.setError(null);

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                WalletSecurity.encrypt(wallet, password);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                }
                runOnUiThread(() -> {
                    passwordInput.setText("");
                    passwordConfirmationInput.setText("");
                    setBusy(false);
                    refreshStatus();
                    Toast.makeText(this, R.string.password_set_success, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                WalletSecurity.clearSessionKey();
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-encrypt").start();
    }

    private void unlockWallet() {
        String password = password();
        if (password.isEmpty()) {
            showMessage(R.string.password_required);
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                boolean unlocked = WalletSecurity.unlock(wallet, password);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!unlocked) {
                        Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_LONG).show();
                        return;
                    }
                    passwordInput.setText("");
                    passwordConfirmationInput.setText("");
                    refreshStatus();
                    Toast.makeText(this, R.string.wallet_unlocked, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-unlock").start();
    }

    private void lockWallet() {
        String enteredPassword = password();
        if (enteredPassword.isEmpty()) {
            showMessage(R.string.password_required);
            passwordInput.requestFocus();
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                boolean valid = WalletSecurity.unlock(wallet, enteredPassword);
                if (valid) {
                    WalletSecurity.clearSessionKey();
                }
                runOnUiThread(() -> {
                    setBusy(false);
                    passwordInput.setText("");
                    passwordConfirmationInput.setText("");
                    if (!valid) {
                        showMessage(R.string.wrong_password);
                        return;
                    }
                    refreshStatus();
                    Toast.makeText(this, R.string.wallet_locked_success, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-lock-verify").start();
    }

    private void removePassword() {
        String enteredPassword = password();
        if (enteredPassword.isEmpty()) {
            showMessage(R.string.password_required);
            passwordInput.requestFocus();
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_password_title)
                .setMessage(R.string.remove_password_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove_password_action, (dialog, which) -> {
                    setBusy(true);
                    new Thread(() -> {
                        try {
                            boolean valid = WalletSecurity.unlock(wallet, enteredPassword);
                            if (!valid) {
                                runOnUiThread(() -> {
                                    setBusy(false);
                                    passwordInput.setText("");
                                    showMessage(R.string.wrong_password);
                                });
                                return;
                            }

                            WalletSecurity.decrypt(wallet);
                            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                            if (presenter != null) {
                                presenter.saveWalletNow();
                            }
                            runOnUiThread(() -> {
                                setBusy(false);
                                passwordInput.setText("");
                                passwordConfirmationInput.setText("");
                                refreshStatus();
                                Toast.makeText(
                                        this,
                                        R.string.password_removed,
                                        Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                setBusy(false);
                                showError(error);
                            });
                        }
                    }, "wallet-decrypt").start();
                })
                .show();
    }

    private void setBusy(boolean busy) {
        operationBusy = busy;
        passwordInput.setEnabled(!busy);
        passwordConfirmationInput.setEnabled(!busy);
        setPasswordButton.setEnabled(!busy);
        unlockButton.setEnabled(!busy);
        lockButton.setEnabled(!busy);
        removePasswordButton.setEnabled(!busy);
        keyRotationDateInput.setEnabled(!busy);
        setKeyRotationButton.setEnabled(!busy);
        runKeyRotationButton.setEnabled(!busy);
        clearKeyRotationButton.setEnabled(!busy);
        exportAccountXpubButton.setEnabled(!busy);
    }

    private void showMessage(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }

    private void showError(Exception error) {
        if (error instanceof WalletSecurity.WalletSecurityException) {
            int messageId;
            switch (((WalletSecurity.WalletSecurityException) error).getCode()) {
                case WalletSecurity.ERROR_ALREADY_ENCRYPTED:
                    messageId = R.string.wallet_already_encrypted;
                    break;
                case WalletSecurity.ERROR_LOCKED:
                    messageId = R.string.wallet_locked;
                    break;
                case WalletSecurity.ERROR_UNSUPPORTED_ENCRYPTION:
                    messageId = R.string.security_unsupported_wallet_encryption;
                    break;
                case WalletSecurity.ERROR_NO_DETERMINISTIC_SEED:
                    messageId = R.string.recovery_phrase_unavailable;
                    break;
                default:
                    messageId = R.string.security_operation_failed;
                    Toast.makeText(this, getString(messageId, error.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
                    return;
            }
            Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(
                this,
                getString(
                        R.string.security_operation_failed,
                        LocalizedErrors.resolve(this, error)),
                Toast.LENGTH_LONG).show();
    }
}
