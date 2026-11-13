package wallet.qr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.RGBLuminanceSource;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Scans and generates QR codes and common one- and two-dimensional barcodes. */
public class QrToolsActivity extends BaseActivity {
    public static final String EXTRA_OPEN_GENERATOR = "wallet.qr.QrToolsActivity.OPEN_GENERATOR";

    private final ExecutorService imageDecoder = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<ScanOptions> cameraScanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result == null || result.getContents() == null) {
                    scanStatus.setText(R.string.qr_tools_scan_cancelled);
                    return;
                }
                showScannedContent(result.getContents(), result.getFormatName());
            });
    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) decodeSelectedImage(uri);
            });

    private ScrollView contentScroll;
    private TextView scanStatus;
    private View scanResultCard;
    private TextView scanFormatValue;
    private TextView scanContentValue;
    private EditText contentInput;
    private Spinner formatSpinner;
    private View generatedCard;
    private ImageView generatedImage;
    private TextView generatedContentValue;
    private String scannedContent = "";
    private String generatedContent = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_tools);

        Toolbar toolbar = findViewById(R.id.toolbar_qr_tools);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.qr_tools_title);
        }

        contentScroll = findViewById(R.id.qr_tools_scroll);
        scanStatus = findViewById(R.id.qr_tools_scan_status);
        scanResultCard = findViewById(R.id.qr_tools_scan_result_card);
        scanFormatValue = findViewById(R.id.qr_tools_scan_format_value);
        scanContentValue = findViewById(R.id.qr_tools_scan_content_value);
        contentInput = findViewById(R.id.qr_tools_content_input);
        formatSpinner = findViewById(R.id.qr_tools_format_spinner);
        generatedCard = findViewById(R.id.qr_tools_generated_card);
        generatedImage = findViewById(R.id.qr_tools_generated_image);
        generatedContentValue = findViewById(R.id.qr_tools_generated_content_value);

        ArrayAdapter<CharSequence> formatAdapter = ArrayAdapter.createFromResource(
                this, R.array.qr_tools_format_names, android.R.layout.simple_spinner_item);
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(formatAdapter);

        findViewById(R.id.qr_tools_scan_camera).setOnClickListener(v -> startCameraScan());
        findViewById(R.id.qr_tools_scan_image).setOnClickListener(v -> imagePicker.launch("image/*"));
        findViewById(R.id.qr_tools_scan_copy).setOnClickListener(v -> copyText(
                scannedContent, R.string.qr_tools_scan_content_copied));
        findViewById(R.id.qr_tools_scan_share).setOnClickListener(v -> shareText(scannedContent));
        findViewById(R.id.qr_tools_generate).setOnClickListener(v -> generateCode());
        findViewById(R.id.qr_tools_generated_copy).setOnClickListener(v -> copyText(
                generatedContent, R.string.qr_tools_generated_content_copied));
        findViewById(R.id.qr_tools_generated_share).setOnClickListener(v -> shareText(generatedContent));

        if (getIntent().getBooleanExtra(EXTRA_OPEN_GENERATOR, false)) {
            View generatorCard = findViewById(R.id.qr_tools_generate_card);
            contentScroll.post(() -> contentScroll.smoothScrollTo(0, generatorCard.getTop()));
        }
    }

    private void startCameraScan() {
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                .setPrompt(getString(R.string.qr_tools_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true);
        cameraScanner.launch(options);
    }

    private void showScannedContent(String contents, @Nullable String formatName) {
        scannedContent = contents == null ? "" : contents;
        scanFormatValue.setText(TextUtils.isEmpty(formatName)
                ? getString(R.string.qr_tools_unknown_format) : formatName);
        scanContentValue.setText(scannedContent);
        scanResultCard.setVisibility(View.VISIBLE);
        scanStatus.setText(R.string.qr_tools_scan_success);
        findViewById(R.id.qr_tools_scan_copy).setEnabled(!scannedContent.isEmpty());
        findViewById(R.id.qr_tools_scan_share).setEnabled(!scannedContent.isEmpty());
        contentScroll.post(() -> contentScroll.smoothScrollTo(0, scanResultCard.getTop()));
    }

    private void decodeSelectedImage(Uri imageUri) {
        scanStatus.setText(R.string.qr_tools_image_scan_in_progress);
        imageDecoder.execute(() -> {
            try {
                Result result = decodeImage(imageUri);
                postImageResult(() -> showScannedContent(
                        result.getText(), result.getBarcodeFormat().toString()));
            } catch (NotFoundException e) {
                postImageResult(() -> scanStatus.setText(R.string.qr_tools_no_code_found));
            } catch (IOException | ChecksumException | FormatException | RuntimeException e) {
                postImageResult(() -> scanStatus.setText(R.string.qr_tools_scan_image_failed));
            }
        });
    }


    private void postImageResult(Runnable update) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) update.run();
        });
    }

    private Result decodeImage(Uri imageUri)
            throws IOException, NotFoundException, ChecksumException, FormatException {
        Bitmap bitmap = decodeSampledBitmap(imageUri);
        if (bitmap == null) throw new IOException();

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.allOf(BarcodeFormat.class));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        MultiFormatReader reader = new MultiFormatReader();
        try {
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
        } finally {
            reader.reset();
            bitmap.recycle();
        }
    }

    private Bitmap decodeSampledBitmap(Uri imageUri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(imageUri)) {
            if (stream == null) throw new IOException();
            BitmapFactory.decodeStream(stream, null, bounds);
        }

        int sampleSize = 1;
        int largestDimension = Math.max(bounds.outWidth, bounds.outHeight);
        int maxImageDimension = getResources().getInteger(R.integer.qr_tools_max_image_dimension_px);
        while (largestDimension / sampleSize > maxImageDimension) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream stream = getContentResolver().openInputStream(imageUri)) {
            if (stream == null) throw new IOException();
            return BitmapFactory.decodeStream(stream, null, options);
        }
    }

    private void generateCode() {
        String contents = contentInput.getText() == null
                ? "" : contentInput.getText().toString();
        if (contents.trim().isEmpty()) {
            contentInput.setError(getString(R.string.qr_tools_input_required));
            contentInput.requestFocus();
            return;
        }
        contentInput.setError(null);

        try {
            String[] formatValues = getResources().getStringArray(R.array.qr_tools_format_values);
            int selectedPosition = formatSpinner.getSelectedItemPosition();
            if (selectedPosition < 0 || selectedPosition >= formatValues.length) {
                selectedPosition = 0;
            }
            BarcodeFormat format = BarcodeFormat.valueOf(formatValues[selectedPosition]);
            Bitmap bitmap = createCodeBitmap(contents, format);

            generatedContent = contents;
            generatedImage.setImageBitmap(bitmap);
            generatedContentValue.setText(contents);
            generatedCard.setVisibility(View.VISIBLE);
            contentScroll.post(() -> contentScroll.smoothScrollTo(0, generatedCard.getTop()));
        } catch (WriterException | IllegalArgumentException | OutOfMemoryError e) {
            Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap createCodeBitmap(String contents, BarcodeFormat format) throws WriterException {
        if (format == BarcodeFormat.QR_CODE) {
            return QrCodeGenerator.generate(contents,
                    getResources().getInteger(R.integer.qr_tools_square_code_size_px));
        }

        int width;
        int height;
        if (format == BarcodeFormat.DATA_MATRIX || format == BarcodeFormat.AZTEC) {
            width = getResources().getInteger(R.integer.qr_tools_square_code_size_px);
            height = width;
        } else if (format == BarcodeFormat.PDF_417) {
            width = getResources().getInteger(R.integer.qr_tools_pdf417_width_px);
            height = getResources().getInteger(R.integer.qr_tools_pdf417_height_px);
        } else {
            width = getResources().getInteger(R.integer.qr_tools_linear_code_width_px);
            height = getResources().getInteger(R.integer.qr_tools_linear_code_height_px);
        }

        BitMatrix matrix = new MultiFormatWriter().encode(contents, format, width, height);
        int matrixWidth = matrix.getWidth();
        int matrixHeight = matrix.getHeight();
        int[] pixels = new int[matrixWidth * matrixHeight];
        for (int y = 0; y < matrixHeight; y++) {
            int rowOffset = y * matrixWidth;
            for (int x = 0; x < matrixWidth; x++) {
                pixels[rowOffset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, 0, matrixWidth,
                matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888);
    }

    private void copyText(String text, int copiedMessage) {
        if (TextUtils.isEmpty(text)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_tools_clip_label), text));
            Toast.makeText(this, copiedMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String text) {
        if (TextUtils.isEmpty(text)) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, getString(R.string.qr_tools_share_title)));
    }

    @Override
    protected void onDestroy() {
        imageDecoder.shutdown();
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
}
