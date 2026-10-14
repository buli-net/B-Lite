package wallet.tools;

import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.util.Patterns;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import wallet.main.BaseActivity;
import wallet.main.R;

/** Writes user-confirmed NDEF text, URI or vCard data to compatible writable NFC tags. */
public class NfcWriteActivity extends BaseActivity implements NfcAdapter.ReaderCallback {
    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView detectedView;
    private TextView payloadSizeView;
    private Spinner modeSpinner;
    private EditText contentField;
    private EditText nameField;
    private EditText organizationField;
    private EditText titleField;
    private EditText phoneField;
    private EditText emailField;
    private EditText websiteField;
    private EditText mimeTypeField;
    private TextView contentLabel;
    private LinearLayout contactFields;
    private LinearLayout mimeFields;
    private Button prepareButton;
    private Button checkCapabilityButton;

    private volatile boolean checkArmed;
    private volatile boolean writeArmed;
    private volatile NdefMessage pendingCheckMessage;
    private volatile NdefMessage pendingMessage;
    private volatile String pendingProtectedWritePassword;
    private volatile String pendingProtectedWriteTagId;
    private volatile boolean protectedWritePromptOpen;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_write);

        Toolbar toolbar = findViewById(R.id.toolbar_nfc_write);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.nfc_writer_title);
        }

        statusView = findViewById(R.id.nfc_writer_status);
        detectedView = findViewById(R.id.nfc_writer_detected);
        payloadSizeView = findViewById(R.id.nfc_writer_payload_size);
        modeSpinner = findViewById(R.id.nfc_writer_mode);
        contentField = findViewById(R.id.nfc_writer_content);
        nameField = findViewById(R.id.nfc_writer_name);
        organizationField = findViewById(R.id.nfc_writer_organization);
        titleField = findViewById(R.id.nfc_writer_job_title);
        phoneField = findViewById(R.id.nfc_writer_phone);
        emailField = findViewById(R.id.nfc_writer_email);
        websiteField = findViewById(R.id.nfc_writer_website);
        mimeTypeField = findViewById(R.id.nfc_writer_mime_type);
        contentLabel = findViewById(R.id.nfc_writer_content_label);
        contactFields = findViewById(R.id.nfc_writer_contact_fields);
        mimeFields = findViewById(R.id.nfc_writer_mime_fields);
        prepareButton = findViewById(R.id.nfc_writer_prepare);
        checkCapabilityButton = findViewById(R.id.nfc_writer_check_capability);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.nfc_write_modes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateModeFields(position);
                updatePayloadEstimate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        contentField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        nameField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        organizationField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        titleField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        phoneField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        emailField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        websiteField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        mimeTypeField.addTextChangedListener(simpleTextWatcher(this::updatePayloadEstimate));
        checkCapabilityButton.setOnClickListener(v -> {
            pendingProtectedWritePassword = null;
            pendingProtectedWriteTagId = null;
            if (checkArmed) {
                checkArmed = false;
                pendingCheckMessage = null;
                checkCapabilityButton.setText(R.string.nfc_writer_check_capability);
                statusView.setText(R.string.nfc_writer_ready);
                detectedView.setText(R.string.nfc_writer_no_tag);
            } else {
                writeArmed = false;
                pendingMessage = null;
                prepareButton.setText(R.string.nfc_writer_prepare);
                try {
                    pendingCheckMessage = buildMessage(false);
                } catch (IllegalArgumentException incompleteContent) {
                    pendingCheckMessage = null;
                }
                checkArmed = true;
                checkCapabilityButton.setText(R.string.nfc_writer_cancel_check);
                statusView.setText(R.string.nfc_writer_check_waiting);
                detectedView.setText(R.string.nfc_writer_no_tag);
            }
        });



        prepareButton.setOnClickListener(v -> {
            if (checkArmed) {
                checkArmed = false;
                pendingCheckMessage = null;
                checkCapabilityButton.setText(R.string.nfc_writer_check_capability);
            }
            if (writeArmed) {
                writeArmed = false;
                pendingMessage = null;
                pendingProtectedWritePassword = null;
                pendingProtectedWriteTagId = null;
                prepareButton.setText(R.string.nfc_writer_prepare);
                statusView.setText(R.string.nfc_writer_ready);
            } else {
                confirmPrepareWrite();
            }
        });

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            statusView.setText(R.string.nfc_writer_unsupported);
            prepareButton.setEnabled(false);
            checkCapabilityButton.setEnabled(false);
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_writer_disabled);
            prepareButton.setEnabled(false);
            checkCapabilityButton.setEnabled(false);
        } else {
            statusView.setText(R.string.nfc_writer_ready);
            prepareButton.setEnabled(true);
            checkCapabilityButton.setEnabled(true);
        }
        updateModeFields(0);
        updatePayloadEstimate();
    }

    private android.text.TextWatcher simpleTextWatcher(Runnable action) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        };
    }

    private void updateModeFields(int mode) {
        boolean contactMode = mode == 2;
        boolean mimeMode = mode == 5;
        contactFields.setVisibility(contactMode ? View.VISIBLE : View.GONE);
        mimeFields.setVisibility(mimeMode ? View.VISIBLE : View.GONE);
        contentLabel.setVisibility(contactMode ? View.GONE : View.VISIBLE);
        contentField.setVisibility(contactMode ? View.GONE : View.VISIBLE);
        if (mode == 0) {
            contentLabel.setText(R.string.nfc_writer_content_label_text);
            contentField.setHint(R.string.nfc_writer_content_hint_text);
            contentField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            contentField.setMinLines(3);
            contentField.setSingleLine(false);
        } else if (mode == 1) {
            contentLabel.setText(R.string.nfc_writer_content_label_url);
            contentField.setHint(R.string.nfc_writer_content_hint_url);
            contentField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            contentField.setMinLines(1);
            contentField.setSingleLine(true);
        } else if (mode == 3) {
            contentLabel.setText(R.string.nfc_writer_content_label_phone);
            contentField.setHint(R.string.nfc_writer_content_hint_phone);
            contentField.setInputType(InputType.TYPE_CLASS_PHONE);
            contentField.setMinLines(1);
            contentField.setSingleLine(true);
        } else if (mode == 4) {
            contentLabel.setText(R.string.nfc_writer_content_label_email);
            contentField.setHint(R.string.nfc_writer_content_hint_email);
            contentField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            contentField.setMinLines(1);
            contentField.setSingleLine(true);
        } else if (mode == 6) {
            contentLabel.setText(R.string.nfc_writer_content_label_bitcoin_uri);
            contentField.setHint(R.string.nfc_writer_content_hint_bitcoin_uri);
            contentField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            contentField.setMinLines(2);
            contentField.setSingleLine(false);
        } else if (mimeMode) {
            contentLabel.setText(R.string.nfc_writer_content_label_mime_payload);
            contentField.setHint(R.string.nfc_writer_content_hint_mime_payload);
            contentField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            contentField.setMinLines(3);
            contentField.setSingleLine(false);
        }
    }

    private void updatePayloadEstimate() {
        try {
            NdefMessage message = buildMessage(false);
            payloadSizeView.setText(getString(R.string.nfc_writer_payload_estimate, message.toByteArray().length));
        } catch (IllegalArgumentException ignored) {
            payloadSizeView.setText(R.string.nfc_writer_payload_estimate_unavailable);
        }
    }

    private void confirmPrepareWrite() {
        NdefMessage message;
        try {
            message = buildMessage(true);
        } catch (IllegalArgumentException e) {
            String validationMessage = e.getMessage();
            CharSequence messageText = validationMessage == null || validationMessage.trim().isEmpty()
                    ? getString(R.string.nfc_writer_invalid_input) : validationMessage;
            Toast.makeText(this, messageText, Toast.LENGTH_LONG).show();
            return;
        }
        int bytes = message.toByteArray().length;
        new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_writer_confirm_title)
                .setMessage(getString(R.string.nfc_writer_confirm_message, bytes))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    writeArmed = false;
                    pendingMessage = null;
                })
                .setPositiveButton(R.string.nfc_writer_confirm_action, (dialog, which) -> {
                    pendingMessage = message;
                    writeArmed = true;
                    statusView.setText(R.string.nfc_writer_waiting_for_tag);
                    detectedView.setText(R.string.nfc_writer_no_tag);
                    prepareButton.setText(R.string.nfc_writer_cancel_wait);
                    prepareButton.setEnabled(true);
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;
        if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_writer_disabled);
            prepareButton.setEnabled(false);
            checkCapabilityButton.setEnabled(false);
            return;
        }
        prepareButton.setEnabled(true);
        checkCapabilityButton.setEnabled(true);
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V;
        nfcAdapter.enableReaderMode(this, this, flags, null);
        if (!writeArmed && !checkArmed) statusView.setText(R.string.nfc_writer_ready);
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        // Never keep a write authorization armed while the activity is in the background.
        if (writeArmed) {
            writeArmed = false;
            pendingMessage = null;
            if (prepareButton != null) prepareButton.setText(R.string.nfc_writer_prepare);
        }
        // Destructive NFC actions are cancelled when the activity leaves the foreground.
        pendingProtectedWritePassword = null;
        pendingProtectedWriteTagId = null;
        protectedWritePromptOpen = false;
        if (checkArmed) {
            checkArmed = false;
            pendingCheckMessage = null;
            if (checkCapabilityButton != null) checkCapabilityButton.setText(R.string.nfc_writer_check_capability);
        }
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        String tagId = toHex(tag.getId());
        String[] technologies = tag.getTechList();
        String techText = technologies == null || technologies.length == 0
                ? getString(R.string.nfc_reader_unknown) : TextUtils.join(", ", technologies);
        if (protectedWritePromptOpen) return;

        String protectedPassword = pendingProtectedWritePassword;
        if (protectedPassword != null && writeArmed && pendingMessage != null) {
            pendingProtectedWritePassword = null;
            String expectedTagId = pendingProtectedWriteTagId;
            pendingProtectedWriteTagId = null;
            if (expectedTagId == null || !expectedTagId.equalsIgnoreCase(tagId)) {
                writeArmed = false;
                pendingMessage = null;
                runOnUiThread(() -> {
                    statusView.setText(R.string.nfc_reader_protected_wrong_tag);
                    prepareButton.setText(R.string.nfc_writer_prepare);
                    prepareButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
                    checkCapabilityButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
                });
                return;
            }
            NdefMessage message = pendingMessage;
            writeArmed = false;
            pendingMessage = null;
            runOnUiThread(() -> {
                statusView.setText(R.string.nfc_writer_writing);
                detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText));
                prepareButton.setEnabled(false);
            });
            writeProtectedMessageToTag(tag, message, protectedPassword, tagId, techText);
            return;
        }

        if (checkArmed) {
            checkArmed = false;
            NdefMessage checkMessage = pendingCheckMessage;
            pendingCheckMessage = null;
            runOnUiThread(() -> {
                statusView.setText(R.string.nfc_writer_checking);
                detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText));
                checkCapabilityButton.setEnabled(false);
                prepareButton.setEnabled(false);
            });
            checkTagCapabilities(tag, tagId, techText, checkMessage);
            return;
        }

        if (!writeArmed || pendingMessage == null) {
            runOnUiThread(() -> detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText)));
            return;
        }

        if (NfcHardwareSecurity.requiresPasswordForNdefWrite(tag)) {
            promptForProtectedWritePassword(tagId, techText);
            return;
        }

        NdefMessage message = pendingMessage;
        // Consume the arm before performing I/O so a tag callback cannot trigger duplicate writes.
        writeArmed = false;
        pendingMessage = null;
        runOnUiThread(() -> {
            statusView.setText(R.string.nfc_writer_writing);
            detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText));
            prepareButton.setEnabled(false);
        });
        writeMessageToTag(tag, message);
    }

    private void promptForProtectedWritePassword(String tagId, String techText) {
        protectedWritePromptOpen = true;
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
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_reader_protected_prompt_title)
                    .setMessage(R.string.nfc_writer_protected_prompt_message)
                    .setView(container)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        protectedWritePromptOpen = false;
                        pendingProtectedWritePassword = null;
                        pendingProtectedWriteTagId = null;
                        writeArmed = false;
                        pendingMessage = null;
                        statusView.setText(R.string.nfc_writer_ready);
                        prepareButton.setText(R.string.nfc_writer_prepare);
                        prepareButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
                    })
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String password = passwordInput.getText().toString();
                        if (password.trim().length() < 4) {
                            protectedWritePromptOpen = false;
                            pendingProtectedWritePassword = null;
                            pendingProtectedWriteTagId = null;
                            writeArmed = false;
                            pendingMessage = null;
                            statusView.setText(R.string.nfc_reader_protected_bad_password_format);
                            prepareButton.setText(R.string.nfc_writer_prepare);
                            prepareButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
                            return;
                        }
                        pendingProtectedWritePassword = password;
                        pendingProtectedWriteTagId = tagId;
                        protectedWritePromptOpen = false;
                        statusView.setText(R.string.nfc_writer_protected_retap);
                        detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText));
                    }).show();
        });
    }

    private void writeProtectedMessageToTag(Tag tag, NdefMessage message, String password,
                                            String tagId, String techText) {
        String result;
        try {
            NfcHardwareSecurity.writeProtectedNdef(tag, password, message.toByteArray());
            result = getString(R.string.nfc_writer_success_verified);
        } catch (IOException | RuntimeException e) {
            result = getString(R.string.nfc_writer_write_failed_generic);
        }
        final String finalResult = result;
        runOnUiThread(() -> {
            statusView.setText(finalResult);
            detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText));
            prepareButton.setText(R.string.nfc_writer_prepare);
            prepareButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            checkCapabilityButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            if (finalResult.startsWith(getString(R.string.nfc_writer_success_prefix))) {
                Toast.makeText(this, R.string.nfc_writer_success_toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Checks standard NDEF write capability without writing, formatting, or sending APDUs. */
    private void checkTagCapabilities(Tag tag, String tagId, String techText, NdefMessage checkMessage) {
        String result;
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                int capacity = ndef.getMaxSize();
                boolean writable = ndef.isWritable();
                String payloadEstimate = checkMessage == null
                        ? getString(R.string.nfc_writer_check_payload_unavailable)
                        : getString(R.string.nfc_writer_check_payload_bytes, checkMessage.toByteArray().length);
                String fitResult = getPayloadFitResult(checkMessage, capacity);
                result = writable
                        ? getString(R.string.nfc_writer_check_ndef_writable, capacity, payloadEstimate, fitResult)
                        : getString(R.string.nfc_writer_check_ndef_read_only, capacity, payloadEstimate);
            } catch (IOException | RuntimeException e) {
                result = getString(R.string.nfc_writer_check_failed_generic);
            } finally {
                try { ndef.close(); } catch (IOException ignored) { }
            }
        } else if (MifareClassic.get(tag) != null) {
            // A generic NDEF formatter can overwrite proprietary sector data on MIFARE Classic.
            result = getString(R.string.nfc_writer_classic_not_supported);
        } else if (NdefFormatable.get(tag) != null) {
            // Do not call format() during a capability check: formatting changes the tag.
            result = getString(R.string.nfc_writer_check_formatable);
        } else {
            result = getString(R.string.nfc_writer_check_no_ndef);
        }

        final String finalResult = result;
        runOnUiThread(() -> {
            statusView.setText(finalResult);
            detectedView.setText(getString(R.string.nfc_writer_detected_tag, tagId, techText));
            checkCapabilityButton.setText(R.string.nfc_writer_check_capability);
            checkCapabilityButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            prepareButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
        });
    }

    private String getPayloadFitResult(NdefMessage checkMessage, int capacity) {
        if (checkMessage == null) return getString(R.string.nfc_writer_check_payload_unavailable);
        int required = checkMessage.toByteArray().length;
        return required <= capacity
                ? getString(R.string.nfc_writer_check_payload_fits)
                : getString(R.string.nfc_writer_check_payload_too_large, required);
    }

    private void writeMessageToTag(Tag tag, NdefMessage message) {
        String result;
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                if (!ndef.isWritable()) {
                    result = getString(R.string.nfc_writer_tag_read_only);
                } else if (message.toByteArray().length > ndef.getMaxSize()) {
                    result = getString(R.string.nfc_writer_tag_too_small, ndef.getMaxSize(), message.toByteArray().length);
                } else {
                    ndef.writeNdefMessage(message);
                    try {
                        NdefMessage readBack = ndef.getNdefMessage();
                        result = readBack != null && Arrays.equals(message.toByteArray(), readBack.toByteArray())
                                ? getString(R.string.nfc_writer_success_verified)
                                : getString(R.string.nfc_writer_success_unverified);
                    } catch (IOException | FormatException | RuntimeException verificationError) {
                        // A successful write must not be reported as a failed write solely because
                        // this tag cannot be read back during the same connection.
                        result = getString(R.string.nfc_writer_success_unverified);
                    }
                }
            } catch (IOException | FormatException | RuntimeException e) {
                result = getString(R.string.nfc_writer_write_failed_generic);
            } finally {
                try { ndef.close(); } catch (IOException ignored) { }
            }
        } else if (MifareClassic.get(tag) != null) {
            // Never format a non-NDEF MIFARE Classic tag: it may hold parking/access data.
            result = getString(R.string.nfc_writer_classic_not_supported);
        } else {
            NdefFormatable formatable = NdefFormatable.get(tag);
            if (formatable == null) {
                result = getString(R.string.nfc_writer_not_ndef_supported);
            } else {
                try {
                    formatable.connect();
                    formatable.format(message);
                    result = getString(R.string.nfc_writer_success_format);
                } catch (IOException | FormatException | RuntimeException e) {
                    result = getString(R.string.nfc_writer_write_failed_generic);
                } finally {
                    try { formatable.close(); } catch (IOException ignored) { }
                }
            }
        }
        final String finalResult = result;
        runOnUiThread(() -> {
            statusView.setText(finalResult);
            prepareButton.setText(R.string.nfc_writer_prepare);
            prepareButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            checkCapabilityButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            if (finalResult.startsWith(getString(R.string.nfc_writer_success_prefix))) {
                Toast.makeText(this, R.string.nfc_writer_success_toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private NdefMessage buildMessage(boolean validate) {
        int mode = modeSpinner.getSelectedItemPosition();
        List<NdefRecord> records = new ArrayList<>();
        if (mode == 0) {
            String text = contentField.getText().toString().trim();
            if (text.isEmpty()) throw new IllegalArgumentException(getString(R.string.nfc_writer_error_text_required));
            String language = Locale.getDefault().getLanguage();
            if (language.length() < 2 || language.length() > 8) language = "en";
            records.add(NdefRecord.createTextRecord(language, text));
        } else if (mode == 1) {
            String url = normalizeUrl(contentField.getText().toString().trim());
            Uri uri = Uri.parse(url);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_url_invalid));
            }
            records.add(NdefRecord.createUri(uri));
        } else if (mode == 3) {
            String phone = contentField.getText().toString().trim();
            if (phone.isEmpty()) throw new IllegalArgumentException(getString(R.string.nfc_writer_error_phone_required));
            String normalized = phone.replaceAll("[\\s().-]", "");
            if (!normalized.matches("\\+?[0-9]{3,20}")) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_phone_required));
            }
            records.add(NdefRecord.createUri(Uri.parse("tel:" + normalized)));
        } else if (mode == 4) {
            String email = contentField.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_email_invalid));
            }
            records.add(NdefRecord.createUri(Uri.parse("mailto:" + email)));
        } else if (mode == 5) {
            String mimeType = mimeTypeField.getText().toString().trim().toLowerCase(Locale.ROOT);
            String payload = contentField.getText().toString();
            if (!mimeType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_mime_invalid));
            }
            if (payload.isEmpty()) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_mime_payload_required));
            }
            records.add(NdefRecord.createMime(mimeType, payload.getBytes(StandardCharsets.UTF_8)));
        } else if (mode == 6) {
            String bitcoinUri = contentField.getText().toString().trim();
            if (bitcoinUri.regionMatches(true, 0, "bitcoin:", 0, "bitcoin:".length())) {
                bitcoinUri = "bitcoin:" + bitcoinUri.substring("bitcoin:".length());
            } else {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_bitcoin_uri));
            }
            try {
                android.net.Uri parsed = Uri.parse(bitcoinUri);
                if (!"bitcoin".equalsIgnoreCase(parsed.getScheme())
                        || TextUtils.isEmpty(parsed.getSchemeSpecificPart())) {
                    throw new IllegalArgumentException();
                }
                records.add(NdefRecord.createUri(parsed));
            } catch (RuntimeException invalidUri) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_bitcoin_uri));
            }
        } else {
            String name = nameField.getText().toString().trim();
            String organization = organizationField.getText().toString().trim();
            String jobTitle = titleField.getText().toString().trim();
            String phone = phoneField.getText().toString().trim();
            String email = emailField.getText().toString().trim();
            String website = websiteField.getText().toString().trim();
            if (name.isEmpty()) throw new IllegalArgumentException(getString(R.string.nfc_writer_error_name_required));
            if (phone.isEmpty() && email.isEmpty() && website.isEmpty()) {
                throw new IllegalArgumentException(getString(R.string.nfc_writer_error_contact_method_required));
            }
            StringBuilder vcard = new StringBuilder();
            vcard.append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
                    .append("FN:").append(escapeVCard(name)).append("\r\n")
                    .append("N:;").append(escapeVCard(name)).append(";;;\r\n");
            if (!organization.isEmpty()) vcard.append("ORG:").append(escapeVCard(organization)).append("\r\n");
            if (!jobTitle.isEmpty()) vcard.append("TITLE:").append(escapeVCard(jobTitle)).append("\r\n");
            if (!phone.isEmpty()) vcard.append("TEL;TYPE=CELL:").append(escapeVCard(phone)).append("\r\n");
            if (!email.isEmpty()) vcard.append("EMAIL:").append(escapeVCard(email)).append("\r\n");
            if (!website.isEmpty()) {
                String normalized = normalizeUrl(website);
                Uri uri = Uri.parse(normalized);
                if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw new IllegalArgumentException(getString(R.string.nfc_writer_error_url_invalid));
                }
                vcard.append("URL:").append(normalized).append("\r\n");
            }
            vcard.append("END:VCARD\r\n");
            records.add(NdefRecord.createMime("text/vcard", vcard.toString().getBytes(StandardCharsets.UTF_8)));
            if (!website.isEmpty()) records.add(NdefRecord.createUri(Uri.parse(normalizeUrl(website))));
        }
        NdefMessage message = new NdefMessage(records.toArray(new NdefRecord[0]));
        if (validate && message.toByteArray().length > 100_000) {
            throw new IllegalArgumentException(getString(R.string.nfc_writer_error_payload_too_large));
        }
        return message;
    }

    private static String normalizeUrl(String value) {
        if (value == null) return "";
        String url = value.trim();
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*$") && !url.regionMatches(true, 0, "mailto:", 0, 7)) {
            url = "https://" + url;
        }
        return url;
    }

    private static String escapeVCard(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
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
