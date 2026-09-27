package wallet.qr;

import android.graphics.Bitmap;

import net.glxn.qrgen.android.QRCode;

/** Creates QR bitmaps from wallet data. */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    public static Bitmap generate(String data, int size) {
        return QRCode.from(data)
                .withSize(size, size)
                .bitmap();
    }
}
