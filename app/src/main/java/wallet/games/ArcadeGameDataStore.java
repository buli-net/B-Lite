package wallet.games;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Shared, game-keyed storage so backup and restore never depend on individual game names. */
public final class ArcadeGameDataStore {
    public static final String PREFERENCES_NAME = "arcade_game_data";
    private static final String LEGACY_SCORES = "arcade_game_scores";
    private static final String LEGACY_PROGRESS = "arcade_game_progress";
    private static final String MIGRATION_MARKER = "_legacy_migrated_v1";
    private static final String SCORE_PREFIX = "score.";
    private static final String PROGRESS_PREFIX = "progress.";

    private ArcadeGameDataStore() { }

    public static int getBestScore(Context context, String gameKey) {
        ensureMigrated(context);
        return preferences(context).getInt(scoreKey(gameKey), 0);
    }

    public static void setBestScore(Context context, String gameKey, int score) {
        ensureMigrated(context);
        preferences(context).edit().putInt(scoreKey(gameKey), Math.max(0, score)).apply();
    }

    public static String getSavedProgress(Context context, String gameKey) {
        ensureMigrated(context);
        return preferences(context).getString(progressKey(gameKey), null);
    }

    public static boolean saveProgress(Context context, String gameKey, String encodedProgress) {
        ensureMigrated(context);
        return preferences(context).edit().putString(progressKey(gameKey), encodedProgress).commit();
    }

    public static boolean clearProgress(Context context, String gameKey) {
        ensureMigrated(context);
        return preferences(context).edit().remove(progressKey(gameKey)).commit();
    }

    /** Removes all stored records for a game that is deliberately retired. */
    public static boolean removeGame(Context context, String gameKey) {
        ensureMigrated(context);
        String key = requireGameKey(gameKey);
        return preferences(context).edit()
                .remove(scoreKey(key))
                .remove(progressKey(key))
                .commit();
    }

    /** Migrates the former score and progress files once, preserving existing game data. */
    public static synchronized void ensureMigrated(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences current = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (current.getBoolean(MIGRATION_MARKER, false)) return;

        SharedPreferences oldScores = app.getSharedPreferences(LEGACY_SCORES, Context.MODE_PRIVATE);
        SharedPreferences oldProgress = app.getSharedPreferences(LEGACY_PROGRESS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = current.edit();
        for (Map.Entry<String, ?> entry : oldScores.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!isValidGameKey(key) || !(value instanceof Integer)) continue;
            String target = scoreKey(key);
            if (!current.contains(target)) editor.putInt(target, Math.max(0, (Integer) value));
        }
        for (Map.Entry<String, ?> entry : oldProgress.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || !(value instanceof String) || !key.startsWith("saved_game_")) continue;
            String gameKey = key.substring("saved_game_".length());
            if (!isValidGameKey(gameKey)) continue;
            String target = progressKey(gameKey);
            if (!current.contains(target)) editor.putString(target, (String) value);
        }
        editor.putBoolean(MIGRATION_MARKER, true);
        if (!editor.commit()) {
            throw new IllegalStateException("Unable to migrate saved game data");
        }
        oldScores.edit().clear().commit();
        oldProgress.edit().clear().commit();
    }

    /** Marks migration complete after a restore so stale legacy data cannot reappear. */
    public static synchronized boolean markRestored(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences current = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        boolean saved = current.edit().putBoolean(MIGRATION_MARKER, true).commit();
        if (saved) {
            app.getSharedPreferences(LEGACY_SCORES, Context.MODE_PRIVATE).edit().clear().commit();
            app.getSharedPreferences(LEGACY_PROGRESS, Context.MODE_PRIVATE).edit().clear().commit();
        }
        return saved;
    }

    /** Only game records are exported; the migration marker is implementation metadata. */
    public static boolean isBackupKey(String key) {
        return isValidBackupKey(key);
    }

    public static boolean isValidBackupKey(String key) {
        if (key == null || key.length() > 128 || MIGRATION_MARKER.equals(key)) return false;
        String id;
        if (key.startsWith(SCORE_PREFIX)) {
            id = key.substring(SCORE_PREFIX.length());
        } else if (key.startsWith(PROGRESS_PREFIX)) {
            id = key.substring(PROGRESS_PREFIX.length());
        } else {
            return false;
        }
        return isValidGameKey(id);
    }

    private static boolean isValidGameKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 96) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    private static String scoreKey(String gameKey) {
        return SCORE_PREFIX + requireGameKey(gameKey);
    }

    private static String progressKey(String gameKey) {
        return PROGRESS_PREFIX + requireGameKey(gameKey);
    }

    private static String requireGameKey(String key) {
        if (!isValidGameKey(key)) throw new IllegalArgumentException("Invalid game key");
        return key;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
