package wallet.offline;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protobuf.payments.Protos;

import wallet.main.MainActivityPresenter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends the signed transaction in a BIP70 Payment message and validates the merchant's ACK. */
public final class BluetoothPaymentSender {
    public interface Callback {
        void onResult(boolean acknowledged, String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "bitcoin-bluetooth-payment");
        thread.setDaemon(true);
        return thread;
    });

    private BluetoothPaymentSender() { }

    public static void send(BluetoothAdapter adapter, String compactMac, Transaction transaction,
                            Coin amount, Callback callback) {
        EXECUTOR.execute(() -> {
            String error = null;
            boolean acknowledged = false;
            try {
                String formatted = BluetoothSupport.formatMac(compactMac);
                if (adapter == null || formatted == null || transaction == null || amount == null) {
                    throw new IOException("Bluetooth payment information is incomplete.");
                }
                BluetoothDevice device = adapter.getRemoteDevice(formatted);
                try (BluetoothSocket socket = device.createInsecureRfcommSocketToServiceRecord(
                        BluetoothSupport.BIP70_PAYMENT_PROTOCOL_UUID)) {
                    socket.connect();
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    Address refundAddress = null;
                    try {
                        Wallet activeWallet = MainActivityPresenter.getActiveWallet();
                        NetworkParameters activeParameters = MainActivityPresenter.getActiveParameters();
                        if (activeWallet != null && activeParameters != null) {
                            Context.propagate(Context.getOrCreate(activeParameters));
                            refundAddress = activeWallet.freshAddress(KeyChain.KeyPurpose.REFUND);
                        }
                    } catch (Exception ignored) {
                        // Refund address is optional; do not block an otherwise valid payment.
                    }
                    Protos.Payment payment = PaymentProtocol.createPaymentMessage(
                            Collections.singletonList(transaction), amount, refundAddress, null, null);
                    payment.writeDelimitedTo(output);
                    output.flush();
                    Protos.PaymentACK ack = Protos.PaymentACK.parseDelimitedFrom(input);
                    acknowledged = ack != null && "ack".equals(PaymentProtocol.parsePaymentAck(ack).getMemo());
                    if (!acknowledged) error = "The receiving wallet did not acknowledge the payment.";
                }
            } catch (Exception exception) {
                error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            }
            if (callback != null) callback.onResult(acknowledged, error);
        });
    }
}
