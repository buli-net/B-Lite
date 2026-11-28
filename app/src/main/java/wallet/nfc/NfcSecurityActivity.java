package wallet.nfc;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.text.InputType;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;

import wallet.ui.BaseActivity;
import wallet.ui.LocalizedErrors;
import wallet.main.R;

/** Performs chip-aware user-data erasure and capability-limited restoration of writable defaults. */
public class NfcSecurityActivity extends BaseActivity implements NfcAdapter.ReaderCallback {
    private static final int IDLE = 0;
    private static final int INSPECT = 1;
    private static final int ERASE = 2;
    private static final int PROCESSING = 3;
    private static final int HARDWARE_SECURITY = 4;
    private static final int FORMAT = 5;

    private static final int ACTION_SET_PASSWORD = 1;
    private static final int ACTION_CHANGE_PASSWORD = 2;
    private static final int ACTION_REMOVE_PASSWORD = 3;

    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView tagView;
    private Button inspectButton;
    private Button clearButton;
    private Button formatButton;
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
        formatButton = findViewById(R.id.nfc_security_format);
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
        clearButton.setOnClickListener(v -> confirmDestructiveOperation(ERASE));
        formatButton.setOnClickListener(v -> confirmDestructiveOperation(FORMAT));

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            statusView.setText(R.string.nfc_security_unsupported);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            formatButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_security_disabled);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            formatButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
        }
    }

    private void confirmDestructiveOperation(int action) {
        boolean formatting = action == FORMAT;
        new AlertDialog.Builder(this)
                .setTitle(formatting ? R.string.nfc_security_format_confirm_title
                        : R.string.nfc_security_erase_confirm_title)
                .setMessage(formatting ? R.string.nfc_security_format_confirm_message
                        : R.string.nfc_security_erase_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(formatting ? R.string.nfc_security_format_confirm_action
                        : R.string.nfc_security_erase_confirm_action, (d, which) -> {
                    lastIdentifiedTagId = null;
                    lastTagInfo = null;
                    lastTagProtected = false;
                    lastTagCanSetPassword = false;
                    lastTagCanChangePassword = false;
                    lastTagCanRemovePassword = false;
                    operation = action;
                    setHardwareButtonsEnabled(false);
                    statusView.setText(formatting ? R.string.nfc_security_wait_format
                            : R.string.nfc_security_wait_erase);
                    tagView.setText(R.string.nfc_security_no_tag);
                }).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;
        if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_security_disabled);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            formatButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
            return;
        }
        inspectButton.setEnabled(true);
        clearButton.setEnabled(true);
        formatButton.setEnabled(true);
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
        if (pending != INSPECT && pending != ERASE && pending != FORMAT
                && pending != HARDWARE_SECURITY) return;
        String tagId = Nfc.toHex(tag.getId(), getString(R.string.value_unavailable_placeholder));
        // Low-level Android technology names are implementation details. Show a simple NFC label;
        // the detected model and its supported actions are presented separately below.
        String techText = getString(R.string.nfc_security_tag_technology);

        if (clearPasswordDialogOpen) return;
        String clearPassword = pendingClearPassword;
        if (clearPassword != null && (pending == ERASE || pending == FORMAT)) {
            pendingClearPassword = null;
            String expected = pendingClearTagId;
            pendingClearTagId = null;
            if (expected == null || !expected.equalsIgnoreCase(tagId)) {
                operation = IDLE;
                finishHardwareAction(getString(R.string.nfc_security_action_wrong_tag), tagId, techText);
                return;
            }
            operation = PROCESSING;
            clearProtectedNdef(tag, tagId, techText, clearPassword, pending == FORMAT);
            return;
        }

        MifareClassic classicTech = MifareClassic.get(tag);
        boolean classicCard = classicTech != null;
        boolean ultralightC = isUltralightC(tag);
        boolean operationNeedsPassword = (pending == FORMAT
                && NfcHardwareSecurity.requiresPasswordForFactoryRestore(tag))
                || (pending == ERASE && NfcHardwareSecurity.requiresPasswordForNdefWrite(tag));
        if ((pending == ERASE || pending == FORMAT)
                && (ultralightC || classicCard || operationNeedsPassword)) {
            showClearPasswordDialog(tagId, techText, pending, classicCard, ultralightC);
            return;
        }

        operation = PROCESSING;
        runOnUiThread(() -> {
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
            formatButton.setEnabled(false);
            setHardwareButtonsEnabled(false);
        });
        if (pending == ERASE) clearTag(tag, tagId, techText);
        else if (pending == FORMAT) formatTag(tag, tagId, techText);
        else if (pending == HARDWARE_SECURITY) applyHardwareSecurity(tag, tagId, techText);
        else inspectTag(tag, tagId, techText);
    }

    private void showClearPasswordDialog(String tagId, String techText, int action,
                                         boolean classicCard, boolean ultralightC) {
        clearPasswordDialogOpen = true;
        operation = action;
        boolean formatting = action == FORMAT;
        runOnUiThread(() -> {
            View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
            EditText password = passwordView.findViewById(R.id.dialogPasswordInput);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setHint(ultralightC ? R.string.nfc_security_ultralight_c_key_hint
                    : R.string.nfc_hardware_password_hint);
            password.setFilters(new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(128)
            });
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_reader_protected_prompt_title)
                    .setMessage(classicCard ? R.string.nfc_security_classic_maintenance_key_optional
                            : ultralightC ? R.string.nfc_security_ultralight_c_maintenance_key_optional
                            : formatting ? R.string.nfc_security_format_password_required
                            : R.string.nfc_security_clear_password_required)
                    .setView(passwordView)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        clearPasswordDialogOpen = false;
                        operation = IDLE;
                        statusView.setText(R.string.nfc_security_cancelled);
                        setSecurityButtonsEnabled();
                    })
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String value = password.getText().toString().trim();
                        if (ultralightC) {
                            String compactKey = value.replace(" ", "").replace(":", "").replace("-", "");
                            if (!compactKey.isEmpty() && !compactKey.matches("(?i)[0-9a-f]{32}")) {
                                clearPasswordDialogOpen = false;
                                operation = IDLE;
                                statusView.setText(R.string.nfc_security_ultralight_c_key_format);
                                setSecurityButtonsEnabled();
                                return;
                            }
                            value = compactKey;
                        } else if ((!classicCard || !value.isEmpty()) && value.length() < 4) {
                            clearPasswordDialogOpen = false;
                            operation = IDLE;
                            statusView.setText(R.string.nfc_reader_protected_bad_password_format);
                            setSecurityButtonsEnabled();
                            return;
                        }
                        pendingClearPassword = value;
                        pendingClearTagId = tagId;
                        clearPasswordDialogOpen = false;
                        operation = action;
                        statusView.setText(formatting ? R.string.nfc_security_format_password_waiting
                                : R.string.nfc_security_clear_password_waiting);
                        tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
                        disableSecurityActions();
                    }).show();
        });
    }

    private void clearProtectedNdef(Tag tag, String tagId, String techText, String password,
                                    boolean formatting) {
        String result;
        try {
            NfcHardwareSecurity.MaintenanceResult maintenance = formatting
                    ? NfcHardwareSecurity.restoreFactoryDefaults(tag, password)
                    : NfcHardwareSecurity.eraseUserData(tag, password);
            result = maintenanceMessage(maintenance);
        } catch (IOException | RuntimeException e) {
            result = maintenanceError(e);
        }
        lastTagProtected = NfcHardwareSecurity.appearsPasswordWriteProtected(tag);
        finishHardwareAction(result, tagId, techText);
    }

    private boolean isUltralightC(Tag tag) {
        // Use the exact chip signature first. Android's generic technology enum alone can
        // misclassify NTAG21x as Ultralight C on some devices.
        return NfcHardwareSecurity.isUltralightC(tag);
    }

    private void disableSecurityActions() {
        inspectButton.setEnabled(false);
        clearButton.setEnabled(false);
        formatButton.setEnabled(false);
        setHardwareButtonsEnabled(false);
    }

    private void setSecurityButtonsEnabled() {
        boolean available = nfcAdapter != null && nfcAdapter.isEnabled();
        inspectButton.setEnabled(available);
        clearButton.setEnabled(available);
        formatButton.setEnabled(available);
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
                try {
                    ndef.close();
                } catch (IOException ignored) { }
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
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
            statusView.setText(uiStatusText);
            operation = IDLE;
            boolean available = nfcAdapter != null && nfcAdapter.isEnabled();
            inspectButton.setEnabled(available);
            clearButton.setEnabled(available);
            formatButton.setEnabled(available);
            setHardwareButtonsEnabled(available);
        });
    }

    private void clearTag(Tag tag, String tagId, String techText) {
        String result;
        try {
            result = maintenanceMessage(NfcHardwareSecurity.eraseUserData(tag, null));
        } catch (IOException | RuntimeException e) {
            result = maintenanceError(e);
        }
        finishDestructiveAction(result, tagId, techText);
    }

    private void formatTag(Tag tag, String tagId, String techText) {
        String result;
        try {
            result = maintenanceMessage(NfcHardwareSecurity.restoreFactoryDefaults(tag, null));
        } catch (IOException | RuntimeException e) {
            result = maintenanceError(e);
        }
        finishDestructiveAction(result, tagId, techText);
    }

    private String maintenanceMessage(NfcHardwareSecurity.MaintenanceResult result) {
        if (result == null) return getString(R.string.nfc_security_operation_failed_generic);
        switch (result.code) {
            case TYPE2_ERASED:
                return getString(R.string.nfc_security_type2_erased, result.completed);
            case TYPE2_ERASE_PARTIAL:
                return getString(R.string.nfc_security_type2_erase_partial,
                        result.completed, result.total, result.firstFailedPage);
            case ULTRALIGHT_C_ERASED:
                return getString(R.string.nfc_security_ultralight_c_erased, result.completed);
            case ULTRALIGHT_C_ERASE_PARTIAL:
                return getString(R.string.nfc_security_ultralight_c_erase_partial,
                        result.completed, result.total, result.firstFailedPage);
            case CLASSIC_ERASED:
                return getString(R.string.nfc_security_classic_erased, result.completed, result.total);
            case CLASSIC_ERASE_PARTIAL:
                return getString(R.string.nfc_security_classic_erase_partial, result.completed, result.total);
            case NDEF_ERASED:
                return getString(R.string.nfc_security_ndef_only_erased);
            case NDEF_FORMATTED:
                return getString(R.string.nfc_security_ndef_formatted_only);
            case TYPE2_FACTORY_LIMITED:
                return getString(R.string.nfc_security_type2_factory_limited, result.completed,
                        getString(result.configurationRestored ? R.string.nfc_security_config_defaults_restored
                                : R.string.nfc_security_config_defaults_not_restored));
            case TYPE2_FACTORY_PARTIAL:
                return getString(R.string.nfc_security_type2_factory_partial,
                        result.completed, result.total, result.firstFailedPage,
                        getString(result.configurationRestored ? R.string.nfc_security_config_defaults_restored
                                : R.string.nfc_security_config_defaults_not_restored));
            case ULTRALIGHT_C_FACTORY_LIMITED:
                return getString(R.string.nfc_security_ultralight_c_factory_limited, result.completed,
                        getString(result.configurationRestored
                                ? R.string.nfc_security_ultralight_c_factory_config_restored
                                : R.string.nfc_security_ultralight_c_factory_config_not_restored));
            case ULTRALIGHT_C_FACTORY_PARTIAL:
                return getString(R.string.nfc_security_ultralight_c_factory_partial,
                        result.completed, result.total, result.firstFailedPage,
                        getString(result.configurationRestored
                                ? R.string.nfc_security_ultralight_c_factory_config_restored
                                : R.string.nfc_security_ultralight_c_factory_config_not_restored));
            case CLASSIC_FACTORY_LIMITED:
                return getString(R.string.nfc_security_classic_factory_limited, result.completed, result.total);
            case CLASSIC_FACTORY_PARTIAL:
                return getString(R.string.nfc_security_classic_factory_partial, result.completed, result.total);
            case FACTORY_UNSUPPORTED:
            default:
                return getString(R.string.nfc_security_factory_reset_unsupported);
        }
    }

    private String maintenanceError(Throwable error) {
        String detail = LocalizedErrors.resolve(this, error);
        if (detail == null || detail.trim().isEmpty() || detail.equals(getString(R.string.error_unknown))) {
            return getString(R.string.nfc_security_operation_failed_generic);
        }
        return detail;
    }

    private void finishDestructiveAction(String result, String tagId, String techText) {
        final String finalResult = result;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            setSecurityButtonsEnabled();
        });
    }

    private void showHardwareCredentialsDialog(int action) {
        if (lastTagInfo == null || !isPasswordDriverFamily(lastTagInfo.family) || lastIdentifiedTagId == null) {
            statusView.setText(R.string.nfc_security_wait_inspect);
            return;
        }
        View fields = getLayoutInflater().inflate(R.layout.dialog_nfc_security_credentials, null);
        boolean classicCard = lastTagInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC;
        // Use a single XML form for all three actions; hide fields that do not apply to the action.
        EditText currentField = fields.findViewById(R.id.nfcSecurityCurrentPassword);
        EditText nextField = fields.findViewById(R.id.nfcSecurityNewPassword);
        EditText confirmField = fields.findViewById(R.id.nfcSecurityConfirmPassword);
        EditText current = (action == ACTION_CHANGE_PASSWORD || action == ACTION_REMOVE_PASSWORD)
                ? currentField : null;
        EditText next = action == ACTION_REMOVE_PASSWORD ? null : nextField;
        EditText confirm = action == ACTION_REMOVE_PASSWORD ? null : confirmField;
        currentField.setVisibility(current == null ? View.GONE : View.VISIBLE);
        nextField.setVisibility(next == null ? View.GONE : View.VISIBLE);
        confirmField.setVisibility(confirm == null ? View.GONE : View.VISIBLE);
        for (EditText input : new EditText[] {currentField, nextField, confirmField}) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setFilters(new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(128)
            });
        }
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
            if (action == ACTION_REMOVE_PASSWORD) {
                // Verify the resulting security state independently before displaying the success
                // message. Type-2 tags must be eligible for a fresh setup; Classic NDEF sectors
                // must no longer require a private key.
                boolean protectionStillActive = NfcHardwareSecurity.appearsPasswordWriteProtected(tag);
                if (protectionStillActive) {
                    throw new IOException("Hardware password removal could not be verified");
                }
            }
            int successResource = action == ACTION_SET_PASSWORD ? R.string.nfc_security_password_set_success
                    : action == ACTION_CHANGE_PASSWORD ? R.string.nfc_security_password_change_success
                    : (lastTagInfo != null && lastTagInfo.family == NfcHardwareSecurity.Family.MIFARE_CLASSIC)
                            ? R.string.nfc_security_classic_password_remove_success
                            : R.string.nfc_security_password_remove_success;
            result = getString(successResource);
        } catch (IOException | RuntimeException e) {
            String detail = LocalizedErrors.resolve(this, e);
            result = detail == null || detail.trim().isEmpty()
                    || detail.equals(getString(R.string.error_unknown))
                    ? getString(R.string.nfc_security_operation_failed_generic) : detail;
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
            case MIFARE_ULTRALIGHT_C: return R.string.nfc_chip_name_ultralight_c;
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
            case MIFARE_ULTRALIGHT_C:
                return R.string.nfc_chip_details_ultralight_c;
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
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
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

    private void clearPendingSecrets() {
        pendingCurrentSecret = null;
        pendingNewSecret = null;
        pendingHardwareAction = 0;
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
