package wallet.browser;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Typeface;
import android.util.TypedValue;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** App-styled download manager backed by Android DownloadManager records. */
public final class BrowserDownloadsActivity extends BaseActivity {
    private DownloadManager manager;
    private LinearLayout list;
    private TextView empty;
    private TextView summary;
    private boolean newestFirst = true;
    private final List<DownloadItem> items = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_browser_downloads);
        manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        list = findViewById(R.id.downloadsList);
        empty = findViewById(R.id.downloadsEmpty);
        summary = findViewById(R.id.downloadsSummary);
        Toolbar toolbar = findViewById(R.id.downloadsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.browser_downloads_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_browser_downloads);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_sort_downloads) {
                showSortMenu(toolbar);
                return true;
            }
            return false;
        });
        refreshDownloads();
    }

    @Override protected void onResume() { super.onResume(); refreshDownloads(); }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, R.string.browser_downloads_sort_newest);
        menu.getMenu().add(0, 2, 2, R.string.browser_downloads_sort_oldest);
        menu.setOnMenuItemClickListener(item -> {
            newestFirst = item.getItemId() == 1;
            refreshDownloads();
            return true;
        });
        menu.show();
    }

    private void refreshDownloads() {
        items.clear();
        if (manager != null) {
            Cursor cursor = null;
            try {
                cursor = manager.query(new DownloadManager.Query());
                if (cursor != null) while (cursor.moveToNext()) {
                    DownloadItem item = new DownloadItem();
                    item.id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                    item.title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    item.description = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION));
                    item.status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    item.reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    item.bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    item.total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    item.modified = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
                    item.mime = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                    items.add(item);
                }
            } catch (RuntimeException ignored) {
                // The empty state remains usable if the platform download provider is unavailable.
            } finally { if (cursor != null) cursor.close(); }
        }
        Collections.sort(items, Comparator.comparingLong(a -> newestFirst ? -a.modified : a.modified));
        list.removeAllViews();
        for (DownloadItem item : items) list.addView(createRow(item));
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        summary.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        summary.setText(items.isEmpty() ? "" : getResources().getQuantityString(R.plurals.browser_downloads_count, items.size(), items.size()));
    }

    private View createRow(DownloadItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(8);
        card.setLayoutParams(cp);

        ImageButton icon = new ImageButton(this);
        icon.setImageResource(R.drawable.ic_browser_download_24dp);
        icon.setColorFilter(resolveColor(android.R.attr.textColorSecondary));
        icon.setBackgroundResource(android.R.color.transparent);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, -2, 1f);
        card.addView(details, dp);

        TextView title = new TextView(this);
        title.setText(item.title == null || item.title.trim().isEmpty() ? getString(R.string.browser_downloads_title) : item.title);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        details.addView(title);

        TextView meta = new TextView(this);
        meta.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setText(formatSize(item.bytes, item.total) + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(item.modified)));
        details.addView(meta);

        TextView status = new TextView(this);
        status.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText(statusText(item));
        details.addView(status);

        card.setOnClickListener(v -> openDownload(item));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.browser_downloads_delete_confirm_title)
                .setMessage(R.string.browser_downloads_delete_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.browser_downloads_remove, (dialog, which) -> {
                    if (manager != null) manager.remove(item.id);
                    refreshDownloads();
                    Toast.makeText(this, R.string.browser_downloads_removed, Toast.LENGTH_SHORT).show();
                }).show();
            return true;
        });
        icon.setOnClickListener(v -> openDownload(item));
        return card;
    }

    private void openDownload(DownloadItem item) {
        if (manager == null || item.status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, R.string.browser_downloads_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = manager.getUriForDownloadedFile(item.id);
            if (uri == null) throw new ActivityNotFoundException();
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, item.mime == null ? "*/*" : item.mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, R.string.browser_downloads_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String statusText(DownloadItem item) {
        switch (item.status) {
            case DownloadManager.STATUS_SUCCESSFUL: return getString(R.string.browser_downloads_completed);
            case DownloadManager.STATUS_RUNNING: return getString(R.string.browser_downloads_running);
            case DownloadManager.STATUS_PENDING: return getString(R.string.browser_downloads_pending);
            case DownloadManager.STATUS_PAUSED: return getString(R.string.browser_downloads_paused);
            case DownloadManager.STATUS_FAILED: return getString(R.string.browser_downloads_failed);
            default: return getString(R.string.browser_downloads_unknown);
        }
    }

    private String formatSize(long bytes, long total) {
        if (total > 0) return android.text.format.Formatter.formatFileSize(this, bytes) + " / " + android.text.format.Formatter.formatFileSize(this, total);
        if (bytes > 0) return android.text.format.Formatter.formatFileSize(this, bytes);
        return getString(R.string.browser_downloads_size_unknown);
    }
    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) return ContextCompat.getColor(this, value.resourceId);
            return value.data;
        }
        return 0xff777777;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private static final class DownloadItem {
        long id, bytes, total, modified;
        int status, reason;
        String title, description, mime;
    }
}
