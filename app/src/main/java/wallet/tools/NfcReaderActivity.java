package wallet.tools;

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
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
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

import wallet.main.BaseActivity;
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
    private TextView ndefView;
    private TextView decodedView;
    private TextView protocolView;
    private Button copyButton;
    private View protocolCard;
    private View ndefCard;
    private View decodedCard;
    private String lastReport = "";

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
        decodedView = findViewById(R.id.nfc_reader_decoded_value);
        decodedCard = findViewById(R.id.nfc_reader_decoded_card);
        protocolView = findViewById(R.id.nfc_reader_protocol_value);
        protocolCard = findViewById(R.id.nfc_reader_protocol_card);
        ndefCard = findViewById(R.id.nfc_reader_ndef_card);
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
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        // ReaderCallback is already dispatched off the main thread by Android.
        String id = toHex(tag.getId());
        String technologies = buildTechnologyReport(tag);
        String ndefText = readNdef(tag);
        String decodedText = readDecodedContent(tag);
        String protocolText = readProtocolDetails(tag);
        boolean hasNdef = Ndef.get(tag) != null;
        boolean isMifareClassic = MifareClassic.get(tag) != null;
        boolean hasIsoDep = IsoDep.get(tag) != null;
        boolean hasProtocolDetails = hasIsoDep || NfcA.get(tag) != null || NfcB.get(tag) != null
                || NfcF.get(tag) != null || NfcV.get(tag) != null || isMifareClassic
                || MifareUltralight.get(tag) != null || NdefFormatable.get(tag) != null;
        String report = getString(R.string.nfc_reader_report_id, id.isEmpty() ? getString(R.string.nfc_reader_unknown) : id)
                + "\n\n" + getString(R.string.nfc_reader_report_technologies) + "\n" + technologies
                + "\n\n" + getString(R.string.nfc_reader_report_ndef) + "\n" + ndefText
                + "\n\n" + getString(R.string.nfc_reader_report_protocol) + "\n" + protocolText;

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
            ndefView.setText(ndefText);
            decodedView.setText(decodedText);
            decodedCard.setVisibility(decodedText.isEmpty() ? View.GONE : View.VISIBLE);
            protocolView.setText(protocolText);
            copyButton.setEnabled(true);
        });
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
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
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
            return getString(R.string.nfc_reader_ndef_read_failed, e.getClass().getSimpleName(), safeMessage(e));
        } finally {
            try { ndef.close(); } catch (IOException ignored) { }
        }
    }

    private void appendRecordDetails(StringBuilder out, NdefRecord record, int depth) {
        String indent = depth > 0 ? "  " : "";
        out.append(indent).append(getString(R.string.nfc_reader_record_tnf, record.getTnf())).append('\n');
        byte[] type = record.getType();
        byte[] id = record.getId();
        if (type != null && type.length > 0) out.append(indent).append(getString(R.string.nfc_reader_record_type, printableUtf8(type))).append('\n');
        if (id != null && id.length > 0) out.append(indent).append(getString(R.string.nfc_reader_record_id, toHex(id))).append('\n');
        // This card is intentionally technical/raw only. Human-readable decoded content is shown separately.
        appendPayloadHex(out, record.getPayload(), indent);
    }

    /** Build a human-readable reconstruction of the stored NDEF content, separate from tag diagnostics. */
    private String readDecodedContent(Tag tag) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return "";
        try {
            ndef.connect();
            NdefMessage message = ndef.getNdefMessage();
            if (message == null) return "";
            StringBuilder out = new StringBuilder();
            for (NdefRecord record : message.getRecords()) {
                if (out.length() > 0) out.append("\n\n");
                appendDecodedRecord(out, record);
            }
            return out.toString().trim();
        } catch (IOException | FormatException | RuntimeException e) {
            return "";
        } finally {
            try { ndef.close(); } catch (IOException ignored) { }
        }
    }

    private void appendDecodedRecord(StringBuilder out, NdefRecord record) {
        byte[] type = record.getType();
        byte[] payload = record.getPayload();
        if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
            out.append(getString(R.string.nfc_reader_decoded_text, decodeTextPayload(payload)));
        } else if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
            appendDecodedUri(out, decodeUriPayload(payload), "");
        } else if (record.getTnf() == NdefRecord.TNF_ABSOLUTE_URI) {
            appendDecodedUri(out, printableUtf8(type), "");
        } else if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA) {
            String mimeType = printableUtf8(type);
            String content = decodeUtf8IfReadable(payload);
            String normalizedType = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
            boolean isVCard = normalizedType.equals("text/vcard") || normalizedType.equals("text/x-vcard");
            if (isVCard && content != null && content.toUpperCase(Locale.ROOT).contains("BEGIN:VCARD")) {
                appendVCardForm(out, content);
            } else if (content != null && (normalizedType.startsWith("text/") || normalizedType.equals("application/json")
                    || normalizedType.equals("application/xml") || normalizedType.endsWith("+json") || normalizedType.endsWith("+xml"))) {
                out.append(getString(R.string.nfc_reader_decoded_mime_content, content));
            } else if (content != null) {
                out.append(getString(R.string.nfc_reader_decoded_mime_content, content));
            } else {
                out.append(getString(R.string.nfc_reader_mime_type, mimeType));
            }
        } else if (record.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE) {
            out.append(getString(R.string.nfc_reader_external_type, printableUtf8(type)));
            String text = decodeUtf8IfReadable(payload);
            if (text != null && !text.trim().isEmpty()) out.append("\n").append(getString(R.string.nfc_reader_decoded_mime_content, text));
        } else if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_SMART_POSTER)) {
            try {
                NdefMessage nested = new NdefMessage(payload);
                for (NdefRecord child : nested.getRecords()) appendDecodedRecord(out, child);
            } catch (FormatException ignored) { }
        } else {
            String text = decodeUtf8IfReadable(payload);
            if (text != null && !text.trim().isEmpty()) out.append(getString(R.string.nfc_reader_decoded_mime_content, text));
        }
    }

    /** Render the decoded vCard like a read-only version of the input form; raw bytes stay in the technical card. */
    private void appendVCardForm(StringBuilder out, String content) {
        java.util.Map<String, List<String>> values = parseVCardProperties(content);
        out.append(getString(R.string.nfc_reader_vcard_form_title)).append('\n');
        appendVCardFormField(out, R.string.nfc_reader_vcard_name, firstVCard(values, "FN"));
        String structured = firstVCard(values, "N");
        String[] nameParts = structured.split(";", -1);
        String family = nameParts.length > 0 ? nameParts[0] : "";
        String given = nameParts.length > 1 ? nameParts[1] : "";
        String additional = nameParts.length > 2 ? nameParts[2] : "";
        String prefix = nameParts.length > 3 ? nameParts[3] : "";
        String suffix = nameParts.length > 4 ? nameParts[4] : "";
        String structuredDisplay = getString(R.string.nfc_reader_vcard_name_parts, displayOrDash(family), displayOrDash(given), displayOrDash(additional), displayOrDash(prefix), displayOrDash(suffix));
        appendVCardFormField(out, R.string.nfc_reader_vcard_structured_name, structuredDisplay);
        appendVCardFormField(out, R.string.nfc_reader_vcard_organization, firstVCard(values, "ORG"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_title, firstVCard(values, "TITLE"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_phone, joinVCard(values, "TEL"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_email, joinVCard(values, "EMAIL"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_url, joinVCard(values, "URL"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_address, joinVCard(values, "ADR"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_note, joinVCard(values, "NOTE"));
        appendVCardFormField(out, R.string.nfc_reader_vcard_birthday, firstVCard(values, "BDAY"));
        out.append("\n").append(getString(R.string.nfc_reader_vcard_original)).append('\n').append(content.trim());
    }

    private void appendVCardFormField(StringBuilder out, int labelRes, String value) {
        out.append("\n\n").append(getString(labelRes)).append('\n').append(displayOrDash(value));
    }

    private String displayOrDash(String value) {
        return value == null || value.trim().isEmpty() ? getString(R.string.nfc_reader_field_empty) : value;
    }

    private String firstVCard(java.util.Map<String, List<String>> values, String key) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private String joinVCard(java.util.Map<String, List<String>> values, String key) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? "" : TextUtils.join("\n", list);
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

    private void appendDecodedUri(StringBuilder out, String uri, String indent) {
        if (uri == null || uri.isEmpty()) return;
        String lower = uri.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tel:")) {
            out.append(indent).append(getString(R.string.nfc_reader_decoded_phone, uri.substring(4))).append('\n');
        } else if (lower.startsWith("mailto:")) {
            out.append(indent).append(getString(R.string.nfc_reader_decoded_email, uri.substring(7))).append('\n');
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            out.append(indent).append(getString(R.string.nfc_reader_decoded_url, uri)).append('\n');
        } else {
            out.append(indent).append(getString(R.string.nfc_reader_decoded_uri, uri)).append('\n');
        }
    }

    private void appendDecodedMime(StringBuilder out, String mimeType, byte[] payload, String indent) {
        String normalizedType = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        String content = decodeUtf8IfReadable(payload);
        boolean isVCard = normalizedType.equals("text/vcard") || normalizedType.equals("text/x-vcard");
        if (isVCard && content != null && content.toUpperCase(Locale.ROOT).contains("BEGIN:VCARD")) {
            List<String> fields = parseVCardFields(content);
            out.append(indent).append(getString(R.string.nfc_reader_vcard_heading)).append('\n');
            if (fields.isEmpty()) {
                out.append(indent).append(getString(R.string.nfc_reader_vcard_no_fields)).append('\n');
            } else {
                for (String field : fields) out.append(indent).append("  ").append(field).append('\n');
            }
            out.append(indent).append(getString(R.string.nfc_reader_vcard_full, content)).append('\n');
        } else if (content != null && (normalizedType.startsWith("text/")
                || normalizedType.equals("application/json")
                || normalizedType.equals("application/xml")
                || normalizedType.endsWith("+json") || normalizedType.endsWith("+xml"))) {
            out.append(indent).append(getString(R.string.nfc_reader_decoded_mime_content, content)).append('\n');
        } else if (content != null && isVCard) {
            // Some tags omit or alter the expected vCard header; show the text, but do not invent fields.
            out.append(indent).append(getString(R.string.nfc_reader_decoded_mime_content, content)).append('\n');
        } else {
            appendDecodedTextIfReadable(out, payload, indent);
        }
    }

    private void appendDecodedTextIfReadable(StringBuilder out, byte[] payload, String indent) {
        String text = decodeUtf8IfReadable(payload);
        if (text != null && !text.trim().isEmpty()) {
            out.append(indent).append(getString(R.string.nfc_reader_decoded_mime_content, text)).append('\n');
        }
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

    private List<String> parseVCardFields(String content) {
        List<String> fields = new ArrayList<>();
        List<String> unfolded = new ArrayList<>();
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !unfolded.isEmpty()) {
                int last = unfolded.size() - 1;
                unfolded.set(last, unfolded.get(last) + line.substring(1));
            } else {
                unfolded.add(line);
            }
        }
        for (String line : unfolded) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String property = line.substring(0, colon).split("[;.]", 2)[0].toUpperCase(Locale.ROOT);
            String value = unescapeVCardValue(line.substring(colon + 1));
            String label = vCardFieldLabel(property);
            if (label != null && !value.trim().isEmpty()) {
                String field = getString(R.string.nfc_reader_vcard_field, label, value);
                if (!fields.contains(field)) fields.add(field);
            }
        }
        return fields;
    }

    private String unescapeVCardValue(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    @Nullable
    private String vCardFieldLabel(String property) {
        switch (property) {
            case "FN": return getString(R.string.nfc_reader_vcard_name);
            case "N": return getString(R.string.nfc_reader_vcard_structured_name);
            case "ORG": return getString(R.string.nfc_reader_vcard_organization);
            case "TITLE": return getString(R.string.nfc_reader_vcard_title);
            case "TEL": return getString(R.string.nfc_reader_vcard_phone);
            case "EMAIL": return getString(R.string.nfc_reader_vcard_email);
            case "URL": return getString(R.string.nfc_reader_vcard_url);
            case "ADR": return getString(R.string.nfc_reader_vcard_address);
            case "NOTE": return getString(R.string.nfc_reader_vcard_note);
            case "BDAY": return getString(R.string.nfc_reader_vcard_birthday);
            default: return null;
        }
    }

    private void appendPayloadHex(StringBuilder out, byte[] payload, String indent) {
        if (payload == null || payload.length == 0) {
            out.append(indent).append(getString(R.string.nfc_reader_empty_payload)).append('\n');
            return;
        }
        int length = Math.min(payload.length, MAX_PAYLOAD_HEX_BYTES);
        out.append(indent).append(getString(R.string.nfc_reader_payload_hex, toHex(Arrays.copyOf(payload, length))));
        if (payload.length > length) out.append(" … ").append(getString(R.string.nfc_reader_payload_truncated, payload.length));
        out.append('\n');
    }

    private String readProtocolDetails(Tag tag) {
        List<String> lines = new ArrayList<>();
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep != null) {
            lines.add(getString(R.string.nfc_reader_iso_dep));
            lines.add(getString(R.string.nfc_reader_max_transceive, isoDep.getMaxTransceiveLength()));
            lines.add(getString(R.string.nfc_reader_extended_length, yesNo(isoDep.isExtendedLengthApduSupported())));
            byte[] historical = isoDep.getHistoricalBytes();
            if (historical != null && historical.length > 0) lines.add(getString(R.string.nfc_reader_historical_bytes, toHex(historical)));
            byte[] hiLayer = isoDep.getHiLayerResponse();
            if (hiLayer != null && hiLayer.length > 0) lines.add(getString(R.string.nfc_reader_hi_layer, toHex(hiLayer)));
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
                    if (apps.isEmpty()) {
                        lines.add(getString(R.string.nfc_reader_ppse_no_apps));
                    } else {
                        lines.add(getString(R.string.nfc_reader_ppse_apps));
                        for (String app : apps) lines.add("• " + app);
                    }
                } else {
                    lines.add(getString(R.string.nfc_reader_ppse_unavailable));
                }
            } catch (IOException | RuntimeException e) {
                lines.add(getString(R.string.nfc_reader_connection_failed, e.getClass().getSimpleName()));
            } finally {
                try { if (isoDep.isConnected()) isoDep.close(); } catch (IOException ignored) { }
            }
        }

        NfcA nfcA = NfcA.get(tag);
        if (nfcA != null) {
            lines.add(getString(R.string.nfc_reader_nfca));
            lines.add(getString(R.string.nfc_reader_atqa, toHex(nfcA.getAtqa())));
            lines.add(getString(R.string.nfc_reader_sak, String.format(Locale.ROOT, "%04X", nfcA.getSak() & 0xFFFF)));
        }
        NfcB nfcB = NfcB.get(tag);
        if (nfcB != null) {
            lines.add(getString(R.string.nfc_reader_nfcb));
            lines.add(getString(R.string.nfc_reader_application_data, toHex(nfcB.getApplicationData())));
            lines.add(getString(R.string.nfc_reader_protocol_info, toHex(nfcB.getProtocolInfo())));
        }
        NfcF nfcF = NfcF.get(tag);
        if (nfcF != null) {
            lines.add(getString(R.string.nfc_reader_nfcf));
            lines.add(getString(R.string.nfc_reader_system_code, toHex(nfcF.getSystemCode())));
            lines.add(getString(R.string.nfc_reader_manufacturer, toHex(nfcF.getManufacturer())));
        }
        NfcV nfcV = NfcV.get(tag);
        if (nfcV != null) {
            lines.add(getString(R.string.nfc_reader_nfcv));
            lines.add(getString(R.string.nfc_reader_dsfid, String.format(Locale.ROOT, "%02X", nfcV.getDsfId() & 0xFF)));
            lines.add(getString(R.string.nfc_reader_response_flags, String.format(Locale.ROOT, "%02X", nfcV.getResponseFlags() & 0xFF)));
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
            lines.add(getString(R.string.nfc_reader_mifare_type, ultralight.getType() == MifareUltralight.TYPE_ULTRALIGHT_C ? "Ultralight C" : "Ultralight"));
            lines.addAll(readUltralightPages(ultralight));
        }
        if (NdefFormatable.get(tag) != null) lines.add(getString(R.string.nfc_reader_ndef_formatable));

        // Give a clear, capability-based explanation instead of implying all protocols share one memory layout.
        if (Ndef.get(tag) != null) {
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

    /** Read only the pages the tag exposes through the standard Ultralight read command. */
    private List<String> readUltralightPages(MifareUltralight tag) {
        List<String> lines = new ArrayList<>();
        int type = tag.getType();
        int pageLimit = type == MifareUltralight.TYPE_ULTRALIGHT ? 16
                : type == MifareUltralight.TYPE_ULTRALIGHT_C ? 48 : 64;
        lines.add(getString(R.string.nfc_reader_ultralight_public_pages));
        int pagesRead = 0;
        try {
            tag.connect();
            for (int page = 0; page < pageLimit; page += 4) {
                byte[] data;
                try {
                    data = tag.readPages(page);
                } catch (IOException | RuntimeException inaccessible) {
                    lines.add(getString(R.string.nfc_reader_page_read_stopped, page,
                            inaccessible.getClass().getSimpleName()));
                    break;
                }
                if (data == null || data.length == 0) break;
                for (int offset = 0; offset + 3 < data.length && page + offset / 4 < pageLimit; offset += 4) {
                    byte[] pageData = Arrays.copyOfRange(data, offset, offset + 4);
                    lines.add(String.format(Locale.ROOT, "Page %02d: %s  | %s", page + offset / 4,
                            toHex(pageData), printableAscii(pageData)));
                    pagesRead++;
                }
            }
            if (pagesRead == 0) lines.add(getString(R.string.nfc_reader_no_pages_read));
        } catch (IOException | RuntimeException e) {
            lines.add(getString(R.string.nfc_reader_page_read_stopped, 0, e.getClass().getSimpleName()));
        } finally {
            try { tag.close(); } catch (IOException ignored) { }
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
                    if (child.tag == 0x4F) aid = toHex(child.value);
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
        String language = new String(payload, 1, languageLength, StandardCharsets.US_ASCII);
        String text = new String(payload, textOffset, payload.length - textOffset, charset);
        return (language.isEmpty() ? "" : "[" + language + "] ") + text;
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
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) { printable = false; break; }
        }
        return printable ? value : toHex(bytes);
    }

    private String asciiOrHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        for (byte b : bytes) if ((b & 0xFF) < 0x20 || (b & 0xFF) > 0x7E) return toHex(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private boolean hasSuccessStatus(byte[] response) {
        return response != null && response.length >= 2
                && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == 0x00;
    }

    private String statusWord(byte[] response) {
        if (response == null || response.length < 2) return getString(R.string.nfc_reader_apdu_no_status);
        return String.format(Locale.ROOT, "%02X%02X", response[response.length - 2] & 0xFF, response[response.length - 1] & 0xFF);
    }

    private String mifareClassicType(int type) {
        switch (type) {
            case MifareClassic.TYPE_CLASSIC: return "Classic";
            case MifareClassic.TYPE_PLUS: return "Plus";
            case MifareClassic.TYPE_PRO: return "Pro";
            default: return getString(R.string.nfc_reader_unknown);
        }
    }

    private String yesNo(boolean value) { return getString(value ? R.string.nfc_reader_yes : R.string.nfc_reader_no); }
    private String safeString(String value) { return value == null ? getString(R.string.nfc_reader_unknown) : value; }
    private String safeMessage(Exception e) { return e.getMessage() == null ? "" : e.getMessage(); }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.nfc_reader_title), lastReport));
            Toast.makeText(this, R.string.nfc_reader_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearResults() {
        lastReport = "";
        idView.setText(R.string.nfc_reader_not_scanned);
        technologiesView.setText(R.string.nfc_reader_not_scanned);
        ndefView.setText(R.string.nfc_reader_not_scanned);
        decodedView.setText("");
        protocolView.setText(R.string.nfc_reader_not_scanned);
        protocolCard.setVisibility(View.GONE);
        ndefCard.setVisibility(View.GONE);
        decodedCard.setVisibility(View.GONE);
        copyButton.setEnabled(false);
        if (nfcAdapter != null && nfcAdapter.isEnabled()) statusView.setText(R.string.nfc_reader_waiting);
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
        return result.toString();
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
