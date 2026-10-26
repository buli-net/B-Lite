package wallet.browser;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import wallet.main.R;

public final class BrowserSettingsActivity extends AppCompatActivity {
    private static final String PREFS = "browser_storage";
    private static final String KEY_HOME_PAGE = "home_page";
    private EditText homePage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
            int color = value.resourceId != 0 ? getColor(value.resourceId) : value.data;
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
        }
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(resolveColor(android.R.attr.colorBackground));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText(R.string.browser_settings);
        title.setTextSize(22);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);

        TextView label = new TextView(this);
        label.setText(R.string.browser_home_page_setting);
        label.setTextSize(16);
        label.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        root.addView(label);

        TextView description = new TextView(this);
        description.setText(R.string.browser_home_page_description);
        description.setTextSize(14);
        description.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(6);
        descParams.bottomMargin = dp(12);
        root.addView(description, descParams);

        homePage = new EditText(this);
        homePage.setSingleLine(true);
        homePage.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        homePage.setHint("https://www.google.com/");
        homePage.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HOME_PAGE, "https://www.google.com/"));
        root.addView(homePage, new LinearLayout.LayoutParams(-1, dp(52)));

        Button save = new Button(this);
        save.setText(R.string.browser_save_settings);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(16);
        root.addView(save, buttonParams);
        save.setOnClickListener(v -> saveHomePage());

        Button defaultButton = new Button(this);
        defaultButton.setText(R.string.browser_home_page_default);
        root.addView(defaultButton, new LinearLayout.LayoutParams(-1, -2));
        defaultButton.setOnClickListener(v -> homePage.setText("https://www.google.com/"));
    }

    private void saveHomePage() {
        String url = homePage.getText().toString().trim();
        if (url.isEmpty()) url = "https://www.google.com/";
        if (!url.matches("(?i)^https?://.+")) url = "https://" + url;
        android.net.Uri parsed = android.net.Uri.parse(url);
        if (parsed.getHost() == null || !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOME_PAGE, url).apply();
        homePage.setText(url);
        Toast.makeText(this, R.string.browser_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private int resolveColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? getColor(value.resourceId) : value.data;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
