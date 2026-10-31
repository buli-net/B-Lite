package wallet.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Lightweight pixel-style game renderer; colors are resolved from the active system theme. */
public final class ArcadeGameView extends View {
    public static final String GAME_TETRIS = "tetris";
    public static final String GAME_RACING = "racing";
    public static final String GAME_TANK = "tank";
    public static final String GAME_SNAKE = "snake";

    public interface Listener {
        void onScoreChanged(int score, int level);
        void onGameOver(int score);
    }

    private static final int[][][] PIECES = {
            {{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{1,1,0,0},{0,0,0,0},{0,0,0,0}},
            {{1,1,0,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{1,0,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,0,1,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}}
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running || gameOver) return;
            tick();
            invalidate();
            if (running && !gameOver) handler.postDelayed(this, tickDelay());
        }
    };

    private Listener listener;
    private String game = GAME_TETRIS;
    private int columns = 10;
    private int rows = 20;
    private int[][] board = new int[20][10];
    private int[][] activePiece;
    private int pieceX;
    private int pieceY;
    private int lane;
    private int playerX;
    private int playerY;
    private int directionX = 1;
    private int directionY;
    private int score;
    private int level = 1;
    private int ticks;
    private int speedBoostTicks;
    private boolean snakeBoost;
    private boolean racingBoost;
    private int colorBackground;
    private int colorPrimary;
    private int colorSecondary;
    private int colorAccent;
    private boolean running;
    private boolean gameOver;
    private boolean initialized;
    private float downX;
    private float downY;
    private final List<Point> objects = new ArrayList<>();
    private final List<Point> snake = new ArrayList<>();
    private Point food = new Point();

