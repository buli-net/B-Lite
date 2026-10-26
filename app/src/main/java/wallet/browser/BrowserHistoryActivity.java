package wallet.browser;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import wallet.main.R;

public final class BrowserHistoryActivity extends AppCompatActivity {
    public static final String EXTRA_BOOKMARKS = "bookmarks";
    private static final String PREFS = "browser_storage";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private final ArrayList<JSONObject> rows = new ArrayList<>();
    private ListView list;
    private TextView empty;
    private boolean bookmarks;
    private RowAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        bookmarks = getIntent().getBooleanExtra(EXTRA_BOOKMARKS, false);
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
            int color = value.resourceId != 0 ? getColor(value.resourceId) : value.data;
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(resolveColor(android.R.attr.colorBackground));
        setContentView(root);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(10), dp(12), dp(10));
        TextView title = new TextView(this);
        title.setText(bookmarks ? R.string.browser_bookmarks_title : R.string.browser_history_title);
        title.setTextSize(21);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = new Button(this);
        clear.setText(bookmarks ? R.string.browser_clear_bookmarks : R.string.browser_clear_history);
        clear.setOnClickListener(v -> confirmClear());
        toolbar.addView(clear);
        root.addView(toolbar);
        list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(resolveColor(android.R.attr.colorBackground));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setText(bookmarks ? R.string.browser_bookmarks_empty : R.string.browser_history_empty);
        empty.setTextSize(16);
        empty.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        root.addView(empty, new LinearLayout.LayoutParams(-1, dp(72)));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < rows.size()) {
                String url = rows.get(position).optString("url", "");
                if (!url.isEmpty()) {
                    startActivity(new Intent(this, BrowserActivity.class).putExtra("browser_url", url));
                    finish();
                }
            }
        });
        reload();
    }

    private void reload() {
        rows.clear();
        String key = bookmarks ? KEY_BOOKMARKS : KEY_HISTORY;
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row != null && row.has("url")) rows.add(row);
            }
        } catch (JSONException ignored) { }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (list != null) list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void deleteAt(int position) {
        if (position < 0 || position >= rows.size()) return;
        String url = rows.get(position).optString("url", "");
        String key = bookmarks ? KEY_BOOKMARKS : KEY_HISTORY;
        JSONArray old;
        try { old = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "[]")); }
        catch (JSONException ex) { old = new JSONArray(); }
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null && !url.equals(row.optString("url"))) next.put(row);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, next.toString()).apply();
        reload();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle(bookmarks ? R.string.browser_clear_bookmarks : R.string.browser_clear_history)
                .setMessage(bookmarks ? R.string.browser_clear_bookmarks_message : R.string.browser_clear_history_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.browser_clear, (d, w) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(bookmarks ? KEY_BOOKMARKS : KEY_HISTORY, "[]").apply();
                    reload();
                }).show();
    }

    private int resolveColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue(); getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? getColor(value.resourceId) : value.data;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(BrowserHistoryActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(8), dp(10));
            TextView icon = new TextView(BrowserHistoryActivity.this);
            icon.setGravity(Gravity.CENTER);
            icon.setText(domainInitial(rows.get(position).optString("url", "")));
            icon.setTextSize(16);
            icon.setTypeface(Typeface.DEFAULT_BOLD);
            icon.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(resolveColor(android.R.attr.colorControlHighlight));
            icon.setBackground(circle);
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout textColumn = new LinearLayout(BrowserHistoryActivity.this);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setPadding(dp(12), 0, dp(6), 0);
            TextView name = new TextView(BrowserHistoryActivity.this);
            name.setText(rows.get(position).optString("title", rows.get(position).optString("url")));
            name.setTextSize(16); name.setMaxLines(2); name.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            TextView host = new TextView(BrowserHistoryActivity.this);
            host.setText(hostOf(rows.get(position).optString("url", "")));
            host.setTextSize(13); host.setMaxLines(1); host.setTextColor(resolveColor(android.R.attr.textColorSecondary));
            textColumn.addView(name); textColumn.addView(host);
            row.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton delete = new ImageButton(BrowserHistoryActivity.this);
            delete.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            delete.setContentDescription(getString(R.string.browser_history_delete));
            delete.setBackgroundResource(android.R.drawable.btn_default);
            delete.setOnClickListener(v -> deleteAt(position));
            row.addView(delete, new LinearLayout.LayoutParams(dp(42), dp(42)));
            return row;
        }
        private String domainInitial(String url) { String host = hostOf(url); return host.isEmpty() ? "•" : host.substring(0, 1).toUpperCase(java.util.Locale.ROOT); }
        private String hostOf(String url) { try { String host = android.net.Uri.parse(url).getHost(); return host == null ? url : host; } catch (Exception e) { return url; } }
    }
}
