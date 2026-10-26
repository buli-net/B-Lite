package wallet.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;

import android.graphics.drawable.Icon;

import wallet.main.R;

/** Keeps the browser process eligible to continue user-started web media in the background. */
public final class BrowserPlaybackService extends Service {
    public static final String ACTION_START = "wallet.browser.action.START";
    public static final String ACTION_STOP = "wallet.browser.action.STOP";
    public static final String ACTION_PLAY = "wallet.browser.action.PLAY";
    public static final String ACTION_PAUSE = "wallet.browser.action.PAUSE";
    public static final String ACTION_PAGE = "wallet.browser.action.PAGE";
    public static final String ACTION_STATE = "wallet.browser.action.STATE";
    public static final String ACTION_MEDIA_PLAY = "wallet.browser.MEDIA_PLAY";
    public static final String ACTION_MEDIA_PAUSE = "wallet.browser.MEDIA_PAUSE";
    public static final String ACTION_MEDIA_STOP = "wallet.browser.MEDIA_STOP";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PLAYING = "playing";

    private static final String CHANNEL_ID = "browser_media_playback";
    private static final int NOTIFICATION_ID = 7304;
    private MediaSession mediaSession;
    private String pageTitle;
    private String pageUrl;
    private boolean playing;
    private PowerManager.WakeLock playbackWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":browser-media");
            playbackWakeLock.setReferenceCounted(false);
        }
        mediaSession = new MediaSession(this, "BrowserMediaSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { dispatch(ACTION_MEDIA_PLAY); setPlaying(true); }
            @Override public void onPause() { dispatch(ACTION_MEDIA_PAUSE); setPlaying(false); }
            @Override public void onStop() { dispatch(ACTION_MEDIA_STOP); setPlaying(false); }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (intent != null) {
            if (intent.hasExtra(EXTRA_TITLE)) pageTitle = intent.getStringExtra(EXTRA_TITLE);
            if (intent.hasExtra(EXTRA_URL)) pageUrl = intent.getStringExtra(EXTRA_URL);
        }
        if (ACTION_STOP.equals(action)) {
            dispatch(ACTION_MEDIA_STOP);
            setPlaying(false);
            if (mediaSession != null) mediaSession.setActive(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            dispatch(ACTION_MEDIA_PLAY);
            setPlaying(true);
        } else if (ACTION_PAUSE.equals(action)) {
            dispatch(ACTION_MEDIA_PAUSE);
            setPlaying(false);
        } else if (ACTION_PAGE.equals(action)) {
            updateMetadata();
        } else if (ACTION_STATE.equals(action)) {
            setPlaying(intent != null && intent.getBooleanExtra(EXTRA_PLAYING, false));
        } else if (ACTION_START.equals(action) || action == null) {
            setPlaying(intent != null && intent.getBooleanExtra(EXTRA_PLAYING, false));
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_NOT_STICKY;
    }

    private void dispatch(String action) {
        Intent command = new Intent(action).setPackage(getPackageName());
        sendBroadcast(command);
    }

    private void setPlaying(boolean value) {
        playing = value;
        if (playbackWakeLock != null) {
            try {
                if (value && !playbackWakeLock.isHeld()) playbackWakeLock.acquire();
                else if (!value && playbackWakeLock.isHeld()) playbackWakeLock.release();
            } catch (RuntimeException ignored) { }
        }
        if (mediaSession != null) {
            long actionSet = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(actionSet)
                    .setState(value ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build();
            mediaSession.setPlaybackState(state);
        }
        if (mediaSession != null) mediaSession.setActive(true);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException ignored) {
            // The service remains useful if the system notification manager is temporarily unavailable.
        }
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        android.media.MediaMetadata metadata = new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,
                        pageTitle == null || pageTitle.trim().isEmpty()
                                ? getString(R.string.browser_background_notification_title) : pageTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                        pageTitle == null ? getString(R.string.browser_title) : pageTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        pageUrl == null ? "" : Uri.parse(pageUrl).getHost())
                .build();
        mediaSession.setMetadata(metadata);
    }

    private Notification buildNotification() {
        updateMetadata();
        Intent openIntent = new Intent(this, BrowserActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 7305, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent play = PendingIntent.getService(this, 7306,
                new Intent(this, BrowserPlaybackService.class).setAction(ACTION_PLAY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 7307,
                new Intent(this, BrowserPlaybackService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 7308,
                new Intent(this, BrowserPlaybackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(pageTitle == null || pageTitle.trim().isEmpty()
                        ? getString(R.string.browser_background_notification_title) : pageTitle)
                .setContentText(getString(R.string.browser_background_note))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_play),
                        getString(R.string.browser_background_action_play), play).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        getString(R.string.browser_background_action_pause), pause).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.browser_background_action_stop), stop).build());
        if (mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1));
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.browser_background_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.browser_background_note));
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (playbackWakeLock != null && playbackWakeLock.isHeld()) {
            try { playbackWakeLock.release(); } catch (RuntimeException ignored) { }
        }
        playbackWakeLock = null;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
