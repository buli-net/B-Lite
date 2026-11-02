package wallet.notepad;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class NoteStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "notepad.db";
    private static final int DATABASE_VERSION = 1;

    static final class Note {
        long id;
        String title = "";
        String content = "";
        long createdAt;
        long updatedAt;
        boolean pinned;
        long deletedAt;
    }

    NoteStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "iv BLOB NOT NULL, "
                + "ciphertext BLOB NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "pinned INTEGER NOT NULL DEFAULT 0, "
                + "deleted_at INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX notes_active_order "
                + "ON notes(deleted_at, pinned, updated_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema changes must use additive, versioned migrations.
    }

    synchronized long save(Note note) throws Exception {
        if (note == null) {
            throw new IllegalArgumentException("Note is required");
        }
        long now = System.currentTimeMillis();
        NoteCrypto.EncryptedContent encrypted = NoteCrypto.encrypt(
                note.title, note.content);
        ContentValues values = new ContentValues();
        values.put("iv", encrypted.iv);
        values.put("ciphertext", encrypted.ciphertext);
        values.put("created_at", note.createdAt > 0 ? note.createdAt : now);
        values.put("updated_at", now);
        values.put("pinned", note.pinned ? 1 : 0);
        values.put("deleted_at", note.deletedAt);

        SQLiteDatabase db = getWritableDatabase();
        if (note.id > 0) {
            int changed = db.update("notes", values, "id=?",
                    new String[]{String.valueOf(note.id)});
            if (changed == 0) {
                throw new IllegalStateException("Note no longer exists");
            }
            note.updatedAt = now;
            return note.id;
        }

        long id = db.insertOrThrow("notes", null, values);
        note.id = id;
        note.createdAt = now;
        note.updatedAt = now;
        return id;
    }

    synchronized Note find(long id) throws Exception {
        try (Cursor cursor = getReadableDatabase().query(
                "notes", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            return cursor.moveToFirst() ? readNote(cursor) : null;
        }
    }

    synchronized List<Note> load(boolean deleted) throws Exception {
        List<Note> notes = new ArrayList<>();
        String selection = deleted ? "deleted_at>0" : "deleted_at=0";
        try (Cursor cursor = getReadableDatabase().query(
                "notes", null, selection, null, null, null,
                "pinned DESC, updated_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                notes.add(readNote(cursor));
            }
        }
        return notes;
    }

    synchronized boolean setPinned(long id, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(
                "notes", values, "id=? AND deleted_at=0",
                new String[]{String.valueOf(id)}) > 0;
    }

    synchronized boolean moveToTrash(long id) {
        ContentValues values = new ContentValues();
        values.put("deleted_at", System.currentTimeMillis());
        return getWritableDatabase().update(
                "notes", values, "id=? AND deleted_at=0",
                new String[]{String.valueOf(id)}) > 0;
    }

    synchronized boolean restore(long id) {
        ContentValues values = new ContentValues();
        values.put("deleted_at", 0);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(
                "notes", values, "id=? AND deleted_at>0",
                new String[]{String.valueOf(id)}) > 0;
    }

    synchronized boolean deletePermanently(long id) {
        return getWritableDatabase().delete(
                "notes", "id=? AND deleted_at>0",
                new String[]{String.valueOf(id)}) > 0;
    }

    synchronized int emptyTrash() {
        return getWritableDatabase().delete("notes", "deleted_at>0", null);
    }

    private Note readNote(Cursor cursor) throws Exception {
        Note note = new Note();
        note.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        byte[] iv = cursor.getBlob(cursor.getColumnIndexOrThrow("iv"));
        byte[] ciphertext = cursor.getBlob(
                cursor.getColumnIndexOrThrow("ciphertext"));
        String[] content = NoteCrypto.decrypt(iv, ciphertext);
        note.title = content[0];
        note.content = content[1];
        note.createdAt = cursor.getLong(
                cursor.getColumnIndexOrThrow("created_at"));
        note.updatedAt = cursor.getLong(
                cursor.getColumnIndexOrThrow("updated_at"));
        note.pinned = cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) != 0;
        note.deletedAt = cursor.getLong(
                cursor.getColumnIndexOrThrow("deleted_at"));
        return note;
    }
}
