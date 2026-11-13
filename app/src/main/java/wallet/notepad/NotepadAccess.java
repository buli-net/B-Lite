package wallet.notepad;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.bitcoinj.wallet.Wallet;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.security.WalletSecurity;

final class NotepadAccess {

    interface Callback {
        void onAuthorized();
    }

    private static final long SESSION_DURATION_MS = 5L * 60L * 1000L;
    private static final ExecutorService AUTH_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private static WeakReference<Wallet> authorizedWallet = new WeakReference<>(null);
    private static long authorizedUntil;

    private NotepadAccess() {
    }

    static void request(Activity activity, Callback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            Toast.makeText(activity, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }

        if (!WalletSecurity.isEncrypted(wallet)
                || WalletSecurity.isSessionValid(wallet)
                || hasTemporaryAuthorization(wallet)) {
            callback.onAuthorized();
            return;
        }

        View passwordView = activity.getLayoutInflater().inflate(
                R.layout.dialog_password, null);
        EditText passwordInput = passwordView.findViewById(
                R.id.dialogPasswordInput);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.notepad_unlock_title)
                .setMessage(R.string.notepad_unlock_message)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, (ignored, which) -> activity.finish())
                .setPositiveButton(R.string.notepad_unlock_action, null)
                .setCancelable(false)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String password = passwordInput.getText().toString();
                    if (password.isEmpty()) {
                        passwordInput.setError(activity.getString(R.string.password_required));
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    AUTH_EXECUTOR.execute(() -> {
                        boolean valid = false;
                        try {
                            valid = WalletSecurity.verifyPassword(wallet, password) != null;
                        } catch (RuntimeException ignoredError) {
                            valid = false;
                        }
                        boolean authorized = valid;
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                dialog.dismiss();
                                return;
                            }
                            if (!authorized || MainActivityPresenter.getActiveWallet() != wallet) {
                                passwordInput.setError(activity.getString(
                                        authorized ? R.string.notepad_wallet_changed
                                                : R.string.wrong_password));
                                passwordInput.setText("");
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                return;
                            }
                            grantTemporaryAuthorization(wallet);
                            dialog.dismiss();
                            callback.onAuthorized();
                        });
                    });
                }));
        dialog.show();
    }

    private static synchronized boolean hasTemporaryAuthorization(Wallet wallet) {
        return authorizedWallet.get() == wallet
                && SystemClock.elapsedRealtime() < authorizedUntil;
    }

    private static synchronized void grantTemporaryAuthorization(Wallet wallet) {
        authorizedWallet = new WeakReference<>(wallet);
        authorizedUntil = SystemClock.elapsedRealtime() + SESSION_DURATION_MS;
    }
}
