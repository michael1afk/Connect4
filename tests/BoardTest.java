public class BoardTest {

    public static void main(String[] args) {
        testAll69WinningPatterns();
        testShiftsDoNotWrapBetweenColumns();
        testCanPlayOnFullColumn();
        testSixPiecesFillColumnAndSeventhRejected();
        testPlayUndoRestoresBoardExactly();
        testOpponentImmediateWinningMoveDetection();

        System.out.println("All Board tests passed.");
    }

    /*
     * Instead of Java's built-in assert, use this so tests
     * run even if Java was not started with -ea.
     */
    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /*
     * Returns the bit corresponding to board cell (col, row).
     *
     * row 0 = bottom
     * row 5 = top
     *
     * Each column occupies 7 bits:
     *
     * col * 7 + row
     */
    static long bit(int col, int row) {
        return 1L << (col * 7 + row);
    }

    /*
     * ============================================================
     * TEST 1: ALL 69 POSSIBLE CONNECT-4 WINNING PATTERNS
     * ============================================================
     *
     * There should be:
     *
     * Horizontal:  4 starting columns * 6 rows = 24
     * Vertical:    7 columns * 3 starting rows = 21
     * Diagonal /:  4 * 3 = 12
     * Diagonal \:  4 * 3 = 12
     *
     * Total = 69
     */
    static void testAll69WinningPatterns() {
        int count = 0;

        // -------------------------------------
        // Horizontal wins
        // -------------------------------------
        // Start column can only be 0,1,2,3
        // because we need room for 4 pieces.
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col <= 3; col++) {

                long pos =
                        bit(col,     row) |
                        bit(col + 1, row) |
                        bit(col + 2, row) |
                        bit(col + 3, row);

                check(
                        Board.alignment(pos),
                        "Horizontal win not detected at col=" + col
                                + ", row=" + row
                );

                count++;
            }
        }

        // 24 patterns so far
        check(count == 24,
                "Expected 24 horizontal wins, got " + count);

        // -------------------------------------
        // Vertical wins
        // -------------------------------------
        //
        // Starting row can only be 0,1,2.
        for (int col = 0; col < 7; col++) {
            for (int row = 0; row <= 2; row++) {

                long pos =
                        bit(col, row) |
                        bit(col, row + 1) |
                        bit(col, row + 2) |
                        bit(col, row + 3);

                check(
                        Board.alignment(pos),
                        "Vertical win not detected at col=" + col
                                + ", row=" + row
                );

                count++;
            }
        }

        // 24 + 21 = 45
        check(count == 45,
                "Expected 45 wins after vertical tests, got " + count);

        // -------------------------------------
        // Diagonal \
        //
        // Example:
        //
        // X . . .
        // . X . .
        // . . X .
        // . . . X
        //
        // As column increases, we move LEFT,
        // and row increases, so we move UP.
        // -------------------------------------
        for (int col = 0; col <= 3; col++) {
            for (int row = 0; row <= 2; row++) {

                long pos =
                        bit(col,     row) |
                        bit(col + 1, row + 1) |
                        bit(col + 2, row + 2) |
                        bit(col + 3, row + 3);

                check(
                        Board.alignment(pos),
                        "\\ diagonal win not detected at col="
                                + col + ", row=" + row
                );

                count++;
            }
        }

        // 45 + 12 = 57
        check(count == 57,
                "Expected 57 wins after first diagonal, got " + count);

        // -------------------------------------
        // Diagonal /
        //
        // Example:
        //
        // . . . X
        // . . X .
        // . X . .
        // X . . .
        //
        // As column increases, we move LEFT,
        // and row decreases, so we move DOWN.
        // -------------------------------------
        for (int col = 0; col <= 3; col++) {
            for (int row = 3; row < 6; row++) {

                long pos =
                        bit(col,     row) |
                        bit(col + 1, row - 1) |
                        bit(col + 2, row - 2) |
                        bit(col + 3, row - 3);

                check(
                        Board.alignment(pos),
                        "/ diagonal win not detected at col="
                                + col + ", row=" + row
                );

                count++;
            }
        }

        check(count == 69,
                "Expected exactly 69 winning patterns, got " + count);
    }

    /*
     * ============================================================
     * TEST 2: SHIFTS MUST NOT WRAP BETWEEN COLUMNS
     * ============================================================
     *
     * This pattern is NOT a win:
     *
     * column 0      column 1
     *
     * X             .
     * X             .
     * .             .
     * .             .
     * .             X
     * .             X
     *
     * Specifically:
     *
     * (0,4)
     * (0,5)
     * (1,0)
     * (1,1)
     *
     * If columns were packed directly beside each other with
     * no sentinel bit, these could look like four consecutive
     * raw bits:
     *
     * ... 11 | 11 ...
     *
     * The extra bit between columns must prevent the vertical
     * >>> 1 test from treating them as four in a row.
     */
    static void testShiftsDoNotWrapBetweenColumns() {
        long pos =
                bit(0, 4) |
                bit(0, 5) |
                bit(1, 0) |
                bit(1, 1);

        check(
                !Board.alignment(pos),
                "Alignment incorrectly wrapped between columns"
        );

        /*
         * Repeat the same idea at every column boundary,
         * not just between columns 0 and 1.
         */
        for (int col = 0; col < 6; col++) {

            pos =
                    bit(col,     4) |
                    bit(col,     5) |
                    bit(col + 1, 0) |
                    bit(col + 1, 1);

            check(
                    !Board.alignment(pos),
                    "Alignment wrapped between columns "
                            + col + " and " + (col + 1)
            );
        }
    }

    /*
     * ============================================================
     * TEST 3: canPlay() MUST BE FALSE FOR A FULL COLUMN
     * ============================================================
     */
    static void testCanPlayOnFullColumn() {
        Board board = new Board();

        int col = 3;

        check(board.canPlay(col),
                "Empty column should initially be playable");

        for (int i = 0; i < 6; i++) {
            check(board.canPlay(col),
                    "Column became full too early after " + i + " moves");

            board.play(col);
        }

        check(
                !board.canPlay(col),
                "canPlay() returned true for a full column"
        );

        // Filling column 3 should not affect another column.
        check(
                board.canPlay(2),
                "Filling column 3 incorrectly affected column 2"
        );

        check(
                board.canPlay(4),
                "Filling column 3 incorrectly affected column 4"
        );
    }

    /*
     * ============================================================
     * TEST 4: SIX PIECES FILL A COLUMN; SEVENTH IS REJECTED
     * ============================================================
     */
    static void testSixPiecesFillColumnAndSeventhRejected() {
        Board board = new Board();

        int col = 6;

        // Six legal moves.
        for (int i = 0; i < 6; i++) {
            check(
                    board.canPlay(col),
                    "Move " + (i + 1) + " should be legal"
            );

            board.play(col);
        }

        check(
                !board.canPlay(col),
                "Column should be full after exactly 6 pieces"
        );

        // Seventh move should be rejected.
        boolean rejected = false;

        try {
            board.play(col);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }

        check(
                rejected,
                "Playing a 7th piece in a full column was not rejected"
        );
    }

    /*
     * ============================================================
     * TEST 5: playUnchecked() + undo() MUST RESTORE EXACT STATE
     * ============================================================
     *
     * Solver now mutates one Board object during DFS instead of creating a
     * Board copy for every child. That is only safe if every play/undo pair
     * restores the parent state exactly.
     *
     * key() captures position + occupancy, and getMoves() captures ply count.
     */
    static void testPlayUndoRestoresBoardExactly() {
        Board board = new Board();

        int[] setup = {3, 2, 3, 4, 1, 5, 2, 4};
        for (int col : setup) {
            board.play(col);
        }

        long originalKey = board.key();
        int originalMoves = board.getMoves();

        for (int col = 0; col < 7; col++) {
            if (!board.canPlay(col)) {
                continue;
            }

            board.playUnchecked(col);
            board.undo(col);

            check(board.key() == originalKey,
                    "play/undo changed board key for column " + col);
            check(board.getMoves() == originalMoves,
                    "play/undo changed move count for column " + col);
        }
    }

    /*
     * ============================================================
     * TEST 6: OPPONENT IMMEDIATE-WIN DETECTION
     * ============================================================
     *
     * Sequence gives Player 2 three vertical pieces in column 3.
     * After six moves it is Player 1's turn, so column 3 is an immediate
     * winning move for the opponent and therefore a forced block.
     */
    static void testOpponentImmediateWinningMoveDetection() {
        Board board = new Board();
        int[] setup = {0, 3, 1, 3, 5, 3};

        for (int col : setup) {
            board.play(col);
        }

        check(board.isOpponentWinningMove(3),
                "Expected opponent to have an immediate vertical win in column 3");

        for (int col = 0; col < 7; col++) {
            if (col == 3 || !board.canPlay(col)) {
                continue;
            }

            check(!board.isOpponentWinningMove(col),
                    "Unexpected opponent immediate win in column " + col);
        }
    }

}