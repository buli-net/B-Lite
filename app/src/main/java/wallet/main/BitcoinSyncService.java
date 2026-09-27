package wallet.main;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;

/**
 * Keeps the bitcoinj WalletAppKit alive independently of the Activity.
 * The Activity is only a UI client; this foreground service owns the sync lifecycle.
 */
public class BitcoinSyncService extends Service {

    private static final String CHANNEL_ID = "bitcoin_sync";
    private static final String TRANSACTION_CHANNEL_ID = "bitcoin_transactions";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS_NOTIFICATIONS = "transaction_notifications";
    private static final String KEY_RECEIVED_PREFIX = "received_";
    private static final String KEY_SENT_PREFIX = "sent_";

    public static void start(Context context) {
        Intent intent = new Intent(context.getApplicationContext(), BitcoinSyncService.class);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(NOTIFICATION_ID, buildSyncNotification(this, 0, 0, 0, true));
        ensureWalletPresenter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureWalletPresenter();
        return START_STICKY;
    }

    private void ensureWalletPresenter() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            presenter = new MainActivityPresenter(
                    new ServiceView(getApplicationContext()),
                    getFilesDir());
        }
        presenter.subscribe();
    }

    public static void updateSyncNotification(
            Context context,
            int percent,
            int currentBlock,
            int targetBlock,
            boolean syncing) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(
                NOTIFICATION_ID,
                buildSyncNotification(app, percent, currentBlock, targetBlock, syncing));
    }

    public static void notifyReceived(
            Context context, Coin amount, Transaction transaction) {
        postTransactionNotification(
                context,
                appText(context, wallet.main.R.string.notification_bitcoin_received),
                appText(context, wallet.main.R.string.notification_received_amount,
                        amount == null ? "Bitcoin" : amount.toFriendlyString()),
                transaction);
    }

    public static void notifySent(
            Context context, Coin amount, Transaction transaction) {
        postTransactionNotification(
                context,
                appText(context, wallet.main.R.string.notification_bitcoin_sent),
                appText(context, wallet.main.R.string.notification_sent_amount,
                        amount == null ? "Bitcoin" : amount.toFriendlyString()),
                transaction);
    }

    private static String appText(Context context, int resId, Object... args) {
        return context.getString(resId, args);
    }

    private static void postTransactionNotification(
            Context context, String title, String text, Transaction transaction) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        String hash = transaction == null ? "" : transaction.getTxId().toString();
        if (!hash.isEmpty() && !markTransactionNotificationSeen(app, title, hash)) {
            return;
        }

        String expanded = text;
        if (!hash.isEmpty()) {
            expanded = text + "\nTX: " + hash;
        }

        Notification notification = new NotificationCompat.Builder(app, TRANSACTION_CHANNEL_ID)
                .setSmallIcon(wallet.main.R.drawable.ic_bitcoin_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded))
                .setContentIntent(mainActivityPendingIntent(app))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .build();

        int notificationId = hash.isEmpty()
                ? (int) (System.currentTimeMillis() & 0x7fffffff)
                : ((title + ":" + hash).hashCode() & 0x7fffffff);
        if (notificationId == 0) {
            notificationId = 1;
        }
        manager.notify(notificationId, notification);
    }

    private static boolean markTransactionNotificationSeen(
            Context context, String title, String txid) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NOTIFICATIONS, Context.MODE_PRIVATE);
        String prefix = context.getString(wallet.main.R.string.notification_bitcoin_sent).equals(title)
                ? KEY_SENT_PREFIX
                : KEY_RECEIVED_PREFIX;
        String key = prefix + txid;
        synchronized (BitcoinSyncService.class) {
            if (prefs.getBoolean(key, false)) {
                return false;
            }
            prefs.edit().putBoolean(key, true).apply();
            return true;
        }
    }

    private static PendingIntent mainActivityPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 2001, intent, flags);
    }

    private static Notification buildSyncNotification(
            Context context,
            int percent,
            int currentBlock,
            int targetBlock,
            boolean syncing) {
        int safePercent = Math.max(0, Math.min(100, percent));
        String title = context.getString(wallet.main.R.string.app_name);
        String content;

        if (syncing) {
            if (targetBlock > 0) {
                content = context.getString(
                        wallet.main.R.string.notification_syncing,
                        safePercent, currentBlock, targetBlock);
            } else if (currentBlock > 0) {
                content = context.getString(
                        wallet.main.R.string.notification_waiting_peers,
                        currentBlock);
            } else {
                content = context.getString(
                        wallet.main.R.string.notification_syncing_no_target,
                        safePercent, currentBlock);
            }
        } else {
            content = context.getString(
                    wallet.main.R.string.notification_sync_complete, currentBlock);
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(wallet.main.R.drawable.ic_bitcoin_notification)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setProgress(100, safePercent, false);

        builder.setContentIntent(mainActivityPendingIntent(context));
        return builder.build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel syncChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(wallet.main.R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW);
        syncChannel.setDescription(
                getString(wallet.main.R.string.notification_channel_sync_description));
        manager.createNotificationChannel(syncChannel);

        NotificationChannel transactionChannel = new NotificationChannel(
                TRANSACTION_CHANNEL_ID,
                getString(wallet.main.R.string.notification_channel_transactions),
                NotificationManager.IMPORTANCE_DEFAULT);
        transactionChannel.setDescription(
                getString(wallet.main.R.string.notification_channel_transactions_description));
        manager.createNotificationChannel(transactionChannel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Do not stop the service when the wallet task is removed from Recents.
        // START_STICKY keeps the sync engine alive/restartable independently of the UI task.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter != null) {
            presenter.unsubscribe();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class ServiceView implements MainActivityContract.MainActivityView {
        private final Context context;

        ServiceView(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override public void setPresenter(MainActivityContract.MainActivityPresenter presenter) { }
        @Override public void displayDownloadContent(boolean shown) { }
        @Override public void displayProgress(int percent) { }
        @Override public void displayPercentage(int percent) { }
        @Override public void displayMyBalance(String balance) { }
        @Override public void displayBalanceState(String available, String pending) { }
        @Override public void displayMyAddress(String address) { }
        @Override public void displayWalletType(String type) { }
        @Override public void displayTransactions(java.util.List<wallet.model.TransactionItem> transactions) { }
        @Override public void showToastMessage(String message) { }
        @Override public Context getActivityContext() { return context; }
    }
}
