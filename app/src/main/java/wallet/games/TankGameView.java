package wallet.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

/** Tank movement, enemy waves and shooting rules. */
public final class TankGameView extends ArcadeGameView {
    private final List<Point> enemies = new ArrayList<>();
    private final List<Point> bullets = new ArrayList<>();
    private int playerX;
    private int playerY;

    public TankGameView(Context context) { super(context); }
    public TankGameView(Context context, AttributeSet attrs) { super(context, attrs); }
    public TankGameView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override protected void onInitializeGame() {
        columns = 11;
        rows = 16;
        enemies.clear();
        bullets.clear();
        playerX = columns / 2;
        playerY = rows - 2;
    }

    @Override protected void onGameTick() {
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Point enemy = enemies.get(i);
            if (enemy.y < 0) { enemy.y++; continue; }
            enemy.y++;
            if (enemy.y >= playerY - 1 && enemy.x == playerX) { endGame(); return; }
            if (enemy.y >= rows) enemies.remove(i);
        }
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Point bullet = bullets.get(i);
            bullet.y--;
            if (bullet.y < 0) { bullets.remove(i); continue; }
            boolean hit = false;
            for (int j = enemies.size() - 1; j >= 0; j--) {
                Point enemy = enemies.get(j);
                if (enemy.x == bullet.x && enemy.y == bullet.y) {
                    enemies.remove(j);
                    score += 10;
                    updateLevelAndNotify();
                    hit = true;
                    break;
                }
            }
            if (hit) bullets.remove(i);
        }
        if (ticks % 3 == 0 && random.nextFloat() < 0.78f) {
            int x = 1 + random.nextInt(columns - 2);
            boolean occupied = false;
            for (Point enemy : enemies) if (enemy.x == x && enemy.y < 3) occupied = true;
            if (!occupied) enemies.add(new Point(x, 0));
        }
        if (ticks % 4 == 0) addScore(1);
    }

    @Override protected long getTickDelay() { return Math.max(190, 600 - (level - 1L) * 24L); }
    @Override protected int getPointsPerLevel() { return 500; }

    @Override protected void onMoveLeft() { playerX = Math.max(1, playerX - 1); }
    @Override protected void onMoveRight() { playerX = Math.min(columns - 2, playerX + 1); }
    @Override protected void onMoveUp() { playerY = Math.max(rows / 2, playerY - 1); }
    @Override protected void onMoveDown() { playerY = Math.min(rows - 2, playerY + 1); }
    @Override protected void onAction() { fire(); }
    @Override protected void onTap(float x, float width) { fire(); }

    private void fire() {
        if (bullets.size() < 4) bullets.add(new Point(playerX, playerY - 1));
    }


    @Override protected void onSaveGameState(android.os.Bundle outState) {
        outState.putIntArray("tank_enemies", flattenPoints(enemies));
        outState.putIntArray("tank_bullets", flattenPoints(bullets));
        outState.putInt("tank_player_x", playerX);
        outState.putInt("tank_player_y", playerY);
    }

    @Override protected void onRestoreGameState(android.os.Bundle state) {
        columns = 11;
        rows = 16;
        enemies.clear();
        bullets.clear();
        restorePoints(enemies, state.getIntArray("tank_enemies"));
        restorePoints(bullets, state.getIntArray("tank_bullets"));
        playerX = state.getInt("tank_player_x", columns / 2);
        playerY = state.getInt("tank_player_y", rows - 2);
    }

    private static int[] flattenPoints(List<Point> points) {
        int[] result = new int[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            result[i * 2] = points.get(i).x;
            result[i * 2 + 1] = points.get(i).y;
        }
        return result;
    }

    private static void restorePoints(List<Point> target, int[] positions) {
        if (positions == null) return;
        for (int i = 0; i + 1 < positions.length; i += 2) target.add(new Point(positions[i], positions[i + 1]));
    }

    @Override protected void onDrawGame(Canvas canvas, float left, float top, float cell) {
        for (Point enemy : enemies) drawTankSprite(canvas, left, top, cell, enemy.x, enemy.y, colorSecondary);
        for (Point bullet : bullets) drawCell(canvas, left, top, cell, bullet.x, bullet.y, colorAccent);
        drawTankSprite(canvas, left, top, cell, playerX, playerY, colorAccent);
    }
}
