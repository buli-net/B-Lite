package wallet.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

/** Snake movement, collision, food and speed progression. */
public final class SnakeGameView extends ArcadeGameView {
    private final List<Point> snake = new ArrayList<>();
    private final Point food = new Point();
    private int directionX = 1;
    private int directionY;
    private boolean boost;

    public SnakeGameView(Context context) { super(context); }
    public SnakeGameView(Context context, AttributeSet attrs) { super(context, attrs); }
    public SnakeGameView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override protected void onInitializeGame() {
        columns = 18;
        rows = 24;
        snake.clear();
        int cx = columns / 2;
        int cy = rows / 2;
        snake.add(new Point(cx, cy));
        snake.add(new Point(cx - 1, cy));
        snake.add(new Point(cx - 2, cy));
        directionX = 1;
        directionY = 0;
        boost = false;
        placeFood();
    }

    @Override protected void onGameTick() {
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
            addScore(10);
            placeFood();
        } else {
            snake.remove(snake.size() - 1);
        }
    }

    @Override protected long getTickDelay() {
        return Math.max(120, 520 - (level - 1L) * 24L - (boost ? 170 : 0)); // game speed
    }

    @Override protected int getPointsPerLevel() { return 5000; } // point level up

    @Override protected void onMoveLeft() { setDirection(-1, 0); }
    @Override protected void onMoveRight() { setDirection(1, 0); }
    @Override protected void onMoveUp() { setDirection(0, -1); }
    @Override protected void onMoveDown() { setDirection(0, 1); }
    @Override protected void onAction() { }

    @Override protected void onBoostChanged(boolean enabled) { boost = enabled; }

    private void setDirection(int dx, int dy) {
        if (snake.size() > 1 && directionX == -dx && directionY == -dy) return;
        directionX = dx;
        directionY = dy;
    }

    private void placeFood() {
        for (int tries = 0; tries < 200; tries++) {
            int x = random.nextInt(columns);
            int y = random.nextInt(rows);
            boolean occupied = false;
            for (Point part : snake) {
                if (part.x == x && part.y == y) { occupied = true; break; }
            }
            if (!occupied) { food.set(x, y); return; }
        }
        food.set(random.nextInt(columns), random.nextInt(rows));
    }

    @Override protected void onDrawGame(Canvas canvas, float left, float top, float cell) {
        drawCell(canvas, left, top, cell, food.x, food.y, colorSecondary);
        for (int i = snake.size() - 1; i >= 0; i--) {
            Point part = snake.get(i);
            drawCell(canvas, left, top, cell, part.x, part.y, i == 0 ? colorPrimary : colorAccent);
        }
    }
}
