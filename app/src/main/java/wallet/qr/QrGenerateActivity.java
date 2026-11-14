package wallet.qr;

import android.Manifest;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Generates QR codes and supported one- and two-dimensional barcodes. */
public class QrGenerateActivity extends BaseActivity {
    private ScrollView contentScroll;
    private EditText contentInput;
    private Spinner formatSpinner;
    private ImageView generatedImage;
    private TextView generatedContentValue;
    private android.view.View generatedCard;
    private String generatedContent = "";
    private Bitmap generatedBitmap;
    private String generatedFormatValue = "QR_CODE";

    private static final int REQUEST_WRITE_IMAGE_PERMISSION = 7301;

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

        contentScroll = findViewById(R.id.qr_generate_scroll);
        contentInput = findViewById(R.id.qr_tools_content_input);
        formatSpinner = findViewById(R.id.qr_tools_format_spinner);
        generatedCard = findViewById(R.id.qr_tools_generated_card);
        generatedImage = findViewById(R.id.qr_tools_generated_image);
        generatedContentValue = findViewById(R.id.qr_tools_generated_content_value);

        ArrayAdapter<CharSequence> formatAdapter = ArrayAdapter.createFromResource(
                this, R.array.qr_tools_format_names, android.R.layout.simple_spinner_item);
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(formatAdapter);

        findViewById(R.id.qr_tools_generate).setOnClickListener(v -> generateCode());
        findViewById(R.id.qr_tools_generated_copy).setOnClickListener(v -> copyGeneratedContent());
        findViewById(R.id.qr_tools_generated_share).setOnClickListener(v -> shareGeneratedContent());
        findViewById(R.id.qr_tools_generated_save).setOnClickListener(v -> saveGeneratedImage());
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
            generatedFormatValue = formatValues[selectedPosition];
            generatedBitmap = bitmap;
            generatedImage.setImageBitmap(bitmap);
            generatedContentValue.setText(contents);
            generatedCard.setVisibility(android.view.View.VISIBLE);
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

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        BitMatrix matrix = new MultiFormatWriter().encode(
                contents, format, width, height, hints);
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


    private void saveGeneratedImage() {
        if (generatedBitmap == null || generatedBitmap.isRecycled()) {
            Toast.makeText(this, R.string.qr_tools_generation_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveGeneratedImageToMediaStore();
            return;
        }

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_IMAGE_PERMISSION);
            return;
        }
        saveGeneratedImageToLegacyPictures();
    }

    private String createImageFileName() {
        String formatName = TextUtils.isEmpty(generatedFormatValue)
                ? "QR_CODE" : generatedFormatValue;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date());
        return formatName + "_" + timestamp + ".png";
    }

    private void saveGeneratedImageToMediaStore() {
        Uri imageUri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, createImageFileName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/QR Codes");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            imageUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (imageUri == null) {
                throw new IOException("Unable to create image entry");
            }

            try (OutputStream outputStream = getContentResolver().openOutputStream(imageUri)) {
                if (outputStream == null
                        || !generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    throw new IOException("Unable to write image data");
                }
                outputStream.flush();
            }

            ContentValues publishValues = new ContentValues();
            publishValues.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (getContentResolver().update(imageUri, publishValues, null, null) <= 0) {
                throw new IOException("Unable to publish saved image");
            }
            Toast.makeText(this, R.string.qr_tools_image_saved, Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            if (imageUri != null) {
                try {
                    getContentResolver().delete(imageUri, null, null);
                } catch (RuntimeException ignored) {
                    // Keep the original save failure as the reported result.
                }
            }
            Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void saveGeneratedImageToLegacyPictures() {
        File picturesDirectory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File qrDirectory = new File(picturesDirectory, "QR Codes");
        if ((!qrDirectory.exists() && !qrDirectory.mkdirs()) || !qrDirectory.isDirectory()) {
            Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
            return;
        }

        File imageFile = new File(qrDirectory, createImageFileName());
        try (FileOutputStream outputStream = new FileOutputStream(imageFile)) {
            if (!generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw new IOException("Unable to write image data");
            }
            outputStream.flush();
            MediaScannerConnection.scanFile(this, new String[]{imageFile.getAbsolutePath()},
                    new String[]{"image/png"}, null);
            Toast.makeText(this, R.string.qr_tools_image_saved, Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            if (imageFile.exists() && !imageFile.delete()) {
                // Leave cleanup to the user if the file system rejects deletion.
            }
            Toast.makeText(this, R.string.qr_tools_image_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_IMAGE_PERMISSION) return;

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveGeneratedImageToLegacyPictures();
        } else {
            Toast.makeText(this, R.string.qr_tools_image_permission_denied,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyGeneratedContent() {
        if (TextUtils.isEmpty(generatedContent)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.qr_tools_clip_label), generatedContent));
            Toast.makeText(this, R.string.qr_tools_generated_content_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareGeneratedContent() {
        if (TextUtils.isEmpty(generatedContent)) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, generatedContent);
        startActivity(Intent.createChooser(intent, getString(R.string.qr_tools_share_title)));
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
