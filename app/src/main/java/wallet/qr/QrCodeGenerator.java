package wallet.qr;

import wallet.ui.LocalizedErrors;
import wallet.main.R;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Creates QR bitmaps from wallet data. */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    public static Bitmap generate(String data, int size) {
        return generate(data, size, ErrorCorrectionLevel.L, 4);
    }

    public static Bitmap generate(String data, int size, ErrorCorrectionLevel correction, int margin) {
        if (data == null || data.length() == 0) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_qr_data_empty);
        }
        if (size <= 0) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_qr_size_invalid);
        }

        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            hints.put(EncodeHintType.ERROR_CORRECTION,
                    correction == null ? ErrorCorrectionLevel.L : correction);
            hints.put(EncodeHintType.MARGIN, Math.max(0, margin));

            BitMatrix matrix = new QRCodeWriter().encode(
                    data,
                    BarcodeFormat.QR_CODE,
                    size,
                    size,
                    hints
            );

            return new BarcodeEncoder().createBitmap(matrix);
        } catch (WriterException e) {
            throw new LocalizedErrors.IllegalArgumentException(
                    R.string.error_qr_generation_failed, e
            );
        }
    }
}
