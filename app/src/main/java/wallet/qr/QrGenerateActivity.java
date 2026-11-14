package wallet.qr;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Creates structured QR payloads and supported barcode formats. */
public class QrGenerateActivity extends BaseActivity {
    public static final String EXTRA_INITIAL_CONTENT = "wallet.qr.extra.INITIAL_CONTENT";
    public static final String EXTRA_INITIAL_FORMAT = "wallet.qr.extra.INITIAL_FORMAT";
    private static final int REQUEST_WRITE_IMAGE_PERMISSION = 7301;

    private ScrollView contentScroll;
    private EditText contentInput;
    private Spinner contentTypeSpinner;
    private Spinner formatSpinner;
    private Spinner sizeSpinner;
    private Spinner errorCorrectionSpinner;
    private Spinner marginSpinner;
    private ImageView generatedImage;
    private TextView generatedContentValue;
    private View generatedCard;
    private View qrOptions;
    private View[] inputGroups;
    private String generatedContent = "";
    private Bitmap generatedBitmap;
    private String generatedFormatValue = "QR_CODE";
    private String historyId = "";
    private boolean favorite;
    private boolean pendingBatchAfterPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_generate);

        Toolbar toolbar = findViewById(R.id.toolbar_qr_generate);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.qr_tools_generate_title);
        }

        bindViews();
        configureSpinners();
        contentTypeSpinner.setOnItemSelectedListener(new SimpleSelectionListener(this::updateInputType));
        formatSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> updateFormatOptions()));

        findViewById(R.id.qr_tools_generate).setOnClickListener(v -> generateCode());
        findViewById(R.id.qr_tools_generate_batch).setOnClickListener(v -> generateBatch());
        findViewById(R.id.qr_tools_generated_copy).setOnClickListener(v -> copyGeneratedContent());
        findViewById(R.id.qr_tools_generated_share).setOnClickListener(v -> shareGeneratedContent());
        findViewById(R.id.qr_tools_generated_share_image).setOnClickListener(v -> shareGeneratedImage());
        findViewById(R.id.qr_tools_generated_save).setOnClickListener(v -> saveGeneratedImage());
        findViewById(R.id.qr_tools_generated_favorite).setOnClickListener(v -> toggleFavorite());
        findViewById(R.id.qr_tools_generated_print).setOnClickListener(v -> printGeneratedImage());
        updateInputType(0);
        applyInitialContent();
    }

    private void applyInitialContent() {
        String initialContent = getIntent().getStringExtra(EXTRA_INITIAL_CONTENT);
        if (TextUtils.isEmpty(initialContent)) return;
        contentTypeSpinner.setSelection(0);
        contentInput.setText(initialContent);
        String requestedFormat = getIntent().getStringExtra(EXTRA_INITIAL_FORMAT);
        if (!TextUtils.isEmpty(requestedFormat)) {
            String[] formats = getResources().getStringArray(R.array.qr_tools_format_values);
            for (int i = 0; i < formats.length; i++) {
                if (requestedFormat.equalsIgnoreCase(formats[i])) {
                    formatSpinner.setSelection(i);
                    break;
                }
            }
        }
        updateInputType(0);
    }

    private void bindViews() {
        contentScroll = findViewById(R.id.qr_generate_scroll);
        contentInput = findViewById(R.id.qr_tools_content_input);
        contentTypeSpinner = findViewById(R.id.qr_tools_content_type_spinner);
        formatSpinner = findViewById(R.id.qr_tools_format_spinner);
        sizeSpinner = findViewById(R.id.qr_tools_size_spinner);
        errorCorrectionSpinner = findViewById(R.id.qr_tools_error_correction_spinner);
        marginSpinner = findViewById(R.id.qr_tools_margin_spinner);
        qrOptions = findViewById(R.id.qr_tools_qr_options);
        generatedCard = findViewById(R.id.qr_tools_generated_card);
        generatedImage = findViewById(R.id.qr_tools_generated_image);
        generatedContentValue = findViewById(R.id.qr_tools_generated_content_value);
        inputGroups = new View[]{
                findViewById(R.id.qr_input_text_group),
                findViewById(R.id.qr_input_url_group),
                findViewById(R.id.qr_input_wifi_group),
                findViewById(R.id.qr_input_phone_group),
                findViewById(R.id.qr_input_contact_group),
                findViewById(R.id.qr_input_location_group),
                findViewById(R.id.qr_input_sms_group),
                findViewById(R.id.qr_input_email_group),
                findViewById(R.id.qr_input_event_group),
                findViewById(R.id.qr_input_bitcoin_group)
        };
    }

    private void configureSpinners() {
        setSpinnerAdapter(contentTypeSpinner, R.array.qr_tools_content_type_names);
        setSpinnerAdapter(formatSpinner, R.array.qr_tools_format_names);
        setSpinnerAdapter(sizeSpinner, R.array.qr_tools_size_names);
        setSpinnerAdapter(errorCorrectionSpinner, R.array.qr_tools_error_correction_names);
        setSpinnerAdapter(marginSpinner, R.array.qr_tools_margin_names);
        Spinner wifiSecurity = findViewById(R.id.qr_field_wifi_security);
        setSpinnerAdapter(wifiSecurity, R.array.qr_tools_wifi_security_names);
        wifiSecurity.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            findViewById(R.id.qr_field_password).setVisibility(position == 2 ? View.GONE : View.VISIBLE);
        }));
        sizeSpinner.setSelection(2);
        errorCorrectionSpinner.setSelection(1);
        marginSpinner.setSelection(2);
    }

    private void setSpinnerAdapter(Spinner spinner, int arrayResource) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, arrayResource, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void updateInputType(int position) {
        String[] values = getResources().getStringArray(R.array.qr_tools_content_type_values);
        int selected = Math.max(0, Math.min(position, values.length - 1));
        for (int i = 0; i < inputGroups.length; i++) {
            inputGroups[i].setVisibility(i == selected ? View.VISIBLE : View.GONE);
        }
        boolean freeText = QrPayloadBuilder.TYPE_TEXT.equals(values[selected]);
        findViewById(R.id.qr_tools_generate_batch).setVisibility(freeText ? View.VISIBLE : View.GONE);
        findViewById(R.id.qr_tools_structured_notice).setVisibility(freeText ? View.GONE : View.VISIBLE);
        if (!freeText && formatSpinner.getSelectedItemPosition() != 0) formatSpinner.setSelection(0);
        formatSpinner.setEnabled(freeText);
        updateFormatOptions();
    }

    private void updateFormatOptions() {
        String[] formats = getResources().getStringArray(R.array.qr_tools_format_values);
        int position = Math.max(0, Math.min(formatSpinner.getSelectedItemPosition(), formats.length - 1));
        String format = formats[position];
        boolean qr = "QR_CODE".equals(format);
        qrOptions.setVisibility(qr ? View.VISIBLE : View.GONE);
        if (contentTypeSpinner.getSelectedItemPosition() == 0) {
            updateTextInputForFormat(format);
        }
    }

    private void updateTextInputForFormat(String format) {
        TextView contentLabel = findViewById(R.id.qr_tools_content_label);
        int hintResource;
        int inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        switch (format) {
            case "DATA_MATRIX":
                hintResource = R.string.qr_tools_hint_data_matrix;
                break;
            case "AZTEC":
                hintResource = R.string.qr_tools_hint_aztec;
                break;
            case "PDF_417":
                hintResource = R.string.qr_tools_hint_pdf417;
                break;
            case "CODE_128":
                hintResource = R.string.qr_tools_hint_code128;
                inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                break;
            case "CODE_39":
                hintResource = R.string.qr_tools_hint_code39;
                inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                break;
            case "CODE_93":
                hintResource = R.string.qr_tools_hint_code93;
                inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                break;
            case "EAN_13":
                hintResource = R.string.qr_tools_hint_ean13;
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
            case "EAN_8":
                hintResource = R.string.qr_tools_hint_ean8;
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
            case "UPC_A":
                hintResource = R.string.qr_tools_hint_upca;
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
            case "UPC_E":
                hintResource = R.string.qr_tools_hint_upce;
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
            case "ITF":
                hintResource = R.string.qr_tools_hint_itf;
                inputType = InputType.TYPE_CLASS_NUMBER;
                break;
            case "CODABAR":
                hintResource = R.string.qr_tools_hint_codabar;
                inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                break;
            case "QR_CODE":
            default:
                hintResource = R.string.qr_tools_hint_qr_code;
                break;
        }
        String formatName = formatSpinner.getSelectedItem() == null
                ? "QR Code" : formatSpinner.getSelectedItem().toString();
        contentLabel.setText(getString(R.string.qr_tools_format_input_label, formatName));
        contentInput.setHint(hintResource);
        contentInput.setInputType(inputType);
        contentInput.setError(null);
    }

    @Nullable
    private Integer validateLinearBarcode(String contents, String format) {
        if (contents == null || contents.isEmpty()) return R.string.qr_tools_input_required;
        switch (format) {
            case "EAN_13":
                if (!contents.matches("[0-9]+")) return R.string.qr_tools_barcode_digits_only;
                if (contents.length() != 12 && contents.length() != 13)
                    return R.string.qr_tools_barcode_ean13_length;
                break;
            case "EAN_8":
                if (!contents.matches("[0-9]+")) return R.string.qr_tools_barcode_digits_only;
                if (contents.length() != 7 && contents.length() != 8)
                    return R.string.qr_tools_barcode_ean8_length;
                break;
            case "UPC_A":
                if (!contents.matches("[0-9]+")) return R.string.qr_tools_barcode_digits_only;
                if (contents.length() != 11 && contents.length() != 12)
                    return R.string.qr_tools_barcode_upca_length;
                break;
            case "UPC_E":
                if (!contents.matches("[0-9]+")) return R.string.qr_tools_barcode_digits_only;
                if (contents.length() != 7 && contents.length() != 8)
                    return R.string.qr_tools_barcode_upce_length;
                break;
            case "ITF":
                if (!contents.matches("[0-9]+")) return R.string.qr_tools_barcode_digits_only;
                if (contents.length() < 2 || (contents.length() & 1) != 0)
                    return R.string.qr_tools_barcode_itf_length;
                if (contents.length() > 80) return R.string.qr_tools_barcode_itf_max_length;
                break;
            case "CODE_39":
                // ZXing supports standard Code 39 plus its extended ASCII conversion mode.
                break;
            case "CODABAR":
                if (!isValidCodabar(contents)) return R.string.qr_tools_barcode_codabar_characters;
                break;
            case "CODE_128":
                if (contents.length() > 80) return R.string.qr_tools_barcode_code128_length;
                for (int i = 0; i < contents.length(); i++) {
                    if (contents.charAt(i) > 127 || contents.charAt(i) < 32)
                        return R.string.qr_tools_barcode_code128_ascii;
                }
                break;
            default:
                return null;
        }
        return null;
    }

    private boolean isValidCodabar(String contents) {
        if (contents.isEmpty()) return false;
        boolean hasStartGuard = isCodabarGuard(contents.charAt(0));
        boolean hasStopGuard = isCodabarGuard(contents.charAt(contents.length() - 1));
        if (hasStartGuard != hasStopGuard) return false;
        int start = hasStartGuard ? 1 : 0;
        int end = hasStopGuard ? contents.length() - 1 : contents.length();
        for (int i = start; i < end; i++) {
            char value = contents.charAt(i);
            if (!((value >= '0' && value <= '9') || "-$:/.+".indexOf(value) >= 0)) return false;
        }
        return start < end;
    }

    private boolean isCodabarGuard(char value) {
        return value >= 'A' && value <= 'D';
    }

    private void generateCode() {
        String[] types = getResources().getStringArray(R.array.qr_tools_content_type_values);
        int typePosition = Math.max(0, Math.min(contentTypeSpinner.getSelectedItemPosition(), types.length - 1));
        String type = types[typePosition];
        String contents;
        try {
            contents = QrPayloadBuilder.build(type, collectFields(type));
        } catch (IllegalArgumentException exception) {
            showInputError(type, exception);
            return;
        }

        String[] formatValues = getResources().getStringArray(R.array.qr_tools_format_values);
        int formatPosition = Math.max(0, Math.min(formatSpinner.getSelectedItemPosition(), formatValues.length - 1));
        String formatValue = formatValues[formatPosition];
        Integer validationError = validateLinearBarcode(contents, formatValue);
        if (validationError != null) {
            contentInput.setError(getString(validationError));
            contentInput.requestFocus();
            return;
        }
        try {
            BarcodeFormat format = BarcodeFormat.valueOf(formatValue);
            Bitmap bitmap = createCodeBitmap(contents, format);
            if (generatedBitmap != null && !generatedBitmap.isRecycled()) generatedBitmap.recycle();
            generatedContent = contents;
            generatedFormatValue = formatValue;
            generatedBitmap = bitmap;
            historyId = QrHistoryStore.add(this, contents, formatValue, type,
                    QrHistoryStore.SOURCE_GENERATED);
            favorite = false;
            generatedImage.setImageBitmap(bitmap);
            generatedContentValue.setText(contents);
            generatedCard.setVisibility(View.VISIBLE);
            updateFavoriteButton();
            contentScroll.post(() -> contentScroll.smoothScrollTo(0, generatedCard.getTop()));
        } catch (IllegalArgumentException exception) {
            if (contentTypeSpinner.getSelectedItemPosition() == 0
                    && !"QR_CODE".equals(formatValue) && !"DATA_MATRIX".equals(formatValue)
                    && !"AZTEC".equals(formatValue) && !"PDF_417".equals(formatValue)) {
                contentInput.setError(getString(R.string.qr_tools_barcode_value_invalid));
                contentInput.requestFocus();
            } else {
                Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_LONG).show();
            }
        } catch (WriterException | OutOfMemoryError exception) {
            Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_LONG).show();
        }
    }

    private Map<String, String> collectFields(String type) {
        Map<String, String> fields = new HashMap<>();
        if (QrPayloadBuilder.TYPE_TEXT.equals(type)) fields.put("text", text(R.id.qr_tools_content_input));
        else if (QrPayloadBuilder.TYPE_URL.equals(type)) fields.put("url", text(R.id.qr_field_url));
        else if (QrPayloadBuilder.TYPE_WIFI.equals(type)) {
            fields.put("ssid", text(R.id.qr_field_ssid));
            fields.put("password", text(R.id.qr_field_password));
            fields.put("hidden", Boolean.toString(((android.widget.CheckBox)
                    findViewById(R.id.qr_field_wifi_hidden)).isChecked()));
            String[] security = getResources().getStringArray(R.array.qr_tools_wifi_security_values);
            int index = Math.max(0, Math.min(((Spinner) findViewById(R.id.qr_field_wifi_security)).getSelectedItemPosition(), security.length - 1));
            fields.put("security", security[index]);
        } else if (QrPayloadBuilder.TYPE_PHONE.equals(type)) fields.put("phone", text(R.id.qr_field_phone));
        else if (QrPayloadBuilder.TYPE_CONTACT.equals(type)) {
            fields.put("name", text(R.id.qr_field_name));
            fields.put("phone", text(R.id.qr_field_contact_phone));
            fields.put("email", text(R.id.qr_field_contact_email));
            fields.put("company", text(R.id.qr_field_company));
            fields.put("address", text(R.id.qr_field_address));
        } else if (QrPayloadBuilder.TYPE_LOCATION.equals(type)) {
            fields.put("latitude", text(R.id.qr_field_latitude));
            fields.put("longitude", text(R.id.qr_field_longitude));
            fields.put("location_label", text(R.id.qr_field_location_label));
        } else if (QrPayloadBuilder.TYPE_SMS.equals(type)) {
            fields.put("sms_number", text(R.id.qr_field_sms_number));
            fields.put("sms_body", text(R.id.qr_field_sms_body));
        } else if (QrPayloadBuilder.TYPE_EMAIL.equals(type)) {
            fields.put("email_to", text(R.id.qr_field_email_to));
            fields.put("email_subject", text(R.id.qr_field_email_subject));
            fields.put("email_body", text(R.id.qr_field_email_body));
        } else if (QrPayloadBuilder.TYPE_EVENT.equals(type)) {
            fields.put("event_title", text(R.id.qr_field_event_title));
            fields.put("event_location", text(R.id.qr_field_event_location));
            fields.put("event_start", text(R.id.qr_field_event_start));
            fields.put("event_end", text(R.id.qr_field_event_end));
        } else if (QrPayloadBuilder.TYPE_BITCOIN.equals(type)) {
            fields.put("btc_address", text(R.id.qr_field_btc_address));
            fields.put("btc_amount", text(R.id.qr_field_btc_amount));
            fields.put("btc_label", text(R.id.qr_field_btc_label));
            fields.put("btc_message", text(R.id.qr_field_btc_message));
        }
        return fields;
    }

    private String text(int id) {
        EditText input = findViewById(id);
        return input.getText() == null ? "" : input.getText().toString();
    }

    private void showInputError(String type, IllegalArgumentException exception) {
        int id = R.id.qr_tools_content_input;
        switch (type) {
            case QrPayloadBuilder.TYPE_URL: id = R.id.qr_field_url; break;
            case QrPayloadBuilder.TYPE_WIFI: id = R.id.qr_field_ssid; break;
            case QrPayloadBuilder.TYPE_PHONE: id = R.id.qr_field_phone; break;
            case QrPayloadBuilder.TYPE_CONTACT: id = R.id.qr_field_name; break;
            case QrPayloadBuilder.TYPE_LOCATION: id = R.id.qr_field_latitude; break;
            case QrPayloadBuilder.TYPE_SMS: id = R.id.qr_field_sms_number; break;
            case QrPayloadBuilder.TYPE_EMAIL: id = R.id.qr_field_email_to; break;
            case QrPayloadBuilder.TYPE_EVENT: id = R.id.qr_field_event_title; break;
            case QrPayloadBuilder.TYPE_BITCOIN: id = R.id.qr_field_btc_address; break;
        }
        EditText input = findViewById(id);
        input.setError(getString(R.string.qr_tools_input_invalid));
        input.requestFocus();
    }

    private Bitmap createCodeBitmap(String contents, BarcodeFormat format) throws WriterException {
        int[] sizeValues = readIntArray(R.array.qr_tools_size_values);
        int size = sizeValues[Math.max(0, Math.min(sizeSpinner.getSelectedItemPosition(), sizeValues.length - 1))];
        if (format == BarcodeFormat.QR_CODE) {
            String[] levels = getResources().getStringArray(R.array.qr_tools_error_correction_values);
            int levelPosition = Math.max(0, Math.min(errorCorrectionSpinner.getSelectedItemPosition(), levels.length - 1));
            ErrorCorrectionLevel level = ErrorCorrectionLevel.valueOf(levels[levelPosition]);
            int[] margins = readIntArray(R.array.qr_tools_margin_values);
            int margin = margins[Math.max(0, Math.min(marginSpinner.getSelectedItemPosition(), margins.length - 1))];
            return QrCodeGenerator.generate(contents, size, level, margin);
        }

        int width;
        int height;
        if (format == BarcodeFormat.DATA_MATRIX || format == BarcodeFormat.AZTEC) {
            width = size;
            height = size;
        } else if (format == BarcodeFormat.PDF_417) {
            width = getResources().getInteger(R.integer.qr_tools_pdf417_width_px);
            height = getResources().getInteger(R.integer.qr_tools_pdf417_height_px);
        } else {
            width = getResources().getInteger(R.integer.qr_tools_linear_code_width_px);
            height = getResources().getInteger(R.integer.qr_tools_linear_code_height_px);
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        BitMatrix matrix = new MultiFormatWriter().encode(contents, format, width, height, hints);
        int matrixWidth = matrix.getWidth();
        int matrixHeight = matrix.getHeight();
        int[] pixels = new int[matrixWidth * matrixHeight];
        for (int y = 0; y < matrixHeight; y++) {
            int rowOffset = y * matrixWidth;
            for (int x = 0; x < matrixWidth; x++) {
                pixels[rowOffset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, 0, matrixWidth, matrixWidth,
                matrixHeight, Bitmap.Config.ARGB_8888);
    }

    private int[] readIntArray(int resource) {
        String[] values = getResources().getStringArray(resource);
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Integer.parseInt(values[i]);
        return result;
    }

    private void generateBatch() {
        if (contentTypeSpinner.getSelectedItemPosition() != 0) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingBatchAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_IMAGE_PERMISSION);
            return;
        }
        runBatchGeneration();
    }

    private void runBatchGeneration() {
        String raw = text(R.id.qr_tools_content_input);
        String[] lines = raw.split("\\r?\\n");
        int saved = 0;
        String[] formats = getResources().getStringArray(R.array.qr_tools_format_values);
        int selected = Math.max(0, Math.min(formatSpinner.getSelectedItemPosition(), formats.length - 1));
        try {
            BarcodeFormat format = BarcodeFormat.valueOf(formats[selected]);
            for (String line : lines) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                Bitmap bitmap = null;
                try {
                    bitmap = createCodeBitmap(value, format);
                    if (saveBitmapToPictures(bitmap, createImageFileName(formats[selected]))) {
                        saved++;
                        QrHistoryStore.add(this, value, formats[selected], QrPayloadBuilder.TYPE_TEXT,
                                QrHistoryStore.SOURCE_GENERATED);
                    }
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
                if (saved >= 100) break;
            }
        } catch (WriterException | IllegalArgumentException | OutOfMemoryError exception) {
            Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (saved == 0) Toast.makeText(this, R.string.qr_tools_batch_empty, Toast.LENGTH_LONG).show();
        else Toast.makeText(this, getString(R.string.qr_tools_batch_saved, saved), Toast.LENGTH_LONG).show();
    }

    private void saveGeneratedImage() {
        if (generatedBitmap == null || generatedBitmap.isRecycled()) {
            Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (saveBitmapToPictures(generatedBitmap, createImageFileName(generatedFormatValue)))
                Toast.makeText(this, R.string.qr_tools_image_saved, Toast.LENGTH_LONG).show();
            else Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_IMAGE_PERMISSION);
            return;
        }
        if (saveBitmapToPictures(generatedBitmap, createImageFileName(generatedFormatValue)))
            Toast.makeText(this, R.string.qr_tools_image_saved, Toast.LENGTH_LONG).show();
        else Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
    }

    private boolean saveBitmapToPictures(Bitmap bitmap, String fileName) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri imageUri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QR Codes");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (imageUri == null) throw new IOException("MediaStore insert failed");
                try (OutputStream output = getContentResolver().openOutputStream(imageUri)) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        throw new IOException("Image write failed");
                    output.flush();
                }
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(imageUri, publish, null, null) <= 0)
                    throw new IOException("Image publish failed");
                return true;
            } catch (IOException | RuntimeException exception) {
                if (imageUri != null) {
                    try { getContentResolver().delete(imageUri, null, null); }
                    catch (RuntimeException ignored) { }
                }
                return false;
            }
        }

        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File directory = new File(pictures, "QR Codes");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) return false;
        File image = new File(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(image)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Image write failed");
            output.flush();
            MediaScannerConnection.scanFile(this, new String[]{image.getAbsolutePath()}, new String[]{"image/png"}, null);
            return true;
        } catch (IOException | RuntimeException exception) {
            if (image.exists()) image.delete();
            return false;
        }
    }

    private String createImageFileName(String format) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return (TextUtils.isEmpty(format) ? "QR_CODE" : format) + "_" + timestamp + ".png";
    }

    private void shareGeneratedContent() {
        if (TextUtils.isEmpty(generatedContent)) return;
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, generatedContent);
        startActivity(Intent.createChooser(intent, getString(R.string.qr_tools_share_title)));
    }

    private void shareGeneratedImage() {
        if (generatedBitmap == null || generatedBitmap.isRecycled()) return;
        File directory = new File(getCacheDir(), "qr_share");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        File image = new File(directory, createImageFileName(generatedFormatValue));
        try (FileOutputStream output = new FileOutputStream(image)) {
            if (!generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Image write failed");
            output.flush();
            Uri imageUri = FileProvider.getUriForFile(this, getPackageName() + ".qrfileprovider", image);
            Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, imageUri);
            intent.setClipData(ClipData.newUri(getContentResolver(), getString(R.string.qr_tools_code_content_description), imageUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.qr_tools_share_title)));
        } catch (IOException | RuntimeException exception) {
            Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
        }
    }


    private void printGeneratedImage() {
        if (generatedBitmap == null || generatedBitmap.isRecycled()) {
            Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(this, R.string.qr_tools_print_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap printBitmap = generatedBitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (printBitmap == null) {
            Toast.makeText(this, R.string.qr_tools_print_failed, Toast.LENGTH_LONG).show();
            return;
        }
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("qr", "QR", 300, 300))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            private PrintedPdfDocument document;

            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                 CancellationSignal cancellationSignal,
                                 LayoutResultCallback callback, Bundle extras) {
                if (cancellationSignal.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                if (document != null) document.close();
                document = new PrintedPdfDocument(QrGenerateActivity.this, newAttributes);
                PrintDocumentInfo info = new PrintDocumentInfo.Builder("qr_code.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build();
                callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                CancellationSignal cancellationSignal, WriteResultCallback callback) {
                try {
                    if (cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    if (document == null) throw new IOException("Print layout is unavailable");
                    PdfDocument.Page page = document.startPage(1);
                    Rect bounds = page.getCanvas().getClipBounds();
                    RectF target = new RectF(bounds);
                    target.inset(54, 54);
                    float scale = Math.min(target.width() / printBitmap.getWidth(),
                            target.height() / printBitmap.getHeight());
                    float width = printBitmap.getWidth() * scale;
                    float height = printBitmap.getHeight() * scale;
                    float left = target.centerX() - width / 2f;
                    float top = target.centerY() - height / 2f;
                    page.getCanvas().drawBitmap(printBitmap, null,
                            new RectF(left, top, left + width, top + height),
                            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                    document.finishPage(page);
                    try (FileOutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
                        document.writeTo(output);
                        output.flush();
                    }
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (IOException | RuntimeException exception) {
                    callback.onWriteFailed(getString(R.string.qr_tools_print_failed));
                } finally {
                    if (document != null) {
                        document.close();
                        document = null;
                    }
                }
            }

            @Override
            public void onFinish() {
                if (document != null) {
                    document.close();
                    document = null;
                }
                if (!printBitmap.isRecycled()) printBitmap.recycle();
                super.onFinish();
            }
        };
        printManager.print(getString(R.string.qr_tools_print_job_title), adapter, attributes);
    }

    private void copyGeneratedContent() {
        if (TextUtils.isEmpty(generatedContent)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_tools_clip_label), generatedContent));
            Toast.makeText(this, R.string.qr_tools_generated_content_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFavorite() {
        if (TextUtils.isEmpty(historyId)) return;
        QrHistoryStore.toggleFavorite(this, historyId);
        favorite = !favorite;
        updateFavoriteButton();
        Toast.makeText(this, favorite ? R.string.qr_tools_favorite_added : R.string.qr_tools_favorite_removed,
                Toast.LENGTH_SHORT).show();
    }

    private void updateFavoriteButton() {
        ((TextView) findViewById(R.id.qr_tools_generated_favorite)).setText(
                favorite ? R.string.qr_tools_remove_favorite : R.string.qr_tools_add_favorite);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_IMAGE_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, R.string.qr_tools_image_permission_denied, Toast.LENGTH_LONG).show();
            pendingBatchAfterPermission = false;
            return;
        }
        if (pendingBatchAfterPermission) {
            pendingBatchAfterPermission = false;
            runBatchGeneration();
        } else if (generatedBitmap != null && !generatedBitmap.isRecycled()) {
            if (saveBitmapToPictures(generatedBitmap, createImageFileName(generatedFormatValue)))
                Toast.makeText(this, R.string.qr_tools_image_saved, Toast.LENGTH_LONG).show();
            else Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (generatedBitmap != null && !generatedBitmap.isRecycled()) generatedBitmap.recycle();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final class SimpleSelectionListener implements AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer callback;
        SimpleSelectionListener(java.util.function.IntConsumer callback) { this.callback = callback; }
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { callback.accept(position); }
        @Override public void onNothingSelected(AdapterView<?> parent) { }
    }
}
