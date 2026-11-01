package wallet.games;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

/** Falling-block puzzle rules and rendering. */
public final class TetrisGameView extends ArcadeGameView {
    private static final int[][][] PIECES = {
            {{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{1,1,0,0},{0,0,0,0},{0,0,0,0}},
            {{1,1,0,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{1,0,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,0,1,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}}
    };

    private int[][] board;
    private int[][] activePiece;
    private int pieceX;
    private int pieceY;

    public TetrisGameView(Context context) {
        super(context);
    }
    public TetrisGameView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public TetrisGameView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onInitializeGame() {
        columns = 10;
        rows = 20;
        board = new int[rows][columns];
        activePiece = null;
        spawnPiece();
    }

    @Override
    protected void onGameTick() {
        tickFallingPiece();
    }

    @Override
    protected long getTickDelay() {
        return Math.max(190, 720 - (level - 1L) * 30L);
    }

    @Override
    protected int getPointsPerLevel() {
        return 1000;
    }

    @Override
    protected void onMoveLeft() {
        movePiece(-1, 0);
    }
    @Override
    protected void onMoveRight() {
        movePiece(1, 0);
    }
    @Override
    protected void onMoveUp() {
        hardDropPiece();
    }
    @Override
    protected void onMoveDown() {
        dropPieceOne();
    }
    @Override
    protected void onAction() {
        rotatePiece();
    }

    @Override
    protected void onTap(float x, float width) {
        rotatePiece();
    }

    private void tickFallingPiece() {
        if (!movePiece(0, 1)) {
            lockPiece();
            clearLines();
            if (isRunning()) {
                spawnPiece();
                if (collides(activePiece, pieceX, pieceY)) endGame();
            }
        }
    }

    private void spawnPiece() {
        activePiece = copy(PIECES[random.nextInt(PIECES.length)]);
        pieceX = 3;
        pieceY = -1;
        if (collides(activePiece, pieceX, pieceY)) endGame();
    }

    private boolean movePiece(int dx, int dy) {
        if (activePiece == null || collides(activePiece, pieceX + dx, pieceY + dy)) return false;
        pieceX += dx;
        pieceY += dy;
        refreshBoard();
        return true;
    }

    private boolean collides(int[][] piece, int x, int y) {
        if (piece == null) return true;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (piece[r][c] == 0) continue;
                int bx = x + c;
                int by = y + r;
                if (bx < 0 || bx >= columns || by >= rows) return true;
                if (by >= 0 && board[by][bx] != 0) return true;
            }
        }
        return false;
    }

    private void lockPiece() {
        if (activePiece == null) return;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int bx = pieceX + c;
                int by = pieceY + r;
                if (activePiece[r][c] != 0 && by >= 0 && by < rows && bx >= 0 && bx < columns) {
                    board[by][bx] = 1;
                }
            }
        }
    }

    private void clearLines() {
        int cleared = 0;
        for (int r = rows - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < columns; c++) {
                if (board[r][c] == 0) {
                    full = false;
                    break;
                }
            }
            if (full) {
                cleared++;
                for (int y = r; y > 0; y--) System.arraycopy(board[y - 1], 0, board[y], 0, columns);
                for (int c = 0; c < columns; c++) board[0][c] = 0;
                r++;
            }
        }
        if (cleared > 0) addScore(cleared * cleared * 100);
    }

    private void rotatePiece() {
        if (!isRunning() || activePiece == null) return;
        int[][] rotated = new int[4][4];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) rotated[c][3 - r] = activePiece[r][c];
        }
        if (!collides(rotated, pieceX, pieceY)) activePiece = rotated;
        else if (!collides(rotated, pieceX - 1, pieceY)) {
            activePiece = rotated;
            pieceX--;
        }
        else if (!collides(rotated, pieceX + 1, pieceY)) {
            activePiece = rotated;
            pieceX++;
        }
        refreshBoard();
    }

    private void dropPieceOne() {
        if (movePiece(0, 1)) addScore(1);
        else tickFallingPiece();
    }

    private void hardDropPiece() {
        while (movePiece(0, 1)) score += 2;
        tickFallingPiece();
        updateLevelAndNotify();
    }

    private static int[][] copy(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }


    @Override
    protected void onSaveGameState(android.os.Bundle outState) {
        outState.putIntArray("tetris_board", flatten(board, rows, columns));
        outState.putIntArray("tetris_piece", activePiece == null ? null : flatten(activePiece, 4, 4));
        outState.putInt("tetris_piece_x", pieceX);
        outState.putInt("tetris_piece_y", pieceY);
    }

    @Override
    protected void onRestoreGameState(android.os.Bundle state) {
        columns = 10;
        rows = 20;
        board = inflate(state.getIntArray("tetris_board"), rows, columns);
        int[] piece = state.getIntArray("tetris_piece");
        activePiece = piece == null ? null : inflate(piece, 4, 4);
        pieceX = state.getInt("tetris_piece_x", 3);
        pieceY = state.getInt("tetris_piece_y", -1);
    }

    private static int[] flatten(int[][] values, int height, int width) {
        int[] result = new int[height * width];
        for (int y = 0; y < height; y++) System.arraycopy(values[y], 0, result, y * width, width);
        return result;
    }

    private static int[][] inflate(int[] values, int height, int width) {
        int[][] result = new int[height][width];
        if (values != null && values.length == height * width) {
            for (int y = 0; y < height; y++) System.arraycopy(values, y * width, result[y], 0, width);
        }
        return result;
    }

    @Override
    protected void onDrawGame(Canvas canvas, float left, float top, float cell) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (board[r][c] != 0) drawCell(canvas, left, top, cell, c, r, colorSecondary);
            }
        }
        if (activePiece != null) {
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    int y = pieceY + r;
                    if (activePiece[r][c] != 0 && y >= 0) drawCell(canvas, left, top, cell, pieceX + c, y, colorAccent);
                }
            }
        }
    }
}
