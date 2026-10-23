package wallet.node;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.bitcoinj.base.BitcoinNetwork;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import wallet.main.R;
import wallet.network.NetworkConfig;

/** Owns the optional Bitcoin Core process independently of the status screen. */
public class BitcoinCoreNodeService extends Service {
    public static final String ACTION_START = "wallet.node.action.START";
    public static final String ACTION_STOP = "wallet.node.action.STOP";
    public static final String EXTRA_NETWORK = "wallet.node.extra.NETWORK";

    private static final String PREFS = "bitcoin_core_node_state";
    private static final String KEY_STATE = "state";
    private static final String KEY_NETWORK = "network";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_BLOCKS = "blocks";
    private static final String KEY_PEERS = "peers";
    private static final String KEY_MEMPOOL = "mempool";
    private static final String CHANNEL_ID = "bitcoin_core_node";
    private static final int NOTIFICATION_ID = 2301;

    private final ExecutorService processExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService statusExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile Process nodeProcess;
    private volatile BitcoinNetwork activeNetwork = BitcoinNetwork.MAINNET;
    private volatile boolean stopRequested;
    private volatile boolean launchInProgress;
    private volatile boolean statusPollingScheduled;

    public static void start(Context context, BitcoinNetwork network) {
        Intent intent = new Intent(context.getApplicationContext(), BitcoinCoreNodeService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_NETWORK, network.name());
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
        else app.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context.getApplicationContext(), BitcoinCoreNodeService.class);
        intent.setAction(ACTION_STOP);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
        else app.startService(intent);
    }

    public static String state(Context context) {
        return prefs(context).getString(KEY_STATE, "stopped");
    }

    public static String networkName(Context context) {
        return prefs(context).getString(KEY_NETWORK, "");
    }

    public static String message(Context context) {
        return prefs(context).getString(KEY_MESSAGE, "");
    }

    public static boolean isActive(Context context) {
        String value = state(context);
        return "starting".equals(value) || "running".equals(value) || "syncing".equals(value);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveState(Context context, String state, BitcoinNetwork network, String message) {
        prefs(context).edit()
                .putString(KEY_STATE, state)
                .putString(KEY_NETWORK, network == null ? "" : network.name())
                .putString(KEY_MESSAGE, message == null ? "" : message)
                .apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            saveState(this, "stopping", activeNetwork, getString(R.string.core_node_stopping));
            showForegroundNotification(getString(R.string.core_node_stopping));
            processExecutor.execute(this::stopProcess);
            return START_NOT_STICKY;
        }

        String requestedNetwork = intent == null ? null : intent.getStringExtra(EXTRA_NETWORK);
        BitcoinNetwork parsed = parseNetwork(requestedNetwork);
        if ((nodeProcess != null && nodeProcess.isAlive()) || launchInProgress) {
            if (parsed != activeNetwork) {
                saveState(this, "running", activeNetwork, getString(R.string.core_node_network_already_running));
            }
            return START_STICKY;
        }

        activeNetwork = parsed;
        stopRequested = false;
        saveState(this, "starting", activeNetwork, getString(R.string.core_node_starting));
        showForegroundNotification(getString(R.string.core_node_starting));

        File binary = BitcoinCoreConfig.binary(this);
        if (!binary.isFile() || !binary.canExecute()) {
            saveState(this, "missing_binary", activeNetwork, getString(R.string.core_node_binary_missing_detail));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        File dataDirectory = BitcoinCoreConfig.dataDirectory(this, activeNetwork);
        if (!dataDirectory.exists() && !dataDirectory.mkdirs()) {
            failAndStop(startId, getString(R.string.core_node_data_directory_failed));
            return START_NOT_STICKY;
        }

        launchInProgress = true;
        if (!statusPollingScheduled) {
            statusPollingScheduled = true;
            statusExecutor.scheduleWithFixedDelay(this::refreshStatus, 3L, 15L, TimeUnit.SECONDS);
        }
        processExecutor.execute(() -> runProcess(startId));
        return START_STICKY;
    }

    private void runProcess(int startId) {
        try {
            if (stopRequested) {
                launchInProgress = false;
                saveState(this, "stopped", activeNetwork, getString(R.string.core_node_stopped));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
                return;
            }
            List<String> arguments = BitcoinCoreConfig.command(this, activeNetwork);
            String[] command = arguments.toArray(new String[0]);
            File workingDirectory = BitcoinCoreConfig.dataDirectory(this, activeNetwork);
            Map<String, String> environment = new HashMap<>(System.getenv());
            String nativeLibraryDirectory = getApplicationInfo().nativeLibraryDir;
            String existingLibraryPath = environment.get("LD_LIBRARY_PATH");
            environment.put("LD_LIBRARY_PATH", existingLibraryPath == null
                    || existingLibraryPath.isEmpty()
                    ? nativeLibraryDirectory
                    : nativeLibraryDirectory + File.pathSeparator + existingLibraryPath);
            String[] environmentArray = new String[environment.size()];
            int environmentIndex = 0;
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                environmentArray[environmentIndex++] = entry.getKey() + "=" + entry.getValue();
            }
            nodeProcess = Runtime.getRuntime().exec(command, environmentArray, workingDirectory);
            launchInProgress = false;
            if (stopRequested && nodeProcess != null) {
                try {
                    new BitcoinCoreRpcClient(this, activeNetwork).call("stop", null);
                } catch (Exception ignored) {
                    nodeProcess.destroy();
                }
            }
            Thread stdout = drainQuietly(nodeProcess.getInputStream(), "bitcoin-core-stdout");
            Thread stderr = drainQuietly(nodeProcess.getErrorStream(), "bitcoin-core-stderr");
            saveState(this, "running", activeNetwork, getString(R.string.core_node_process_started));
            showForegroundNotification(getString(R.string.core_node_process_started));

            int exitCode = nodeProcess.waitFor();
            nodeProcess = null;
            if (stopRequested) {
                saveState(this, "stopped", activeNetwork, getString(R.string.core_node_stopped));
            } else {
                String message = getString(R.string.core_node_process_exited, exitCode);
                saveState(this, "error", activeNetwork, message);
            }
            statusExecutor.shutdownNow();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        } catch (InterruptedException interrupted) {
            launchInProgress = false;
            Thread.currentThread().interrupt();
            saveState(this, stopRequested ? "stopped" : "error", activeNetwork,
                    getString(R.string.core_node_interrupted));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        } catch (IOException | RuntimeException exception) {
            launchInProgress = false;
            saveState(this, "error", activeNetwork,
                    getString(R.string.core_node_launch_failed, safeMessage(exception)));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private Thread drainQuietly(InputStream stream, String threadName) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Bitcoin Core writes its operational log to its own debug.log file.
                    // Drain process pipes to prevent a blocked child process; do not duplicate logs here.
                }
            } catch (IOException ignored) {
                // The process may close its pipes as part of a normal shutdown.
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void refreshStatus() {
        if (stopRequested || nodeProcess == null || !nodeProcess.isAlive()) return;
        try {
            BitcoinCoreStatus status = new BitcoinCoreRpcClient(this, activeNetwork).fetchStatus();
            prefs(this).edit()
                    .putString(KEY_STATE, status.initialBlockDownload ? "syncing" : "running")
                    .putInt(KEY_BLOCKS, status.blocks)
                    .putInt(KEY_PEERS, status.connections)
                    .putInt(KEY_MEMPOOL, status.mempoolTransactions)
                    .putString(KEY_MESSAGE, getString(R.string.core_node_height_peers,
                            status.blocks, status.connections))
                    .apply();
            showForegroundNotification(getString(R.string.core_node_notification_status,
                    status.blocks, status.connections, status.mempoolTransactions));
        } catch (Exception ignored) {
            // Core can take time to create its RPC cookie while it starts. The next poll retries.
        }
    }

    private void stopProcess() {
        Process process = nodeProcess;
        if (process == null && launchInProgress) {
            // runProcess observes stopRequested before or immediately after launch and cleans up.
            return;
        }
        if (process == null) {
            saveState(this, "stopped", activeNetwork, getString(R.string.core_node_stopped));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        try {
            new BitcoinCoreRpcClient(this, activeNetwork).call("stop", null);
        } catch (Exception ignored) {
            // If RPC is not yet ready, fall back to terminating the owned process.
        }
        try {
            if (!process.waitFor(15L, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(3L, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            launchInProgress = false;
            Thread.currentThread().interrupt();
            process.destroy();
        }
        nodeProcess = null;
        saveState(this, "stopped", activeNetwork, getString(R.string.core_node_stopped));
        statusExecutor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void failAndStop(int startId, String message) {
        saveState(this, "error", activeNetwork, message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    private void showForegroundNotification(String text) {
        Intent openIntent = new Intent(this, BitcoinCoreNodeActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 2302, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bitcoin_notification)
                .setContentTitle(getString(R.string.core_node_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.core_node_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.core_node_notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private static BitcoinNetwork parseNetwork(String value) {
        try {
            BitcoinNetwork network = BitcoinNetwork.valueOf(value == null ? "MAINNET" : value);
            return BitcoinCoreConfig.isSupportedNetwork(network) ? network : BitcoinNetwork.MAINNET;
        } catch (IllegalArgumentException ignored) {
            return BitcoinNetwork.MAINNET;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message.trim();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        statusExecutor.shutdownNow();
        processExecutor.shutdown();
        super.onDestroy();
    }
}
