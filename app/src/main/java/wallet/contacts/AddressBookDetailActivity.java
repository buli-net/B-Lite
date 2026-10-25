package wallet.contacts;

import wallet.ui.BaseActivity;

import androidx.appcompat.app.AlertDialog;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Matrix;
import android.media.ExifInterface;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.UUID;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Editable local contact profile with vCard-style, independently editable information cards. */
public final class AddressBookDetailActivity extends BaseActivity {
    public static final String EXTRA_ADDRESS = "contact_address";
    private AddressBookStore.Entry entry;
    private TextView profileName, profileOrganization, bitcoinAddress, phone, email, website, note;
    private ImageView avatarImage;
    private TextView avatarInitials;
    private ActivityResultLauncher<String> avatarPicker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && entry != null) savePickedAvatar(uri);
        });
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
        avatarImage = findViewById(R.id.contactAvatarImage);
        avatarInitials = findViewById(R.id.contactAvatarInitials);
        findViewById(R.id.editContactAvatarButton).setOnClickListener(v -> editAvatar());
        avatarImage.setOnClickListener(v -> editAvatar());
        avatarInitials.setOnClickListener(v -> editAvatar());
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
        findViewById(R.id.shareContactQrButton).setOnClickListener(v -> showContactQr());
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
        ContactAvatarView.bind(this, entry, avatarImage, avatarInitials);
        avatarImage.setContentDescription(getString(R.string.address_book_avatar_edit));
        avatarInitials.setContentDescription(getString(R.string.address_book_avatar_edit));
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
                phone, email, website, note, entry.avatarFileName);
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

    private void editAvatar() {
        boolean hasAvatar = !TextUtils.isEmpty(entry.avatarFileName)
                && AddressBookStore.getAvatarFile(this, entry.avatarFileName) != null
                && AddressBookStore.getAvatarFile(this, entry.avatarFileName).isFile();
        if (hasAvatar) {
            String[] choices = {getString(R.string.address_book_avatar_choose),
                    getString(R.string.address_book_avatar_remove)};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.address_book_avatar_title)
                    .setItems(choices, (dialog, which) -> {
                        if (which == 0) avatarPicker.launch("image/*");
                        else confirmRemoveAvatar();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.address_book_avatar_title)
                    .setMessage(R.string.address_book_avatar_help)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.address_book_avatar_choose,
                            (dialog, which) -> avatarPicker.launch("image/*"))
                    .show();
        }
    }

    private void confirmRemoveAvatar() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_avatar_remove_title)
                .setMessage(R.string.address_book_avatar_remove_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_avatar_remove, (dialog, which) -> {
                    if (AddressBookStore.setAvatar(this, entry.address, "")) {
                        entry = AddressBookStore.find(this, entry.address);
                        render();
                        setResult(Activity.RESULT_OK);
                        Toast.makeText(this, R.string.address_book_avatar_removed, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.address_book_avatar_save_failed, Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void savePickedAvatar(Uri uri) {
        String fileName = null;
        try {
            fileName = importAvatar(uri);
            if (!AddressBookStore.setAvatar(this, entry.address, fileName)) {
                File file = AddressBookStore.getAvatarFile(this, fileName);
                if (file != null) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                Toast.makeText(this, R.string.address_book_avatar_save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            entry = AddressBookStore.find(this, entry.address);
            render();
            setResult(Activity.RESULT_OK);
            Toast.makeText(this, R.string.address_book_avatar_updated, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            if (fileName != null) {
                File file = AddressBookStore.getAvatarFile(this, fileName);
                if (file != null) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            Toast.makeText(this, R.string.address_book_avatar_load_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Copies and center-crops the selected photo into a small app-private JPEG. */
    private String importAvatar(Uri uri) throws IOException {
        File directory = AddressBookStore.getAvatarDirectory(this);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create avatar directory");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open selected image");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input != null) {
                orientation = new ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
            // Some image providers do not expose EXIF metadata; use the decoded orientation.
        }

        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 1024) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap source;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot reopen selected image");
            source = BitmapFactory.decodeStream(input, null, options);
        }
        if (source == null) throw new IOException("Unsupported image");
        Bitmap oriented = orientBitmap(source, orientation);
        if (oriented != source) {
            source.recycle();
            source = oriented;
        }

        Bitmap square = null;
        Bitmap output = null;
        String fileName = "avatar_" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        File target = AddressBookStore.getAvatarFile(this, fileName);
        try {
            int side = Math.min(source.getWidth(), source.getHeight());
            int left = (source.getWidth() - side) / 2;
            int top = (source.getHeight() - side) / 2;
            square = Bitmap.createBitmap(source, left, top, side, side);
            output = Bitmap.createScaledBitmap(square, 384, 384, true);
            if (target == null) throw new IOException("Invalid avatar target");
            try (FileOutputStream stream = new FileOutputStream(target)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                    throw new IOException("Could not encode avatar");
                }
                stream.flush();
            }
            return fileName;
        } catch (Exception error) {
            if (target != null && target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not process avatar", error);
        } finally {
            if (output != null && output != square && output != source) output.recycle();
            if (square != null && square != source) square.recycle();
            source.recycle();
        }
    }

    private Bitmap orientBitmap(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return source;
        }
        try {
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (RuntimeException ignored) {
            return source;
        }
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


    private void showContactQr() {
        try {
            String payload = "bitcoin:" + entry.address + "?label="
                    + java.net.URLEncoder.encode(entry.name, java.nio.charset.StandardCharsets.UTF_8.name());
            Bitmap qr = wallet.qr.QrCodeGenerator.generate(payload, 720);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            content.setPadding(padding, padding, padding, padding);
            ImageView image = new ImageView(this);
            image.setImageBitmap(qr);
            image.setAdjustViewBounds(true);
            image.setBackground(new ColorDrawable(Color.WHITE));
            content.addView(image, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView caption = new TextView(this);
            caption.setText(getString(R.string.address_book_qr_caption, entry.name, entry.address));
            caption.setTextColor(getResources().getColor(android.R.color.primary_text_light));
            caption.setPadding(0, padding / 2, 0, 0);
            content.addView(caption);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.address_book_share_qr_title)
                    .setView(content)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.address_book_share_text, (dialog, which) -> {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_TEXT, payload);
                        startActivity(Intent.createChooser(share, getString(R.string.address_book_share_contact)));
                    }).show();
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.address_book_qr_failed, Toast.LENGTH_LONG).show();
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
