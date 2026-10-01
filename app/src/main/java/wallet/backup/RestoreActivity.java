/** Selects and restores a wallet backup. */

package wallet.backup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class RestoreActivity extends AppCompatActivity {

    private static final int REQUEST_OPEN_BACKUP = 8201;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_restore);

        Toolbar toolbar = findViewById(R.id.toolbar_restore);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.restore_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());

        Button chooseButton = findViewById(R.id.btnChooseRestore);
        chooseButton.setOnClickListener(v -> chooseBackup());

        EditText mnemonicInput = findViewById(R.id.etRecoveryPhrase);
        EditText birthdayInput = findViewById(R.id.etWalletBirthday);
        Button mnemonicButton = findViewById(R.id.btnRestoreMnemonic);
        mnemonicButton.setOnClickListener(v ->
                restoreFromMnemonic(mnemonicInput, birthdayInput));
    }

    private void chooseBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        startActivityForResult(intent, REQUEST_OPEN_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_OPEN_BACKUP
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        confirmRestore(data.getData());
    }

    private void restoreFromMnemonic(
            EditText mnemonicInput,
            EditText birthdayInput) {
        String mnemonic = mnemonicInput.getText().toString().trim();
        String birthday = birthdayInput.getText().toString().trim();

        if (mnemonic.isEmpty()) {
            Toast.makeText(this, R.string.recovery_phrase_required, Toast.LENGTH_LONG).show();
            return;
        }

        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Toast.makeText(this, R.string.invalid_wallet_birthday, Toast.LENGTH_LONG).show();
            return;
        }

        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            Toast.makeText(this, R.string.wallet_core_not_ready, Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.mnemonic_restore_question)
                .setMessage(R.string.mnemonic_restore_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore_action, (dialog, which) -> {
                    presenter.restoreWalletFromMnemonic(mnemonic, birthday);
                    Toast.makeText(
                            this,
                            R.string.mnemonic_restore_in_progress,
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
    }

    private void confirmRestore(Uri backupUri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_question)
                .setMessage(R.string.restore_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore_action, (dialog, which) -> {
                    MainActivityPresenter presenter =
                            MainActivityPresenter.getActivePresenter();

                    if (presenter == null) {
                        Toast.makeText(
                                this,
                                R.string.wallet_core_not_ready,
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    presenter.restoreWallet(backupUri);
                    Toast.makeText(
                            this,
                            R.string.restore_in_progress,
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
    }
}
