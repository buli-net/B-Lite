package wallet.games;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Hosts one self-contained offline arcade game. */
public final class ArcadeGameActivity extends BaseActivity implements ArcadeGameView.Listener {
    public static final String EXTRA_GAME = "arcade_game";
    private static final String PREFS = "arcade_game_scores";

    private String game = ArcadeGameView.GAME_TETRIS;
    private ArcadeGameView gameView;
    private TextView scoreView;
    private TextView bestView;
    private Button pauseButton;
    private Button actionButton;
    private boolean paused;
    private boolean gameOverDialogShown;
    private int bestScore;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        game = getIntent().getStringExtra(EXTRA_GAME);
        if (!ArcadeGameView.isSupported(game)) game = ArcadeGameView.GAME_TETRIS;
        setContentView(R.layout.activity_arcade_game);

        Toolbar toolbar = findViewById(R.id.arcadeGameToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(titleForGame());
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        scoreView = findViewById(R.id.arcadeScoreValue);
        bestView = findViewById(R.id.arcadeBestValue);
        pauseButton = findViewById(R.id.arcadePauseButton);
        actionButton = findViewById(R.id.arcadeActionButton);
        bestScore = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(game, 0);
        bestView.setText(getString(R.string.arcade_best_score, bestScore));
        actionButton.setText(actionLabel());
        if (ArcadeGameView.GAME_SNAKE.equals(game) || ArcadeGameView.GAME_RACING.equals(game)) {
            actionButton.setVisibility(android.view.View.GONE);
        }
        Button upButton = findViewById(R.id.arcadeUpButton);
        if (ArcadeGameView.GAME_TETRIS.equals(game)) {
            upButton.setText("↻");
            upButton.setContentDescription(getString(R.string.arcade_rotate));
        } else {
            upButton.setText("▲");
            upButton.setContentDescription(getString(R.string.arcade_up));
        }

        gameView = findViewById(R.id.arcadeGameView);
        gameView.configure(game, this);
        findViewById(R.id.arcadeLeftButton).setOnClickListener(v -> gameView.moveLeft());
        findViewById(R.id.arcadeRightButton).setOnClickListener(v -> gameView.moveRight());
        findViewById(R.id.arcadeUpButton).setOnClickListener(v -> gameView.moveUp());
        findViewById(R.id.arcadeDownButton).setOnClickListener(v -> gameView.moveDown());
        actionButton.setOnClickListener(v -> gameView.action());
        findViewById(R.id.arcadeRestartButton).setOnClickListener(v -> restartGame());
        pauseButton.setOnClickListener(v -> togglePause());
    }

    private String titleForGame() {
        if (ArcadeGameView.GAME_RACING.equals(game)) return getString(R.string.arcade_racing_title);
        if (ArcadeGameView.GAME_TANK.equals(game)) return getString(R.string.arcade_tank_title);
        if (ArcadeGameView.GAME_SNAKE.equals(game)) return getString(R.string.arcade_snake_title);
        return getString(R.string.arcade_tetris_title);
    }

    private String actionLabel() {
        if (ArcadeGameView.GAME_TANK.equals(game)) return getString(R.string.arcade_fire);
        if (ArcadeGameView.GAME_TETRIS.equals(game)) return getString(R.string.arcade_hard_drop);
        if (ArcadeGameView.GAME_RACING.equals(game)) return getString(R.string.arcade_accelerate);
        return getString(R.string.arcade_action);
    }

    private void togglePause() {
        paused = !paused;
        if (paused) gameView.pauseGame(); else gameView.resumeGame();
        pauseButton.setText(paused ? R.string.arcade_resume : R.string.arcade_pause);
    }

    private void restartGame() {
        paused = false;
        gameOverDialogShown = false;
        pauseButton.setText(R.string.arcade_pause);
        gameView.restartGame();
    }

    @Override public void onScoreChanged(int score) {
        scoreView.setText(getString(R.string.arcade_score, score));
        if (score > bestScore) {
            bestScore = score;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(game, bestScore).apply();
            bestView.setText(getString(R.string.arcade_best_score, bestScore));
        }
    }

    @Override public void onGameOver(int score) {
        if (gameOverDialogShown || isFinishing()) return;
        gameOverDialogShown = true;
        pauseButton.setText(R.string.arcade_resume);
        new AlertDialog.Builder(this)
                .setTitle(R.string.arcade_game_over)
                .setMessage(getString(R.string.arcade_final_score, score))
                .setPositiveButton(R.string.arcade_play_again, (dialog, which) -> restartGame())
                .setNegativeButton(R.string.arcade_back_to_games, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> gameOverDialogShown = false)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (gameView != null && !paused) gameView.resumeGame();
    }

    @Override protected void onPause() {
        if (gameView != null) gameView.pauseGame();
        super.onPause();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
