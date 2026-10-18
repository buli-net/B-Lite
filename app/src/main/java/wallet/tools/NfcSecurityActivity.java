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

    private static final int ACTION_SET_PASSWORD = 1;
    private static final int ACTION_CHANGE_PASSWORD = 2;
    private static final int ACTION_REMOVE_PASSWORD = 3;

    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView tagView;
    private Button inspectButton;
    private Button clearButton;
    private Button setPasswordButton;
    private Button changePasswordButton;
    private Button removePasswordButton;
    private volatile int operation = IDLE;
    private volatile int pendingHardwareAction;
    private volatile String pendingCurrentSecret;
    private volatile String pendingNewSecret;
    private volatile String lastIdentifiedTagId;
    private volatile NfcHardwareSecurity.TagInfo lastTagInfo;
    private volatile boolean lastTagProtected;
    private volatile boolean lastTagCanSetPassword;
    private volatile boolean lastTagCanChangePassword;
    private volatile boolean lastTagCanRemovePassword;
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
        setHardwareButtonsEnabled(false);

        setPasswordButton.setOnClickListener(v -> showHardwareCredentialsDialog(ACTION_SET_PASSWORD));
        changePasswordButton.setOnClickListener(v -> showHardwareCredentialsDialog(ACTION_CHANGE_PASSWORD));
        removePasswordButton.setOnClickListener(v -> showHardwareCredentialsDialog(ACTION_REMOVE_PASSWORD));

        inspectButton.setOnClickListener(v -> {
            lastIdentifiedTagId = null;
            lastTagInfo = null;
            lastTagProtected = false;
            lastTagCanSetPassword = false;
            lastTagCanChangePassword = false;
            lastTagCanRemovePassword = false;
            operation = INSPECT;
            setHardwareButtonsEnabled(false);
            statusView.setText(R.string.nfc_security_wait_inspect);
            tagView.setText(R.string.nfc_security_no_tag);
        });
        clearButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_security_clear_confirm_title)
                .setMessage(R.string.nfc_security_clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.nfc_security_clear_confirm_action, (d, which) -> {
                    lastIdentifiedTagId = null;
                    lastTagInfo = null;
                    lastTagProtected = false;
                    lastTagCanSetPassword = false;
                    lastTagCanChangePassword = false;
                    lastTagCanRemovePassword = false;
                    operation = ERASE;
                    setHardwareButtonsEnabled(false);
                    statusView.setText(R.string.nfc_security_wait_erase);
                    tagView.setText(R.string.nfc_security_no_tag);
                }).show());

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            statusView.setText(R.string.nfc_security_unsupported);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_security_disabled);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
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
            setHardwareButtonsEnabled(false);
            return;
        }
        inspectButton.setEnabled(true);
        clearButton.setEnabled(true);
        setHardwareButtonsEnabled(false);
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
        lastIdentifiedTagId = null;
        lastTagInfo = null;
        lastTagProtected = false;
        lastTagCanSetPassword = false;
        lastTagCanChangePassword = false;
        lastTagCanRemovePassword = false;
        setHardwareButtonsEnabled(false);
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        int pending = operation;
        if (pending != INSPECT && pending != ERASE && pending != HARDWARE_SECURITY) return;
        String tagId = toHex(tag.getId());
        // Low-level Android technology names are implementation details. Show a simple NFC label;
        // the detected model and its supported actions are presented separately below.
        String techText = getString(R.string.nfc_security_tag_technology);

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
            clearProtectedNdef(tag, tagId, techText, clearPassword);
            return;
        }

        if (pending == ERASE && NfcHardwareSecurity.requiresPasswordForNdefWrite(tag)) {
            showClearPasswordDialog(tagId, techText);
            return;
        }

        operation = PROCESSING;
        runOnUiThread(() -> {
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
        });
        if (pending == ERASE) clearTag(tag, tagId, techText);
        else if (pending == HARDWARE_SECURITY) applyHardwareSecurity(tag, tagId, techText);
        else inspectTag(tag, tagId, techText);
    }

    private void showClearPasswordDialog(String tagId, String techText) {
        clearPasswordDialogOpen = true;
        operation = ERASE;
        runOnUiThread(() -> {
            LinearLayout fields = new LinearLayout(this);
            fields.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            fields.setPadding(pad, 0, pad, 0);
            EditText password = makeSecretInput(getString(R.string.nfc_security_current_password), 128);
            password.setHint(R.string.nfc_hardware_password_hint);
            fields.addView(password, matchParams());
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_reader_protected_prompt_title)
                    .setMessage(R.string.nfc_security_clear_password_required)
                    .setView(fields)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        clearPasswordDialogOpen = false;
                        operation = IDLE;
                        statusView.setText(R.string.nfc_security_cancelled);
                        setSecurityButtonsEnabled();
                    })
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String value = password.getText().toString();
                        if (value.trim().length() < 4) {
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
                        tagView.setText(getString(R.string.nfc_security_detected_tag, techText));
                        disableSecurityActions();
                    }).show();
        });
    }

    private void clearProtectedNdef(Tag tag, String tagId, String techText, String password) {
        String result;
        try {
            NfcHardwareSecurity.clearProtectedNdef(tag, password);
            result = getString(R.string.nfc_security_clear_verified);
        } catch (IOException | RuntimeException e) {
            result = getString(R.string.nfc_security_operation_failed_generic);
        }
        lastTagProtected = NfcHardwareSecurity.appearsPasswordWriteProtected(tag);
        finishHardwareAction(result, tagId, techText);
    }

    private void disableSecurityActions() {
        inspectButton.setEnabled(false);
        clearButton.setEnabled(false);
        setHardwareButtonsEnabled(false);
    }

    private void setSecurityButtonsEnabled() {
        boolean available = nfcAdapter != null && nfcAdapter.isEnabled();
        inspectButton.setEnabled(available);
        clearButton.setEnabled(available);
        setHardwareButtonsEnabled(available);
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
                result = getString(R.string.nfc_security_inspect_failed_generic);
            } finally {
                try { ndef.close(); } catch (IOException ignored) { }
            }
        } else if (MifareClassic.get(tag) != null && NfcHardwareSecurity.supportsHardwarePassword(tag)) {
            result = getString(R.string.nfc_security_classic_ndef_info);
        } else if (NdefFormatable.get(tag) != null) {
            result = getString(R.string.nfc_security_formatable_info);
        } else if (MifareClassic.get(tag) != null) {
            result = getString(R.string.nfc_security_classic_info);
        } else {
            result = getString(R.string.nfc_security_unknown_info);
        }
        lastTagProtected = chipInfo.hardwarePasswordImplemented
                && NfcHardwareSecurity.appearsPasswordWriteProtected(tag);
        lastTagCanSetPassword = chipInfo.hardwarePasswordImplemented
                && NfcHardwareSecurity.canSetHardwarePassword(tag);
        lastTagCanChangePassword = chipInfo.hardwarePasswordImplemented && lastTagProtected
                && NfcHardwareSecurity.canChangeHardwarePassword(tag);
        lastTagCanRemovePassword = chipInfo.hardwarePasswordImplemented && lastTagProtected
                && NfcHardwareSecurity.canRemoveHardwarePassword(tag);
        int protectionStatusText = !chipInfo.hardwarePasswordImplemented
                ? R.string.nfc_security_hardware_password_unknown
                : lastTagProtected ? R.string.nfc_security_hardware_password_active
                : R.string.nfc_security_hardware_password_not_active;
        String statusResult = result + "\n\n" + getString(R.string.nfc_security_chip_identification,
                getString(chipNameResource(chipInfo.family)), getString(chipDetailsResource(chipInfo.family)))
                + "\n\n" + getString(protectionStatusText);
        if (chipInfo.hardwarePasswordImplemented && !lastTagProtected && !lastTagCanSetPassword) {
            int reason = chipInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC
                    ? R.string.nfc_security_classic_set_unavailable
                    : R.string.nfc_security_password_set_unavailable;
            statusResult += "\n\n" + getString(reason);
        } else if (chipInfo.hardwarePasswordImplemented && lastTagProtected
                && !lastTagCanChangePassword && !lastTagCanRemovePassword) {
            statusResult += "\n\n" + getString(R.string.nfc_security_password_management_unavailable);
        }
        final String uiStatusText = new String(statusResult);
        runOnUiThread(() -> {
            lastIdentifiedTagId = tagId;
            lastTagInfo = chipInfo;
            tagView.setText(getString(R.string.nfc_security_detected_tag, techText));
            statusView.setText(uiStatusText);
            operation = IDLE;
            boolean available = nfcAdapter != null && nfcAdapter.isEnabled();
            inspectButton.setEnabled(available);
            clearButton.setEnabled(available);
            setHardwareButtonsEnabled(available);
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
                result = getString(R.string.nfc_security_operation_failed_generic);
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
                    result = getString(R.string.nfc_security_operation_failed_generic);
                } finally {
                    try { formatable.close(); } catch (IOException ignored) { }
                }
            }
        }
        final String finalResult = result;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_security_detected_tag, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            inspectButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            clearButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            setHardwareButtonsEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
        });
    }

    private void showHardwareCredentialsDialog(int action) {
        if (lastTagInfo == null || !isPasswordDriverFamily(lastTagInfo.family) || lastIdentifiedTagId == null) {
            statusView.setText(R.string.nfc_security_wait_inspect);
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);
        boolean classicCard = lastTagInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC;
        // Initial setup uses the detected factory/default credential automatically. Ask for the
        // user's current password only when changing or removing an already active protection.
        EditText current = (action == ACTION_CHANGE_PASSWORD || action == ACTION_REMOVE_PASSWORD)
                ? makeSecretInput(getString(R.string.nfc_security_current_password), 128) : null;
        EditText next = action == ACTION_REMOVE_PASSWORD ? null
                : makeSecretInput(getString(R.string.nfc_security_new_password), 128);
        EditText confirm = action == ACTION_REMOVE_PASSWORD ? null
                : makeSecretInput(getString(R.string.nfc_security_confirm_password), 128);
        if (current != null) fields.addView(current, matchParams());
        if (next != null) fields.addView(next, matchParams());
        if (confirm != null) fields.addView(confirm, matchParams());
        final EditText currentInput = current;
        final EditText nextInput = next;
        final EditText confirmInput = confirm;
        int title = action == ACTION_SET_PASSWORD ? R.string.nfc_security_action_set_title
                : action == ACTION_CHANGE_PASSWORD ? R.string.nfc_security_action_change_title
                : R.string.nfc_security_action_remove_title;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(classicCard
                        ? (action == ACTION_SET_PASSWORD
                            ? R.string.nfc_security_classic_password_first_setup_message
                            : R.string.nfc_security_classic_password_dialog_message)
                        : R.string.nfc_security_password_dialog_message)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String oldPass = currentInput == null ? "" : currentInput.getText().toString();
                    String newPass = nextInput == null ? "" : nextInput.getText().toString();
                    String confirmation = confirmInput == null ? "" : confirmInput.getText().toString();
                    if (((action == ACTION_CHANGE_PASSWORD || action == ACTION_REMOVE_PASSWORD)
                            && oldPass.trim().length() < 4)
                            || (action == ACTION_SET_PASSWORD && classicCard && !oldPass.isEmpty()
                            && oldPass.trim().length() < 4)) {
                        statusView.setText(R.string.nfc_security_password_input_error);
                        return;
                    }
                    if (action != ACTION_REMOVE_PASSWORD
                            && (newPass.trim().length() < 4 || !newPass.equals(confirmation))) {
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

    private void applyHardwareSecurity(Tag tag, String tagId, String techText) {
        if (lastIdentifiedTagId == null || !lastIdentifiedTagId.equalsIgnoreCase(tagId)) {
            clearPendingSecrets();
            lastIdentifiedTagId = null;
            lastTagInfo = null;
            lastTagProtected = false;
            lastTagCanSetPassword = false;
            lastTagCanChangePassword = false;
            lastTagCanRemovePassword = false;
            finishHardwareAction(getString(R.string.nfc_security_action_wrong_tag), tagId, techText);
            return;
        }
        int action = pendingHardwareAction;
        String current = pendingCurrentSecret;
        String next = pendingNewSecret;
        String result;
        try {
            NfcHardwareSecurity.NtagAction hardwareAction = action == ACTION_SET_PASSWORD
                    ? NfcHardwareSecurity.NtagAction.SET
                    : action == ACTION_CHANGE_PASSWORD ? NfcHardwareSecurity.NtagAction.CHANGE
                    : NfcHardwareSecurity.NtagAction.REMOVE;
            NfcHardwareSecurity.updateHardwarePasswordFromUserText(tag, hardwareAction, current, next);
            int successResource = action == ACTION_SET_PASSWORD ? R.string.nfc_security_password_set_success
                    : action == ACTION_CHANGE_PASSWORD ? R.string.nfc_security_password_change_success
                    : (lastTagInfo != null && lastTagInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC)
                            ? R.string.nfc_security_classic_password_remove_success
                            : R.string.nfc_security_password_remove_success;
            result = getString(successResource);
        } catch (IOException | RuntimeException e) {
            result = getString(R.string.nfc_security_operation_failed_generic);
        } finally {
            clearPendingSecrets();
        }
        // Always refresh the status after an attempted hardware operation. A sector write can fail
        // partway through, so stale UI state must not imply that protection is unchanged.
        try {
            lastTagInfo = NfcHardwareSecurity.identify(tag);
            lastTagProtected = lastTagInfo.hardwarePasswordImplemented
                    && NfcHardwareSecurity.appearsPasswordWriteProtected(tag);
            lastTagCanSetPassword = lastTagInfo.hardwarePasswordImplemented
                    && NfcHardwareSecurity.canSetHardwarePassword(tag);
            lastTagCanChangePassword = lastTagInfo.hardwarePasswordImplemented && lastTagProtected
                    && NfcHardwareSecurity.canChangeHardwarePassword(tag);
            lastTagCanRemovePassword = lastTagInfo.hardwarePasswordImplemented && lastTagProtected
                    && NfcHardwareSecurity.canRemoveHardwarePassword(tag);
        } catch (RuntimeException ignored) {
            lastTagInfo = null;
            lastTagProtected = false;
            lastTagCanSetPassword = false;
            lastTagCanChangePassword = false;
            lastTagCanRemovePassword = false;
        }
        finishHardwareAction(result, tagId, techText);
    }

    private int chipNameResource(NfcHardwareSecurity.Family family) {
        switch (family) {
            case NTAG210: return R.string.nfc_chip_name_ntag210;
            case NTAG212: return R.string.nfc_chip_name_ntag212;
            case NTAG213: return R.string.nfc_chip_name_ntag213;
            case NTAG215: return R.string.nfc_chip_name_ntag215;
            case NTAG216: return R.string.nfc_chip_name_ntag216;
            case MIFARE_ULTRALIGHT_EV1_48: return R.string.nfc_chip_name_ultralight_ev1_48;
            case MIFARE_ULTRALIGHT_EV1_128: return R.string.nfc_chip_name_ultralight_ev1_128;
            case MIFARE_CLASSIC: return R.string.nfc_chip_name_classic;
            case MIFARE_ULTRALIGHT_OTHER: return R.string.nfc_chip_name_type2_unknown;
            case ISO_DEP: return R.string.nfc_chip_name_isodep;
            case NFC_V: return R.string.nfc_chip_name_nfc_v;
            case NFC_F: return R.string.nfc_chip_name_nfc_f;
            case NFC_A: return R.string.nfc_chip_name_nfc_a;
            case NFC_B: return R.string.nfc_chip_name_nfc_b;
            case NDEF_ONLY: return R.string.nfc_chip_name_ndef_unknown;
            default: return R.string.nfc_chip_name_unknown;
        }
    }

    private int chipDetailsResource(NfcHardwareSecurity.Family family) {
        switch (family) {
            case NTAG210:
            case NTAG212:
            case NTAG213:
            case NTAG215:
            case NTAG216:
            case MIFARE_ULTRALIGHT_EV1_48:
            case MIFARE_ULTRALIGHT_EV1_128:
                return R.string.nfc_chip_details_type2_supported;
            case MIFARE_CLASSIC:
                return R.string.nfc_chip_details_classic;
            case MIFARE_ULTRALIGHT_OTHER:
                return R.string.nfc_chip_details_type2_unknown;
            case ISO_DEP: return R.string.nfc_chip_details_isodep;
            case NFC_V: return R.string.nfc_chip_details_nfc_v;
            case NFC_F: return R.string.nfc_chip_details_nfc_f;
            case NFC_A: return R.string.nfc_chip_details_nfc_a;
            case NFC_B: return R.string.nfc_chip_details_nfc_b;
            case NDEF_ONLY: return R.string.nfc_chip_details_ndef_unknown;
            default: return R.string.nfc_chip_details_unknown;
        }
    }

    private boolean isPasswordDriverFamily(NfcHardwareSecurity.Family family) {
        return family == NfcHardwareSecurity.Family.NTAG210 || family == NfcHardwareSecurity.Family.NTAG212
                || family == NfcHardwareSecurity.Family.NTAG213 || family == NfcHardwareSecurity.Family.NTAG215
                || family == NfcHardwareSecurity.Family.NTAG216
                || family == NfcHardwareSecurity.Family.MIFARE_ULTRALIGHT_EV1_48
                || family == NfcHardwareSecurity.Family.MIFARE_ULTRALIGHT_EV1_128
                || family == NfcHardwareSecurity.Family.MIFARE_CLASSIC;
    }

    private void finishHardwareAction(String result, String tagId, String techText) {
        final String finalResult = result;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_security_detected_tag, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            setSecurityButtonsEnabled();
        });
    }

    private void setHardwareButtonsEnabled(boolean enabled) {
        boolean driverAvailable = enabled && lastTagInfo != null
                && lastTagInfo.hardwarePasswordImplemented && isPasswordDriverFamily(lastTagInfo.family);
        boolean showSet = driverAvailable && !lastTagProtected && lastTagCanSetPassword;
        boolean showChange = driverAvailable && lastTagProtected && lastTagCanChangePassword;
        boolean showRemove = driverAvailable && lastTagProtected && lastTagCanRemovePassword;
        if (setPasswordButton != null) {
            setPasswordButton.setVisibility(showSet ? android.view.View.VISIBLE : android.view.View.GONE);
            setPasswordButton.setEnabled(showSet);
        }
        if (changePasswordButton != null) {
            changePasswordButton.setVisibility(showChange ? android.view.View.VISIBLE : android.view.View.GONE);
            changePasswordButton.setEnabled(showChange);
        }
        if (removePasswordButton != null) {
            removePasswordButton.setVisibility(showRemove ? android.view.View.VISIBLE : android.view.View.GONE);
            removePasswordButton.setEnabled(showRemove);
        }
    }

    private EditText makeSecretInput(String hint, int maxLength) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(hint);
        input.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(maxLength)
        });
        return input;
    }

    private LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void clearPendingSecrets() {
        pendingCurrentSecret = null;
        pendingNewSecret = null;
        pendingHardwareAction = 0;
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
