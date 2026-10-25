package wallet.contacts;

import wallet.ui.BaseActivity;

import androidx.appcompat.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;
import java.util.List;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.ui.TextViewUtils;

/** Local Bitcoin address book summary. Contact details are shown on the detail screen. */
public final class AddressBookActivity extends BaseActivity {
    public static final String EXTRA_SELECTED_ADDRESS = "selected_address";
    private static final int REQUEST_CONTACT_DETAIL = 2410;

    private LinearLayout list;
    private TextView count;
    private EditText searchInput;
    private String searchQuery = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_address_book);
        Toolbar toolbar = findViewById(R.id.toolbar_address_book);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.address_book_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.addressBookList);
        count = findViewById(R.id.addressBookCount);
        searchInput = findViewById(R.id.addressBookSearch);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim();
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.addAddressButton).setOnClickListener(v -> showAddDialog());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) render();
    }

    private void render() {
        list.removeAllViews();
        List<AddressBookStore.Entry> entries = AddressBookStore.load(this);
        int totalCount = entries.size();
        String query = searchQuery.toLowerCase(java.util.Locale.ROOT);
        if (!query.isEmpty()) {
            List<AddressBookStore.Entry> filtered = new java.util.ArrayList<>();
            for (AddressBookStore.Entry entry : entries) {
                if (matches(entry, query)) filtered.add(entry);
            }
            entries = filtered;
        }
        count.setText(getResources().getQuantityString(
                R.plurals.address_book_saved_count, totalCount, totalCount));
        if (entries.isEmpty()) {
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, list, false);
            empty.setText(searchQuery.isEmpty()
                    ? getString(R.string.address_book_empty)
                    : getString(R.string.address_book_search_empty, searchQuery));
            list.addView(empty);
            return;
        }

        for (AddressBookStore.Entry entry : entries) {
            View row = getLayoutInflater().inflate(R.layout.item_address_book, list, false);
            TextView name = row.findViewById(R.id.addressBookName);
            ImageView avatarImage = row.findViewById(R.id.addressBookAvatarImage);
            TextView avatarInitials = row.findViewById(R.id.addressBookAvatarInitials);
            ContactAvatarView.bind(this, entry, avatarImage, avatarInitials);
            TextView organization = row.findViewById(R.id.addressBookOrganization);
            TextView address = row.findViewById(R.id.addressBookAddress);
            TextViewUtils.configureSelectableMiddleEllipsis(address);
            name.setText(entry.name);
            if (TextUtils.isEmpty(entry.organization)) {
                organization.setVisibility(View.GONE);
            } else {
                organization.setText(entry.organization);
                organization.setVisibility(View.VISIBLE);
            }
            TextViewUtils.setTextIfChanged(address, entry.address);
            row.setContentDescription(getString(R.string.address_book_open_details) + ": " + entry.name);
            row.setOnClickListener(v -> openDetails(entry.address));
            list.addView(row);
        }
    }


    private boolean matches(AddressBookStore.Entry entry, String query) {
        return contains(entry.name, query)
                || contains(entry.organization, query)
                || contains(entry.address, query)
                || contains(entry.phone, query)
                || contains(entry.email, query)
                || contains(entry.website, query)
                || contains(entry.note, query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private void openDetails(String address) {
        Intent intent = new Intent(this, AddressBookDetailActivity.class);
        intent.putExtra(AddressBookDetailActivity.EXTRA_ADDRESS, address);
        startActivityForResult(intent, REQUEST_CONTACT_DETAIL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CONTACT_DETAIL) return;
        if (resultCode == Activity.RESULT_OK && data != null
                && data.hasExtra(EXTRA_SELECTED_ADDRESS)) {
            setResult(Activity.RESULT_OK, new Intent().putExtra(
                    EXTRA_SELECTED_ADDRESS, data.getStringExtra(EXTRA_SELECTED_ADDRESS)));
            finish();
            return;
        }
        render();
    }

    private void showAddDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_address_book_add, null);
        EditText name = content.findViewById(R.id.addressBookNameInput);
        EditText address = content.findViewById(R.id.addressBookAddressInput);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_add_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            String value = address.getText().toString().trim();
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(value)) {
                Toast.makeText(this, R.string.address_book_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidAddress(value)) {
                Toast.makeText(this, R.string.address_book_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!AddressBookStore.add(this, label, value)) {
                Toast.makeText(this, R.string.address_book_duplicate, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private boolean isValidAddress(String value) {
        org.bitcoinj.core.NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        if (parameters == null) return false;
        try {
            Address.fromString(parameters, value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
