package wallet.games;

import wallet.main.R;

/** Dedicated offline tank game screen. */
public final class TankActivity extends ArcadeGameBaseActivity {
    @Override protected String getGameKey() {
        return ArcadeGameView.GAME_TANK;
    }

    @Override protected int getGameLayout() {
        return R.layout.activity_game_tank;
    }
}
