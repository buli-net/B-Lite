package wallet.notepad;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import wallet.ui.BaseActivity;

import wallet.main.R;

public class NoteEditorActivity extends BaseActivity {

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveNow;
    private EditText titleInput;
    private EditText contentInput;
    private TextView saveStatus;
    private NoteStore store;
    private NoteStore.Note note;
    private boolean loading;
    private boolean changed;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("text/plain"),
                    this::writeExport);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_note_editor);
        store = new NoteStore(this);

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
        loading = true;
        long noteId = getIntent().getLongExtra(NotepadActivity.EXTRA_NOTE_ID, 0L);
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
