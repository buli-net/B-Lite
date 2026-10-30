package wallet.browser;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

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
        menu.inflate(R.menu.menu_browser_download_sort);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.browser_download_sort_newest) {
                newestFirst = true;
            } else if (item.getItemId() == R.id.browser_download_sort_oldest) {
                newestFirst = false;
            } else {
                return false;
            }
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
        String previousDay = null;
        for (DownloadItem item : items) {
            String day = BrowserDateGroup.key(item.modified);
            if (!day.equals(previousDay)) {
                View heading = getLayoutInflater().inflate(R.layout.item_browser_date_header, list, false);
                TextView headingText = heading.findViewById(R.id.browserDateGroupTitle);
                headingText.setText(BrowserDateGroup.label(this, item.modified));
                list.addView(heading);
                previousDay = day;
            }
            list.addView(createRow(item));
        }
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        summary.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        summary.setText(items.isEmpty() ? "" : getResources().getQuantityString(R.plurals.browser_downloads_count, items.size(), items.size()));
    }

    private View createRow(DownloadItem item) {
        View row = getLayoutInflater().inflate(R.layout.item_browser_download, list, false);
        ImageView icon = row.findViewById(R.id.browserDownloadIcon);
        TextView title = row.findViewById(R.id.browserDownloadTitle);
        TextView meta = row.findViewById(R.id.browserDownloadMeta);
        TextView status = row.findViewById(R.id.browserDownloadStatus);
        ImageButton remove = row.findViewById(R.id.browserDownloadRemove);

        icon.setImageResource(R.drawable.ic_browser_download_24dp);
        title.setText(item.title == null || item.title.trim().isEmpty()
                ? getString(R.string.browser_downloads_title) : item.title.trim());
        meta.setText(formatSize(item.bytes, item.total) + "  ·  "
                + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(item.modified)));
        status.setText(statusText(item));

        row.setOnClickListener(v -> openDownload(item));
        icon.setOnClickListener(v -> openDownload(item));
        View.OnLongClickListener removeAction = v -> {
            confirmRemove(item);
            return true;
        };
        row.setOnLongClickListener(removeAction);
        remove.setContentDescription(getString(R.string.browser_downloads_remove));
        remove.setOnClickListener(v -> confirmRemove(item));
        return row;
    }

    private void confirmRemove(DownloadItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.browser_downloads_delete_confirm_title)
                .setMessage(R.string.browser_downloads_delete_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.browser_downloads_remove, (dialog, which) -> {
                    if (manager != null) manager.remove(item.id);
                    refreshDownloads();
                    Toast.makeText(this, R.string.browser_downloads_removed, Toast.LENGTH_SHORT).show();
                }).show();
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
    private static final class DownloadItem {
        long id, bytes, total, modified;
        int status, reason;
        String title, description, mime;
    }
}
