package wallet.games;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/** Shared lifecycle, theme-aware board rendering and controls for independent games. */
public abstract class ArcadeGameView extends View {
    public static final String GAME_TETRIS = "tetris";
    public static final String GAME_RACING = "racing";
    public static final String GAME_TANK = "tank";
    public static final String GAME_SNAKE = "snake";

    public interface Listener {
        void onScoreChanged(int score, int level);
        void onGameOver(int score);
    }

    public static boolean isSupported(String key) {
        return GAME_TETRIS.equals(key) || GAME_RACING.equals(key)
                || GAME_TANK.equals(key) || GAME_SNAKE.equals(key);
    }

    protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Random random = new Random();
    protected int columns;
    protected int rows;
    protected int score;
    protected int level = 1;
    protected int ticks;
    protected int colorBackground;
    protected int colorPrimary;
    protected int colorSecondary;
    protected int colorAccent;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running;
    private boolean gameOver;
    private boolean initialized;
    private float downX;
    private float downY;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running || gameOver) return;
            ticks++;
            onGameTick();
            invalidate();
            if (running && !gameOver) handler.postDelayed(this, getTickDelay());
        }
    };

    public ArcadeGameView(Context context) {
        super(context);
        initializeView();
    }
    public ArcadeGameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initializeView();
    }
    public ArcadeGameView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeView();
    }

    private void initializeView() {
        setFocusable(true);
        resolveThemeColors();
    }

    public final void configure(Listener callback, Bundle savedState) {
        listener = callback;
        resolveThemeColors();
        if (savedState == null) {
            restartGame();
            return;
        }
        columns = savedState.getInt("columns", 0);
        rows = savedState.getInt("rows", 0);
        score = savedState.getInt("score", 0);
        level = savedState.getInt("level", 1);
        ticks = savedState.getInt("ticks", 0);
        gameOver = savedState.getBoolean("gameOver", false);
        running = false;
        initialized = true;
        onBoostChanged(false);
        onRestoreGameState(savedState);
        if (listener != null) {
            listener.onScoreChanged(score, level);
            if (gameOver) listener.onGameOver(score);
        }
        invalidate();
    }

    public final boolean hasActiveGame() {
        return initialized && !gameOver;
    }

    public final void saveGameState(Bundle outState) {
        if (outState == null || !initialized) return;
        outState.putInt("columns", columns);
        outState.putInt("rows", rows);
        outState.putInt("score", score);
        outState.putInt("level", level);
        outState.putInt("ticks", ticks);
        outState.putBoolean("gameOver", gameOver);
        onSaveGameState(outState);
    }

    protected void onSaveGameState(Bundle outState) { }
    protected void onRestoreGameState(Bundle savedState) { }

    public final void restartGame() {
        handler.removeCallbacks(ticker);
        score = 0;
        level = 1;
        ticks = 0;
        gameOver = false;
        running = true;
        onInitializeGame();
        initialized = true;
        if (listener != null) listener.onScoreChanged(score, level);
        invalidate();
        if (running && !gameOver) handler.postDelayed(ticker, getTickDelay());
    }

    public final void pauseGame() {
        running = false;
        onBoostChanged(false);
        handler.removeCallbacks(ticker);
    }

    public final void resumeGame() {
        if (!initialized || gameOver || running) return;
        running = true;
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, getTickDelay());
    }

    public final void moveLeft() {
        performControl(this::onMoveLeft);
    }
    public final void moveRight() {
        performControl(this::onMoveRight);
    }
    public final void moveUp() {
        performControl(this::onMoveUp);
    }
    public final void moveDown() {
        performControl(this::onMoveDown);
    }
    public final void action() {
        performControl(this::onAction);
    }

    public final void setBoost(boolean enabled) {
        if (!running || gameOver) enabled = false;
        onBoostChanged(enabled);
        rescheduleForSpeedChange();
    }

    protected final void performControl(Runnable control) {
        if (!running || gameOver) return;
        control.run();
        invalidate();
        updateLevelAndNotify();
    }

    private void rescheduleForSpeedChange() {
        if (running && !gameOver) {
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, getTickDelay());
        }
    }

    protected final void addScore(int amount) {
        if (amount == 0) return;
        score = Math.max(0, score + amount);
        updateLevelAndNotify();
    }

    protected final void updateLevelAndNotify() {
        level = Math.min(20, 1 + Math.max(0, score) / getPointsPerLevel());
        if (listener != null) listener.onScoreChanged(score, level);
    }

    protected final void endGame() {
        if (gameOver) return;
        gameOver = true;
        running = false;
        onBoostChanged(false);
        handler.removeCallbacks(ticker);
        if (listener != null) listener.onGameOver(score);
        invalidate();
    }

    protected final boolean isRunning() {
        return running && !gameOver;
    }
    protected final void refreshBoard() {
        invalidate();
    }

    protected abstract void onInitializeGame();
    protected abstract void onGameTick();
    protected abstract long getTickDelay();
    protected abstract int getPointsPerLevel();
    protected abstract void onMoveLeft();
    protected abstract void onMoveRight();
    protected abstract void onMoveUp();
    protected abstract void onMoveDown();
    protected abstract void onAction();
    protected void onBoostChanged(boolean enabled) { }
    protected void onTap(float x, float width) { }
    protected abstract void onDrawGame(Canvas canvas, float left, float top, float cell);

    private void resolveThemeColors() {
        colorBackground = themeColor(android.R.attr.colorBackground);
        colorPrimary = themeColor(android.R.attr.textColorPrimary);
        colorSecondary = themeColor(android.R.attr.textColorSecondary);
        colorAccent = themeColor(android.R.attr.colorAccent);
    }

    private int themeColor(int attribute) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    ColorStateList list = getResources().getColorStateList(value.resourceId, getContext().getTheme());
                    return list.getDefaultColor();
                } catch (RuntimeException ignored) { }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
        }
        return androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.transparent);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            float threshold = Math.max(24f, Math.min(getWidth(), getHeight()) * 0.07f);
            if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) {
                if (Math.abs(dx) > Math.abs(dy)) {
                    if (dx < 0) moveLeft();
                    else moveRight();
                } else {
                    if (dy < 0) moveUp();
                    else moveDown();
                }
            } else if (isRunning()) {
                onTap(event.getX(), getWidth());
            }
            return true;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        resolveThemeColors();
        if (columns <= 0 || rows <= 0) return;
        float cell = Math.min((getWidth() - dp(28)) / (float) columns, (getHeight() - dp(24)) / (float) rows);
        if (cell <= 0) return;
        float boardWidth = columns * cell;
        float boardHeight = rows * cell;
        float left = (getWidth() - boardWidth) / 2f;
        float top = (getHeight() - boardHeight) / 2f;
        RectF boardRect = new RectF(left - dp(5), top - dp(5), left + boardWidth + dp(5), top + boardHeight + dp(5));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withThemeAlpha(colorPrimary, 18));
        canvas.drawRoundRect(boardRect, dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(withThemeAlpha(colorSecondary, 100));
        canvas.drawRoundRect(boardRect, dp(8), dp(8), paint);
        paint.setStrokeWidth(1f);
        paint.setColor(withThemeAlpha(colorSecondary, 55));
        for (int c = 0; c <= columns; c++) canvas.drawLine(left + c * cell, top, left + c * cell, top + boardHeight, paint);
        for (int r = 0; r <= rows; r++) canvas.drawLine(left, top + r * cell, left + boardWidth, top + r * cell, paint);

        onDrawGame(canvas, left, top, cell);

        if (gameOver) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withThemeAlpha(colorBackground, 215));
            canvas.drawRoundRect(boardRect, dp(8), dp(8), paint);
            paint.setColor(colorPrimary);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(20));
            canvas.drawText(getContext().getString(wallet.main.R.string.arcade_game_over), getWidth() / 2f, getHeight() / 2f, paint);
        }
    }

    protected final void drawCell(Canvas canvas, float left, float top, float cell, int x, int y, int color) {
        if (x < 0 || x >= columns || y < 0 || y >= rows) return;
        float gap = Math.max(1f, cell * 0.08f);
        float x1 = left + x * cell + gap;
        float y1 = top + y * cell + gap;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRoundRect(x1, y1, x1 + cell - gap * 2, y1 + cell - gap * 2,
                Math.max(1f, cell * 0.12f), Math.max(1f, cell * 0.12f), paint);
    }

    protected final void drawVehicle(Canvas canvas, float left, float top, float cell, int x, int y, int color) {
        float inset = cell * 0.16f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        float x1 = left + x * cell + inset;
        float y1 = top + y * cell + inset;
        canvas.drawRoundRect(x1, y1, x1 + cell - inset * 2, y1 + cell - inset * 2,
                cell * 0.18f, cell * 0.18f, paint);
        paint.setColor(withThemeAlpha(colorBackground, 180));
        canvas.drawRect(x1 + cell * 0.32f, y1 + cell * 0.18f,
                x1 + cell * 0.68f, y1 + cell * 0.82f, paint);
    }

    protected final void drawTankSprite(Canvas canvas, float left, float top, float cell, int x, int y, int color) {
        float inset = cell * 0.2f;
        float x1 = left + x * cell + inset;
        float y1 = top + y * cell + inset;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(x1, y1, x1 + cell - inset * 2, y1 + cell - inset * 2, paint);
        canvas.drawRect(x1 + cell * 0.38f, y1 - cell * 0.1f,
                x1 + cell * 0.62f, y1 + cell * 0.5f, paint);
    }

    /** Applies opacity only; RGB channels always come from the active Android theme. */
    protected final int withThemeAlpha(int themeColor, int alpha) {
        int safeAlpha = Math.max(0, Math.min(255, alpha));
        return android.graphics.Color.argb(
                safeAlpha,
                android.graphics.Color.red(themeColor),
                android.graphics.Color.green(themeColor),
                android.graphics.Color.blue(themeColor));
    }

    protected final float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        running = false;
        super.onDetachedFromWindow();
    }
}
