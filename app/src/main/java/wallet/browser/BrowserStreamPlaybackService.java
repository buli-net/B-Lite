package wallet.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wallet.main.R;

/** Plays an extracted audio stream independently from the browser WebView. */
public final class BrowserStreamPlaybackService extends Service {
    public static final String ACTION_START = "wallet.browser.stream.START";
    public static final String ACTION_STOP = "wallet.browser.stream.STOP";
    public static final String ACTION_PLAY = "wallet.browser.stream.PLAY";
    public static final String ACTION_PAUSE = "wallet.browser.stream.PAUSE";
    public static final String ACTION_ERROR = "wallet.browser.stream.ERROR";
    public static final String ACTION_FINISHED = "wallet.browser.stream.FINISHED";
    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_POSITION_MS = "stream_position_ms";
    private static final String CHANNEL_ID = "browser_stream_playback";
    private static final int NOTIFICATION_ID = 7314;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService extractorExecutor = Executors.newSingleThreadExecutor();
    private ExoPlayer player;
    private MediaSession mediaSession;
    private String pageTitle;
    private String pageUrl;
    private long requestedPositionMs;
    private long durationMs;
    private boolean resolving;
    private boolean playWhenResolved = true;
    private boolean shuttingDown;
    private int requestGeneration;
    private static volatile boolean extractorReady;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateSessionState();
                refreshNotification();
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                updateSessionState();
                refreshNotification();
                if (playbackState == Player.STATE_ENDED && !shuttingDown) {
                    sendEvent(ACTION_FINISHED, null);
                    stopPlaybackService();
                }
            }

            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if (!shuttingDown) {
                    sendEvent(ACTION_ERROR, null);
                    stopPlaybackService();
                }
            }
        });
        mediaSession = new MediaSession(this, "BrowserStreamSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { handlePlay(); }
            @Override public void onPause() { handlePause(); }
            @Override public void onStop() { stopPlaybackService(); }
            @Override public void onSeekTo(long position) {
                if (player != null && player.getMediaItemCount() > 0) player.seekTo(position);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlaybackService();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            handlePlay();
            startForeground(NOTIFICATION_ID, buildNotification());
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            handlePause();
            startForeground(NOTIFICATION_ID, buildNotification());
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        pageUrl = intent.getStringExtra(EXTRA_URL);
        String incomingTitle = intent.getStringExtra(EXTRA_TITLE);
        pageTitle = incomingTitle == null || incomingTitle.trim().isEmpty()
                ? getString(R.string.browser_title) : incomingTitle.trim();
        requestedPositionMs = Math.max(0L, intent.getLongExtra(EXTRA_POSITION_MS, 0L));
        playWhenResolved = true;
        shuttingDown = false;
        resolving = true;
        int generation = ++requestGeneration;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        updateSessionMetadata(0L);
        startForeground(NOTIFICATION_ID, buildNotification());
        extractorExecutor.execute(() -> {
            try {
                ExtractedAudio audio = resolveAudio(pageUrl);
                mainHandler.post(() -> {
                    if (shuttingDown || generation != requestGeneration || player == null) return;
                    resolving = false;
                    if (audio.title != null && !audio.title.trim().isEmpty()) pageTitle = audio.title.trim();
                    durationMs = audio.durationMs;
                    updateSessionMetadata(durationMs);
                    MediaItem item = new MediaItem.Builder()
                            .setUri(audio.url)
                            .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(pageTitle)
                                    .build())
                            .build();
                    player.setMediaItem(item, requestedPositionMs);
                    player.prepare();
                    if (playWhenResolved) player.play();
                    updateSessionState();
                    refreshNotification();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation != requestGeneration || shuttingDown) return;
                    resolving = false;
                    sendEvent(ACTION_ERROR, null);
                    stopPlaybackService();
                });
            }
        });
        return START_NOT_STICKY;
    }

    private static ExtractedAudio resolveAudio(String url) throws Exception {
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("Missing video URL");
        synchronized (BrowserStreamPlaybackService.class) {
            if (!extractorReady) {
                NewPipe.init(new BrowserExtractorDownloader());
                extractorReady = true;
            }
        }
        StreamInfo info = StreamInfo.getInfo(url);
        List<AudioStream> streams = info.getAudioStreams();
        if (streams != null && !streams.isEmpty()) {
            AudioStream best = streams.stream()
                    .filter(stream -> stream != null && stream.getContent() != null
                            && (stream.getContent().startsWith("https://")
                            || stream.getContent().startsWith("http://")))
                    .max(Comparator.comparingInt(stream -> Math.max(0, stream.getAverageBitrate())))
                    .orElse(null);
            if (best != null) {
                return new ExtractedAudio(best.getContent(), info.getName(), info.getDuration() * 1000L);
            }
        }
        String hls = info.getHlsUrl();
        if (hls != null && (hls.startsWith("https://") || hls.startsWith("http://"))) {
            return new ExtractedAudio(hls, info.getName(), info.getDuration() * 1000L);
        }
        String dash = info.getDashMpdUrl();
        if (dash != null && (dash.startsWith("https://") || dash.startsWith("http://"))) {
            return new ExtractedAudio(dash, info.getName(), info.getDuration() * 1000L);
        }
        throw new IllegalStateException("No playable audio stream was returned");
    }

    private static final class ExtractedAudio {
        final String url;
        final String title;
        final long durationMs;
        ExtractedAudio(String url, String title, long durationMs) {
            this.url = url;
            this.title = title;
            this.durationMs = Math.max(0L, durationMs);
        }
    }

    private void handlePlay() {
        playWhenResolved = true;
        if (!resolving && player != null && player.getMediaItemCount() > 0) player.play();
        updateSessionState();
        refreshNotification();
    }

    private void handlePause() {
        playWhenResolved = false;
        if (player != null) player.pause();
        updateSessionState();
        refreshNotification();
    }

    private void stopPlaybackService() {
        if (shuttingDown) return;
        shuttingDown = true;
        requestGeneration++;
        resolving = false;
        playWhenResolved = false;
        if (player != null) {
            try { player.stop(); player.clearMediaItems(); } catch (RuntimeException ignored) { }
        }
        if (mediaSession != null) mediaSession.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void updateSessionMetadata(long durationMs) {
        if (mediaSession == null) return;
        android.media.MediaMetadata.Builder metadata = new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, pageTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, pageTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        pageUrl == null ? "" : safeHost(pageUrl));
        if (durationMs > 0L) metadata.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, durationMs);
        mediaSession.setMetadata(metadata.build());
    }

    private String safeHost(String url) {
        try { String host = Uri.parse(url).getHost(); return host == null ? "" : host; }
        catch (RuntimeException ignored) { return ""; }
    }

    private void updateSessionState() {
        if (mediaSession == null || player == null) return;
        int state;
        float speed = 1.0f;
        if (resolving || player.getPlaybackState() == Player.STATE_BUFFERING) {
            state = PlaybackState.STATE_BUFFERING;
        } else if (player.isPlaying()) {
            state = PlaybackState.STATE_PLAYING;
            speed = player.getPlaybackParameters().speed;
        } else if (player.getPlaybackState() == Player.STATE_ENDED) {
            state = PlaybackState.STATE_STOPPED;
        } else {
            state = PlaybackState.STATE_PAUSED;
        }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO;
        long position = player.getMediaItemCount() == 0 ? 0L : player.getCurrentPosition();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, speed)
                .build());
        mediaSession.setActive(true);
    }

    private Notification buildNotification() {
        updateSessionMetadata(durationMs);
        Intent openIntent = new Intent(this, BrowserActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(BrowserActivity.EXTRA_URL, pageUrl)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 7315, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent play = PendingIntent.getService(this, 7316,
                new Intent(this, BrowserStreamPlaybackService.class).setAction(ACTION_PLAY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 7317,
                new Intent(this, BrowserStreamPlaybackService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 7318,
                new Intent(this, BrowserStreamPlaybackService.class).setAction(ACTION_STOP),
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
                        Icon.createWithResource(this, player != null && player.isPlaying()
                                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        getString(player != null && player.isPlaying()
                                ? R.string.browser_background_action_pause
                                : R.string.browser_background_action_play),
                        player != null && player.isPlaying() ? pause : play).build())
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

    private void refreshNotification() {
        updateSessionState();
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException ignored) { }
    }

    private void sendEvent(String action, @Nullable String ignored) {
        sendBroadcast(new Intent(action).setPackage(getPackageName()));
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

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        requestGeneration++;
        extractorExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
