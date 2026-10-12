package wallet.tools;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.tech.Ndef;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import wallet.main.BaseActivity;
import wallet.main.R;

/** Reads publicly accessible NFC tag metadata and NDEF records. */
public class NfcReaderActivity extends BaseActivity implements NfcAdapter.ReaderCallback {
    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView idView;
    private TextView technologiesView;
    private TextView ndefView;

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
        statusView.setText(R.string.nfc_reader_waiting);
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        String id = toHex(tag.getId());
        String technologies = TextUtils.join("\n", tag.getTechList());
        String ndefText = readNdef(tag);
        runOnUiThread(() -> {
            statusView.setText(R.string.nfc_reader_found);
            idView.setText(id.isEmpty() ? getString(R.string.nfc_reader_no_ndef) : id);
            technologiesView.setText(technologies);
            ndefView.setText(ndefText);
        });
    }

    private String readNdef(Tag tag) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            return getString(R.string.nfc_reader_ndef_not_formatted);
        }
        try {
            ndef.connect();
            NdefMessage message = ndef.getNdefMessage();
            if (message == null || message.getRecords().length == 0) {
                return getString(R.string.nfc_reader_no_ndef);
            }
            List<String> lines = new ArrayList<>();
            for (NdefRecord record : message.getRecords()) {
                lines.add("TNF: " + record.getTnf());
                byte[] type = record.getType();
                if (type.length > 0) lines.add("Type: " + new String(type, StandardCharsets.UTF_8));
                byte[] payload = record.getPayload();
                if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN
                        && Arrays.equals(type, NdefRecord.RTD_TEXT) && payload.length > 0) {
                    int languageLength = payload[0] & 0x3F;
                    if (payload.length > 1 + languageLength) {
                        lines.add("Text: " + new String(payload, 1 + languageLength,
                                payload.length - 1 - languageLength, StandardCharsets.UTF_8));
                    }
                } else if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN
                        && Arrays.equals(type, NdefRecord.RTD_URI) && payload.length > 0) {
                    lines.add("URI: " + decodeUriPayload(payload));
                } else {
                    lines.add("Payload (hex): " + toHex(payload));
                }
            }
            return TextUtils.join("\n", lines);
        } catch (IOException | android.nfc.FormatException e) {
            return getString(R.string.nfc_reader_no_ndef) + "\n" + e.getClass().getSimpleName();
        } finally {
            try { ndef.close(); } catch (IOException ignored) { }
        }
    }

    private static String decodeUriPayload(byte[] payload) {
        String[] prefixes = {"", "http://www.", "https://www.", "http://", "https://",
                "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
                "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://",
                "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://",
                "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
                "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"};
        int prefix = payload[0] & 0xFF;
        String start = prefix < prefixes.length ? prefixes[prefix] : "";
        return start + new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xFF));
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
