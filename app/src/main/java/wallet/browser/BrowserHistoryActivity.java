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
import java.util.Collections;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** App-styled history and bookmarks screen. */
public final class BrowserHistoryActivity extends BaseActivity {
    public static final String EXTRA_BOOKMARKS = "bookmarks";
    private final ArrayList<JSONObject> rows = new ArrayList<>();
    private final ArrayList<DisplayItem> displayItems = new ArrayList<>();
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
        list.setOnItemClickListener((parent, view, position, id) -> openSelectedRow(position));
        reload();
    }

    private void openSelectedRow(int position) {
        if (position < 0 || position >= displayItems.size()) return;
        DisplayItem displayItem = displayItems.get(position);
        if (displayItem.header || displayItem.row == null) return;
        openSelectedRow(displayItem.row);
    }

    private void openSelectedRow(JSONObject selected) {
        String url = selected.optString("url", "").trim();
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && parsed.getHost() != null && !parsed.getHost().isEmpty()) {
            android.content.Intent result = new android.content.Intent();
            result.putExtra(BrowserActivity.EXTRA_URL, url);
            setResult(RESULT_OK, result);
            finish();
        } else {
            android.widget.Toast.makeText(this, R.string.browser_invalid_url,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
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

        if (!bookmarks) {
            Collections.sort(rows, (left, right) -> Long.compare(
                    right.optLong("time", 0L), left.optLong("time", 0L)));
        }
        displayItems.clear();
        String previousDay = null;
        for (JSONObject row : rows) {
            if (!bookmarks) {
                long timestamp = row.optLong("time", 0L);
                String day = BrowserDateGroup.key(timestamp);
                if (!day.equals(previousDay)) {
                    displayItems.add(DisplayItem.header(BrowserDateGroup.label(this, timestamp)));
                    previousDay = day;
                }
            }
            displayItems.add(DisplayItem.row(row));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (list != null) list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
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

    private static final class DisplayItem {
        final boolean header;
        final String heading;
        final JSONObject row;
        private DisplayItem(boolean header, String heading, JSONObject row) {
            this.header = header;
            this.heading = heading;
            this.row = row;
        }
        static DisplayItem header(String heading) { return new DisplayItem(true, heading, null); }
        static DisplayItem row(JSONObject row) { return new DisplayItem(false, null, row); }
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return displayItems.size(); }
        @Override public Object getItem(int position) { return displayItems.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return displayItems.get(position).header ? 0 : 1; }
        @Override public boolean isEnabled(int position) { return !displayItems.get(position).header; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            DisplayItem displayItem = displayItems.get(position);
            if (displayItem.header) {
                View header = convertView;
                if (header == null || header.findViewById(R.id.browserDateGroupTitle) == null) {
                    header = LayoutInflater.from(BrowserHistoryActivity.this)
                            .inflate(R.layout.item_browser_date_header, parent, false);
                }
                ((TextView) header.findViewById(R.id.browserDateGroupTitle)).setText(displayItem.heading);
                return header;
            }
            View row = LayoutInflater.from(BrowserHistoryActivity.this)
                    .inflate(R.layout.item_browser_history, parent, false);
            JSONObject item = displayItem.row;
            String url = item.optString("url", "");
            TextView title = row.findViewById(R.id.browserHistoryTitle);
            TextView host = row.findViewById(R.id.browserHistoryHost);
            ImageView icon = row.findViewById(R.id.browserHistorySiteIcon);
            ImageButton delete = row.findViewById(R.id.browserHistoryDelete);

            String text = item.optString("title", "").trim();
            title.setText(text.isEmpty() ? url : text);
            host.setText(hostOf(url));
            bindSiteIcon(icon, url);
            row.setOnClickListener(v -> openSelectedRow(item));
            delete.setOnClickListener(v -> deleteAtItem(item));
            return row;
        }
    }

    private void deleteAtItem(JSONObject selected) {
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

}
