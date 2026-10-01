package wallet.tools;

import wallet.main.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import wallet.main.MainActivityPresenter;
import wallet.main.R;

public final class WalletUtilityActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallet_utility);

        Toolbar toolbar = findViewById(R.id.toolbar_wallet_utility);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_utilities_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.walletHealthButton).setOnClickListener(v -> showWalletHealth());
        findViewById(R.id.walletDiagnosticsButton).setOnClickListener(v -> showWalletDiagnostics());
    }
    private void showWalletHealth() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(presenter.getWalletHealthReport());
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.wallet_health_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
    private void showWalletDiagnostics() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(presenter.getWalletDiagnostics());
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.wallet_diagnostics_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
