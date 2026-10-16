package wallet.tools;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.util.Arrays;

import wallet.main.BaseActivity;
import wallet.main.R;

/** Copies standard NDEF messages between compatible tags; it does not clone chip memory or credentials. */
public class NfcCopyActivity extends BaseActivity implements NfcAdapter.ReaderCallback {
    private static final int IDLE = 0;
    private static final int WAIT_SOURCE = 1;
    private static final int WAIT_DESTINATION = 2;
    private static final int PROCESSING = 3;

    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView tagView;
    private TextView payloadView;
    private Spinner modeSpinner;
    private Button readSourceButton;
    private Button writeButton;
    private Button finishButton;
    private volatile int phase = IDLE;
    private volatile NdefMessage sourceMessage;
    private volatile Tag pendingFormatTag;
    private volatile String pendingFormatTagId;
    private volatile String pendingFormatTech;
    private volatile String pendingProtectedPassword;
    private volatile String pendingProtectedTagId;
    private volatile boolean protectedPasswordPromptOpen;
    private int successfulCopies;
    private volatile boolean manyMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_copy);

        Toolbar toolbar = findViewById(R.id.toolbar_nfc_copy);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.nfc_copy_title);
        }

        statusView = findViewById(R.id.nfc_copy_status);
        tagView = findViewById(R.id.nfc_copy_tag);
        payloadView = findViewById(R.id.nfc_copy_payload);
        modeSpinner = findViewById(R.id.nfc_copy_mode);
        readSourceButton = findViewById(R.id.nfc_copy_read_source);
        writeButton = findViewById(R.id.nfc_copy_write_destination);
        finishButton = findViewById(R.id.nfc_copy_finish);

        modeSpinner.setAdapter(android.widget.ArrayAdapter.createFromResource(
                this, R.array.nfc_copy_modes, android.R.layout.simple_spinner_item));
        ((android.widget.ArrayAdapter<?>) modeSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                                 int position, long id) { manyMode = position == 1; }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { manyMode = false; }
        });

        readSourceButton.setOnClickListener(v -> {
            pendingProtectedPassword = null;
            pendingProtectedTagId = null;
            protectedPasswordPromptOpen = false;
            sourceMessage = null;
            successfulCopies = 0;
            phase = WAIT_SOURCE;
            updateUi(R.string.nfc_copy_wait_source);
            tagView.setText(R.string.nfc_copy_no_tag);
            payloadView.setText(R.string.nfc_copy_no_source);
        });
        writeButton.setOnClickListener(v -> {
            if (sourceMessage == null) {
                statusView.setText(R.string.nfc_copy_read_source_first);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_copy_confirm_title)
                    .setMessage(getString(R.string.nfc_copy_confirm_message,
                            modeSpinner.getSelectedItemPosition() == 1
                                    ? getString(R.string.nfc_copy_mode_many_label)
                                    : getString(R.string.nfc_copy_mode_one_label)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.nfc_copy_start, (d, which) -> {
                        phase = WAIT_DESTINATION;
                        updateUi(R.string.nfc_copy_wait_destination);
                        tagView.setText(R.string.nfc_copy_no_tag);
                    }).show();
        });
        finishButton.setOnClickListener(v -> finishSession());

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            statusView.setText(R.string.nfc_copy_unsupported);
            readSourceButton.setEnabled(false);
            writeButton.setEnabled(false);
            finishButton.setEnabled(false);
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_copy_disabled);
            readSourceButton.setEnabled(false);
            writeButton.setEnabled(false);
            finishButton.setEnabled(false);
        }
        renderPayload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;
        if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_copy_disabled);
            readSourceButton.setEnabled(false);
            writeButton.setEnabled(false);
            finishButton.setEnabled(false);
            return;
        }
        readSourceButton.setEnabled(phase == IDLE);
        finishButton.setEnabled(true);
        writeButton.setEnabled(sourceMessage != null && phase == IDLE);
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V;
        nfcAdapter.enableReaderMode(this, this, flags, null);
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        phase = IDLE;
        pendingFormatTag = null;
        pendingProtectedPassword = null;
        pendingProtectedTagId = null;
        protectedPasswordPromptOpen = false;
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        final int currentPhase = phase;
        if (currentPhase != WAIT_SOURCE && currentPhase != WAIT_DESTINATION) return;

        String tagId = toHex(tag.getId());
        String techText = tag.getTechList() == null ? "" : TextUtils.join(", ", tag.getTechList());
        if (protectedPasswordPromptOpen) return;

        String suppliedPassword = pendingProtectedPassword;
        if (suppliedPassword != null) {
            pendingProtectedPassword = null;
            String expectedTagId = pendingProtectedTagId;
            pendingProtectedTagId = null;
            if (expectedTagId == null || !expectedTagId.equalsIgnoreCase(tagId)) {
                final int retryPhase = currentPhase;
                runOnUiThread(() -> {
                    statusView.setText(R.string.nfc_reader_protected_wrong_tag);
                    tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
                    phase = retryPhase;
                    renderPayload();
                });
                return;
            }
            phase = PROCESSING;
            if (currentPhase == WAIT_SOURCE) readProtectedSource(tag, tagId, techText, suppliedPassword);
            else writeProtectedDestination(tag, tagId, techText, suppliedPassword);
            return;
        }

        if (currentPhase == WAIT_SOURCE && NfcHardwareSecurity.requiresPasswordForNdefRead(tag)) {
            promptForProtectedPassword(tagId, techText, currentPhase);
            return;
        }
        if (currentPhase == WAIT_DESTINATION && NfcHardwareSecurity.requiresPasswordForNdefWrite(tag)) {
            promptForProtectedPassword(tagId, techText, currentPhase);
            return;
        }

        if (currentPhase == WAIT_SOURCE) {
            phase = PROCESSING;
            readSource(tag, tagId, techText);
        } else {
            phase = PROCESSING;
            writeDestination(tag, tagId, techText);
        }
    }

    private void promptForProtectedPassword(String tagId, String techText, int actionPhase) {
        protectedPasswordPromptOpen = true;
        runOnUiThread(() -> {
            EditText passwordInput = new EditText(this);
            passwordInput.setSingleLine(true);
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordInput.setHint(R.string.nfc_hardware_password_hint);
            passwordInput.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(128) });
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            FrameLayout container = new FrameLayout(this);
            container.setPadding(padding, 0, padding, 0);
            container.addView(passwordInput, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            int message = actionPhase == WAIT_SOURCE
                    ? R.string.nfc_copy_protected_source_prompt
                    : R.string.nfc_copy_protected_destination_prompt;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_reader_protected_prompt_title)
                    .setMessage(message)
                    .setView(container)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        protectedPasswordPromptOpen = false;
                        pendingProtectedPassword = null;
                        pendingProtectedTagId = null;
                        phase = actionPhase == WAIT_SOURCE ? IDLE : WAIT_DESTINATION;
                        statusView.setText(R.string.nfc_security_cancelled);
                        renderPayload();
                    })
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String password = passwordInput.getText().toString();
                        if (password.trim().length() < 4) {
                            protectedPasswordPromptOpen = false;
                            pendingProtectedPassword = null;
                            pendingProtectedTagId = null;
                            phase = actionPhase == WAIT_SOURCE ? IDLE : WAIT_DESTINATION;
                            statusView.setText(R.string.nfc_reader_protected_bad_password_format);
                            renderPayload();
                            return;
                        }
                        pendingProtectedPassword = password;
                        pendingProtectedTagId = tagId;
                        protectedPasswordPromptOpen = false;
                        phase = actionPhase;
                        statusView.setText(actionPhase == WAIT_SOURCE
                                ? R.string.nfc_reader_protected_retap
                                : R.string.nfc_copy_protected_retap);
                        tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
                        renderPayload();
                    }).show();
        });
    }

    private void readProtectedSource(Tag tag, String tagId, String techText, String password) {
        String result;
        NdefMessage message = null;
        byte[] bytes = null;
        try {
            bytes = NfcHardwareSecurity.readProtectedNdef(tag, password);
            if (bytes == null || bytes.length == 0) {
                result = getString(R.string.nfc_copy_source_empty);
            } else {
                message = new NdefMessage(bytes);
                result = getString(R.string.nfc_copy_source_read, message.getRecords().length,
                        message.toByteArray().length);
            }
        } catch (IOException | FormatException | RuntimeException e) {
            result = getString(R.string.nfc_copy_operation_failed_generic);
            message = null;
        } finally {
            if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        }
        final String finalResult = result;
        final NdefMessage finalMessage = message;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
            sourceMessage = finalMessage == null ? null : new NdefMessage(finalMessage.getRecords());
            statusView.setText(finalResult);
            phase = IDLE;
            renderPayload();
        });
    }

    private void writeProtectedDestination(Tag tag, String tagId, String techText, String password) {
        String result;
        NdefMessage message = sourceMessage;
        if (message == null) {
            result = getString(R.string.nfc_copy_read_source_first);
        } else {
            try {
                NfcHardwareSecurity.writeProtectedNdef(tag, password, message.toByteArray());
                successfulCopies++;
                result = getString(R.string.nfc_copy_success_verified, successfulCopies);
            } catch (IOException | RuntimeException e) {
                result = getString(R.string.nfc_copy_operation_failed_generic);
            }
        }
        finishDestinationStep(result, tagId, techText);
    }

    private void readSource(Tag tag, String tagId, String techText) {
        String result;
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            result = getString(R.string.nfc_copy_source_not_ndef);
            final String finalResult = result;
            runOnUiThread(() -> {
                statusView.setText(finalResult);
                tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
                phase = IDLE;
                renderPayload();
            });
            return;
        }
        NdefMessage message = null;
        try {
            ndef.connect();
            message = ndef.getNdefMessage();
            if (message == null || message.getRecords().length == 0) {
                result = getString(R.string.nfc_copy_source_empty);
                message = null;
            } else {
                result = getString(R.string.nfc_copy_source_read, message.getRecords().length,
                        message.toByteArray().length);
            }
        } catch (IOException | FormatException | RuntimeException e) {
            result = getString(R.string.nfc_copy_operation_failed_generic);
            message = null;
        } finally {
            try { ndef.close(); } catch (IOException ignored) { }
        }
        final String finalResult = result;
        final NdefMessage finalMessage = message;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
            if (finalMessage != null) {
                sourceMessage = new NdefMessage(finalMessage.getRecords());
                statusView.setText(finalResult);
                phase = IDLE;
                renderPayload();
            } else {
                sourceMessage = null;
                statusView.setText(finalResult);
                phase = IDLE;
                renderPayload();
            }
        });
    }

    private void writeDestination(Tag tag, String tagId, String techText) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            NdefFormatable formatable = NdefFormatable.get(tag);
            if (formatable != null) {
                pendingFormatTag = tag;
                pendingFormatTagId = tagId;
                pendingFormatTech = techText;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.nfc_copy_format_target_title)
                        .setMessage(R.string.nfc_copy_format_target_message)
                        .setNegativeButton(android.R.string.cancel, (d, w) -> {
                            pendingFormatTag = null;
                            phase = IDLE;
                            statusView.setText(R.string.nfc_copy_cancelled);
                            renderPayload();
                        })
                        .setPositiveButton(R.string.nfc_copy_format_target_action, (d, w) -> {
                            Tag target = pendingFormatTag;
                            String targetId = pendingFormatTagId;
                            String targetTech = pendingFormatTech;
                            pendingFormatTag = null;
                            new Thread(() -> formatAndWriteTarget(target, targetId, targetTech),
                                    "nfc-copy-format").start();
                        })
                        .show());
            } else {
                runOnUiThread(() -> {
                    phase = modeIsMany() ? WAIT_DESTINATION : IDLE;
                    tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
                    statusView.setText(R.string.nfc_copy_destination_not_supported);
                    renderPayload();
                });
            }
            return;
        }

        String result;
        try {
            ndef.connect();
            if (!ndef.isWritable()) {
                result = getString(R.string.nfc_copy_destination_read_only);
            } else {
                int size = sourceMessage.toByteArray().length;
                if (size > ndef.getMaxSize()) {
                    result = getString(R.string.nfc_copy_destination_too_small, size, ndef.getMaxSize());
                } else {
                    ndef.writeNdefMessage(sourceMessage);
                    NdefMessage verify = ndef.getNdefMessage();
                    boolean verified = verify != null
                            && Arrays.equals(sourceMessage.toByteArray(), verify.toByteArray());
                    if (verified) {
                        successfulCopies++;
                        result = getString(R.string.nfc_copy_success_verified, successfulCopies);
                    } else {
                        result = getString(R.string.nfc_copy_success_unverified);
                    }
                }
            }
        } catch (IOException | FormatException | RuntimeException e) {
            result = getString(R.string.nfc_copy_operation_failed_generic);
        } finally {
            try { ndef.close(); } catch (IOException ignored) { }
        }
        finishDestinationStep(result, tagId, techText);
    }

    private void formatAndWriteTarget(Tag tag, String tagId, String techText) {
        if (tag == null || sourceMessage == null) {
            phase = IDLE;
            runOnUiThread(() -> {
                statusView.setText(R.string.nfc_copy_cancelled);
                renderPayload();
                readSourceButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            });
            return;
        }
        NdefFormatable formatable = NdefFormatable.get(tag);
        String result;
        if (formatable == null) {
            result = getString(R.string.nfc_copy_destination_not_supported);
        } else {
            try {
                formatable.connect();
                formatable.format(sourceMessage);
                successfulCopies++;
                result = getString(R.string.nfc_copy_formatted_and_written, successfulCopies);
            } catch (IOException | FormatException | RuntimeException e) {
                result = getString(R.string.nfc_copy_operation_failed_generic);
            } finally {
                try { formatable.close(); } catch (IOException ignored) { }
            }
        }
        finishDestinationStep(result, tagId == null ? "?" : tagId, techText == null ? "" : techText);
    }

    private void finishDestinationStep(String result, String tagId, String techText) {
        final boolean continueMany = modeIsMany() && sourceMessage != null;
        runOnUiThread(() -> {
            statusView.setText(result);
            tagView.setText(getString(R.string.nfc_copy_detected_tag, tagId, techText));
            phase = continueMany ? WAIT_DESTINATION : IDLE;
            if (!continueMany) {
                sourceMessage = null;
                renderPayload();
            } else {
                statusView.append("\n");
                statusView.append(getString(R.string.nfc_copy_many_next, successfulCopies));
                renderPayload();
            }
            readSourceButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
        });
    }

    private boolean modeIsMany() {
        return manyMode;
    }

    private void finishSession() {
        phase = IDLE;
        pendingProtectedPassword = null;
        pendingProtectedTagId = null;
        protectedPasswordPromptOpen = false;
        sourceMessage = null;
        pendingFormatTag = null;
        successfulCopies = 0;
        statusView.setText(R.string.nfc_copy_session_finished);
        tagView.setText(R.string.nfc_copy_no_tag);
        renderPayload();
    }

    private void renderPayload() {
        NdefMessage message = sourceMessage;
        if (message == null) {
            payloadView.setText(R.string.nfc_copy_no_source);
        } else {
            payloadView.setText(getString(R.string.nfc_copy_payload_summary,
                    message.getRecords().length, message.toByteArray().length));
        }
        boolean nfcAvailable = nfcAdapter != null && nfcAdapter.isEnabled();
        readSourceButton.setEnabled(nfcAvailable && phase == IDLE);
        writeButton.setEnabled(message != null && phase == IDLE && nfcAvailable);
        finishButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled()
                && (message != null || phase != IDLE));
    }

    private void updateUi(int message) {
        statusView.setText(message);
        renderPayload();
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
