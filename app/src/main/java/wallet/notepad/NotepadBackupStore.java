package wallet.notepad;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exports note content as portable data and re-encrypts it on the restoring device. */
public final class NotepadBackupStore {
    private NotepadBackupStore() { }

    public static JSONObject createSnapshot(Context context) throws Exception {
        NoteStore store = new NoteStore(context);
        JSONArray notes = new JSONArray();
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor = db.query("notes", null, null, null, null, null, "id ASC")) {
            while (cursor.moveToNext()) {
                byte[] iv = cursor.getBlob(cursor.getColumnIndexOrThrow("iv"));
                byte[] ciphertext = cursor.getBlob(cursor.getColumnIndexOrThrow("ciphertext"));
                String[] content = NoteCrypto.decrypt(iv, ciphertext);
                JSONObject item = new JSONObject();
                item.put("title", content[0]);
                item.put("content", content[1]);
                item.put("createdAt", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
                item.put("updatedAt", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
                item.put("pinned", cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) != 0);
                item.put("deletedAt", cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")));
                notes.put(item);
            }
        } finally {
            store.close();
        }
        JSONObject snapshot = new JSONObject();
        snapshot.put("notes", notes);
        return snapshot;
    }

    public static void restoreSnapshot(Context context, JSONObject snapshot) throws Exception {
        JSONArray notes = snapshot.getJSONArray("notes");
        NoteStore store = new NoteStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("notes", null, null);
            for (int i = 0; i < notes.length(); i++) {
                JSONObject item = notes.getJSONObject(i);
                String title = item.getString("title");
                String content = item.getString("content");
                NoteCrypto.EncryptedContent encrypted = NoteCrypto.encrypt(title, content);
                ContentValues values = new ContentValues();
                values.put("iv", encrypted.iv);
                values.put("ciphertext", encrypted.ciphertext);
                values.put("created_at", item.getLong("createdAt"));
                values.put("updated_at", item.getLong("updatedAt"));
                values.put("pinned", item.getBoolean("pinned") ? 1 : 0);
                values.put("deleted_at", item.getLong("deletedAt"));
                db.insertOrThrow("notes", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            if (db.inTransaction()) db.endTransaction();
            store.close();
        }
    }
}