    public ArcadeGameView(Context context) { super(context); init(); }
    public ArcadeGameView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ArcadeGameView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setFocusable(true);
        resolveThemeColors();
    }

    public void configure(String gameKey, Listener callback) {
        game = isSupported(gameKey) ? gameKey : GAME_TETRIS;
        listener = callback;
        resolveThemeColors();
        restartGame();
    }

    public static boolean isSupported(String key) {
        return GAME_TETRIS.equals(key) || GAME_RACING.equals(key)
                || GAME_TANK.equals(key) || GAME_SNAKE.equals(key);
    }

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
                    android.content.res.ColorStateList list = getResources().getColorStateList(value.resourceId, getContext().getTheme());
                    return list.getDefaultColor();
                } catch (RuntimeException ignored) { }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
        }
        return android.graphics.Color.TRANSPARENT;
    }

    public void restartGame() {
        handler.removeCallbacks(ticker);
        score = 0;
        level = 1;
        ticks = 0;
        speedBoostTicks = 0;
        snakeBoost = false;
        racingBoost = false;
        bullets.clear();
        gameOver = false;
        running = true;
        objects.clear();
        snake.clear();
        if (GAME_TETRIS.equals(game)) {
            columns = 10; rows = 20; board = new int[rows][columns]; spawnPiece();
        } else if (GAME_RACING.equals(game)) {
            columns = 7; rows = 14; board = new int[rows][columns]; lane = columns / 2;
        } else if (GAME_TANK.equals(game)) {
            columns = 11; rows = 16; board = new int[rows][columns]; playerX = columns / 2; playerY = rows - 2;
        } else {
            columns = 18; rows = 24; board = new int[rows][columns];
            int cx = columns / 2; int cy = rows / 2;
            snake.add(new Point(cx, cy)); snake.add(new Point(cx - 1, cy)); snake.add(new Point(cx - 2, cy));
            directionX = 1; directionY = 0; placeFood();
        }
        initialized = true;
        if (listener != null) listener.onScoreChanged(score, level);
        invalidate();
        handler.postDelayed(ticker, tickDelay());
    }

    public void pauseGame() {
        running = false;
        snakeBoost = false;
        racingBoost = false;
        handler.removeCallbacks(ticker);
    }

    public void resumeGame() {
        if (!initialized || gameOver || running) return;
        running = true;
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, tickDelay());
    }

    public void moveLeft() {
        if (!canControl()) return;
        if (GAME_SNAKE.equals(game)) setSnakeDirection(-1, 0);
        else if (GAME_RACING.equals(game)) lane = Math.max(0, lane - 1);
        else if (GAME_TANK.equals(game)) playerX = Math.max(1, playerX - 1);
        else movePiece(-1, 0);
        invalidate();
    }

    public void moveRight() {
        if (!canControl()) return;
        if (GAME_SNAKE.equals(game)) setSnakeDirection(1, 0);
        else if (GAME_RACING.equals(game)) lane = Math.min(columns - 1, lane + 1);
        else if (GAME_TANK.equals(game)) playerX = Math.min(columns - 2, playerX + 1);
        else movePiece(1, 0);
        invalidate();
    }

    public void moveUp() {
        if (!canControl()) return;
        if (GAME_SNAKE.equals(game)) setSnakeDirection(0, -1);
        else if (GAME_TETRIS.equals(game)) hardDropPiece();
        else if (GAME_TANK.equals(game)) playerY = Math.max(rows / 2, playerY - 1);
        else if (GAME_RACING.equals(game)) speedBoostTicks = 12;
        invalidate();
        notifyScore();
    }

    public void moveDown() {
        if (!canControl()) return;
        if (GAME_SNAKE.equals(game)) setSnakeDirection(0, 1);
        else if (GAME_TETRIS.equals(game)) dropPieceOne();
        else if (GAME_TANK.equals(game)) playerY = Math.min(rows - 2, playerY + 1);
        else if (GAME_RACING.equals(game)) speedBoostTicks = 0;
        invalidate();
        notifyScore();
    }

    public void action() {
        if (!canControl()) return;
        if (GAME_TETRIS.equals(game)) rotatePiece();
        else if (GAME_TANK.equals(game)) fire();
        else if (GAME_RACING.equals(game)) speedBoostTicks = 12;
        invalidate();
        notifyScore();
    }

    public void setSnakeBoost(boolean enabled) {
        snakeBoost = GAME_SNAKE.equals(game) && enabled && running && !gameOver;
        rescheduleForBoost();
    }

    public void setRacingBoost(boolean enabled) {
        racingBoost = GAME_RACING.equals(game) && enabled && running && !gameOver;
        rescheduleForBoost();
    }

    private void rescheduleForBoost() {
        if (running && !gameOver) {
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, tickDelay());
        }
    }

    private boolean canControl() { return running && !gameOver; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            float threshold = Math.max(24f, Math.min(getWidth(), getHeight()) * 0.07f);
            if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) {
                if (Math.abs(dx) > Math.abs(dy)) { if (dx < 0) moveLeft(); else moveRight(); }
                else { if (dy < 0) moveUp(); else moveDown(); }
            } else if (GAME_TANK.equals(game)) action();
            else if (GAME_TETRIS.equals(game)) action();
            else if (GAME_RACING.equals(game)) { if (event.getX() < getWidth() / 2f) moveLeft(); else moveRight(); }
            return true;
        }
        return true;
    }

    private long tickDelay() {
        int gainedLevels = Math.max(0, level - 1);
        if (GAME_SNAKE.equals(game)) return Math.max(120, 520 - gainedLevels * 24 - (snakeBoost ? 170 : 0));
        if (GAME_TANK.equals(game)) return Math.max(190, 600 - gainedLevels * 24);
        if (GAME_RACING.equals(game)) return Math.max(180, 620 - gainedLevels * 26 - ((racingBoost || speedBoostTicks > 0) ? 90 : 0));
        return Math.max(190, 720 - gainedLevels * 30);
    }

    public int getPointsPerLevel() { return GAME_TETRIS.equals(game) ? 1000 : 500; }

    private int calculateLevel() {
        return Math.min(20, 1 + Math.max(0, score) / getPointsPerLevel());
    }


    private void tick() {
        ticks++;
        if (GAME_TETRIS.equals(game)) tickTetris();
        else if (GAME_RACING.equals(game)) tickRacing();
        else if (GAME_TANK.equals(game)) tickTank();
        else tickSnake();
    }

    private void tickTetris() {
        if (!movePiece(0, 1)) {
            lockPiece();
            clearLines();
            spawnPiece();
            if (collides(activePiece, pieceX, pieceY)) endGame();
        }
    }

    private void spawnPiece() {
        activePiece = copy(PIECES[random.nextInt(PIECES.length)]);
        pieceX = 3; pieceY = -1;
        if (collides(activePiece, pieceX, pieceY)) endGame();
    }

    private boolean movePiece(int dx, int dy) {
        if (activePiece == null) return false;
        if (collides(activePiece, pieceX + dx, pieceY + dy)) return false;
        pieceX += dx; pieceY += dy; return true;
    }

    private boolean collides(int[][] piece, int x, int y) {
        if (piece == null) return true;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (piece[r][c] == 0) continue;
            int bx = x + c, by = y + r;
            if (bx < 0 || bx >= columns || by >= rows) return true;
            if (by >= 0 && board[by][bx] != 0) return true;
        }
        return false;
    }

    private void lockPiece() {
        if (activePiece == null) return;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            int bx = pieceX + c, by = pieceY + r;
            if (activePiece[r][c] != 0 && by >= 0 && by < rows && bx >= 0 && bx < columns) board[by][bx] = 1;
        }
    }

    private void clearLines() {
        int cleared = 0;
        for (int r = rows - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < columns; c++) if (board[r][c] == 0) { full = false; break; }
            if (full) {
                cleared++;
                for (int y = r; y > 0; y--) System.arraycopy(board[y - 1], 0, board[y], 0, columns);
                for (int c = 0; c < columns; c++) board[0][c] = 0;
                r++;
            }
        }
        if (cleared > 0) { score += cleared * cleared * 100; notifyScore(); }
    }

    private void rotatePiece() {
        if (activePiece == null) return;
        int[][] rotated = new int[4][4];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) rotated[c][3 - r] = activePiece[r][c];
        if (!collides(rotated, pieceX, pieceY)) activePiece = rotated;
        else if (!collides(rotated, pieceX - 1, pieceY)) { activePiece = rotated; pieceX--; }
        else if (!collides(rotated, pieceX + 1, pieceY)) { activePiece = rotated; pieceX++; }
    }

    private void dropPieceOne() {
        if (movePiece(0, 1)) score++;
        else tickTetris();
        notifyScore();
    }

    private void hardDropPiece() {
        while (movePiece(0, 1)) score += 2;
        tickTetris();
        notifyScore();
    }

    private void tickRacing() {
        if (speedBoostTicks > 0) speedBoostTicks--;
        for (int i = objects.size() - 1; i >= 0; i--) {
            Point p = objects.get(i); p.y++;
            if (p.y >= rows - 2 && p.x == lane) { endGame(); return; }
            if (p.y >= rows) { objects.remove(i); score += 5; }
        }
        if (random.nextFloat() < 0.46f) {
            int newLane = random.nextInt(columns);
            boolean occupied = false;
            for (Point p : objects) if (p.y < 3 && p.x == newLane) occupied = true;
            if (!occupied) objects.add(new Point(newLane, 0));
        }
        if (ticks % 2 == 0) { score++; notifyScore(); }
    }

    private void tickTank() {
        for (int i = objects.size() - 1; i >= 0; i--) {
            Point p = objects.get(i);
            if (p.y < 0) { p.y++; continue; }
            p.y++;
            if (p.y >= playerY - 1 && p.x == playerX) { endGame(); return; }
            if (p.y >= rows) objects.remove(i);
        }
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Point b = bullets.get(i); b.y--;
            if (b.y < 0) { bullets.remove(i); continue; }
            boolean hit = false;
            for (int j = objects.size() - 1; j >= 0; j--) {
                Point e = objects.get(j);
                if (e.x == b.x && e.y == b.y) { objects.remove(j); score += 10; hit = true; notifyScore(); break; }
            }
            if (hit) bullets.remove(i);
        }
        if (ticks % 3 == 0 && random.nextFloat() < 0.78f) {
            int x = 1 + random.nextInt(columns - 2);
            boolean occupied = false;
            for (Point e : objects) if (e.x == x && e.y < 3) occupied = true;
            if (!occupied) objects.add(new Point(x, 0));
        }
        if (ticks % 4 == 0) { score++; notifyScore(); }
    }

    private final List<Point> bullets = new ArrayList<>();
    private void fire() {
        if (bullets.size() < 4) bullets.add(new Point(playerX, playerY - 1));
    }

    private void tickSnake() {
        if (snake.isEmpty()) return;
        Point head = snake.get(0);
        Point next = new Point(head.x + directionX, head.y + directionY);
        if (next.x < 0 || next.x >= columns || next.y < 0 || next.y >= rows) { endGame(); return; }
        for (int i = 0; i < snake.size() - 1; i++) {
            Point part = snake.get(i);
            if (part.x == next.x && part.y == next.y) { endGame(); return; }
        }
        snake.add(0, next);
        if (next.x == food.x && next.y == food.y) {
            score += 10; placeFood(); notifyScore();
        } else snake.remove(snake.size() - 1);
    }

    private void setSnakeDirection(int dx, int dy) {
        if (snake.size() > 1 && directionX == -dx && directionY == -dy) return;
        directionX = dx; directionY = dy;
    }

    private void placeFood() {
        for (int tries = 0; tries < 200; tries++) {
            int x = random.nextInt(columns); int y = random.nextInt(rows);
            boolean occupied = false;
            for (Point p : snake) if (p.x == x && p.y == y) { occupied = true; break; }
            if (!occupied) { food.set(x, y); return; }
        }
        food.set(random.nextInt(columns), random.nextInt(rows));
    }

    private void endGame() {
        if (gameOver) return;
        gameOver = true;
        running = false;
        handler.removeCallbacks(ticker);
        if (listener != null) listener.onGameOver(score);
    }

    private void notifyScore() {
        level = calculateLevel();
        if (listener != null) listener.onScoreChanged(score, level);
    }
    private static int[][] copy(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        resolveThemeColors();
        float cell = Math.min((getWidth() - dp(28)) / (float) columns, (getHeight() - dp(24)) / (float) rows);
        if (cell <= 0) return;
        float boardWidth = columns * cell;
        float boardHeight = rows * cell;
        float left = (getWidth() - boardWidth) / 2f;
        float top = (getHeight() - boardHeight) / 2f;
        RectF boardRect = new RectF(left - dp(5), top - dp(5), left + boardWidth + dp(5), top + boardHeight + dp(5));
        paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(colorPrimary, 18));
        canvas.drawRoundRect(boardRect, dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(withAlpha(colorSecondary, 100));
        canvas.drawRoundRect(boardRect, dp(8), dp(8), paint);
        paint.setStrokeWidth(1f); paint.setColor(withAlpha(colorSecondary, 55));
        for (int c = 0; c <= columns; c++) canvas.drawLine(left + c * cell, top, left + c * cell, top + boardHeight, paint);
        for (int r = 0; r <= rows; r++) canvas.drawLine(left, top + r * cell, left + boardWidth, top + r * cell, paint);

        if (GAME_TETRIS.equals(game)) drawTetris(canvas, left, top, cell);
        else if (GAME_RACING.equals(game)) drawRacing(canvas, left, top, cell);
        else if (GAME_TANK.equals(game)) drawTank(canvas, left, top, cell);
        else drawSnake(canvas, left, top, cell);

        if (gameOver) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(colorBackground, 215));
            canvas.drawRoundRect(boardRect, dp(8), dp(8), paint);
            paint.setColor(colorPrimary); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(20));
            canvas.drawText(getContext().getString(wallet.main.R.string.arcade_game_over), getWidth() / 2f, getHeight() / 2f, paint);
        }
    }

    private void drawTetris(Canvas canvas, float left, float top, float cell) {
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) if (board[r][c] != 0) drawCell(canvas, left, top, cell, c, r, colorSecondary);
        if (activePiece != null) for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            int y = pieceY + r;
            if (activePiece[r][c] != 0 && y >= 0) drawCell(canvas, left, top, cell, pieceX + c, y, colorAccent);
        }
    }

    private void drawRacing(Canvas canvas, float left, float top, float cell) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f, cell * 0.07f)); paint.setColor(withAlpha(colorSecondary, 100));
        for (int c = 1; c < columns; c++) canvas.drawLine(left + c * cell, top, left + c * cell, top + rows * cell, paint);
        for (Point p : objects) drawVehicle(canvas, left, top, cell, p.x, p.y, colorSecondary, false);
        drawVehicle(canvas, left, top, cell, lane, rows - 2, colorAccent, true);
    }

    private void drawTank(Canvas canvas, float left, float top, float cell) {
        for (Point p : objects) drawTankSprite(canvas, left, top, cell, p.x, p.y, colorSecondary, false);
        for (Point p : bullets) drawCell(canvas, left, top, cell, p.x, p.y, colorAccent);
        drawTankSprite(canvas, left, top, cell, playerX, playerY, colorAccent, true);
    }

    private void drawSnake(Canvas canvas, float left, float top, float cell) {
        drawCell(canvas, left, top, cell, food.x, food.y, colorSecondary);
        for (int i = snake.size() - 1; i >= 0; i--) {
            Point p = snake.get(i);
            drawCell(canvas, left, top, cell, p.x, p.y, i == 0 ? colorPrimary : colorAccent);
        }
    }

    private void drawVehicle(Canvas canvas, float left, float top, float cell, int x, int y, int color, boolean player) {
        float inset = cell * 0.16f;
        paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        float x1 = left + x * cell + inset; float y1 = top + y * cell + inset;
        canvas.drawRoundRect(x1, y1, x1 + cell - inset * 2, y1 + cell - inset * 2, cell * 0.18f, cell * 0.18f, paint);
        paint.setColor(withAlpha(colorBackground, 180));
        canvas.drawRect(x1 + cell * 0.32f, y1 + cell * 0.18f, x1 + cell * 0.68f, y1 + cell * 0.82f, paint);
    }

    private void drawTankSprite(Canvas canvas, float left, float top, float cell, int x, int y, int color, boolean player) {
        float inset = cell * 0.2f;
        float x1 = left + x * cell + inset; float y1 = top + y * cell + inset;
        paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        canvas.drawRect(x1, y1, x1 + cell - inset * 2, y1 + cell - inset * 2, paint);
        canvas.drawRect(x1 + cell * 0.38f, y1 - cell * 0.1f, x1 + cell * 0.62f, y1 + cell * 0.5f, paint);
    }

    private void drawCell(Canvas canvas, float left, float top, float cell, int x, int y, int color) {
        if (x < 0 || x >= columns || y < 0 || y >= rows) return;
        float gap = Math.max(1f, cell * 0.08f);
        float x1 = left + x * cell + gap; float y1 = top + y * cell + gap;
        paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        canvas.drawRoundRect(x1, y1, x1 + cell - gap * 2, y1 + cell - gap * 2, Math.max(1f, cell * 0.12f), Math.max(1f, cell * 0.12f), paint);
    }

    private int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        running = false;
        super.onDetachedFromWindow();
    }
}
