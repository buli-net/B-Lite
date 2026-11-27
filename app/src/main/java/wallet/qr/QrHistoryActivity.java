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
import java.util.Map;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Shows locally stored scanned/generated code history and favorites. */
public class QrHistoryActivity extends BaseActivity {
    private static final String FILTER_ALL = "all";
    private static final String FILTER_FAVORITES = "favorites";
    private static final String FILTER_SCANNED = "scanned";
    private static final String FILTER_GENERATED = "generated";
    private static final String[] FILTER_VALUES = {
            FILTER_ALL, FILTER_FAVORITES, FILTER_SCANNED, FILTER_GENERATED
    };
    private Spinner filterSpinner;
    private ListView listView;
    private TextView emptyView;
    private HistoryAdapter adapter;
    private String filter = FILTER_ALL;

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
                filter = FILTER_VALUES[Math.max(0, Math.min(position, FILTER_VALUES.length - 1))];
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
            if (FILTER_FAVORITES.equals(filter) && !row.optBoolean("favorite", false)) continue;
            if (FILTER_SCANNED.equals(filter) && !QrHistoryStore.SOURCE_SCANNED.equals(row.optString("source"))) continue;
            if (FILTER_GENERATED.equals(filter) && !QrHistoryStore.SOURCE_GENERATED.equals(row.optString("source"))) continue;
            result.add(row);
        }
        return result;
    }

    private void showEntry(JSONObject entry) {
        if (entry == null) return;
        String content = entry.optString("content", "");
        QrPayloadParser.ParsedContent parsed = QrPayloadParser.parse(this, content);
        View detailView = LayoutInflater.from(this).inflate(R.layout.dialog_qr_payload_detail, null, false);
        android.widget.LinearLayout fields = detailView.findViewById(R.id.qr_history_decoded_fields);
        QrPayloadRenderer.render(this, fields, content);
        View decodedTitle = detailView.findViewById(R.id.qr_history_decoded_title);
        boolean hasStructuredFields = !QrPayloadBuilder.TYPE_TEXT.equals(parsed.type);
        decodedTitle.setVisibility(hasStructuredFields ? View.VISIBLE : View.GONE);
        fields.setVisibility(hasStructuredFields ? View.VISIBLE : View.GONE);
        TextView raw = detailView.findViewById(R.id.qr_history_raw_payload);
        raw.setText(content);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(parsed.typeLabel)
                .setView(detailView)
                .setNeutralButton(R.string.qr_tools_copy_result, (dialog, which) -> copy(content));
        boolean generated = QrHistoryStore.SOURCE_GENERATED.equals(entry.optString("source"));
        if (generated) {
            builder.setPositiveButton(R.string.qr_tools_history_regenerate,
                    (dialog, which) -> launchRegenerate(content, entry, parsed, true));
        } else {
            Button regenerateButton = detailView.findViewById(R.id.qr_history_regenerate);
            regenerateButton.setVisibility(View.VISIBLE);
            regenerateButton.setOnClickListener(v -> {
                AlertDialog dialog = (AlertDialog) v.getTag();
                if (dialog != null) dialog.dismiss();
                launchRegenerate(content, entry, parsed, false);
            });
        }
        if (!generated && !QrPayloadBuilder.TYPE_TEXT.equals(parsed.type)) {
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
        builder.setNegativeButton(R.string.cancel, null);
        AlertDialog dialog = builder.create();
        View regenerateButton = detailView.findViewById(R.id.qr_history_regenerate);
        regenerateButton.setTag(dialog);
        dialog.show();
    }

    private void launchRegenerate(String content, JSONObject entry,
                                  QrPayloadParser.ParsedContent parsed, boolean restoreOptions) {
        Map<String, String> fieldsToRestore = QrHistoryStore.readFields(entry);
        if (fieldsToRestore.isEmpty()) fieldsToRestore.putAll(parsed.values);
        Intent intent = new Intent(this, QrGenerateActivity.class);
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_CONTENT, content);
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_TYPE,
                entry.optString("type", parsed.type));
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_FIELDS,
                QrHistoryStore.serializeFields(fieldsToRestore));
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_FORMAT,
                restoreOptions ? entry.optString("format", "QR_CODE") : "QR_CODE");
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_SIZE_POSITION,
                restoreOptions ? entry.optInt("sizePosition", 2) : 2);
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_CORRECTION_POSITION,
                restoreOptions ? entry.optInt("correctionPosition", 1) : 1);
        intent.putExtra(QrGenerateActivity.EXTRA_INITIAL_MARGIN_POSITION,
                restoreOptions ? entry.optInt("marginPosition", 2) : 2);
        startActivity(intent);
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

    private String historyPreview(String content) {
        QrPayloadParser.ParsedContent parsed = QrPayloadParser.parse(this, content);
        if (parsed.displayFields.isEmpty()) return content;
        StringBuilder preview = new StringBuilder();
        int previewCount = 0;
        for (QrPayloadParser.Field field : parsed.displayFields) {
            String value = field.value;
            if (value == null || value.trim().isEmpty()) continue;
            if (previewCount > 0) preview.append(" • ");
            preview.append(value.replace('\n', ' '));
            if (++previewCount >= 2) break;
        }
        return preview.length() == 0 ? content : preview.toString();
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
            value.setText(historyPreview(content));
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
