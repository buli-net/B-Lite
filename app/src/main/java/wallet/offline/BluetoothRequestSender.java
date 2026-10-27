package wallet.offline;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends the NFC-compatible protobuf PaymentRequest directly to another paired wallet app over RFCOMM. */
public final class BluetoothRequestSender {
    public interface Callback { void onResult(boolean sent, String error); }
    private static final int MAX_REQUEST_BYTES = wallet.nfc.Nfc.HCE_MAX_PAYLOAD;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wallet-bt-request-send"); t.setDaemon(true); return t;
    });
    private BluetoothRequestSender() { }

    public static void send(BluetoothAdapter adapter, BluetoothDevice device,
                            byte[] paymentRequest, Callback callback) {
        EXECUTOR.execute(() -> {
            boolean sent = false;
            String error = null;
            try {
                if (adapter == null || device == null || paymentRequest == null
                        || paymentRequest.length == 0 || paymentRequest.length > MAX_REQUEST_BYTES) {
                    throw new IOException("invalid_payment_request");
                }
                // Avoid a caller changing the payload while the background transfer is in progress.
                byte[] payload = Arrays.copyOf(paymentRequest, paymentRequest.length);
                try (BluetoothSocket socket = device.createInsecureRfcommSocketToServiceRecord(
                        BluetoothSupport.DIRECT_REQUEST_UUID)) {
                    socket.connect();
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    output.writeUTF(BluetoothSupport.DIRECT_PAYMENT_REQUEST_MAGIC);
                    output.writeInt(payload.length);
                    output.write(payload);
                    output.flush();
                    // Success means the receiving wallet app accepted the payload and dispatched
                    // it into the same SendActivity PaymentRequest parser used by NFC.
                    sent = input.readBoolean();
                    if (!sent) error = "request_rejected";
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            if (callback != null) {
                final boolean result = sent;
                final String message = error;
                callback.onResult(result, message);
            }
        });
    }

}
