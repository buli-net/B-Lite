package wallet.games;

import wallet.main.R;

/** Dedicated offline snake game screen. */
public final class SnakeActivity extends ArcadeGameBaseActivity {
    @Override protected String getGameKey() {
        return ArcadeGameView.GAME_SNAKE;
    }

    @Override protected int getGameLayout() {
        return R.layout.activity_game_snake;
    }
}
