package wallet.qr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.android.Intents;
import com.google.zxing.FormatException;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
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

/** Scans QR codes and supported one- and two-dimensional barcodes. */
public class QrScanActivity extends BaseActivity {
    private final ExecutorService imageDecoder = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<ScanOptions> cameraScanner =
            registerForActivityResult(new ScanContract(), this::handleCameraScanResult);
    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) decodeSelectedImage(uri);
            });

    private ScrollView contentScroll;
    private TextView scanStatus;
    private View scanResultCard;
    private TextView scanFormatValue;
    private TextView scanTypeValue;
    private TextView scanContentValue;
    private android.widget.Button openActionButton;
    private String scannedContent = "";
    private String historyId = "";
    private boolean favorite;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        Toolbar toolbar = findViewById(R.id.toolbar_qr_scan);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.qr_tools_scan_title);
        }

        contentScroll = findViewById(R.id.qr_scan_scroll);
        scanStatus = findViewById(R.id.qr_tools_scan_status);
        scanResultCard = findViewById(R.id.qr_tools_scan_result_card);
        scanFormatValue = findViewById(R.id.qr_tools_scan_format_value);
        scanTypeValue = findViewById(R.id.qr_tools_scan_type_value);
        scanContentValue = findViewById(R.id.qr_tools_scan_content_value);
        openActionButton = findViewById(R.id.qr_tools_scan_open);

        findViewById(R.id.qr_tools_scan_camera).setOnClickListener(v -> startCameraScan());
        findViewById(R.id.qr_tools_scan_image).setOnClickListener(v -> imagePicker.launch("image/*"));
        findViewById(R.id.qr_tools_scan_copy).setOnClickListener(v -> copyScannedContent());
        findViewById(R.id.qr_tools_scan_share).setOnClickListener(v -> shareScannedContent());
        findViewById(R.id.qr_tools_scan_favorite).setOnClickListener(v -> toggleFavorite());
        openActionButton.setOnClickListener(v -> confirmAndOpenCompatibleApp());
    }

    private void handleCameraScanResult(com.journeyapps.barcodescanner.ScanIntentResult result) {
        if (result == null || result.getContents() == null) {
            scanStatus.setText(R.string.qr_tools_scan_cancelled);
            return;
        }
        showScannedContent(result.getContents(), result.getFormatName());
    }

    private void startCameraScan() {
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                .setPrompt(getString(R.string.qr_tools_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .addExtra(Intents.Scan.CHARACTER_SET, "UTF-8");
        cameraScanner.launch(options);
    }

    private void showScannedContent(String contents, @Nullable String formatName) {
        scannedContent = contents == null ? "" : contents;
        scanFormatValue.setText(TextUtils.isEmpty(formatName)
                ? getString(R.string.qr_tools_unknown_format) : formatName);
        scanContentValue.setText(scannedContent);
        QrContentClassifier.Detection detection = QrContentClassifier.detect(scannedContent);
        scanTypeValue.setText(detection.typeLabel);
        openActionButton.setVisibility(detection.actionable ? View.VISIBLE : View.GONE);
        if (detection.actionable) openActionButton.setText(detection.actionLabel);
        scanResultCard.setVisibility(View.VISIBLE);
        scanStatus.setText(R.string.qr_tools_scan_success);
        findViewById(R.id.qr_tools_scan_copy).setEnabled(!scannedContent.isEmpty());
        findViewById(R.id.qr_tools_scan_share).setEnabled(!scannedContent.isEmpty());
        historyId = QrHistoryStore.add(this, scannedContent,
                TextUtils.isEmpty(formatName) ? "" : formatName, detection.type,
                QrHistoryStore.SOURCE_SCANNED);
        favorite = false;
        updateFavoriteButton();
        contentScroll.post(() -> contentScroll.smoothScrollTo(0, scanResultCard.getTop()));
    }


    private void confirmAndOpenCompatibleApp() {
        if (TextUtils.isEmpty(scannedContent)) return;
        QrContentClassifier.Detection detection = QrContentClassifier.detect(scannedContent);
        if (!detection.actionable) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.qr_tools_launch_confirm_title)
                .setMessage(R.string.qr_tools_launch_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.qr_tools_open_confirm, (dialog, which) -> {
                    Intent intent = QrContentClassifier.createIntent(this, scannedContent);
                    if (intent == null) {
                        Toast.makeText(this, R.string.qr_tools_launch_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        startActivity(intent);
                    } catch (android.content.ActivityNotFoundException | SecurityException exception) {
                        Toast.makeText(this, R.string.qr_tools_launch_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void toggleFavorite() {
        if (TextUtils.isEmpty(historyId)) return;
        QrHistoryStore.toggleFavorite(this, historyId);
        favorite = !favorite;
        updateFavoriteButton();
        Toast.makeText(this, favorite ? R.string.qr_tools_favorite_added
                : R.string.qr_tools_favorite_removed, Toast.LENGTH_SHORT).show();
    }

    private void updateFavoriteButton() {
        ((TextView) findViewById(R.id.qr_tools_scan_favorite)).setText(
                favorite ? R.string.qr_tools_remove_favorite : R.string.qr_tools_add_favorite);
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

        MultiFormatReader reader = new MultiFormatReader();
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.allOf(BarcodeFormat.class));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
        } finally {
            reader.reset();
            if (!bitmap.isRecycled()) bitmap.recycle();
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

    private void copyScannedContent() {
        if (TextUtils.isEmpty(scannedContent)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.qr_tools_clip_label), scannedContent));
            Toast.makeText(this, R.string.qr_tools_scan_content_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareScannedContent() {
        if (TextUtils.isEmpty(scannedContent)) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, scannedContent);
        startActivity(Intent.createChooser(intent, getString(R.string.qr_tools_share_title)));
    }

    @Override
    protected void onDestroy() {
        imageDecoder.shutdownNow();
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
