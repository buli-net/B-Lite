package wallet.games;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Hosts one self-contained offline arcade game. */
public abstract class ArcadeGameBaseActivity extends BaseActivity implements ArcadeGameView.Listener {
    protected abstract String getGameKey();
    protected abstract int getGameLayout();
    private static final int SAVE_VERSION = 1;

    private String game = ArcadeGameView.GAME_TETRIS;
    private ArcadeGameView gameView;
    private TextView scoreView;
    private TextView bestView;
    private TextView levelView;
    private Button pauseButton;
    private Button actionButton;
    private boolean paused;
    private boolean gameOverDialogShown;
    private int bestScore;
    private Bundle pendingSavedGameState;
    private boolean pendingSavedPaused;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        game = getGameKey();
        if (!ArcadeGameView.isSupported(game)) {
            throw new IllegalStateException("Unsupported game activity configuration");
        }
        setContentView(getGameLayout());

        Toolbar toolbar = findViewById(R.id.arcadeGameToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        scoreView = findViewById(R.id.arcadeScoreValue);
        bestView = findViewById(R.id.arcadeBestValue);
        levelView = findViewById(R.id.arcadeLevelValue);
        pauseButton = findViewById(R.id.arcadePauseButton);
        actionButton = findViewById(R.id.arcadeActionButton);
        bestScore = ArcadeGameDataStore.getBestScore(this, game);
        bestView.setText(getString(R.string.arcade_best_score, bestScore));
        gameView = findViewById(R.id.arcadeGameView);
        if (state != null) {
            Bundle restoredGameState = state.getBundle("arcade_saved_game_state");
            if (state.getBoolean("arcade_pending_restore_choice", false)
                    && restoredGameState != null) {
                pendingSavedGameState = restoredGameState;
                pendingSavedPaused = restoredGameState.getBoolean("activityPaused", false);
                paused = true;
                gameView.configure(this, restoredGameState);
                gameView.pauseGame();
            } else {
                paused = state.getBoolean("arcade_saved_paused", false);
                gameView.configure(this, restoredGameState);
            }
        } else {
            Bundle savedProgress = loadPersistentGameState();
            if (savedProgress != null) {
                pendingSavedGameState = savedProgress;
                pendingSavedPaused = savedProgress.getBoolean("activityPaused", false);
                paused = true;
                gameView.configure(this, savedProgress);
                gameView.pauseGame();
            } else {
                paused = false;
                gameView.configure(this, null);
            }
        }
        pauseButton.setText(paused ? R.string.arcade_resume : R.string.arcade_pause);
        findViewById(R.id.arcadeLeftButton).setOnClickListener(v -> gameView.moveLeft());
        findViewById(R.id.arcadeRightButton).setOnClickListener(v -> gameView.moveRight());
        findViewById(R.id.arcadeUpButton).setOnClickListener(v -> gameView.moveUp());
        findViewById(R.id.arcadeDownButton).setOnClickListener(v -> gameView.moveDown());
        if (ArcadeGameView.GAME_SNAKE.equals(game)
                || ArcadeGameView.GAME_RACING.equals(game)) {
            actionButton.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    gameView.setBoost(true);
                    view.setPressed(true);
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    gameView.setBoost(false);
                    view.setPressed(false);
                    return true;
                }
                return true;
            });
        } else {
            actionButton.setOnClickListener(v -> gameView.action());
        }
        findViewById(R.id.arcadeRestartButton).setOnClickListener(v -> restartGame());
        pauseButton.setOnClickListener(v -> togglePause());
        if (pendingSavedGameState != null) showSavedGameDialog();
    }

    private void showSavedGameDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.arcade_saved_game_title)
                .setMessage(R.string.arcade_saved_game_message)
                .setPositiveButton(R.string.arcade_continue_game, (dialog, which) -> {
                    paused = pendingSavedPaused;
                    pendingSavedGameState = null;
                    pauseButton.setText(paused ? R.string.arcade_resume : R.string.arcade_pause);
                    if (paused) gameView.pauseGame();
                    else gameView.resumeGame();
                })
                .setNegativeButton(R.string.arcade_new_game, (dialog, which) -> {
                    pendingSavedGameState = null;
                    clearPersistentGameState();
                    paused = false;
                    pauseButton.setText(R.string.arcade_pause);
                    gameView.restartGame();
                })
                .setCancelable(false)
                .show();
    }

    private void togglePause() {
        paused = !paused;
        if (paused) gameView.setBoost(false);
        if (paused) gameView.pauseGame();
        else gameView.resumeGame();
        pauseButton.setText(paused ? R.string.arcade_resume : R.string.arcade_pause);
        if (paused) persistGameState();
    }

    private void restartGame() {
        if (gameView != null) gameView.setBoost(false);
        paused = false;
        gameOverDialogShown = false;
        pendingSavedGameState = null;
        clearPersistentGameState();
        pauseButton.setText(R.string.arcade_pause);
        gameView.restartGame();
    }

    @Override
    public void onScoreChanged(int score, int level) {
        scoreView.setText(getString(R.string.arcade_score, score));
        levelView.setText(getString(R.string.arcade_level, level));
        if (score > bestScore) {
            bestScore = score;
            ArcadeGameDataStore.setBestScore(this, game, bestScore);
            bestView.setText(getString(R.string.arcade_best_score, bestScore));
        }
    }

    @Override
    public void onGameOver(int score) {
        clearPersistentGameState();
        pendingSavedGameState = null;
        if (gameOverDialogShown || isFinishing()) return;
        gameOverDialogShown = true;
        pauseButton.setText(R.string.arcade_resume);
        new AlertDialog.Builder(this)
                .setTitle(R.string.arcade_game_over)
                .setMessage(getString(R.string.arcade_final_score, score))
                .setPositiveButton(R.string.arcade_play_again, (dialog, which) -> restartGame())
                .setNegativeButton(R.string.arcade_exit, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> gameOverDialogShown = false)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Bundle gameState = new Bundle();
        if (gameView != null) gameView.saveGameState(gameState);
        if (pendingSavedGameState != null) {
            gameState.putBoolean("activityPaused", pendingSavedPaused);
        }
        outState.putBundle("arcade_saved_game_state", gameState);
        outState.putBoolean("arcade_saved_paused", paused);
        outState.putBoolean("arcade_pending_restore_choice", pendingSavedGameState != null);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView != null && !paused) gameView.resumeGame();
    }

    @Override
    protected void onPause() {
        if (gameView != null) {
            gameView.pauseGame();
            persistGameState();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (gameView != null) persistGameState();
        super.onStop();
    }

    private void persistGameState() {
        if (gameView == null) return;
        if (!gameView.hasActiveGame()) {
            clearPersistentGameState();
            return;
        }

        Bundle progress = new Bundle();
        gameView.saveGameState(progress);
        progress.putInt("saveVersion", SAVE_VERSION);
        progress.putString("savedGame", game);
        progress.putBoolean("activityPaused", pendingSavedGameState != null
                ? pendingSavedPaused : paused);

        try {
            String encoded = encodeGameState(progress);
            ArcadeGameDataStore.saveProgress(this, game, encoded);
        } catch (JSONException | RuntimeException exception) {
            clearPersistentGameState();
        }
    }

    private Bundle loadPersistentGameState() {
        String encoded = ArcadeGameDataStore.getSavedProgress(this, game);
        if (encoded == null || encoded.isEmpty()) return null;

        try {
            JSONObject json = new JSONObject(encoded);
            Bundle progress = decodeGameState(json);
            if (progress.getInt("saveVersion", -1) != SAVE_VERSION
                    || !game.equals(progress.getString("savedGame"))
                    || !progress.containsKey("score")
                    || !progress.containsKey("level")) {
                clearPersistentGameState();
                return null;
            }
            return progress;
        } catch (JSONException | RuntimeException exception) {
            clearPersistentGameState();
            return null;
        }
    }

    private String encodeGameState(Bundle state) throws JSONException {
        JSONObject json = new JSONObject();
        for (String key : state.keySet()) {
            Object value = state.get(key);
            if (value instanceof int[]) {
                JSONArray array = new JSONArray();
                for (int item : (int[]) value) array.put(item);
                json.put(key, array);
            } else if (value instanceof Integer || value instanceof Boolean
                    || value instanceof String || value instanceof Long
                    || value instanceof Double) {
                json.put(key, value);
            } else {
                throw new JSONException("Unsupported saved game state value");
            }
        }
        return json.toString();
    }

    private Bundle decodeGameState(JSONObject json) throws JSONException {
        Bundle progress = new Bundle();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (array.length() > 10000) throw new JSONException("Saved game array is too large");
                int[] items = new int[array.length()];
                for (int i = 0; i < array.length(); i++) items[i] = array.getInt(i);
                progress.putIntArray(key, items);
            } else if (value instanceof Integer) {
                progress.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                progress.putLong(key, (Long) value);
            } else if (value instanceof Double) {
                progress.putDouble(key, (Double) value);
            } else if (value instanceof Boolean) {
                progress.putBoolean(key, (Boolean) value);
            } else if (value instanceof String) {
                progress.putString(key, (String) value);
            } else {
                throw new JSONException("Unsupported saved game state value");
            }
        }
        return progress;
    }

    private void clearPersistentGameState() {
        ArcadeGameDataStore.clearProgress(this, game);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
