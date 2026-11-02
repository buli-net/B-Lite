package wallet.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Paint;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

/** Lane racing obstacles, steering and hold-to-boost mechanics. */
public final class RacingGameView extends ArcadeGameView {
    private final List<Point> obstacles = new ArrayList<>();
    private int lane;
    private int speedBoostTicks;
    private boolean boost;

    public RacingGameView(Context context) {
        super(context);
    }
    public RacingGameView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public RacingGameView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onInitializeGame() {
        columns = 7;
        rows = 14;
        obstacles.clear();
        lane = columns / 2;
        speedBoostTicks = 0;
        boost = false;
    }

    @Override
    protected void onGameTick() {
        if (speedBoostTicks > 0) speedBoostTicks--;
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Point obstacle = obstacles.get(i);
            obstacle.y++;
            if (obstacle.y >= rows - 2 && obstacle.x == lane) {
                endGame();
                return;
            }
            if (obstacle.y >= rows) {
                obstacles.remove(i);
                score += 5;
            }
        }
        if (random.nextFloat() < 0.46f) {
            int newLane = random.nextInt(columns);
            boolean occupied = false;
            for (Point obstacle : obstacles) if (obstacle.y < 3 && obstacle.x == newLane) occupied = true;
            if (!occupied) obstacles.add(new Point(newLane, 0));
        }
        if (ticks % 2 == 0) addScore(1);
    }

    @Override
    protected long getTickDelay() {
        return Math.max(180, 620 - (level - 1L) * 26L - ((boost || speedBoostTicks > 0) ? 90 : 0));
    }

    @Override
    protected int getPointsPerLevel() {
        return 500;
    }

    @Override
    protected void onMoveLeft() {
        lane = Math.max(0, lane - 1);
    }
    @Override
    protected void onMoveRight() {
        lane = Math.min(columns - 1, lane + 1);
    }
    @Override
    protected void onMoveUp() {
        speedBoostTicks = 12;
    }
    @Override
    protected void onMoveDown() {
        speedBoostTicks = 0;
    }
    @Override
    protected void onAction() {
        speedBoostTicks = 12;
    }
    @Override
    protected void onBoostChanged(boolean enabled) {
        boost = enabled;
    }

    @Override
    protected void onTap(float x, float width) {
        if (x < width / 2f) onMoveLeft();
        else onMoveRight();
        refreshBoard();
    }


    @Override
    protected void onSaveGameState(android.os.Bundle outState) {
        int[] positions = new int[obstacles.size() * 2];
        for (int i = 0; i < obstacles.size(); i++) {
            positions[i * 2] = obstacles.get(i).x;
            positions[i * 2 + 1] = obstacles.get(i).y;
        }
        outState.putIntArray("racing_obstacles", positions);
        outState.putInt("racing_lane", lane);
        outState.putInt("racing_boost_ticks", speedBoostTicks);
    }

    @Override
    protected void onRestoreGameState(android.os.Bundle state) {
        columns = 7;
        rows = 14;
        obstacles.clear();
        int[] positions = state.getIntArray("racing_obstacles");
        if (positions != null) {
            for (int i = 0; i + 1 < positions.length; i += 2) obstacles.add(new Point(positions[i], positions[i + 1]));
        }
        lane = Math.max(0, Math.min(columns - 1, state.getInt("racing_lane", columns / 2)));
        speedBoostTicks = state.getInt("racing_boost_ticks", 0);
        boost = false;
    }

    @Override
    protected void onDrawGame(Canvas canvas, float left, float top, float cell) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, cell * 0.07f));
        paint.setColor(withThemeAlpha(colorSecondary, 100));
        for (int c = 1; c < columns; c++) {
            canvas.drawLine(left + c * cell, top, left + c * cell, top + rows * cell, paint);
        }
        for (Point obstacle : obstacles) drawVehicle(canvas, left, top, cell, obstacle.x, obstacle.y, colorSecondary);
        drawVehicle(canvas, left, top, cell, lane, rows - 2, colorAccent);
    }
}
