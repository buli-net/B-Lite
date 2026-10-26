package wallet.browser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** App-styled history and bookmarks screen. */
public final class BrowserHistoryActivity extends BaseActivity {
    public static final String EXTRA_BOOKMARKS = "bookmarks";
    private final ArrayList<JSONObject> rows = new ArrayList<>();
    private ListView list;
    private TextView empty;
    private boolean bookmarks;
    private RowAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        bookmarks = getIntent().getBooleanExtra(EXTRA_BOOKMARKS, false);
        setContentView(R.layout.activity_browser_history);

        Toolbar toolbar = findViewById(R.id.browserHistoryToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(bookmarks ? R.string.browser_bookmarks_title : R.string.browser_history_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_browser_history);
        MenuItem clearItem = toolbar.getMenu().findItem(R.id.action_clear_browser_list);
        clearItem.setTitle(bookmarks ? R.string.browser_clear_bookmarks : R.string.browser_clear_history);
        clearItem.setContentDescription(getString(bookmarks ? R.string.browser_clear_bookmarks : R.string.browser_clear_history));
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_clear_browser_list) {
                confirmClear();
                return true;
            }
            return false;
        });

        list = findViewById(R.id.browserHistoryList);
        empty = findViewById(R.id.browserHistoryEmpty);
        empty.setText(bookmarks ? R.string.browser_bookmarks_empty : R.string.browser_history_empty);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < rows.size()) {
                String url = rows.get(position).optString("url", "");
                if (!url.isEmpty()) {
                    setResult(RESULT_OK, new android.content.Intent()
                            .putExtra(BrowserActivity.EXTRA_URL, url));
                    finish();
                }
            }
        });
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        rows.clear();
        String key = bookmarks ? BrowserActivity.KEY_BOOKMARKS : BrowserActivity.KEY_HISTORY;
        String raw = getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE).getString(key, "[]");
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
        JSONObject selected = rows.get(position);
        String url = selected.optString("url", "");
        long time = selected.optLong("time", Long.MIN_VALUE);
        String key = bookmarks ? BrowserActivity.KEY_BOOKMARKS : BrowserActivity.KEY_HISTORY;
        JSONArray old;
        try {
            old = new JSONArray(getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE).getString(key, "[]"));
        } catch (JSONException ex) {
            old = new JSONArray();
        }
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row == null) continue;
            boolean isSelected = !removed && url.equals(row.optString("url", ""))
                    && (time == Long.MIN_VALUE || time == row.optLong("time", Long.MIN_VALUE));
            if (isSelected) removed = true;
            else next.put(row);
        }
        getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE).edit().putString(key, next.toString()).apply();
        reload();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(bookmarks ? R.string.browser_clear_bookmarks : R.string.browser_clear_history)
                .setMessage(bookmarks ? R.string.browser_clear_bookmarks_message : R.string.browser_clear_history_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.browser_clear, (dialog, which) -> {
                    getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE)
                            .edit().putString(bookmarks ? BrowserActivity.KEY_BOOKMARKS : BrowserActivity.KEY_HISTORY, "[]").apply();
                    reload();
                })
                .show();
    }

    private String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException ex) {
            return url;
        }
    }

    private void bindSiteIcon(ImageView icon, String url) {
        String host = Uri.parse(url == null ? "" : url).getHost();
        String encoded = null;
        if (host != null && !host.isEmpty()) {
            encoded = getSharedPreferences(BrowserActivity.PREFS, MODE_PRIVATE)
                    .getString("favicon_" + host.toLowerCase(java.util.Locale.ROOT), null);
        }
        if (encoded != null) {
            try {
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    icon.setImageBitmap(bitmap);
                    icon.clearColorFilter();
                    return;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        icon.setImageResource(bookmarks ? R.drawable.ic_browser_bookmark_24dp : R.drawable.ic_browser_site_24dp);
        icon.setColorFilter(resolveColor(android.R.attr.textColorSecondary));
    }

    private int resolveColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? androidx.core.content.ContextCompat.getColor(this, value.resourceId) : value.data;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = LayoutInflater.from(BrowserHistoryActivity.this)
                    .inflate(R.layout.item_browser_history, parent, false);
            JSONObject item = rows.get(position);
            String url = item.optString("url", "");
            TextView title = row.findViewById(R.id.browserHistoryTitle);
            TextView host = row.findViewById(R.id.browserHistoryHost);
            ImageView icon = row.findViewById(R.id.browserHistorySiteIcon);
            ImageButton delete = row.findViewById(R.id.browserHistoryDelete);

            String text = item.optString("title", "").trim();
            title.setText(text.isEmpty() ? url : text);
            host.setText(hostOf(url));
            bindSiteIcon(icon, url);
            delete.setOnClickListener(v -> deleteAt(position));
            return row;
        }
    }
}
