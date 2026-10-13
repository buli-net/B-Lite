package wallet.tools;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;

import wallet.main.BaseActivity;
import wallet.main.R;

/** Performs explicit, standard NDEF clearing/formatting and reports tag security capabilities conservatively. */
public class NfcSecurityActivity extends BaseActivity implements NfcAdapter.ReaderCallback {
    private static final int IDLE = 0;
    private static final int INSPECT = 1;
    private static final int ERASE = 2;
    private static final int PROCESSING = 3;
    private static final int HARDWARE_SECURITY = 4;

    private static final int ACTION_SET_NTAG = 1;
    private static final int ACTION_CHANGE_NTAG = 2;
    private static final int ACTION_REMOVE_NTAG = 3;
    private static final int ACTION_CHANGE_CLASSIC_KEYS = 4;

    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView tagView;
    private Button inspectButton;
    private Button clearButton;
    private Button setPasswordButton;
    private Button changePasswordButton;
    private Button removePasswordButton;
    private Button classicKeysButton;
    private volatile int operation = IDLE;
    private volatile int pendingHardwareAction;
    private volatile String pendingCurrentSecret;
    private volatile String pendingNewSecret;
    private volatile String lastIdentifiedTagId;
    private volatile NfcHardwareSecurity.TagInfo lastTagInfo;
    private volatile String pendingClearPassword;
    private volatile String pendingClearTagId;
    private volatile boolean clearPasswordDialogOpen;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_security);

        Toolbar toolbar = findViewById(R.id.toolbar_nfc_security);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.nfc_security_title);
        }

        statusView = findViewById(R.id.nfc_security_status);
        tagView = findViewById(R.id.nfc_security_tag);
        inspectButton = findViewById(R.id.nfc_security_inspect);
        clearButton = findViewById(R.id.nfc_security_clear);
        setPasswordButton = findViewById(R.id.nfc_security_set_password);
        changePasswordButton = findViewById(R.id.nfc_security_change_password);
        removePasswordButton = findViewById(R.id.nfc_security_remove_password);
        classicKeysButton = findViewById(R.id.nfc_security_classic_keys);

        setPasswordButton.setOnClickListener(v -> showNtagCredentialsDialog(ACTION_SET_NTAG));
        changePasswordButton.setOnClickListener(v -> showNtagCredentialsDialog(ACTION_CHANGE_NTAG));
        removePasswordButton.setOnClickListener(v -> showNtagCredentialsDialog(ACTION_REMOVE_NTAG));
        classicKeysButton.setOnClickListener(v -> showClassicKeysDialog());

        inspectButton.setOnClickListener(v -> {
            operation = INSPECT;
            statusView.setText(R.string.nfc_security_wait_inspect);
            tagView.setText(R.string.nfc_security_no_tag);
        });
        clearButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_security_clear_confirm_title)
                .setMessage(R.string.nfc_security_clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.nfc_security_clear_confirm_action, (d, which) -> {
                    operation = ERASE;
                    statusView.setText(R.string.nfc_security_wait_erase);
                    tagView.setText(R.string.nfc_security_no_tag);
                }).show());

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            statusView.setText(R.string.nfc_security_unsupported);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            setHardwareButtonsEnabled(false, false);
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_security_disabled);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            setHardwareButtonsEnabled(false, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;
        if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_security_disabled);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            setHardwareButtonsEnabled(false, false);
            return;
        }
        inspectButton.setEnabled(true);
        clearButton.setEnabled(true);
        setHardwareButtonsEnabled(lastTagInfo != null && lastTagInfo.family != NfcHardwareSecurity.Family.UNKNOWN,
                lastTagInfo != null && lastTagInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC);
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V;
        nfcAdapter.enableReaderMode(this, this, flags, null);
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        operation = IDLE;
        clearPendingSecrets();
        pendingClearPassword = null;
        pendingClearTagId = null;
        clearPasswordDialogOpen = false;
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        int pending = operation;
        if (pending != INSPECT && pending != ERASE && pending != HARDWARE_SECURITY) return;
        String tagId = toHex(tag.getId());
        String techText = tag.getTechList() == null ? "" : TextUtils.join(", ", tag.getTechList());

        if (clearPasswordDialogOpen) return;
        String clearPassword = pendingClearPassword;
        if (clearPassword != null && pending == ERASE) {
            pendingClearPassword = null;
            String expected = pendingClearTagId;
            pendingClearTagId = null;
            if (expected == null || !expected.equalsIgnoreCase(tagId)) {
                operation = IDLE;
                finishHardwareAction(getString(R.string.nfc_security_action_wrong_tag), tagId, techText);
                return;
            }
            operation = PROCESSING;
            clearProtectedNtag(tag, tagId, techText, clearPassword);
            return;
        }

        if (pending == ERASE && !clearPasswordDialogOpen
                && NfcHardwareSecurity.supportsHardwarePassword(tag)
                && NfcHardwareSecurity.appearsPasswordWriteProtected(tag)) {
            showClearPasswordDialog(tagId, techText);
            return;
        }

        operation = PROCESSING;
        runOnUiThread(() -> {
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
        });
        if (pending == ERASE) clearTag(tag, tagId, techText);
        else if (pending == HARDWARE_SECURITY) applyHardwareSecurity(tag, tagId, techText);
        else inspectTag(tag, tagId, techText);
    }

    private void showClearPasswordDialog(String tagId, String techText) {
        clearPasswordDialogOpen = true;
        operation = ERASE;
        runOnUiThread(() -> {
            EditText password = makeSecretInput("XXXXXXXX", 8);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            android.widget.FrameLayout container = new android.widget.FrameLayout(this);
            container.setPadding(pad, 0, pad, 0);
            container.addView(password, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_reader_protected_prompt_title)
                    .setMessage(R.string.nfc_security_clear_password_required)
                    .setView(container)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        clearPasswordDialogOpen = false;
                        operation = IDLE;
                        statusView.setText(R.string.nfc_security_cancelled);
                        setSecurityButtonsEnabled();
                    })
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String value = password.getText().toString().trim();
                        if (!isHexLength(value, 8)) {
                            clearPasswordDialogOpen = false;
                            operation = IDLE;
                            statusView.setText(R.string.nfc_reader_protected_bad_password_format);
                            setSecurityButtonsEnabled();
                            return;
                        }
                        pendingClearPassword = value;
                        pendingClearTagId = tagId;
                        clearPasswordDialogOpen = false;
                        operation = ERASE;
                        statusView.setText(R.string.nfc_security_clear_password_waiting);
                        tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
                        disableSecurityActions();
                    }).show();
        });
    }

    private void clearProtectedNtag(Tag tag, String tagId, String techText, String password) {
        String result;
        try {
            NdefMessage emptyMessage = new NdefMessage(new NdefRecord(
                    NdefRecord.TNF_EMPTY, null, null, new byte[0]));
            NfcHardwareSecurity.writeProtectedNdef(tag, password, emptyMessage.toByteArray());
            result = getString(R.string.nfc_security_clear_verified);
        } catch (IOException | FormatException | RuntimeException e) {
            result = getString(R.string.nfc_security_operation_failed,
                    e.getClass().getSimpleName(), safeMessage(e));
        }
        finishHardwareAction(result, tagId, techText);
    }

    private void disableSecurityActions() {
        inspectButton.setEnabled(false);
        clearButton.setEnabled(false);
        setPasswordButton.setEnabled(false);
        changePasswordButton.setEnabled(false);
        removePasswordButton.setEnabled(false);
        classicKeysButton.setEnabled(false);
    }

    private void setSecurityButtonsEnabled() {
        boolean available = nfcAdapter != null && nfcAdapter.isEnabled();
        inspectButton.setEnabled(available);
        clearButton.setEnabled(available);
        setPasswordButton.setEnabled(available && lastTagInfo != null && isPasswordDriverFamily(lastTagInfo.family));
        changePasswordButton.setEnabled(available && lastTagInfo != null && isPasswordDriverFamily(lastTagInfo.family));
        removePasswordButton.setEnabled(available && lastTagInfo != null && isPasswordDriverFamily(lastTagInfo.family));
        classicKeysButton.setEnabled(available && lastTagInfo != null
                && lastTagInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC);
    }

    private void inspectTag(Tag tag, String tagId, String techText) {
        String result;
        NfcHardwareSecurity.TagInfo chipInfo = NfcHardwareSecurity.identify(tag);
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                result = getString(R.string.nfc_security_ndef_info,
                        ndef.isWritable() ? getString(R.string.nfc_security_writable) : getString(R.string.nfc_security_read_only),
                        ndef.getMaxSize());
            } catch (IOException | RuntimeException e) {
                result = getString(R.string.nfc_security_inspect_failed,
                        e.getClass().getSimpleName(), safeMessage(e));
            } finally {
                try { ndef.close(); } catch (IOException ignored) { }
            }
        } else if (NdefFormatable.get(tag) != null) {
            result = getString(R.string.nfc_security_formatable_info);
        } else if (MifareClassic.get(tag) != null) {
            result = getString(R.string.nfc_security_classic_info);
        } else {
            result = getString(R.string.nfc_security_unknown_info);
        }
        final String finalResult = result + "\n\n" + getString(R.string.nfc_security_chip_identification,
                chipInfo.name, chipInfo.details);
        runOnUiThread(() -> {
            lastIdentifiedTagId = tagId;
            lastTagInfo = chipInfo;
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            boolean available = nfcAdapter != null && nfcAdapter.isEnabled();
            inspectButton.setEnabled(available);
            clearButton.setEnabled(available);
            setPasswordButton.setEnabled(available && isPasswordDriverFamily(chipInfo.family));
            changePasswordButton.setEnabled(available && isPasswordDriverFamily(chipInfo.family));
            removePasswordButton.setEnabled(available && isPasswordDriverFamily(chipInfo.family));
            classicKeysButton.setEnabled(available && chipInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC);
        });
    }

    private void clearTag(Tag tag, String tagId, String techText) {
        String result;
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                if (!ndef.isWritable()) {
                    result = getString(R.string.nfc_security_read_only_no_change);
                } else {
                    NdefRecord emptyRecord = new NdefRecord(NdefRecord.TNF_EMPTY, null, null, new byte[0]);
                    ndef.writeNdefMessage(new NdefMessage(emptyRecord));
                    try {
                        NdefMessage readBack = ndef.getNdefMessage();
                        result = readBack != null && readBack.getRecords().length == 1
                                && readBack.getRecords()[0].getTnf() == NdefRecord.TNF_EMPTY
                                && readBack.getRecords()[0].getPayload().length == 0
                                ? getString(R.string.nfc_security_clear_verified)
                                : getString(R.string.nfc_security_clear_unverified);
                    } catch (IOException | FormatException | RuntimeException e) {
                        result = getString(R.string.nfc_security_clear_unverified);
                    }
                }
            } catch (IOException | FormatException | RuntimeException e) {
                result = getString(R.string.nfc_security_operation_failed,
                        e.getClass().getSimpleName(), safeMessage(e));
            } finally {
                try { ndef.close(); } catch (IOException ignored) { }
            }
        } else {
            NdefFormatable formatable = NdefFormatable.get(tag);
            if (formatable == null) {
                result = getString(R.string.nfc_security_clear_unsupported);
            } else {
                try {
                    formatable.connect();
                    formatable.format(new NdefMessage(
                            new NdefRecord(NdefRecord.TNF_EMPTY, null, null, new byte[0])));
                    result = getString(R.string.nfc_security_format_success);
                } catch (IOException | FormatException | RuntimeException e) {
                    result = getString(R.string.nfc_security_operation_failed,
                            e.getClass().getSimpleName(), safeMessage(e));
                } finally {
                    try { formatable.close(); } catch (IOException ignored) { }
                }
            }
        }
        final String finalResult = result;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            inspectButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            clearButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
        });
    }

    private void showNtagCredentialsDialog(int action) {
        if (lastTagInfo == null || !isPasswordDriverFamily(lastTagInfo.family) || lastIdentifiedTagId == null) {
            statusView.setText(R.string.nfc_security_wait_inspect);
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);
        EditText current = null;
        current = makeSecretInput(getString(R.string.nfc_security_current_password), 8);
        fields.addView(current, matchParams());
        EditText next = null;
        EditText confirm = null;
        if (action != ACTION_REMOVE_NTAG) {
            next = makeSecretInput(getString(R.string.nfc_security_new_password), 8);
            confirm = makeSecretInput(getString(R.string.nfc_security_confirm_password), 8);
            fields.addView(next, matchParams());
            fields.addView(confirm, matchParams());
        }
        final EditText currentInput = current;
        final EditText nextInput = next;
        final EditText confirmInput = confirm;
        int title = action == ACTION_SET_NTAG ? R.string.nfc_security_action_set_title
                : action == ACTION_CHANGE_NTAG ? R.string.nfc_security_action_change_title
                : R.string.nfc_security_action_remove_title;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(R.string.nfc_security_password_dialog_message)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String oldPass = currentInput == null ? "" : currentInput.getText().toString().trim();
                    String newPass = nextInput == null ? "" : nextInput.getText().toString().trim();
                    String confirmation = confirmInput == null ? "" : confirmInput.getText().toString().trim();
                    if ((action == ACTION_CHANGE_NTAG || action == ACTION_REMOVE_NTAG) && oldPass.isEmpty()) {
                        statusView.setText(R.string.nfc_security_password_input_error);
                        return;
                    }
                    if (action != ACTION_REMOVE_NTAG && !newPass.equals(confirmation)) {
                        statusView.setText(R.string.nfc_security_password_input_error);
                        return;
                    }
                    if (action != ACTION_REMOVE_NTAG && !isHexLength(newPass, 8)) {
                        statusView.setText(R.string.nfc_security_password_input_error);
                        return;
                    }
                    if (!oldPass.isEmpty() && !isHexLength(oldPass, 8)) {
                        statusView.setText(R.string.nfc_security_password_input_error);
                        return;
                    }
                    pendingHardwareAction = action;
                    pendingCurrentSecret = oldPass;
                    pendingNewSecret = newPass;
                    operation = HARDWARE_SECURITY;
                    statusView.setText(R.string.nfc_security_action_waiting);
                    tagView.setText(R.string.nfc_security_no_tag);
                    disableSecurityActions();
                }).show();
    }

    private void showClassicKeysDialog() {
        if (lastTagInfo == null || lastTagInfo.family != NfcHardwareSecurity.Family.MIFARE_CLASSIC
                || lastIdentifiedTagId == null) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);
        EditText current = makeSecretInput(getString(R.string.nfc_security_current_classic_key), 12);
        EditText next = makeSecretInput(getString(R.string.nfc_security_new_classic_key), 12);
        EditText confirm = makeSecretInput(getString(R.string.nfc_security_confirm_classic_key), 12);
        fields.addView(current, matchParams());
        fields.addView(next, matchParams());
        fields.addView(confirm, matchParams());
        new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_security_action_classic_title)
                .setMessage(R.string.nfc_security_classic_keys_dialog_message)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String oldKey = current.getText().toString().trim();
                    String newKey = next.getText().toString().trim();
                    String confirmKey = confirm.getText().toString().trim();
                    if (!isHexLength(oldKey, 12) || !isHexLength(newKey, 12) || !newKey.equals(confirmKey)) {
                        statusView.setText(R.string.nfc_security_password_input_error);
                        return;
                    }
                    pendingHardwareAction = ACTION_CHANGE_CLASSIC_KEYS;
                    pendingCurrentSecret = oldKey;
                    pendingNewSecret = newKey;
                    operation = HARDWARE_SECURITY;
                    statusView.setText(R.string.nfc_security_action_waiting);
                    tagView.setText(R.string.nfc_security_no_tag);
                    disableSecurityActions();
                }).show();
    }

    private EditText makeSecretInput(String hint, int maxLength) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(maxLength) });
        return input;
    }

    private LinearLayout.LayoutParams matchParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (6 * getResources().getDisplayMetrics().density);
        return params;
    }

    private void applyHardwareSecurity(Tag tag, String tagId, String techText) {
        if (lastIdentifiedTagId == null || !lastIdentifiedTagId.equalsIgnoreCase(tagId)) {
            finishHardwareAction(getString(R.string.nfc_security_action_wrong_tag), tagId, techText);
            return;
        }
        int action = pendingHardwareAction;
        String current = pendingCurrentSecret;
        String next = pendingNewSecret;
        String result;
        try {
            if (action == ACTION_CHANGE_CLASSIC_KEYS) {
                if (NfcHardwareSecurity.identify(tag).family != NfcHardwareSecurity.Family.MIFARE_CLASSIC) {
                    throw new IOException("The scanned tag is not MIFARE Classic.");
                }
                result = NfcHardwareSecurity.changeMifareClassicKeys(tag, current, next);
            } else {
                NfcHardwareSecurity.NtagAction ntagAction = action == ACTION_SET_NTAG
                        ? NfcHardwareSecurity.NtagAction.SET
                        : action == ACTION_CHANGE_NTAG ? NfcHardwareSecurity.NtagAction.CHANGE
                        : NfcHardwareSecurity.NtagAction.REMOVE;
                result = NfcHardwareSecurity.updateHardwarePassword(tag, ntagAction, current, next);
            }
        } catch (IOException | RuntimeException e) {
            result = getString(R.string.nfc_security_operation_failed,
                    e.getClass().getSimpleName(), safeMessage(e));
        } finally {
            clearPendingSecrets();
        }
        finishHardwareAction(result, tagId, techText);
    }

    private void finishHardwareAction(String result, String tagId, String techText) {
        final String finalResult = result;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            setSecurityButtonsEnabled();
        });
    }

    private void setHardwareButtonsEnabled(boolean enabled, boolean classic) {
        if (setPasswordButton != null) setPasswordButton.setEnabled(enabled && lastTagInfo != null && isPasswordDriverFamily(lastTagInfo.family));
        if (changePasswordButton != null) changePasswordButton.setEnabled(enabled && lastTagInfo != null && isPasswordDriverFamily(lastTagInfo.family));
        if (removePasswordButton != null) removePasswordButton.setEnabled(enabled && lastTagInfo != null && isPasswordDriverFamily(lastTagInfo.family));
        if (classicKeysButton != null) classicKeysButton.setEnabled(enabled && classic);
    }

    private boolean isPasswordDriverFamily(NfcHardwareSecurity.Family family) {
        return family == NfcHardwareSecurity.Family.NTAG210 || family == NfcHardwareSecurity.Family.NTAG212
                || family == NfcHardwareSecurity.Family.NTAG213 || family == NfcHardwareSecurity.Family.NTAG215
                || family == NfcHardwareSecurity.Family.NTAG216
                || family == NfcHardwareSecurity.Family.MIFARE_ULTRALIGHT_EV1_48
                || family == NfcHardwareSecurity.Family.MIFARE_ULTRALIGHT_EV1_128;
    }

    private boolean isHexLength(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) if (Character.digit(value.charAt(i), 16) < 0) return false;
        return true;
    }

    private void clearPendingSecrets() {
        pendingCurrentSecret = null;
        pendingNewSecret = null;
        pendingHardwareAction = 0;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? getString(R.string.nfc_common_no_details) : message;
    }

    private String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "—";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02X", value));
        return out.toString();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
