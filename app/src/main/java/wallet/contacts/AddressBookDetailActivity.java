package wallet.contacts;

import wallet.ui.BaseActivity;

import androidx.appcompat.app.AlertDialog;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Editable local contact profile with vCard-style, independently editable information cards. */
public final class AddressBookDetailActivity extends BaseActivity {
    public static final String EXTRA_ADDRESS = "contact_address";
    private AddressBookStore.Entry entry;
    private TextView profileName, profileOrganization, bitcoinAddress, phone, email, website, note;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_address_book_detail);
        Toolbar toolbar = findViewById(R.id.toolbar_address_book_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.address_book_detail_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        profileName = findViewById(R.id.contactProfileName);
        profileOrganization = findViewById(R.id.contactProfileOrganization);
        bitcoinAddress = findViewById(R.id.contactBitcoinAddress);
        phone = findViewById(R.id.contactPhone);
        email = findViewById(R.id.contactEmail);
        website = findViewById(R.id.contactWebsite);
        note = findViewById(R.id.contactNote);

        findViewById(R.id.editContactIdentityButton).setOnClickListener(v -> editIdentity());
        findViewById(R.id.editContactAddressButton).setOnClickListener(v -> editAddress());
        findViewById(R.id.editContactPhoneButton).setOnClickListener(v -> editField(
                R.string.address_book_phone_title, entry == null ? "" : entry.phone,
                InputType.TYPE_CLASS_PHONE, (value) -> saveEntry(entry.name, entry.address,
                        entry.organization, value, entry.email, entry.website, entry.note)));
        findViewById(R.id.editContactEmailButton).setOnClickListener(v -> editField(
                R.string.address_book_email_title, entry == null ? "" : entry.email,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                (value) -> saveEntry(entry.name, entry.address, entry.organization,
                        entry.phone, value, entry.website, entry.note)));
        findViewById(R.id.editContactWebsiteButton).setOnClickListener(v -> editField(
                R.string.address_book_website_title, entry == null ? "" : entry.website,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                (value) -> saveEntry(entry.name, entry.address, entry.organization,
                        entry.phone, entry.email, value, entry.note)));
        findViewById(R.id.editContactNoteButton).setOnClickListener(v -> editField(
                R.string.address_book_note_title, entry == null ? "" : entry.note,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                (value) -> saveEntry(entry.name, entry.address, entry.organization,
                        entry.phone, entry.email, entry.website, value)));
        findViewById(R.id.copyContactAddressButton).setOnClickListener(v -> copyAddress());
        findViewById(R.id.useContactAddressButton).setOnClickListener(v -> selectAddress());
        findViewById(R.id.deleteContactButton).setOnClickListener(v -> confirmDelete());

        String address = getIntent().getStringExtra(EXTRA_ADDRESS);
        entry = AddressBookStore.find(this, address);
        if (entry == null) {
            Toast.makeText(this, R.string.address_book_contact_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (entry != null) {
            AddressBookStore.Entry refreshed = AddressBookStore.find(this, entry.address);
            if (refreshed != null) {
                entry = refreshed;
                render();
            }
        }
    }

    private void render() {
        profileName.setText(entry.name);
        if (TextUtils.isEmpty(entry.organization)) {
            profileOrganization.setText(R.string.address_book_not_set);
            profileOrganization.setVisibility(View.GONE);
        } else {
            profileOrganization.setText(entry.organization);
            profileOrganization.setVisibility(View.VISIBLE);
        }
        bitcoinAddress.setText(entry.address);
        setOptional(phone, entry.phone);
        setOptional(email, entry.email);
        setOptional(website, entry.website);
        setOptional(note, entry.note);
    }

    private void setOptional(TextView view, String value) {
        view.setText(TextUtils.isEmpty(value) ? getString(R.string.address_book_not_set) : value);
    }

    private void editIdentity() {
        View content = getLayoutInflater().inflate(R.layout.dialog_address_book_identity, null);
        EditText name = content.findViewById(R.id.addressBookIdentityName);
        EditText organization = content.findViewById(R.id.addressBookIdentityOrganization);
        name.setText(entry.name);
        organization.setText(entry.organization);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_edit_identity)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = name.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, R.string.address_book_name_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (saveEntry(newName, entry.address, organization.getText().toString(), entry.phone,
                    entry.email, entry.website, entry.note)) dialog.dismiss();
        }));
        dialog.show();
    }

    private interface ValueSaver { boolean save(String value); }

    private void editField(int title, String current, int inputType, ValueSaver saver) {
        EditText input = new EditText(this);
        input.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setInputType(inputType);
        input.setText(current);
        input.setSelection(input.getText().length());
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_save, null)
                .setNeutralButton(R.string.address_book_clear, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (saver.save(input.getText().toString().trim())) dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (saver.save("")) dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void editAddress() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.address_book_address_hint);
        input.setText(entry.address);
        input.setSelection(input.getText().length());
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_edit_address_title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                Toast.makeText(this, R.string.address_book_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidAddress(value)) {
                Toast.makeText(this, R.string.address_book_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            if (saveEntry(entry.name, value, entry.organization, entry.phone, entry.email,
                    entry.website, entry.note)) dialog.dismiss();
        }));
        dialog.show();
    }

    private boolean saveEntry(String name, String address, String organization, String phone,
                              String email, String website, String note) {
        AddressBookStore.Entry updated = new AddressBookStore.Entry(name, address, organization,
                phone, email, website, note);
        if (!AddressBookStore.update(this, entry.address, updated)) {
            int message = !address.equals(entry.address)
                    && AddressBookStore.find(this, address) != null
                    ? R.string.address_book_duplicate : R.string.address_book_save_failed;
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return false;
        }
        entry = AddressBookStore.find(this, address);
        if (entry == null) entry = updated;
        render();
        setResult(Activity.RESULT_OK);
        Toast.makeText(this, R.string.address_book_contact_saved, Toast.LENGTH_SHORT).show();
        return true;
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

    private void copyAddress() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin address", entry.address));
            Toast.makeText(this, R.string.address_book_address_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void selectAddress() {
        setResult(Activity.RESULT_OK, new Intent().putExtra(
                AddressBookActivity.EXTRA_SELECTED_ADDRESS, entry.address));
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_delete_title)
                .setMessage(getString(R.string.address_book_delete_message, entry.name, entry.address))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_delete, (dialog, which) -> {
                    AddressBookStore.remove(this, entry.address);
                    setResult(Activity.RESULT_OK);
                    finish();
                }).show();
    }
}
