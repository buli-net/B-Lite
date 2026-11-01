package wallet.games;

import wallet.main.R;

/** Dedicated offline tetris game screen. */
public final class TetrisActivity extends ArcadeGameBaseActivity {
    @Override protected String getGameKey() {
        return ArcadeGameView.GAME_TETRIS;
    }

    @Override protected int getGameLayout() {
        return R.layout.activity_game_tetris;
    }
}
