package wallet.browser;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Browser preferences using the same toolbar, cards and button styles as the rest of the app. */
public final class BrowserSettingsActivity extends BaseActivity {
    private EditText homePage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_browser_settings);

        Toolbar toolbar = findViewById(R.id.browserSettingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.browser_settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        homePage = findViewById(R.id.browserHomePageInput);
        homePage.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        homePage.setText(getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE)
                .getString(BrowserActivity.KEY_HOME_PAGE, ""));
        ((Button) findViewById(R.id.browserSettingsSave)).setOnClickListener(v -> saveHomePage());
    }

    private void saveHomePage() {
        String input = homePage.getText().toString().trim();
        if (input.isEmpty()) {
            getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE).edit().putString(BrowserActivity.KEY_HOME_PAGE, "").apply();
            homePage.setText("");
            Toast.makeText(this, R.string.browser_settings_saved, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String url = normalizeUrl(input);
        if (url == null) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE).edit().putString(BrowserActivity.KEY_HOME_PAGE, url).apply();
        homePage.setText(url);
        Toast.makeText(this, R.string.browser_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private String normalizeUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (!url.matches("(?i)^https?://.+")) url = "https://" + url;
        android.net.Uri parsed = android.net.Uri.parse(url);
        String scheme = parsed.getScheme();
        if (parsed.getHost() == null || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return null;
        }
        return url;
    }
}
