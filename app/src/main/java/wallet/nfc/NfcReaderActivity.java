package wallet.nfc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.text.InputType;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import wallet.ui.BaseActivity;
import wallet.main.R;

/** Reads NFC tag metadata, public NDEF content and limited read-only ISO-DEP diagnostics. */
public class NfcReaderActivity extends BaseActivity implements NfcAdapter.ReaderCallback {
    private static final int MAX_PAYLOAD_HEX_BYTES = 2048;
    private static final byte[] PPSE_SELECT = new byte[] {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x0E,
            0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53,
            0x2E, 0x44, 0x44, 0x46, 0x30, 0x31, 0x00
    };

    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView idView;
    private TextView technologiesView;
    private LinearLayout ndefView;
    private LinearLayout decodedFieldsContainer;
    private TextView protocolView;
    private Button copyButton;
    private View protocolCard;
    private View ndefCard;
    private View decodedCard;
    private View emvCard;
    private TextView emvSummaryView;
    private TextView emvResultView;
    private Button emvReadButton;
    private volatile boolean emvReadArmed;
    private boolean latestTagSupportsIsoDep;
    private volatile boolean latestTagHasEmvApp;
    private String lastReport = "";
    private volatile String pendingProtectedReadPassword;
    private volatile String pendingProtectedReadTagId;
    private volatile boolean protectedReadPromptOpen;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_reader);

        Toolbar toolbar = findViewById(R.id.toolbar_nfc_reader);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.nfc_reader_title);
        }

        statusView = findViewById(R.id.nfc_reader_status);
        idView = findViewById(R.id.nfc_reader_id);
        technologiesView = findViewById(R.id.nfc_reader_technologies_value);
        ndefView = findViewById(R.id.nfc_reader_ndef_value);
        decodedFieldsContainer = findViewById(R.id.nfc_reader_decoded_fields);
        decodedCard = findViewById(R.id.nfc_reader_decoded_card);
        protocolView = findViewById(R.id.nfc_reader_protocol_value);
        protocolCard = findViewById(R.id.nfc_reader_protocol_card);
        ndefCard = findViewById(R.id.nfc_reader_ndef_card);
        emvCard = findViewById(R.id.nfc_reader_emv_card);
        emvSummaryView = findViewById(R.id.nfc_reader_emv_summary);
        emvResultView = findViewById(R.id.nfc_reader_emv_result);
        emvReadButton = findViewById(R.id.nfc_reader_emv_read);
        emvReadButton.setOnClickListener(v -> confirmEmvRead());
        copyButton = findViewById(R.id.nfc_reader_copy);
        Button clearButton = findViewById(R.id.nfc_reader_clear);
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyReport());
        clearButton.setOnClickListener(v -> clearResults());

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            statusView.setText(R.string.nfc_reader_unsupported);
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_reader_disabled);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;
        if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_reader_disabled);
            return;
        }
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                | NfcAdapter.FLAG_READER_NFC_BARCODE;
        nfcAdapter.enableReaderMode(this, this, flags, null);
        if (lastReport.isEmpty()) statusView.setText(R.string.nfc_reader_waiting);
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        pendingProtectedReadPassword = null;
        pendingProtectedReadTagId = null;
        protectedReadPromptOpen = false;
        emvReadArmed = false;
        latestTagSupportsIsoDep = false;
        latestTagHasEmvApp = false;
        if (emvReadButton != null) emvReadButton.setEnabled(false);
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        // ReaderCallback is already dispatched off the main thread by Android.
        String id = Nfc.toHex(tag.getId());
        String technologies = buildTechnologyReport(tag);
        latestTagHasEmvApp = false;
        if (protectedReadPromptOpen) return;

        String pendingPassword = pendingProtectedReadPassword;
        if (pendingPassword != null) {
            pendingProtectedReadPassword = null;
            String expectedTagId = pendingProtectedReadTagId;
            pendingProtectedReadTagId = null;
            if (expectedTagId == null || !expectedTagId.equalsIgnoreCase(id)) {
                runOnUiThread(() -> statusView.setText(R.string.nfc_reader_protected_wrong_tag));
                return;
            }
            readProtectedTag(tag, id, technologies, pendingPassword);
            return;
        }

        if (NfcHardwareSecurity.requiresPasswordForNdefRead(tag)) {
            promptForProtectedReadPassword(id, technologies);
            return;
        }

        boolean runAdvancedEmv = emvReadArmed && IsoDep.get(tag) != null;
        if (runAdvancedEmv) emvReadArmed = false;
        NfcType4TagDriver.ReadResult type4Read = null;
        if (IsoDep.get(tag) != null) {
            if (Ndef.get(tag) == null) {
                type4Read = NfcType4TagDriver.read(tag);
            } else {
                NfcType4TagDriver.Inspection type4Info = NfcType4TagDriver.inspect(tag);
                if (type4Info != null) type4Read = new NfcType4TagDriver.ReadResult(type4Info, null);
            }
        }
        String ndefText = readNdef(tag, type4Read);
        List<DecodedRecord> decodedRecords = readDecodedContent(tag, type4Read);
        String protocolText = readProtocolDetails(tag, null, type4Read);
        String emvText = runAdvancedEmv
                ? EmvContactlessReader.read(this, tag).toDisplayText(this) : "";
        boolean hasNdef = Ndef.get(tag) != null
                || (type4Read != null && type4Read.inspection.ndefApplicationFound);
        boolean isMifareClassic = MifareClassic.get(tag) != null;
        boolean hasIsoDep = IsoDep.get(tag) != null;
        boolean hasProtocolDetails = hasIsoDep || NfcA.get(tag) != null || NfcB.get(tag) != null
                || NfcF.get(tag) != null || NfcV.get(tag) != null || isMifareClassic
                || MifareUltralight.get(tag) != null || NdefFormatable.get(tag) != null;
        String report = getString(R.string.nfc_reader_report_id, id.isEmpty() ? getString(R.string.nfc_reader_unknown) : id)
                + "\n\n" + getString(R.string.nfc_reader_report_technologies) + "\n" + technologies
                + "\n\n" + getString(R.string.nfc_reader_report_ndef) + "\n" + ndefText
                + "\n\n" + getString(R.string.nfc_reader_report_protocol) + "\n" + protocolText
                + (emvText.isEmpty() ? "" : "\n\n" + getString(R.string.nfc_reader_emv_title) + "\n" + emvText);
        final String finalEmvText = emvText;

        runOnUiThread(() -> {
            lastReport = report;
            // Adapt the screen to the detected technology instead of showing every section for every tag.
            statusView.setText(isMifareClassic ? R.string.nfc_reader_found_mifare
                    : hasNdef ? R.string.nfc_reader_found_ndef
                    : hasIsoDep ? R.string.nfc_reader_found_isodep : R.string.nfc_reader_found_generic);
            ndefCard.setVisibility(hasNdef ? View.VISIBLE : View.GONE);
            // Keep protocol details visible for NDEF tags too (for example ISO15693/NFC-V tags).
            protocolCard.setVisibility(hasProtocolDetails ? View.VISIBLE : View.GONE);
            idView.setText(id.isEmpty() ? getString(R.string.nfc_reader_unknown) : id);
            technologiesView.setText(technologies);
            renderNdefDetails(ndefText);
            renderDecodedRecords(decodedRecords);
            decodedCard.setVisibility(decodedRecords.isEmpty() ? View.GONE : View.VISIBLE);
            protocolView.setText(protocolText);
            latestTagSupportsIsoDep = hasIsoDep;
            emvCard.setVisibility(hasIsoDep && latestTagHasEmvApp ? View.VISIBLE : View.GONE);
            emvReadButton.setEnabled(hasIsoDep && latestTagHasEmvApp);
            emvSummaryView.setText(runAdvancedEmv ? R.string.nfc_reader_emv_complete
                    : latestTagHasEmvApp ? R.string.nfc_reader_emv_summary
                    : R.string.nfc_reader_emv_not_present);
            emvResultView.setText(finalEmvText);
            emvResultView.setVisibility(finalEmvText.isEmpty() ? View.GONE : View.VISIBLE);
            copyButton.setEnabled(true);
        });
    }

    private void promptForProtectedReadPassword(String tagId, String technologies) {
        protectedReadPromptOpen = true;
        runOnUiThread(() -> {
            View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
            EditText passwordInput = passwordView.findViewById(R.id.dialogPasswordInput);
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordInput.setHint(R.string.nfc_hardware_password_hint);
            passwordInput.setFilters(new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(128)
            });
            new AlertDialog.Builder(this)
                    .setTitle(R.string.nfc_reader_protected_prompt_title)
                    .setMessage(R.string.nfc_reader_protected_prompt_message)
                    .setView(passwordView)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        protectedReadPromptOpen = false;
                        pendingProtectedReadPassword = null;
                        pendingProtectedReadTagId = null;
                        statusView.setText(R.string.nfc_reader_waiting);
                    })
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String password = passwordInput.getText().toString();
                        if (password.trim().length() < 4) {
                            protectedReadPromptOpen = false;
                            statusView.setText(R.string.nfc_reader_protected_bad_password_format);
                            return;
                        }
                        pendingProtectedReadPassword = password;
                        pendingProtectedReadTagId = tagId;
                        protectedReadPromptOpen = false;
                        idView.setText(tagId);
                        technologiesView.setText(technologies);
                        statusView.setText(R.string.nfc_reader_protected_retap);
                    }).show();
        });
    }

    private void readProtectedTag(Tag tag, String id, String technologies, String password) {
        String ndefText;
        List<DecodedRecord> decodedRecords = new ArrayList<>();
        boolean runAdvancedEmv = emvReadArmed && IsoDep.get(tag) != null;
        if (runAdvancedEmv) emvReadArmed = false;
        String protocolText = readProtocolDetails(tag, password);
        String emvText = runAdvancedEmv
                ? EmvContactlessReader.read(this, tag).toDisplayText(this) : "";
        byte[] ndefBytes = null;
        try {
            ndefBytes = NfcHardwareSecurity.readProtectedNdef(tag, password);
            if (ndefBytes == null || ndefBytes.length == 0) {
                ndefText = getString(R.string.nfc_reader_no_ndef);
            } else {
                NdefMessage message = new NdefMessage(ndefBytes);
                ndefText = buildProtectedNdefReport(tag, message);
                decodedRecords = readDecodedContent(message);
            }
        } catch (IOException | FormatException | RuntimeException e) {
            ndefText = getString(R.string.nfc_common_operation_failed);
        } finally {
            if (ndefBytes != null) Arrays.fill(ndefBytes, (byte) 0);
        }
        final String finalNdefText = ndefText;
        final List<DecodedRecord> finalDecodedRecords = decodedRecords;
        final boolean hasNdef = Ndef.get(tag) != null || !decodedRecords.isEmpty();
        final boolean isMifareClassic = MifareClassic.get(tag) != null;
        final boolean hasIsoDep = IsoDep.get(tag) != null;
        final boolean hasProtocolDetails = hasIsoDep || NfcA.get(tag) != null || NfcB.get(tag) != null
                || NfcF.get(tag) != null || NfcV.get(tag) != null || isMifareClassic
                || MifareUltralight.get(tag) != null || NdefFormatable.get(tag) != null;
        String report = getString(R.string.nfc_reader_report_id, id.isEmpty() ? getString(R.string.nfc_reader_unknown) : id)
                + "\n\n" + getString(R.string.nfc_reader_report_technologies) + "\n" + technologies
                + "\n\n" + getString(R.string.nfc_reader_report_ndef) + "\n" + finalNdefText
                + "\n\n" + getString(R.string.nfc_reader_report_protocol) + "\n" + protocolText
                + (emvText.isEmpty() ? "" : "\n\n" + getString(R.string.nfc_reader_emv_title) + "\n" + emvText);
        final String finalEmvText = emvText;
        final String finalReport = report;
        runOnUiThread(() -> {
            lastReport = finalReport;
            statusView.setText(isMifareClassic ? R.string.nfc_reader_found_mifare
                    : hasNdef ? R.string.nfc_reader_found_ndef
                    : hasIsoDep ? R.string.nfc_reader_found_isodep : R.string.nfc_reader_found_generic);
            ndefCard.setVisibility(View.VISIBLE);
            protocolCard.setVisibility(hasProtocolDetails ? View.VISIBLE : View.GONE);
            idView.setText(id);
            technologiesView.setText(technologies);
            renderNdefDetails(finalNdefText);
            renderDecodedRecords(finalDecodedRecords);
            decodedCard.setVisibility(finalDecodedRecords.isEmpty() ? View.GONE : View.VISIBLE);
            protocolView.setText(protocolText);
            latestTagSupportsIsoDep = hasIsoDep;
            emvCard.setVisibility(hasIsoDep && latestTagHasEmvApp ? View.VISIBLE : View.GONE);
            emvReadButton.setEnabled(hasIsoDep && latestTagHasEmvApp);
            emvSummaryView.setText(runAdvancedEmv ? R.string.nfc_reader_emv_complete
                    : latestTagHasEmvApp ? R.string.nfc_reader_emv_summary
                    : R.string.nfc_reader_emv_not_present);
            emvResultView.setText(finalEmvText);
            emvResultView.setVisibility(finalEmvText.isEmpty() ? View.GONE : View.VISIBLE);
            copyButton.setEnabled(true);
        });
    }

    private void confirmEmvRead() {
        if (!latestTagSupportsIsoDep || !latestTagHasEmvApp) {
            Toast.makeText(this, R.string.nfc_reader_emv_no_card, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_reader_emv_confirm_title)
                .setMessage(R.string.nfc_reader_emv_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    emvReadArmed = true;
                    emvReadButton.setEnabled(false);
                    emvResultView.setVisibility(View.GONE);
                    emvSummaryView.setText(R.string.nfc_reader_emv_retap);
                    statusView.setText(R.string.nfc_reader_emv_retap);
                })
                .show();
    }

    private String buildProtectedNdefReport(Tag tag, NdefMessage message) {
        StringBuilder result = new StringBuilder();
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                result.append(getString(R.string.nfc_reader_ndef_type, safeString(ndef.getType()))).append('\n');
                result.append(getString(R.string.nfc_reader_ndef_capacity, ndef.getMaxSize())).append('\n');
                result.append(getString(R.string.nfc_reader_ndef_writable, yesNo(ndef.isWritable()))).append('\n');
                result.append(getString(R.string.nfc_reader_ndef_read_only, yesNo(ndef.canMakeReadOnly()))).append('\n');
            } catch (IOException | RuntimeException ignored) {
                result.append(getString(R.string.nfc_reader_ndef_type, getString(R.string.nfc_reader_type2_authenticated_memory))).append('\n');
            } finally {
                try {
                    ndef.close();
                } catch (IOException ignored) { }
            }
        } else {
            result.append(getString(R.string.nfc_reader_ndef_type, getString(R.string.nfc_reader_type2_authenticated_memory))).append('\n');
        }
        NdefRecord[] records = message.getRecords();
        result.append('\n').append(getString(R.string.nfc_reader_record_count, records.length));
        for (int i = 0; i < records.length; i++) {
            result.append("\n\n").append(getString(R.string.nfc_reader_record_number, i + 1)).append('\n');
            appendRecordDetails(result, records[i], 0);
        }
        return result.toString();
    }

    /** Render technical NDEF metadata using reusable XML row/card templates. */
    private void renderNdefDetails(String report) {
        ndefView.removeAllViews();
        if (report == null || report.trim().isEmpty()) return;

        String[] lines = report.split("\\n", -1);
        List<String> metadata = new ArrayList<>();
        List<List<String>> recordLines = new ArrayList<>();
        List<String> currentRecord = null;
        for (String line : lines) {
            boolean startsRecord = false;
            for (int i = 1; i <= 100; i++) {
                if (line.trim().equals(getString(R.string.nfc_reader_record_number, i))) {
                    startsRecord = true;
                    break;
                }
            }
            if (startsRecord) {
                currentRecord = new ArrayList<>();
                recordLines.add(currentRecord);
            } else if (currentRecord != null) {
                currentRecord.add(line);
            } else if (!line.trim().isEmpty()) {
                metadata.add(line);
            }
        }

        addTechnicalField(ndefView, getString(R.string.nfc_reader_technical_summary),
                metadata.isEmpty() ? getString(R.string.nfc_reader_field_empty) : TextUtils.join("\n", metadata));

        if (!recordLines.isEmpty()) {
            TextView count = (TextView) getLayoutInflater().inflate(
                    R.layout.item_nfc_reader_record_count, ndefView, false);
            count.setText(getString(R.string.nfc_reader_record_count, recordLines.size()));
            LinearLayout.LayoutParams countParams = (LinearLayout.LayoutParams) count.getLayoutParams();
            countParams.topMargin = dp(12);
            countParams.bottomMargin = dp(4);
            ndefView.addView(count, countParams);
        }

        for (int i = 0; i < recordLines.size(); i++) {
            View record = getLayoutInflater().inflate(R.layout.item_nfc_reader_record_card, ndefView, false);
            TextView title = record.findViewById(R.id.nfcReaderRecordTitle);
            title.setText(getString(R.string.nfc_reader_record_number, i + 1));
            LinearLayout fields = record.findViewById(R.id.nfcReaderRecordFields);
            ndefView.addView(record);

            for (String detail : recordLines.get(i)) {
                String line = detail.trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                String label = colon > 0 ? line.substring(0, colon).trim()
                        : getString(R.string.nfc_reader_technical_data_label);
                String value = colon > 0 ? line.substring(colon + 1).trim() : line;
                addTechnicalField(fields, label, value);
            }
        }
    }

    private void addTechnicalField(LinearLayout parent, String labelText, String valueText) {
        View field = getLayoutInflater().inflate(R.layout.item_nfc_reader_technical_field, parent, false);
        TextView label = field.findViewById(R.id.nfcReaderTechnicalLabel);
        label.setText(labelText);
        TextView value = field.findViewById(R.id.nfcReaderTechnicalValue);
        value.setText(valueText == null || valueText.trim().isEmpty()
                ? getString(R.string.nfc_reader_field_empty) : valueText);
        parent.addView(field);
    }

    private String buildTechnologyReport(Tag tag) {
        List<String> lines = new ArrayList<>();
        String[] techs = tag.getTechList();
        if (techs != null) {
            for (String tech : techs) lines.add(tech);
        }
        if (lines.isEmpty()) lines.add(getString(R.string.nfc_reader_no_technologies));
        return TextUtils.join("\n", lines);
    }

    private String readNdef(Tag tag) {
        return readNdef(tag, null);
    }

    private String readNdef(Tag tag, NfcType4TagDriver.ReadResult type4Read) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            if (type4Read != null && type4Read.inspection.ndefApplicationFound) {
                NfcType4TagDriver.Inspection info = type4Read.inspection;
                StringBuilder result = new StringBuilder();
                result.append(getString(R.string.nfc_reader_type4_summary,
                        info.mappingVersion, info.ccLength, info.fileIdHex(), info.capacity(),
                        yesNo(info.readAccess == 0), yesNo(info.writeAccess == 0)));
                if (!info.canRead()) {
                    result.append("\n").append(getString(R.string.nfc_reader_type4_read_restricted));
                } else if (type4Read.message == null || type4Read.message.length == 0) {
                    result.append("\n").append(getString(R.string.nfc_reader_no_ndef));
                } else {
                    try {
                        NdefMessage message = new NdefMessage(type4Read.message);
                        NdefRecord[] records = message.getRecords();
                        result.append("\n\n").append(getString(R.string.nfc_reader_record_count, records.length));
                        for (int i = 0; i < records.length; i++) {
                            result.append("\n\n").append(getString(R.string.nfc_reader_record_number, i + 1)).append('\n');
                            appendRecordDetails(result, records[i], 0);
                        }
                    } catch (FormatException ignored) {
                        result.append("\n").append(getString(R.string.nfc_common_operation_failed));
                    }
                }
                return result.toString();
            }
            return getString(R.string.nfc_reader_ndef_not_formatted)
                    + "\n" + getString(R.string.nfc_reader_ndef_tip);
        }
        try {
            ndef.connect();
            StringBuilder result = new StringBuilder();
            result.append(getString(R.string.nfc_reader_ndef_type, safeString(ndef.getType()))).append('\n');
            result.append(getString(R.string.nfc_reader_ndef_capacity, ndef.getMaxSize())).append('\n');
            result.append(getString(R.string.nfc_reader_ndef_writable, yesNo(ndef.isWritable()))).append('\n');
            result.append(getString(R.string.nfc_reader_ndef_read_only, yesNo(ndef.canMakeReadOnly()))).append('\n');
            NdefMessage message = ndef.getNdefMessage();
            if (message == null || message.getRecords().length == 0) {
                result.append(getString(R.string.nfc_reader_no_ndef));
                return result.toString();
            }
            NdefRecord[] records = message.getRecords();
            result.append('\n').append(getString(R.string.nfc_reader_record_count, records.length));
            for (int i = 0; i < records.length; i++) {
                result.append("\n\n").append(getString(R.string.nfc_reader_record_number, i + 1)).append('\n');
                appendRecordDetails(result, records[i], 0);
            }
            return result.toString();
        } catch (IOException | FormatException | RuntimeException e) {
            if (type4Read != null && type4Read.inspection.ndefApplicationFound
                    && type4Read.inspection.canRead() && type4Read.message != null) {
                try {
                    NdefMessage fallback = new NdefMessage(type4Read.message);
                    StringBuilder result = new StringBuilder();
                    result.append(getString(R.string.nfc_reader_type4_summary,
                            type4Read.inspection.mappingVersion, type4Read.inspection.ccLength,
                            type4Read.inspection.fileIdHex(), type4Read.inspection.capacity(),
                            yesNo(type4Read.inspection.canRead()), yesNo(type4Read.inspection.canWrite())));
                    for (int i = 0; i < fallback.getRecords().length; i++) {
                        result.append("\n\n").append(getString(R.string.nfc_reader_record_number, i + 1)).append('\n');
                        appendRecordDetails(result, fallback.getRecords()[i], 0);
                    }
                    return result.toString();
                } catch (FormatException ignored) { }
            }
            return getString(R.string.nfc_common_operation_failed);
        } finally {
            try {
                ndef.close();
            } catch (IOException ignored) { }
        }
    }

    private void appendRecordDetails(StringBuilder out, NdefRecord record, int depth) {
        String indent = depth > 0 ? "  " : "";
        out.append(indent).append(getString(R.string.nfc_reader_record_tnf, record.getTnf())).append('\n');
        byte[] type = record.getType();
        byte[] id = record.getId();
        if (type != null && type.length > 0) out.append(indent).append(getString(R.string.nfc_reader_record_type, printableUtf8(type))).append('\n');
        if (id != null && id.length > 0) out.append(indent).append(getString(R.string.nfc_reader_record_id, Nfc.toHex(id))).append('\n');
        // This card is intentionally technical/raw only. Human-readable decoded content is shown separately.
        appendPayloadHex(out, record.getPayload(), indent);
    }

    /** Read user-facing content while preserving NDEF record boundaries. */
    private List<DecodedRecord> readDecodedContent(Tag tag) {
        return readDecodedContent(tag, null);
    }

    private List<DecodedRecord> readDecodedContent(Tag tag, NfcType4TagDriver.ReadResult type4Read) {
        List<DecodedRecord> records = new ArrayList<>();
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            if (type4Read != null && type4Read.message != null && type4Read.message.length > 0) {
                try { return readDecodedContent(new NdefMessage(type4Read.message)); }
                catch (FormatException ignored) { return records; }
            }
            return records;
        }
        try {
            ndef.connect();
            NdefMessage message = ndef.getNdefMessage();
            return readDecodedContent(message);
        } catch (IOException | FormatException | RuntimeException ignored) {
            records.clear();
            if (type4Read != null && type4Read.inspection.ndefApplicationFound
                    && type4Read.inspection.canRead() && type4Read.message != null
                    && type4Read.message.length > 0) {
                try { return readDecodedContent(new NdefMessage(type4Read.message)); }
                catch (FormatException invalidType4Message) { records.clear(); }
            }
        } finally {
            try {
                ndef.close();
            } catch (IOException ignored) { }
        }
        return records;
    }

    private List<DecodedRecord> readDecodedContent(NdefMessage message) {
        List<DecodedRecord> records = new ArrayList<>();
        if (message == null) return records;
        for (NdefRecord record : message.getRecords()) {
            List<DecodedField> fields = new ArrayList<>();
            appendDecodedRecord(fields, record);
            records.add(new DecodedRecord(fields));
        }
        return records;
    }

    private static final class DecodedRecord {
        final List<DecodedField> fields;
        DecodedRecord(List<DecodedField> fields) {
            this.fields = fields;
        }
    }

    private static final class DecodedField {
        final String label;
        final String value;
        DecodedField(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private void addDecodedField(List<DecodedField> fields, int labelRes, String value) {
        fields.add(new DecodedField(getString(labelRes), value == null ? "" : value));
    }

    private void appendDecodedRecord(List<DecodedField> fields, NdefRecord record) {
        byte[] type = record.getType();
        byte[] payload = record.getPayload();
        if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
            addDecodedField(fields, R.string.nfc_reader_content_type_label, getString(R.string.nfc_reader_text_data_type));
            addDecodedField(fields, R.string.nfc_reader_text_content_label, decodeTextPayload(payload));
        } else if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
            appendDecodedUri(fields, decodeUriPayload(payload));
        } else if (record.getTnf() == NdefRecord.TNF_ABSOLUTE_URI) {
            appendDecodedUri(fields, printableUtf8(type));
        } else if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA) {
            String mimeType = printableUtf8(type);
            String content = decodeUtf8IfReadable(payload);
            String normalizedType = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
            boolean isVCard = normalizedType.equals("text/vcard") || normalizedType.equals("text/x-vcard");
            if (isVCard && content != null && content.toUpperCase(Locale.ROOT).contains("BEGIN:VCARD")) {
                appendVCardFields(fields, content);
            } else if (content != null && !content.trim().isEmpty()) {
                int label = normalizedType.startsWith("text/")
                        ? R.string.nfc_reader_text_content_label : R.string.nfc_reader_generic_content_label;
                addDecodedField(fields, label, content);
            }
        } else if (record.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE) {
            String text = decodeUtf8IfReadable(payload);
            if (text != null && !text.trim().isEmpty()) {
                addDecodedField(fields, R.string.nfc_reader_generic_content_label, text);
            }
        } else if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_SMART_POSTER)) {
            try {
                NdefMessage nested = new NdefMessage(payload);
                for (NdefRecord child : nested.getRecords()) appendDecodedRecord(fields, child);
            } catch (FormatException ignored) { }
        } else {
            String text = decodeUtf8IfReadable(payload);
            if (text != null && !text.trim().isEmpty()) {
                addDecodedField(fields, R.string.nfc_reader_generic_content_label, text);
            }
        }
    }

    /** Recreate the write form's field layout as read-only output, without input hints or technical metadata. */
    private void appendVCardFields(List<DecodedField> fields, String content) {
        java.util.Map<String, List<String>> values = parseVCardProperties(content);
        addDecodedField(fields, R.string.nfc_reader_content_type_label, getString(R.string.nfc_reader_vcard_data_type));
        addDecodedField(fields, R.string.nfc_reader_name_label, firstVCard(values, "FN"));
        addDecodedField(fields, R.string.nfc_reader_organization_label, firstVCard(values, "ORG"));
        addDecodedField(fields, R.string.nfc_reader_job_title_label, firstVCard(values, "TITLE"));
        addDecodedField(fields, R.string.nfc_reader_phone_label, joinVCard(values, "TEL"));
        addDecodedField(fields, R.string.nfc_reader_email_label, joinVCard(values, "EMAIL"));
        addDecodedField(fields, R.string.nfc_reader_website_label, joinVCard(values, "URL"));
    }

    private String firstVCard(java.util.Map<String, List<String>> values, String key) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private String joinVCard(java.util.Map<String, List<String>> values, String key) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? "" : TextUtils.join("\n", list);
    }

    private void appendDecodedUri(List<DecodedField> fields, String uri) {
        if (uri == null || uri.isEmpty()) return;
        String lower = uri.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tel:")) {
            addDecodedField(fields, R.string.nfc_reader_content_type_label, getString(R.string.nfc_reader_phone_link_type));
            addDecodedField(fields, R.string.nfc_reader_phone_label, uri.substring(4));
        } else if (lower.startsWith("mailto:")) {
            String address = uri.substring(7);
            int queryIndex = address.indexOf('?');
            if (queryIndex >= 0) address = address.substring(0, queryIndex);
            addDecodedField(fields, R.string.nfc_reader_content_type_label, getString(R.string.nfc_reader_email_link_type));
            addDecodedField(fields, R.string.nfc_reader_email_label, address);
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            addDecodedField(fields, R.string.nfc_reader_content_type_label, getString(R.string.nfc_reader_url_data_type));
            addDecodedField(fields, R.string.nfc_reader_url_content_label, uri);
        } else {
            addDecodedField(fields, R.string.nfc_reader_content_type_label, getString(R.string.nfc_reader_link_type));
            addDecodedField(fields, R.string.nfc_reader_link_content_label, uri);
        }
    }

    /** Render decoded NDEF records with reusable XML templates and theme-driven text colors. */
    private void renderDecodedRecords(List<DecodedRecord> records) {
        decodedFieldsContainer.removeAllViews();

        TextView count = (TextView) getLayoutInflater().inflate(
                R.layout.item_nfc_reader_record_count, decodedFieldsContainer, false);
        count.setText(getString(R.string.nfc_reader_record_count, records.size()));
        LinearLayout.LayoutParams countParams = (LinearLayout.LayoutParams) count.getLayoutParams();
        countParams.topMargin = dp(4);
        countParams.bottomMargin = dp(8);
        decodedFieldsContainer.addView(count, countParams);

        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            View record = getLayoutInflater().inflate(
                    R.layout.item_nfc_reader_record_card, decodedFieldsContainer, false);
            TextView recordTitle = record.findViewById(R.id.nfcReaderRecordTitle);
            recordTitle.setText(getString(R.string.nfc_reader_record_number, recordIndex + 1));
            LinearLayout fieldsContainer = record.findViewById(R.id.nfcReaderRecordFields);
            decodedFieldsContainer.addView(record);

            List<DecodedField> fields = records.get(recordIndex).fields;
            for (DecodedField field : fields) {
                View row = getLayoutInflater().inflate(
                        R.layout.item_nfc_reader_decoded_field, fieldsContainer, false);
                TextView label = row.findViewById(R.id.nfcReaderDecodedLabel);
                label.setText(field.label);
                TextView value = row.findViewById(R.id.nfcReaderDecodedValue);
                value.setText(field.value == null || field.value.trim().isEmpty()
                        ? getString(R.string.nfc_reader_field_empty) : field.value);
                fieldsContainer.addView(row);
            }
            if (fields.isEmpty()) {
                TextView empty = (TextView) getLayoutInflater().inflate(
                        R.layout.item_nfc_reader_empty_value, fieldsContainer, false);
                empty.setText(R.string.nfc_reader_field_empty);
                fieldsContainer.addView(empty);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private java.util.Map<String, List<String>> parseVCardProperties(String content) {
        java.util.Map<String, List<String>> values = new java.util.LinkedHashMap<>();
        List<String> unfolded = new ArrayList<>();
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !unfolded.isEmpty()) {
                int last = unfolded.size() - 1;
                unfolded.set(last, unfolded.get(last) + line.substring(1));
            } else unfolded.add(line);
        }
        for (String line : unfolded) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String property = line.substring(0, colon).split("[;.]")[0].toUpperCase(Locale.ROOT);
            if (property.equals("BEGIN") || property.equals("END") || property.equals("VERSION")) continue;
            String value = unescapeVCardValue(line.substring(colon + 1));
            if (property.equals("ADR")) value = value.replace(';', ' ').trim();
            if (!value.isEmpty()) values.computeIfAbsent(property, ignored -> new ArrayList<>()).add(value);
        }
        return values;
    }

    /** Return UTF-8 text only when the bytes form valid, printable text rather than binary data. */
    @Nullable
    private String decodeUtf8IfReadable(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(payload)).toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\u0000' || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' && c != '\f')) {
                    return null;
                }
            }
            return text;
        } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private String unescapeVCardValue(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private void appendPayloadHex(StringBuilder out, byte[] payload, String indent) {
        if (payload == null || payload.length == 0) {
            out.append(indent).append(getString(R.string.nfc_reader_empty_payload)).append('\n');
            return;
        }
        int length = Math.min(payload.length, MAX_PAYLOAD_HEX_BYTES);
        String payloadHex = getString(R.string.nfc_reader_payload_hex, Nfc.toHex(Arrays.copyOf(payload, length)));
        if (payload.length > length) {
            out.append(indent).append(getString(R.string.nfc_reader_payload_truncated_line_format,
                    payloadHex, getString(R.string.nfc_reader_payload_truncated, payload.length)));
        } else {
            out.append(indent).append(payloadHex);
        }
        out.append('\n');
    }

    private String readProtocolDetails(Tag tag) {
        return readProtocolDetails(tag, null, null);
    }

    private String readProtocolDetails(Tag tag, String authenticatedPassword) {
        return readProtocolDetails(tag, authenticatedPassword, null);
    }

    private String readProtocolDetails(Tag tag, String authenticatedPassword,
                                       NfcType4TagDriver.ReadResult type4Read) {
        List<String> lines = new ArrayList<>();
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep != null) {
            lines.add(getString(R.string.nfc_reader_iso_dep));
            lines.add(getString(R.string.nfc_reader_max_transceive, isoDep.getMaxTransceiveLength()));
            lines.add(getString(R.string.nfc_reader_extended_length, yesNo(isoDep.isExtendedLengthApduSupported())));
            byte[] historical = isoDep.getHistoricalBytes();
            if (historical != null && historical.length > 0) lines.add(getString(R.string.nfc_reader_historical_bytes, Nfc.toHex(historical)));
            byte[] hiLayer = isoDep.getHiLayerResponse();
            if (hiLayer != null && hiLayer.length > 0) lines.add(getString(R.string.nfc_reader_hi_layer, Nfc.toHex(hiLayer)));
            try {
                isoDep.setTimeout(3000);
                isoDep.connect();
                lines.add(getString(R.string.nfc_reader_connection_ok));
                // This SELECT only asks for the standard EMV payment-system directory (PPSE).
                // It does not request cardholder data, PIN verification or payment authorization.
                byte[] response = isoDep.transceive(PPSE_SELECT);
                lines.add(getString(R.string.nfc_reader_ppse_status, statusWord(response)));
                if (hasSuccessStatus(response)) {
                    List<String> apps = parsePpseApplications(response);
                    latestTagHasEmvApp = !apps.isEmpty();
                    if (apps.isEmpty()) {
                        lines.add(getString(R.string.nfc_reader_ppse_no_apps));
                    } else {
                        lines.add(getString(R.string.nfc_reader_ppse_apps));
                        for (String app : apps) lines.add(getString(R.string.nfc_reader_bullet_item, app));
                    }
                } else {
                    latestTagHasEmvApp = false;
                    lines.add(getString(R.string.nfc_reader_ppse_unavailable));
                }
            } catch (IOException | RuntimeException e) {
                latestTagHasEmvApp = false;
                lines.add(getString(R.string.nfc_reader_connection_failed_generic));
            } finally {
                try {
                    if (isoDep.isConnected()) isoDep.close();
                } catch (IOException ignored) { }
            }
        }

        NfcA nfcA = NfcA.get(tag);
        if (nfcA != null) {
            lines.add(getString(R.string.nfc_reader_nfca));
            lines.add(getString(R.string.nfc_reader_atqa, Nfc.toHex(nfcA.getAtqa())));
            int sak = nfcA.getSak() & 0xFFFF;
            lines.add(getString(R.string.nfc_reader_sak,
                    Nfc.toHex(new byte[]{(byte) (sak >>> 8), (byte) sak})));
        }
        NfcB nfcB = NfcB.get(tag);
        if (nfcB != null) {
            lines.add(getString(R.string.nfc_reader_nfcb));
            lines.add(getString(R.string.nfc_reader_application_data, Nfc.toHex(nfcB.getApplicationData())));
            lines.add(getString(R.string.nfc_reader_protocol_info, Nfc.toHex(nfcB.getProtocolInfo())));
        }
        NfcF nfcF = NfcF.get(tag);
        if (nfcF != null) {
            lines.add(getString(R.string.nfc_reader_nfcf));
            lines.add(getString(R.string.nfc_reader_system_code, Nfc.toHex(nfcF.getSystemCode())));
            lines.add(getString(R.string.nfc_reader_manufacturer, Nfc.toHex(nfcF.getManufacturer())));
        }
        NfcV nfcV = NfcV.get(tag);
        if (nfcV != null) {
            lines.add(getString(R.string.nfc_reader_nfcv));
            lines.add(getString(R.string.nfc_reader_dsfid, Nfc.toHex(new byte[]{nfcV.getDsfId()})));
            lines.add(getString(R.string.nfc_reader_response_flags, Nfc.toHex(new byte[]{nfcV.getResponseFlags()})));
        }
        MifareClassic classic = MifareClassic.get(tag);
        if (classic != null) {
            lines.add(getString(R.string.nfc_reader_mifare_classic));
            lines.add(getString(R.string.nfc_reader_mifare_type, mifareClassicType(classic.getType())));
            lines.add(getString(R.string.nfc_reader_mifare_size, classic.getSize(), classic.getSectorCount(), classic.getBlockCount()));
            lines.add(getString(R.string.nfc_reader_classic_auth_required));
        }
        MifareUltralight ultralight = MifareUltralight.get(tag);
        if (ultralight != null) {
            lines.add(getString(R.string.nfc_reader_mifare_ultralight));
            NfcHardwareSecurity.TagInfo chipInfo = NfcHardwareSecurity.identify(tag);
            lines.add(getString(R.string.nfc_reader_mifare_type,
                    getString(nfcType2ModelName(chipInfo.family, ultralight))));
            lines.addAll(authenticatedPassword == null
                    ? readUltralightPages(ultralight)
                    : readUltralightPagesAuthenticated(tag, authenticatedPassword));
        }
        if (NdefFormatable.get(tag) != null) lines.add(getString(R.string.nfc_reader_ndef_formatable));

        if (type4Read != null) {
            NfcType4TagDriver.Inspection type4 = type4Read.inspection;
            if (type4.ntag424Dna) {
                lines.add(getString(R.string.nfc_reader_type4_ntag424, type4.ntagVersion));
            }
            if (type4.ndefApplicationFound) {
                lines.add(getString(R.string.nfc_reader_type4_summary,
                        type4.mappingVersion, type4.ccLength, type4.fileIdHex(), type4.capacity(),
                        yesNo(type4.readAccess == 0), yesNo(type4.writeAccess == 0)));
                if (!type4.canWrite()) lines.add(getString(R.string.nfc_reader_type4_write_restricted));
                if (!type4.canRead()) lines.add(getString(R.string.nfc_reader_type4_read_restricted));
            }
            if (isoDep != null && !latestTagHasEmvApp) {
                lines.add(getString(R.string.nfc_reader_isodep_non_emv));
            }
        }

        // Give a clear, capability-based explanation instead of implying all protocols share one memory layout.
        if (Ndef.get(tag) != null || (type4Read != null && type4Read.inspection.ndefApplicationFound)) {
            lines.add(getString(R.string.nfc_reader_support_ndef));
        } else if (classic != null) {
            lines.add(getString(R.string.nfc_reader_support_classic_limited));
        } else if (NdefFormatable.get(tag) != null) {
            lines.add(getString(R.string.nfc_reader_support_formatable));
        } else if (isoDep != null) {
            lines.add(getString(R.string.nfc_reader_support_isodep_limited));
        } else if (nfcA != null || nfcB != null || nfcF != null || nfcV != null || ultralight != null) {
            lines.add(getString(R.string.nfc_reader_support_protocol_limited));
        }
        if (lines.isEmpty()) lines.add(getString(R.string.nfc_reader_no_extra_protocol));
        return TextUtils.join("\n", lines);
    }

    private int nfcType2ModelName(NfcHardwareSecurity.Family family, MifareUltralight fallback) {
        switch (family) {
            case NTAG210: return R.string.nfc_chip_name_ntag210;
            case NTAG212: return R.string.nfc_chip_name_ntag212;
            case NTAG213: return R.string.nfc_chip_name_ntag213;
            case NTAG215: return R.string.nfc_chip_name_ntag215;
            case NTAG216: return R.string.nfc_chip_name_ntag216;
            case MIFARE_ULTRALIGHT_EV1_48: return R.string.nfc_chip_name_ultralight_ev1_48;
            case MIFARE_ULTRALIGHT_EV1_128: return R.string.nfc_chip_name_ultralight_ev1_128;
            case MIFARE_ULTRALIGHT_C: return R.string.nfc_chip_name_ultralight_c;
            default:
                // Do not label a generic Android TYPE_ULTRALIGHT_C transport result as
                // Ultralight C. The family must have passed the chip-level AUTHENTICATE probe.
                return R.string.nfc_reader_ultralight;
        }
    }

    /** Read every accessible Type-2 page, using exact NXP user-memory bounds when available. */
    private List<String> readUltralightPages(MifareUltralight tag) {
        List<String> lines = new ArrayList<>();
        int pagesRead = 0;
        try {
            // Keep a single technology connection for GET_VERSION and all READ commands. Opening
            // a second MifareUltralight wrapper around the same Tag here can tear down the first
            // session on some Android NFC stacks, leaving only the initial four-page READ usable.
            tag.connect();
            int detectedLastUserPage = NfcHardwareSecurity.getType2LastUserPage(tag);
            boolean exactLayout = detectedLastUserPage >= 4;
            // Never scan beyond the first 16 pages when no chip-specific memory layout has
            // been positively identified: later pages may be lock/configuration/key storage.
            // All nine managed chip families have an exact GET_VERSION layout or a verified
            // Ultralight C handshake and therefore use their model-specific range above.
            int pageLimit = exactLayout ? detectedLastUserPage + 1 : 16;
            lines.add(getString(R.string.nfc_reader_ultralight_public_pages));
            if (!exactLayout) {
                lines.add(getString(R.string.nfc_reader_type2_unknown_capacity_notice));
            }

            for (int page = 0; page < pageLimit; page += 4) {
                byte[] data;
                try {
                    data = tag.readPages(page);
                } catch (IOException | RuntimeException inaccessible) {
                    lines.add(getString(R.string.nfc_reader_page_read_stopped_generic, page));
                    break;
                }
                if (data == null || data.length < 16) {
                    if (data != null) Arrays.fill(data, (byte) 0);
                    lines.add(getString(R.string.nfc_reader_page_read_stopped_generic, page));
                    break;
                }
                int returnedPages = Math.min(4, pageLimit - page);
                for (int i = 0; i < returnedPages; i++) {
                    int offset = i * 4;
                    byte[] pageData = Arrays.copyOfRange(data, offset, offset + 4);
                    lines.add(getString(R.string.nfc_reader_page_data, page + i,
                            Nfc.toHex(pageData), printableAscii(pageData)));
                    Arrays.fill(pageData, (byte) 0);
                    pagesRead++;
                }
                Arrays.fill(data, (byte) 0);
            }
            lines.add(getString(R.string.nfc_reader_ultralight_pages_read, pagesRead));
            if (NfcHardwareSecurity.isUltralightCOnConnection(tag)) {
                // Never expose pages 40..47: these contain dynamic locks, counter,
                // authentication configuration and the secret 3DES authentication key.
                lines.add(getString(R.string.nfc_reader_ultralight_c_security_pages_hidden));
            }
            if (pagesRead == 0) lines.add(getString(R.string.nfc_reader_no_pages_read));
        } catch (IOException | RuntimeException e) {
            lines.add(getString(R.string.nfc_reader_page_read_stopped_generic, pagesRead));
            if (pagesRead == 0) lines.add(getString(R.string.nfc_reader_no_pages_read));
        } finally {
            try {
                tag.close();
            } catch (IOException ignored) { }
        }
        return lines;
    }

    /** Reads protected Type-2 pages only after PWD_AUTH succeeds. */
    private List<String> readUltralightPagesAuthenticated(Tag tag, String password) {
        List<String> lines = new ArrayList<>();
        byte[] memory = null;
        try {
            memory = NfcHardwareSecurity.readProtectedType2Memory(tag, password);
            lines.add(getString(R.string.nfc_reader_ultralight_authenticated_pages));
            int pageCount = memory.length / 4;
            for (int page = 0; page < pageCount; page++) {
                byte[] pageData = Arrays.copyOfRange(memory, page * 4, page * 4 + 4);
                lines.add(getString(R.string.nfc_reader_page_data, page,
                        Nfc.toHex(pageData), printableAscii(pageData)));
                Arrays.fill(pageData, (byte) 0);
            }
            lines.add(getString(R.string.nfc_reader_ultralight_pages_read, pageCount));
        } catch (IOException | RuntimeException e) {
            lines.add(getString(R.string.nfc_reader_protected_memory_read_failed));
        } finally {
            if (memory != null) Arrays.fill(memory, (byte) 0);
        }
        return lines;
    }

    private String printableAscii(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int c = value & 0xFF;
            out.append(c >= 32 && c <= 126 ? (char) c : '.');
        }
        return out.toString();
    }

    private List<String> parsePpseApplications(byte[] response) {
        List<String> apps = new ArrayList<>();
        if (response == null || response.length < 2) return apps;
        byte[] body = Arrays.copyOf(response, response.length - 2); // Exclude SW1/SW2.
        parseApplicationTemplates(body, apps, 0);
        return apps;
    }

    private void parseApplicationTemplates(byte[] bytes, List<String> apps, int depth) {
        if (bytes == null || depth > 8) return;
        int pos = 0;
        while (pos < bytes.length) {
            Tlv tlv = readTlv(bytes, pos);
            if (tlv == null || tlv.next <= pos) return;
            pos = tlv.next;
            if (tlv.tag == 0x61) {
                List<Tlv> children = readChildren(tlv.value);
                String aid = null;
                String label = null;
                for (Tlv child : children) {
                    if (child.tag == 0x4F) aid = Nfc.toHex(child.value);
                    else if (child.tag == 0x50) label = asciiOrHex(child.value);
                }
                if (aid != null) {
                    String app = label == null || label.trim().isEmpty() ? getString(R.string.nfc_reader_app_aid, aid)
                            : getString(R.string.nfc_reader_app_label_aid, label, aid);
                    if (!apps.contains(app)) apps.add(app);
                }
                parseApplicationTemplates(tlv.value, apps, depth + 1);
            } else if (tlv.constructed) {
                parseApplicationTemplates(tlv.value, apps, depth + 1);
            }
        }
    }

    private List<Tlv> readChildren(byte[] bytes) {
        List<Tlv> result = new ArrayList<>();
        int pos = 0;
        while (pos < bytes.length) {
            Tlv tlv = readTlv(bytes, pos);
            if (tlv == null || tlv.next <= pos) break;
            result.add(tlv);
            pos = tlv.next;
        }
        return result;
    }

    private Tlv readTlv(byte[] bytes, int start) {
        if (start >= bytes.length) return null;
        int pos = start;
        int first = bytes[pos++] & 0xFF;
        int tag = first;
        if ((first & 0x1F) == 0x1F) {
            int count = 0;
            int b;
            do {
                if (pos >= bytes.length || count++ > 2) return null;
                b = bytes[pos++] & 0xFF;
                tag = (tag << 8) | b;
            } while ((b & 0x80) != 0);
        }
        if (pos >= bytes.length) return null;
        int length = bytes[pos++] & 0xFF;
        if (length == 0x81) {
            if (pos >= bytes.length) return null;
            length = bytes[pos++] & 0xFF;
        } else if (length == 0x82) {
            if (pos + 1 >= bytes.length) return null;
            length = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
            pos += 2;
        } else if ((length & 0x80) != 0) {
            return null;
        }
        if (length < 0 || length > bytes.length - pos) return null;
        return new Tlv(tag, (first & 0x20) != 0, Arrays.copyOfRange(bytes, pos, pos + length), pos + length);
    }

    private static final class Tlv {
        final int tag;
        final boolean constructed;
        final byte[] value;
        final int next;
        Tlv(int tag, boolean constructed, byte[] value, int next) {
            this.tag = tag;
            this.constructed = constructed;
            this.value = value;
            this.next = next;
        }
    }

    private String decodeTextPayload(byte[] payload) {
        if (payload == null || payload.length < 1) return getString(R.string.nfc_reader_empty_payload);
        int status = payload[0] & 0xFF;
        int languageLength = status & 0x3F;
        int textOffset = 1 + languageLength;
        if (textOffset > payload.length) return getString(R.string.nfc_reader_invalid_text_record);
        Charset charset = (status & 0x80) != 0 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
        String text = new String(payload, textOffset, payload.length - textOffset, charset);
        return text;
    }

    private static String decodeUriPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return "";
        String[] prefixes = {"", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
                "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
                "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp://",
                "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
                "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"};
        int prefix = payload[0] & 0xFF;
        String start = prefix < prefixes.length ? prefixes[prefix] : "";
        return start + new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
    }

    private String printableUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        String value = new String(bytes, StandardCharsets.UTF_8);
        boolean printable = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) {
                printable = false;
                break;
            }
        }
        return printable ? value : Nfc.toHex(bytes);
    }

    private String asciiOrHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        for (byte b : bytes) if ((b & 0xFF) < 0x20 || (b & 0xFF) > 0x7E) return Nfc.toHex(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private boolean hasSuccessStatus(byte[] response) {
        return response != null && response.length >= 2
                && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == 0x00;
    }

    private String statusWord(byte[] response) {
        if (response == null || response.length < 2) return getString(R.string.nfc_reader_apdu_no_status);
        return Nfc.toHex(new byte[]{response[response.length - 2], response[response.length - 1]});
    }

    private String mifareClassicType(int type) {
        switch (type) {
            case MifareClassic.TYPE_CLASSIC: return getString(R.string.nfc_reader_mifare_type_classic);
            case MifareClassic.TYPE_PLUS: return getString(R.string.nfc_reader_mifare_type_plus);
            case MifareClassic.TYPE_PRO: return getString(R.string.nfc_reader_mifare_type_pro);
            default: return getString(R.string.nfc_reader_unknown);
        }
    }

    private String yesNo(boolean value) {
        return getString(value ? R.string.nfc_reader_yes : R.string.nfc_reader_no);
    }
    private String safeString(String value) {
        return value == null ? getString(R.string.nfc_reader_unknown) : value;
    }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.nfc_reader_title), lastReport));
            Toast.makeText(this, R.string.nfc_reader_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearResults() {
        pendingProtectedReadPassword = null;
        pendingProtectedReadTagId = null;
        protectedReadPromptOpen = false;
        lastReport = "";
        latestTagSupportsIsoDep = false;
        latestTagHasEmvApp = false;
        emvReadArmed = false;
        emvReadButton.setEnabled(false);
        emvCard.setVisibility(View.GONE);
        emvResultView.setText("");
        emvResultView.setVisibility(View.GONE);
        idView.setText(R.string.nfc_reader_not_scanned);
        technologiesView.setText(R.string.nfc_reader_not_scanned);
        ndefView.removeAllViews();
        decodedFieldsContainer.removeAllViews();
        protocolView.setText(R.string.nfc_reader_not_scanned);
        protocolCard.setVisibility(View.GONE);
        ndefCard.setVisibility(View.GONE);
        decodedCard.setVisibility(View.GONE);
        copyButton.setEnabled(false);
        if (nfcAdapter != null && nfcAdapter.isEnabled()) statusView.setText(R.string.nfc_reader_waiting);
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
