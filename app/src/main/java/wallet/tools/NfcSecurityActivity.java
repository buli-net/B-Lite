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
import android.widget.TextView;

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

    private NfcAdapter nfcAdapter;
    private TextView statusView;
    private TextView tagView;
    private Button inspectButton;
    private Button clearButton;
    private volatile int operation = IDLE;

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
        } else if (!nfcAdapter.isEnabled()) {
            statusView.setText(R.string.nfc_security_disabled);
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
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
            return;
        }
        inspectButton.setEnabled(true);
        clearButton.setEnabled(true);
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V;
        nfcAdapter.enableReaderMode(this, this, flags, null);
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        operation = IDLE;
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        int pending = operation;
        if (pending != INSPECT && pending != ERASE) return;
        operation = PROCESSING;
        runOnUiThread(() -> {
            inspectButton.setEnabled(false);
            clearButton.setEnabled(false);
        });
        String tagId = toHex(tag.getId());
        String techText = tag.getTechList() == null ? "" : TextUtils.join(", ", tag.getTechList());
        if (pending == ERASE) clearTag(tag, tagId, techText);
        else inspectTag(tag, tagId, techText);
    }

    private void inspectTag(Tag tag, String tagId, String techText) {
        String result;
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
        final String finalResult = result;
        runOnUiThread(() -> {
            tagView.setText(getString(R.string.nfc_security_detected_tag, tagId, techText));
            statusView.setText(finalResult);
            operation = IDLE;
            inspectButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
            clearButton.setEnabled(nfcAdapter != null && nfcAdapter.isEnabled());
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
