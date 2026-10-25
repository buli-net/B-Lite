package wallet.offline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protobuf.payments.Protos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Receives signed Bitcoin transactions from nearby payers over Bluetooth RFCOMM payment protocols. */
public final class AcceptBluetoothService extends Service {
    private static final String CHANNEL_ID = "bluetooth_payment_receiver";
    private static final int NOTIFICATION_ID = 2307;
    private static final int MAX_CLASSIC_MESSAGES = 20;
    private static final int MAX_TRANSACTION_BYTES = 4 * 1024 * 1024;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<BluetoothServerSocket> sockets = Collections.synchronizedList(new ArrayList<>());
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
    private PowerManager.WakeLock wakeLock;
    private boolean bluetoothReceiverRegistered;
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    BluetoothSupport.setAcceptingPayments(AcceptBluetoothService.this, false);
                    sendReceiverStoppedBroadcast();
                    stopSelf();
                }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        try {
            PowerManager powerManager = getSystemService(PowerManager.class);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":BluetoothPaymentReceiver");
                wakeLock.acquire();
            }
        } catch (RuntimeException ignored) { }
        try {
            ContextCompat.registerReceiver(this, bluetoothStateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
            bluetoothReceiverRegistered = true;
        } catch (RuntimeException ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "wallet.action.STOP_BLUETOOTH_PAYMENTS".equals(intent.getAction())) {
            BluetoothSupport.setAcceptingPayments(this, false);
            sendReceiverStoppedBroadcast();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) startListeners();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Intent stop = new Intent(this, AcceptBluetoothService.class).setAction("wallet.action.STOP_BLUETOOTH_PAYMENTS");
        android.app.PendingIntent stopIntent = android.app.PendingIntent.getService(this, 2308, stop,
                Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bitcoin_notification)
                .setContentTitle(getString(R.string.bluetooth_receiver_notification_title))
                .setContentText(getString(R.string.bluetooth_receiver_notification_text))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.bluetooth_receiver_stop), stopIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        getString(R.string.bluetooth_receiver_channel), NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private void startListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            stopSelf();
            return;
        }
        try {
            addListener(adapter.listenUsingInsecureRfcommWithServiceRecord(
                    BluetoothSupport.CLASSIC_PAYMENT_PROTOCOL_NAME, BluetoothSupport.CLASSIC_PAYMENT_PROTOCOL_UUID), false);
        } catch (IOException | SecurityException ignored) { }
        try {
            addListener(adapter.listenUsingInsecureRfcommWithServiceRecord(
                    BluetoothSupport.BIP70_PAYMENT_PROTOCOL_NAME, BluetoothSupport.BIP70_PAYMENT_PROTOCOL_UUID), true);
        } catch (IOException | SecurityException ignored) { }
        if (sockets.isEmpty()) stopSelf();
    }

    private void addListener(BluetoothServerSocket server, boolean paymentProtocol) {
        sockets.add(server);
        Thread worker = new Thread(() -> acceptLoop(server, paymentProtocol),
                paymentProtocol ? "bitcoin-bt-payment-protocol" : "bitcoin-bt-classic-protocol");
        worker.setDaemon(true);
        workers.add(worker);
        worker.start();
    }

    private void acceptLoop(BluetoothServerSocket server, boolean paymentProtocol) {
        while (running.get()) {
            try (BluetoothSocket socket = server.accept()) {
                if (paymentProtocol) handlePaymentProtocol(socket);
                else handleClassic(socket);
            } catch (IOException | RuntimeException ignored) {
                if (!running.get()) return;
            }
        }
    }

    private void handleClassic(BluetoothSocket socket) throws IOException {
        NetworkParameters activeParameters = MainActivityPresenter.getActiveParameters();
        if (activeParameters == null) return;
        org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(activeParameters));
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        int count = input.readInt();
        boolean accepted = count > 0 && count <= MAX_CLASSIC_MESSAGES;
        for (int i = 0; accepted && i < count; i++) {
            int length = input.readInt();
            if (length <= 0 || length > MAX_TRANSACTION_BYTES) {
                accepted = false;
                break;
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            try {
                Transaction transaction = Transaction.read(ByteBuffer.wrap(bytes));
                accepted = MainActivityPresenter.receiveBluetoothTransaction(transaction);
            } catch (Exception invalidTransaction) {
                accepted = false;
            }
        }
        output.writeBoolean(accepted);
        output.flush();
    }

    private void handlePaymentProtocol(BluetoothSocket socket) throws IOException {
        NetworkParameters activeParameters = MainActivityPresenter.getActiveParameters();
        if (activeParameters == null) return;
        org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(activeParameters));
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        Protos.Payment payment = Protos.Payment.parseDelimitedFrom(input);
        if (payment == null) return;
        boolean accepted = false;
        try {
            NetworkParameters parameters = activeParameters;
            if (payment.getTransactionsCount() > 0 && payment.getTransactionsCount() <= MAX_CLASSIC_MESSAGES) {
                accepted = true;
                for (Transaction transaction : PaymentProtocol.parseTransactionsFromPaymentMessage(parameters, payment)) {
                    if (!MainActivityPresenter.receiveBluetoothTransaction(transaction)) accepted = false;
                }
            }
        } catch (Exception invalidPayment) {
            // Includes checked failures from receiving/validating the transaction.
            // Report a NACK instead of terminating the Bluetooth protocol handler.
            accepted = false;
        }
        Protos.PaymentACK ack = PaymentProtocol.createPaymentAck(payment, accepted ? "ack" : "nack");
        ack.writeDelimitedTo(output);
        output.flush();
    }

    private void sendReceiverStoppedBroadcast() {
        try {
            sendBroadcast(new Intent(BluetoothSupport.ACTION_RECEIVER_STOPPED).setPackage(getPackageName()));
        } catch (RuntimeException ignored) { }
    }

    @Override public void onDestroy() {
        running.set(false);
        BluetoothSupport.setAcceptingPayments(this, false);
        sendReceiverStoppedBroadcast();
        if (bluetoothReceiverRegistered) {
            try { unregisterReceiver(bluetoothStateReceiver); } catch (RuntimeException ignored) { }
            bluetoothReceiverRegistered = false;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) { }
            wakeLock = null;
        }
        synchronized (sockets) {
            for (BluetoothServerSocket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            sockets.clear();
        }
        synchronized (workers) {
            for (Thread worker : workers) worker.interrupt();
            workers.clear();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }
}
