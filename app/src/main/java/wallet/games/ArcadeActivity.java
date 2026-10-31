package wallet.games;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Offline mini-game selection screen. */
public final class ArcadeActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_arcade);
        Toolbar toolbar = findViewById(R.id.arcadeToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.arcade_title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        bindGame(R.id.arcadeGameTetris, R.string.arcade_tetris_title, R.string.arcade_game_tetris);
        bindGame(R.id.arcadeGameRacing, R.string.arcade_racing_title, R.string.arcade_game_racing);
        bindGame(R.id.arcadeGameTank, R.string.arcade_tank_title, R.string.arcade_game_tank);
        bindGame(R.id.arcadeGameSnake, R.string.arcade_snake_title, R.string.arcade_game_snake);
    }

    private void bindGame(int cardId, int titleId, int descriptionId) {
        View card = findViewById(cardId);
        TextView title = card.findViewById(R.id.arcadeCardTitle);
        TextView description = card.findViewById(R.id.arcadeCardDescription);
        title.setText(titleId);
        description.setText(descriptionId);
        card.setOnClickListener(v -> {
            String game = getGameKey(cardId);
            Intent intent = new Intent(this, ArcadeGameActivity.class);
            intent.putExtra(ArcadeGameActivity.EXTRA_GAME, game);
            startActivity(intent);
        });
    }

    private String getGameKey(int cardId) {
        if (cardId == R.id.arcadeGameTetris) return ArcadeGameView.GAME_TETRIS;
        if (cardId == R.id.arcadeGameRacing) return ArcadeGameView.GAME_RACING;
        if (cardId == R.id.arcadeGameTank) return ArcadeGameView.GAME_TANK;
        return ArcadeGameView.GAME_SNAKE;
    }
}
