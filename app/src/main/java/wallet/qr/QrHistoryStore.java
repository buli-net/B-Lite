package wallet.qr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Local-only history for generated and scanned QR/barcode content. */
final class QrHistoryStore {
    static final String SOURCE_SCANNED = "scanned";
    static final String SOURCE_GENERATED = "generated";
    private static final String PREFS = "qr_tools_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 300;

    private QrHistoryStore() { }

    static String add(Context context, String content, String format, String type, String source) {
        if (content == null || content.isEmpty()) return "";
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        String id = UUID.randomUUID().toString();
        try {
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("content", content);
            item.put("format", format == null ? "" : format);
            item.put("type", type == null ? "TEXT" : type);
            item.put("source", source == null ? SOURCE_SCANNED : source);
            item.put("timestamp", System.currentTimeMillis());
            item.put("favorite", false);
            next.put(item);
            // Keep older favorites ahead of ordinary history when the local cap is reached.
            // The newest record always remains at the top of the list.
            for (int i = 0; i < old.length() && next.length() < MAX_ENTRIES; i++) {
                JSONObject row = old.optJSONObject(i);
                if (row != null && row.optBoolean("favorite", false)) next.put(row);
            }
            for (int i = 0; i < old.length() && next.length() < MAX_ENTRIES; i++) {
                JSONObject row = old.optJSONObject(i);
                if (row != null && !row.optBoolean("favorite", false)) next.put(row);
            }
        } catch (JSONException ignored) {
            return "";
        }
        write(context, next);
        return id;
    }

    static JSONArray read(Context context) {
        String raw = prefs(context).getString(KEY_ENTRIES, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    static void toggleFavorite(Context context, String id) {
        update(context, id, null, true);
    }

    static void delete(Context context, String id) {
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null && !id.equals(row.optString("id"))) next.put(row);
        }
        write(context, next);
    }

    static void clear(Context context) {
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null && row.optBoolean("favorite", false)) next.put(row);
        }
        write(context, next);
    }

    private static void update(Context context, String id, Boolean favorite, boolean toggle) {
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row == null) continue;
            if (id.equals(row.optString("id"))) {
                boolean current = row.optBoolean("favorite", false);
                row = copy(row);
                try { row.put("favorite", toggle ? !current : Boolean.TRUE.equals(favorite)); }
                catch (JSONException ignored) { }
            }
            next.put(row);
        }
        write(context, next);
    }

    private static JSONObject copy(JSONObject source) {
        JSONObject copy = new JSONObject();
        JSONArray names = source.names();
        if (names == null) return copy;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            try { copy.put(key, source.get(key)); }
            catch (JSONException ignored) { }
        }
        return copy;
    }

    private static void write(Context context, JSONArray entries) {
        prefs(context).edit().putString(KEY_ENTRIES, entries.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
