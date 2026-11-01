package wallet.games;

import wallet.main.R;

/** Dedicated offline racing game screen. */
public final class RacingActivity extends ArcadeGameBaseActivity {
    @Override protected String getGameKey() {
        return ArcadeGameView.GAME_RACING;
    }

    @Override protected int getGameLayout() {
        return R.layout.activity_game_racing;
    }
}
