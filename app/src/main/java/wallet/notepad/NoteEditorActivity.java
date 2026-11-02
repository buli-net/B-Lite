package wallet.notepad;

import wallet.main.R;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import wallet.ui.BaseActivity;

public class NoteEditorActivity extends BaseActivity {

    public static final String EXTRA_INCOMING_TEXT_URI =
            "wallet.notepad.extra.INCOMING_TEXT_URI";
    public static final String EXTRA_INITIAL_CONTENT =
            "wallet.notepad.extra.INITIAL_CONTENT";
    public static final String EXTRA_INITIAL_TITLE =
            "wallet.notepad.extra.INITIAL_TITLE";

    private static final String STATE_NOTE_ID = "wallet.notepad.state.NOTE_ID";
    private static final int MAX_INCOMING_TEXT_BYTES = 2 * 1024 * 1024;

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveNow;
    private EditText titleInput;
    private EditText contentInput;
    private TextView saveStatus;
    private NoteStore store;
    private NoteStore.Note note;
    private boolean loading;
    private boolean changed;
    private long restoredNoteId;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("text/plain"),
                    this::writeExport);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_note_editor);
        store = new NoteStore(this);
        if (state != null) {
            restoredNoteId = state.getLong(STATE_NOTE_ID, 0L);
        }

        Toolbar toolbar = findViewById(R.id.toolbar_note_editor);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.notepad_editor_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        titleInput = findViewById(R.id.noteTitleInput);
        contentInput = findViewById(R.id.noteContentInput);
        saveStatus = findViewById(R.id.noteSaveStatus);
        installAutoSave();

        NotepadAccess.request(this, this::loadNote);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (note != null && note.id > 0) {
            outState.putLong(STATE_NOTE_ID, note.id);
        } else if (restoredNoteId > 0) {
            outState.putLong(STATE_NOTE_ID, restoredNoteId);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        saveHandler.removeCallbacks(saveTask);
        saveNow();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveHandler.removeCallbacks(saveTask);
        if (store != null) {
            store.close();
            store = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_note_editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem pin = menu.findItem(R.id.menuNotePin);
        if (pin != null) {
            pin.setTitle(note != null && note.pinned
                    ? R.string.notepad_unpin
                    : R.string.notepad_pin);
            pin.setEnabled(note != null && note.id > 0);
        }
        MenuItem remove = menu.findItem(R.id.menuNoteMoveToTrash);
        if (remove != null) {
            remove.setEnabled(note != null && note.id > 0);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menuNotePin) {
            saveNow();
            if (note != null && note.id > 0) {
                note.pinned = !note.pinned;
                store.setPinned(note.id, note.pinned);
                invalidateOptionsMenu();
                Toast.makeText(this, note.pinned
                        ? R.string.notepad_pinned
                        : R.string.notepad_unpinned, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (id == R.id.menuNoteShare) {
            saveNow();
            shareCurrentNote();
            return true;
        }
        if (id == R.id.menuNoteExport) {
            saveNow();
            exportLauncher.launch(exportFileName());
            return true;
        }
        if (id == R.id.menuNoteMoveToTrash) {
            saveNow();
            confirmMoveToTrash();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadNote() {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        long noteId = restoredNoteId > 0
                ? restoredNoteId
                : getIntent().getLongExtra(NotepadActivity.EXTRA_NOTE_ID, 0L);
        if (noteId > 0) {
            loadStoredNote(noteId);
            return;
        }

        Uri incomingUri = getIntent().getParcelableExtra(EXTRA_INCOMING_TEXT_URI);
        if (incomingUri != null) {
            loadIncomingTextFile(incomingUri);
            return;
        }

        if (getIntent().hasExtra(EXTRA_INITIAL_CONTENT)) {
            NoteStore.Note imported = new NoteStore.Note();
            imported.title = getIntent().getStringExtra(EXTRA_INITIAL_TITLE);
            imported.content = getIntent().getStringExtra(EXTRA_INITIAL_CONTENT);
            completeIncomingNote(imported);
            return;
        }

        loadStoredNote(0L);
    }

    private void loadStoredNote(long noteId) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        loading = true;
        try {
            note = noteId > 0 ? store.find(noteId) : new NoteStore.Note();
            if (note == null) {
                Toast.makeText(this, R.string.notepad_note_missing,
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            titleInput.setText(note.title);
            contentInput.setText(note.content);
            changed = false;
            saveStatus.setText(R.string.notepad_saved);
            invalidateOptionsMenu();
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.notepad_load_failed, error.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show();
            finish();
        } finally {
            loading = false;
        }
    }

    private void loadIncomingTextFile(final Uri source) {
        saveStatus.setText(R.string.notepad_saving);
        new Thread(() -> {
            try {
                ImportedText importedText = readIncomingText(source);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    NoteStore.Note imported = new NoteStore.Note();
                    imported.title = importedText.title;
                    imported.content = importedText.content;
                    completeIncomingNote(imported);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    int message = error instanceof IncomingTextTooLargeException
                            ? R.string.notepad_import_too_large
                            : R.string.notepad_import_failed;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "notepad-incoming-text-reader").start();
    }

    private ImportedText readIncomingText(Uri source) throws Exception {
        String title = incomingFileTitle(source);
        byte[] bytes;
        try (InputStream input = getContentResolver().openInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("Unable to open incoming text file");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_INCOMING_TEXT_BYTES) {
                    throw new IncomingTextTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        return new ImportedText(title, decodeIncomingText(bytes));
    }

    private String incomingFileTitle(Uri source) {
        String displayName = null;
        try (Cursor cursor = getContentResolver().query(
                source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    displayName = cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the URI path when the provider does not expose a display name.
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = source.getLastPathSegment();
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            return getString(R.string.notepad_default_title);
        }
        displayName = displayName.substring(displayName.lastIndexOf('/') + 1).trim();
        if (displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }
        return displayName.isEmpty()
                ? getString(R.string.notepad_default_title) : displayName;
    }

    private String decodeIncomingText(byte[] bytes) {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private void completeIncomingNote(NoteStore.Note imported) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        loading = true;
        try {
            note = imported;
            if (note.title == null || note.title.trim().isEmpty()) {
                note.title = getString(R.string.notepad_default_title);
            }
            if (note.content == null) {
                note.content = "";
            }
            store.save(note);
            restoredNoteId = note.id;

            Intent updatedIntent = new Intent(getIntent());
            updatedIntent.removeExtra(EXTRA_INCOMING_TEXT_URI);
            updatedIntent.removeExtra(EXTRA_INITIAL_CONTENT);
            updatedIntent.removeExtra(EXTRA_INITIAL_TITLE);
            updatedIntent.putExtra(NotepadActivity.EXTRA_NOTE_ID, note.id);
            setIntent(updatedIntent);

            titleInput.setText(note.title);
            contentInput.setText(note.content);
            changed = false;
            saveStatus.setText(R.string.notepad_saved);
            invalidateOptionsMenu();
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.notepad_load_failed, error.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show();
            finish();
        } finally {
            loading = false;
        }
    }

    private static final class ImportedText {
        final String title;
        final String content;

        ImportedText(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }

    private static final class IncomingTextTooLargeException extends Exception {
    }

    private void installAutoSave() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (loading) {
                    return;
                }
                changed = true;
                saveStatus.setText(R.string.notepad_saving);
                saveHandler.removeCallbacks(saveTask);
                saveHandler.postDelayed(saveTask, 650L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        titleInput.addTextChangedListener(watcher);
        contentInput.addTextChangedListener(watcher);
    }

    private void saveNow() {
        if (loading || !changed || note == null || store == null || isDestroyed()) {
            return;
        }
        note.title = titleInput.getText().toString();
        note.content = contentInput.getText().toString();
        if (note.title.trim().isEmpty() && note.content.trim().isEmpty()) {
            saveStatus.setText(R.string.notepad_empty_draft);
            changed = false;
            return;
        }
        try {
            store.save(note);
            changed = false;
            saveStatus.setText(R.string.notepad_saved);
            invalidateOptionsMenu();
        } catch (Exception error) {
            saveStatus.setText(R.string.notepad_save_failed);
            Toast.makeText(this, getString(
                    R.string.notepad_save_error, error.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareCurrentNote() {
        if (note == null) {
            return;
        }
        String title = exportTitle();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TEXT, title + "\n\n"
                + contentInput.getText().toString());
        startActivity(Intent.createChooser(send, getString(R.string.notepad_share)));
    }

    private void confirmMoveToTrash() {
        if (note == null || note.id <= 0) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.notepad_move_to_trash_title)
                .setMessage(R.string.notepad_move_to_trash_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.notepad_move_to_trash, (dialog, which) -> {
                    store.moveToTrash(note.id);
                    finish();
                })
                .show();
    }

    private String exportTitle() {
        String title = titleInput.getText().toString().trim();
        if (!title.isEmpty()) {
            return title;
        }
        String content = contentInput.getText().toString().trim();
        if (!content.isEmpty()) {
            int newline = content.indexOf('\n');
            return newline >= 0 ? content.substring(0, newline) : content;
        }
        return getString(R.string.notepad_default_title);
    }

    private String exportFileName() {
        String fileName = exportTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (fileName.isEmpty()) {
            fileName = getString(R.string.notepad_default_title);
        }
        return fileName + ".txt";
    }

    private void writeExport(Uri uri) {
        if (uri == null) {
            return;
        }
        saveNow();
        String text = exportTitle() + "\n\n" + contentInput.getText().toString();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IllegalStateException("Unable to open export destination");
            }
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, R.string.notepad_exported, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.notepad_export_failed, error.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show();
        }
    }
}
