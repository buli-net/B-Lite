package wallet.qr;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Creates QR bitmaps from wallet data. */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    public static Bitmap generate(String data, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    data,
                    BarcodeFormat.QR_CODE,
                    size,
                    size
            );

            Bitmap bitmap = Bitmap.createBitmap(
                    size,
                    size,
                    Bitmap.Config.ARGB_8888
            );

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    bitmap.setPixel(
                            x,
                            y,
                            matrix.get(x, y)
                                    ? 0xFF000000
                                    : 0xFFFFFFFF
                    );
                }
            }

            return bitmap;
        } catch (WriterException e) {
            throw new IllegalArgumentException(
                    "Unable to generate QR code",
                    e
            );
        }
    }
}
