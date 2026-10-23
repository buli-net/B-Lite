/* Experimental full-pruned node integration. */
package wallet.node;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.bitcoinj.base.BitcoinNetwork;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import wallet.main.R;
import wallet.network.NetworkConfig;

/** Foreground owner of the optional bitcoinj full-pruned verification engine. */
public final class FullPrunedNodeService extends Service {
    public static final String ACTION_START = "wallet.node.action.START";
    public static final String ACTION_STOP = "wallet.node.action.STOP";
    public static final String EXTRA_NETWORK = "wallet.node.extra.NETWORK";

    private static final String CHANNEL_ID = "full_pruned_node";
    private static final int NOTIFICATION_ID = 2206;

    private ScheduledExecutorService executor;
    private volatile BitcoinjFullPrunedNodeEngine engine;
    private volatile BitcoinNetwork activeNetwork;
    private volatile String state = "stopped";
    private volatile String error = "";
    private ScheduledFuture<?> statusJob;

    public static void start(Context context, BitcoinNetwork network) {
        Intent intent = new Intent(context.getApplicationContext(), FullPrunedNodeService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NETWORK, network.name());
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
        else app.startService(intent);
    }

    public static void stop(Context context, BitcoinNetwork network) {
        Intent intent = new Intent(context.getApplicationContext(), FullPrunedNodeService.class)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_NETWORK, network == null ? null : network.name());
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
        else app.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "B-Lite-full-pruned-node");
            thread.setDaemon(false);
            return thread;
        });
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.full_pruned_node_state_starting)));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            BitcoinNetwork stopNetwork = intent == null ? null
                    : parseNetwork(intent.getStringExtra(EXTRA_NETWORK));
            if (stopNetwork == null) stopNetwork = activeNetwork;
            if (stopNetwork == null) stopNetwork = FullPrunedNodeStatus.getSelectedNetwork(this);
            if (stopNetwork == null) stopNetwork = NetworkConfig.get(this);
            FullPrunedNodeStatus.setEnabled(this, stopNetwork, false);
            if (activeNetwork != null && activeNetwork != stopNetwork) {
                // A stop request for an inactive network must not stop the active node.
                persistCurrentStatus();
                return START_STICKY;
            }
            state = "stopping";
            persistCurrentStatus();
            stopEngineAsync(stopNetwork);
            return START_NOT_STICKY;
        }

        BitcoinNetwork requested = intent == null
                ? FullPrunedNodeStatus.getSelectedNetwork(this)
                : parseNetwork(intent.getStringExtra(EXTRA_NETWORK));
        if (requested == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        activeNetwork = requested;
        FullPrunedNodeStatus.setEnabled(this, requested, true);
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.full_pruned_node_state_starting)));
        startEngineAsync(requested);
        return START_STICKY;
    }

    private BitcoinNetwork parseNetwork(String name) {
        if (name == null) return null;
        try {
            BitcoinNetwork network = BitcoinNetwork.valueOf(name);
            return network == BitcoinNetwork.MAINNET || network == BitcoinNetwork.SIGNET ? network : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private synchronized void startEngineAsync(BitcoinNetwork network) {
        if (executor == null || executor.isShutdown()) return;
        executor.execute(() -> {
            BitcoinjFullPrunedNodeEngine current = engine;
            if (current != null && current.isRunning() && current.network() == network) {
                state = "running";
                persistCurrentStatus();
                scheduleStatusUpdates();
                return;
            }
            if (current != null) {
                BitcoinNetwork previousNetwork = current.network();
                int previousHeight = current.getVerifiedHeight();
                int previousPeers = current.getConnectedPeers();
                long previousBytes = current.getDatabaseBytes();
                current.close();
                FullPrunedNodeStatus.write(this, previousNetwork, "stopped", previousHeight, 0,
                        0, previousBytes, "");
                if (previousNetwork != network) {
                    FullPrunedNodeStatus.setEnabled(this, previousNetwork, false);
                }
            }
            engine = null;
            state = "starting";
            error = "";
            persistCurrentStatus();
            try {
                BitcoinjFullPrunedNodeEngine next = new BitcoinjFullPrunedNodeEngine(
                        getApplicationContext(), network,
                        (height, blocksLeft, peers) -> {
                            if (activeNetwork == network) {
                                FullPrunedNodeStatus.write(this, network, state, height, blocksLeft, peers,
                                        nextDatabaseSize(), error);
                            }
                        });
                next.start();
                engine = next;
                state = "running";
                persistCurrentStatus();
                scheduleStatusUpdates();
            } catch (Throwable e) {
                state = "error";
                error = conciseError(e);
                FullPrunedNodeStatus.setEnabled(this, network, false);
                persistCurrentStatus();
                updateNotification(getString(R.string.full_pruned_node_state_error));
            }
        });
    }

    private long nextDatabaseSize() {
        BitcoinjFullPrunedNodeEngine current = engine;
        return current == null ? 0L : current.getDatabaseBytes();
    }

    private synchronized void scheduleStatusUpdates() {
        if (executor == null || executor.isShutdown()) return;
        if (statusJob != null) statusJob.cancel(false);
        statusJob = executor.scheduleWithFixedDelay(this::refreshStatus, 2, 5, TimeUnit.SECONDS);
    }

    private void refreshStatus() {
        BitcoinjFullPrunedNodeEngine current = engine;
        BitcoinNetwork network = activeNetwork;
        if (network == null || current == null || !current.isRunning()) return;
        FullPrunedNodeStatus.write(this, network, state, current.getVerifiedHeight(), current.getBlocksLeft(),
                current.getConnectedPeers(), current.getDatabaseBytes(), error);
        updateNotification(getString(R.string.full_pruned_node_notification_running,
                current.getVerifiedHeight(), current.getConnectedPeers()));
    }

    private synchronized void stopEngineAsync(BitcoinNetwork requestedNetwork) {
        if (executor == null || executor.isShutdown()) {
            BitcoinNetwork network = activeNetwork != null ? activeNetwork : requestedNetwork;
            if (network != null) {
                FullPrunedNodeStatus.Snapshot last = FullPrunedNodeStatus.read(this, network);
                FullPrunedNodeStatus.write(this, network, "stopped", last.height, 0, 0,
                        last.databaseBytes, "");
                FullPrunedNodeStatus.setEnabled(this, network, false);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        executor.execute(() -> {
            BitcoinNetwork network = activeNetwork != null ? activeNetwork : requestedNetwork;
            BitcoinjFullPrunedNodeEngine current = engine;
            // Capture persisted values before close() clears the engine's references.
            int savedHeight = current == null ? 0 : current.getVerifiedHeight();
            long savedBytes = current == null ? 0L : current.getDatabaseBytes();
            if (network != null && (current == null || current.network() != network)) {
                FullPrunedNodeStatus.Snapshot last = FullPrunedNodeStatus.read(this, network);
                if (current == null) {
                    savedHeight = last.height;
                    savedBytes = last.databaseBytes;
                }
            }
            engine = null;
            if (current != null) current.close();
            if (statusJob != null) statusJob.cancel(false);
            statusJob = null;
            state = "stopped";
            error = "";
            if (network != null) {
                FullPrunedNodeStatus.write(this, network, state, savedHeight, 0, 0, savedBytes, "");
                FullPrunedNodeStatus.setEnabled(this, network, false);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void persistCurrentStatus() {
        BitcoinNetwork network = activeNetwork;
        BitcoinjFullPrunedNodeEngine current = engine;
        if (network == null) return;
        FullPrunedNodeStatus.write(this, network, state,
                current == null ? 0 : current.getVerifiedHeight(),
                current == null ? 0 : current.getBlocksLeft(),
                current == null ? 0 : current.getConnectedPeers(),
                current == null ? 0 : current.getDatabaseBytes(), error);
        updateNotification(state.equals("running")
                ? getString(R.string.full_pruned_node_notification_running,
                    current == null ? 0 : current.getVerifiedHeight(), current == null ? 0 : current.getConnectedPeers())
                : state.equals("error") ? getString(R.string.full_pruned_node_state_error)
                : getString(R.string.full_pruned_node_state_starting));
    }

    private String conciseError(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) message = cause.getClass().getSimpleName();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.full_pruned_node_title), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.full_pruned_node_intro));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, FullPrunedNodeActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 2206, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.full_pruned_node_title))
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && state.equals("running")) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        BitcoinjFullPrunedNodeEngine current = engine;
        engine = null;
        if (current != null) current.close();
        if (statusJob != null) statusJob.cancel(false);
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
