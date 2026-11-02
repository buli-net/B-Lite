package wallet.notepad;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import wallet.ui.BaseActivity;

import wallet.main.R;

public class NotepadActivity extends BaseActivity {

    public static final String EXTRA_NOTE_ID = "wallet.notepad.extra.NOTE_ID";
    private EditText searchInput;
    private TextView countView;
    private LinearLayout noteList;
    private Button trashButton;
    private boolean showingTrash;
    private boolean sortByTitle;
    private NoteStore store;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_notepad);
        store = new NoteStore(this);

        Toolbar toolbar = findViewById(R.id.toolbar_notepad);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.notepad_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        searchInput = findViewById(R.id.notepadSearch);
        countView = findViewById(R.id.notepadCount);
        noteList = findViewById(R.id.notepadList);
        trashButton = findViewById(R.id.notepadTrashButton);
        findViewById(R.id.notepadNewButton).setOnClickListener(v -> openEditor(0));
        trashButton.setOnClickListener(v -> {
            showingTrash = !showingTrash;
            updateTrashButton();
            render();
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                render();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        updateTrashButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (noteList != null && store != null) {
            NotepadAccess.request(this, this::render);
        }
    }

    @Override
    protected void onDestroy() {
        if (store != null) {
            store.close();
            store = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_notepad, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem sortItem = menu.findItem(R.id.menuNotepadSort);
        if (sortItem != null) {
            sortItem.setTitle(sortByTitle
                    ? R.string.notepad_sort_recent
                    : R.string.notepad_sort_title);
        }
        MenuItem emptyItem = menu.findItem(R.id.menuNotepadEmptyTrash);
        if (emptyItem != null) {
            emptyItem.setVisible(showingTrash);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menuNotepadSort) {
            sortByTitle = !sortByTitle;
            invalidateOptionsMenu();
            render();
            return true;
        }
        if (id == R.id.menuNotepadEmptyTrash) {
            confirmEmptyTrash();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateTrashButton() {
        trashButton.setText(showingTrash
                ? R.string.notepad_show_notes
                : R.string.notepad_show_trash);
        invalidateOptionsMenu();
    }

    private void render() {
        if (isFinishing() || isDestroyed() || noteList == null) {
            return;
        }
        noteList.removeAllViews();
        try {
            List<NoteStore.Note> notes = store.load(showingTrash);
            String query = searchInput.getText().toString().trim()
                    .toLowerCase(Locale.ROOT);
            if (!query.isEmpty()) {
                List<NoteStore.Note> filtered = new ArrayList<>();
                for (NoteStore.Note note : notes) {
                    if (note.title.toLowerCase(Locale.ROOT).contains(query)
                            || note.content.toLowerCase(Locale.ROOT).contains(query)) {
                        filtered.add(note);
                    }
                }
                notes = filtered;
            }
            if (sortByTitle) {
                Collections.sort(notes, Comparator
                        .comparing((NoteStore.Note note) -> displayTitle(note).toLowerCase(Locale.ROOT))
                        .thenComparingLong(note -> -note.updatedAt));
            }

            countView.setText(getResources().getQuantityString(
                    R.plurals.notepad_note_count, notes.size(), notes.size()));
            if (notes.isEmpty()) {
                TextView empty = (TextView) getLayoutInflater().inflate(
                        R.layout.item_empty_message, noteList, false);
                empty.setText(showingTrash
                        ? R.string.notepad_trash_empty
                        : (query.isEmpty()
                        ? R.string.notepad_empty
                        : R.string.notepad_search_empty));
                noteList.addView(empty);
                return;
            }

            for (NoteStore.Note note : notes) {
                addNoteRow(note);
            }
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.notepad_load_failed, error.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addNoteRow(NoteStore.Note note) {
        View row = getLayoutInflater().inflate(R.layout.item_notepad, noteList, false);
        TextView title = row.findViewById(R.id.notepadItemTitle);
        TextView preview = row.findViewById(R.id.notepadItemPreview);
        TextView updated = row.findViewById(R.id.notepadItemUpdated);
        TextView pinned = row.findViewById(R.id.notepadItemPinned);
        View menu = row.findViewById(R.id.notepadItemMenu);

        title.setText(displayTitle(note));
        preview.setText(preview(note.content));
        updated.setText(DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(note.updatedAt));
        pinned.setVisibility(note.pinned && !showingTrash ? View.VISIBLE : View.GONE);
        menu.setOnClickListener(v -> showNoteMenu(v, note));
        row.setOnClickListener(v -> {
            if (showingTrash) {
                showTrashedNoteActions(note);
            } else {
                openEditor(note.id);
            }
        });
        noteList.addView(row);
    }

    private void showNoteMenu(View anchor, NoteStore.Note note) {
        PopupMenu popup = new PopupMenu(this, anchor);
        if (showingTrash) {
            popup.getMenu().add(0, 1, 0, getString(R.string.notepad_restore));
            popup.getMenu().add(0, 2, 1, getString(R.string.notepad_delete_permanently));
        } else {
            popup.getMenu().add(0, 1, 0, getString(note.pinned
                    ? R.string.notepad_unpin : R.string.notepad_pin));
            popup.getMenu().add(0, 2, 1, getString(R.string.notepad_share));
            popup.getMenu().add(0, 3, 2, getString(R.string.notepad_move_to_trash));
        }
        popup.setOnMenuItemClickListener(item -> {
            if (showingTrash) {
                if (item.getItemId() == 1) {
                    store.restore(note.id);
                    render();
                    return true;
                }
                if (item.getItemId() == 2) {
                    confirmDeletePermanently(note);
                    return true;
                }
            } else {
                if (item.getItemId() == 1) {
                    store.setPinned(note.id, !note.pinned);
                    render();
                    return true;
                }
                if (item.getItemId() == 2) {
                    shareNote(note);
                    return true;
                }
                if (item.getItemId() == 3) {
                    store.moveToTrash(note.id);
                    render();
                    return true;
                }
            }
            return false;
        });
        popup.show();
    }

    private void showTrashedNoteActions(NoteStore.Note note) {
        new AlertDialog.Builder(this)
                .setTitle(displayTitle(note))
                .setItems(new CharSequence[]{
                        getString(R.string.notepad_restore),
                        getString(R.string.notepad_delete_permanently)
                }, (dialog, which) -> {
                    if (which == 0) {
                        store.restore(note.id);
                        render();
                    } else {
                        confirmDeletePermanently(note);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDeletePermanently(NoteStore.Note note) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notepad_delete_permanently_title)
                .setMessage(R.string.notepad_delete_permanently_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.notepad_delete_action, (dialog, which) -> {
                    store.deletePermanently(note.id);
                    render();
                })
                .show();
    }

    private void confirmEmptyTrash() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notepad_empty_trash_title)
                .setMessage(R.string.notepad_empty_trash_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.notepad_delete_action, (dialog, which) -> {
                    store.emptyTrash();
                    render();
                })
                .show();
    }

    private void shareNote(NoteStore.Note note) {
        String text = displayTitle(note) + "\n\n" + note.content;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, displayTitle(note));
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.notepad_share)));
    }

    private void openEditor(long noteId) {
        Intent intent = new Intent(this, NoteEditorActivity.class);
        if (noteId > 0) {
            intent.putExtra(EXTRA_NOTE_ID, noteId);
        }
        startActivity(intent);
    }

    static String displayTitle(NoteStore.Note note) {
        if (note.title != null && !note.title.trim().isEmpty()) {
            return note.title.trim();
        }
        String content = note.content == null ? "" : note.content.trim();
        if (!content.isEmpty()) {
            int end = content.indexOf('\n');
            String firstLine = end >= 0 ? content.substring(0, end) : content;
            return firstLine.length() > 48 ? firstLine.substring(0, 48) : firstLine;
        }
        return "";
    }

    private String preview(String content) {
        if (content == null || content.trim().isEmpty()) {
            return getString(R.string.notepad_no_content);
        }
        String compact = content.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 140 ? compact.substring(0, 140) + "…" : compact;
    }
}
