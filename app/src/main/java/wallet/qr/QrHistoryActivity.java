package wallet.qr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Shows locally stored scanned/generated code history and favorites. */
public class QrHistoryActivity extends BaseActivity {
    private Spinner filterSpinner;
    private ListView listView;
    private TextView emptyView;
    private HistoryAdapter adapter;
    private String filter = "all";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_history);
        Toolbar toolbar = findViewById(R.id.toolbar_qr_history);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.qr_tools_history_title);
        }

        filterSpinner = findViewById(R.id.qr_history_filter_spinner);
        listView = findViewById(R.id.qr_history_list);
        emptyView = findViewById(R.id.qr_history_empty);
        android.widget.ArrayAdapter<CharSequence> filterAdapter =
                android.widget.ArrayAdapter.createFromResource(this,
                        R.array.qr_tools_history_filter_names, android.R.layout.simple_spinner_item);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(filterAdapter);
        adapter = new HistoryAdapter();
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((parent, view, position, id) -> showEntry(adapter.getItem(position)));
        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] values = getResources().getStringArray(R.array.qr_tools_history_filter_values);
                filter = values[Math.max(0, Math.min(position, values.length - 1))];
                reload();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        findViewById(R.id.qr_history_clear_button).setOnClickListener(v -> confirmClearHistory());
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        if (adapter == null) return;
        adapter.setEntries(filterEntries(QrHistoryStore.read(this)));
    }

    private List<JSONObject> filterEntries(JSONArray source) {
        ArrayList<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row == null) continue;
            if ("favorites".equals(filter) && !row.optBoolean("favorite", false)) continue;
            if ("scanned".equals(filter) && !QrHistoryStore.SOURCE_SCANNED.equals(row.optString("source"))) continue;
            if ("generated".equals(filter) && !QrHistoryStore.SOURCE_GENERATED.equals(row.optString("source"))) continue;
            result.add(row);
        }
        return result;
    }

    private void showEntry(JSONObject entry) {
        if (entry == null) return;
        String content = entry.optString("content", "");
        QrContentClassifier.Detection detection = QrContentClassifier.detect(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(detection.typeLabel)
                .setMessage(content)
                .setNeutralButton(R.string.qr_tools_copy_result, (dialog, which) -> copy(content));
        boolean generated = QrHistoryStore.SOURCE_GENERATED.equals(entry.optString("source"));
        if (generated) {
            builder.setPositiveButton(R.string.qr_tools_history_regenerate, (dialog, which) -> {
                Intent intent = new Intent(this, QrGenerateActivity.class);
                intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_CONTENT, content);
                intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_FORMAT, entry.optString("format", "QR_CODE"));
                startActivity(intent);
            });
        } else if (detection.actionable) {
            builder.setPositiveButton(R.string.qr_tools_history_open, (dialog, which) -> {
                Intent intent = QrContentClassifier.createIntent(this, content);
                if (intent == null) {
                    Toast.makeText(this, R.string.qr_tools_launch_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                try { startActivity(intent); }
                catch (android.content.ActivityNotFoundException | SecurityException exception) {
                    Toast.makeText(this, R.string.qr_tools_launch_failed, Toast.LENGTH_LONG).show();
                }
            });
        }
        builder.setNegativeButton(R.string.cancel, null).show();
    }

    private void copy(String content) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_tools_clip_label), content));
            Toast.makeText(this, R.string.qr_tools_scan_content_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.qr_tools_history_clear)
                .setMessage(R.string.qr_tools_history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    QrHistoryStore.clear(this);
                    reload();
                })
                .show();
    }

    private void toggleFavorite(JSONObject entry) {
        String id = entry == null ? "" : entry.optString("id", "");
        if (TextUtils.isEmpty(id)) return;
        QrHistoryStore.toggleFavorite(this, id);
        reload();
    }

    private void deleteEntry(JSONObject entry) {
        String id = entry == null ? "" : entry.optString("id", "");
        if (TextUtils.isEmpty(id)) return;
        QrHistoryStore.delete(this, id);
        reload();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private final class HistoryAdapter extends BaseAdapter {
        private final List<JSONObject> entries = new ArrayList<>();
        void setEntries(List<JSONObject> values) {
            entries.clear();
            entries.addAll(values);
            notifyDataSetChanged();
        }
        @Override public int getCount() { return entries.size(); }
        @Override public JSONObject getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return getItem(position).optLong("timestamp", position); }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) row = LayoutInflater.from(QrHistoryActivity.this)
                    .inflate(R.layout.row_qr_history_item, parent, false);
            JSONObject item = getItem(position);
            String content = item.optString("content", "");
            String source = item.optString("source", QrHistoryStore.SOURCE_SCANNED);
            String format = item.optString("format", "");
            boolean isFavorite = item.optBoolean("favorite", false);
            TextView title = row.findViewById(R.id.qr_history_row_title);
            TextView value = row.findViewById(R.id.qr_history_row_content);
            TextView meta = row.findViewById(R.id.qr_history_row_meta);
            title.setText(QrContentClassifier.displayType(QrHistoryActivity.this, content));
            value.setText(content);
            long timestamp = item.optLong("timestamp", 0);
            String date = timestamp > 0 ? DateFormat.getDateTimeInstance().format(new Date(timestamp)) : "";
            meta.setText(getString(QrHistoryStore.SOURCE_GENERATED.equals(source)
                    ? R.string.qr_tools_history_source_generated : R.string.qr_tools_history_source_scanned)
                    + " • " + format + " • " + date);
            // Make the whole card an explicit click target. ListView item clicks can be
            // swallowed by focusable child buttons on some Android versions/devices.
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showEntry(item));

            Button favoriteButton = row.findViewById(R.id.qr_history_row_favorite);
            favoriteButton.setText(isFavorite ? R.string.qr_tools_remove_favorite : R.string.qr_tools_add_favorite);
            favoriteButton.setOnClickListener(v -> toggleFavorite(item));
            row.findViewById(R.id.qr_history_row_delete).setOnClickListener(v -> deleteEntry(item));
            return row;
        }
    }
}
