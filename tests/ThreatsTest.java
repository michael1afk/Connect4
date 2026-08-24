import java.util.Random;

/**
 * The threat bitboard is twelve lines of shifts with no obvious meaning, and a
 * wrong shift constant produces a bot that plays plausibly and evaluates
 * nonsense. So it is checked the only way worth trusting: against a slow,
 * obviously-correct reference that loops over all 69 windows, on tens of
 * thousands of random positions.
 *
 * If these tests pass, the shift constants are right.
 */
public final class ThreatsTest {

    public static void main(String[] args) {
        testMasksAreWellFormed();
        testKnownVerticalThreat();
        testKnownHorizontalGapThreat();
        testNoWrapAcrossColumnBoundary();
        testAgainstBruteForceReference();
        testPlayableCells();
        testNonLosingMovesForcedBlock();
        testNonLosingMovesDoubleThreatIsLost();
        testNonLosingMovesAvoidsFeedingOpponent();

        System.out.println("All Threats tests passed.");
    }

    // ------------------------------------------------------------------
    // Masks
    // ------------------------------------------------------------------

    private static void testMasksAreWellFormed() {
        check(Long.bitCount(Threats.BOARD_MASK) == 42,
                "BOARD_MASK should cover 42 cells, has "
                        + Long.bitCount(Threats.BOARD_MASK));

        check(Long.bitCount(Threats.BOTTOM_ALL) == 7,
                "BOTTOM_ALL should have one bit per column");

        check(Long.bitCount(Threats.ODD_ROWS) == 21,
                "ODD_ROWS should cover 3 rows x 7 columns");

        check(Long.bitCount(Threats.EVEN_ROWS) == 21,
                "EVEN_ROWS should cover 3 rows x 7 columns");

        check((Threats.ODD_ROWS & Threats.EVEN_ROWS) == 0,
                "Row parity masks must not overlap");

        check((Threats.ODD_ROWS | Threats.EVEN_ROWS) == Threats.BOARD_MASK,
                "Row parity masks must exactly tile the board");

        // No sentinel bit should ever appear in a board mask.
        for (int col = 0; col < 7; col++) {
            long sentinel = 1L << (col * 7 + 6);
            check((Threats.BOARD_MASK & sentinel) == 0,
                    "BOARD_MASK includes the sentinel of column " + col);
        }
    }

    // ------------------------------------------------------------------
    // Hand-checked cases
    // ------------------------------------------------------------------

    private static void testKnownVerticalThreat() {
        // Three stones in column 3, rows 0-2. The threat is row 3.
        long position = bit(3, 0) | bit(3, 1) | bit(3, 2);
        long mask = position;

        long wins = Threats.computeWinningPositions(position, mask);

        check(wins == bit(3, 3),
                "Vertical threat should be exactly (3,3), got " + cells(wins));
    }

    private static void testKnownHorizontalGapThreat() {
        // Bottom row: columns 0, 1 and 3 occupied. The gap at column 2 wins.
        long position = bit(0, 0) | bit(1, 0) | bit(3, 0);
        long mask = position;

        long wins = Threats.computeWinningPositions(position, mask);

        check((wins & bit(2, 0)) != 0,
                "Gap-fill threat at (2,0) not detected, got " + cells(wins));
    }

    /*
     * Stones stacked at the top of one column and the bottom of the next must
     * never look like a vertical run. This is the property the sentinel row
     * exists to guarantee.
     */
    private static void testNoWrapAcrossColumnBoundary() {
        for (int col = 0; col < 6; col++) {
            long position = bit(col, 3) | bit(col, 4) | bit(col, 5);
            long mask = position;

            long wins = Threats.computeWinningPositions(position, mask);

            check((wins & Board.columnMask(col + 1)) == 0,
                    "Vertical stack in column " + col
                            + " produced a threat in column " + (col + 1));
        }
    }

    // ------------------------------------------------------------------
    // The real test: agreement with brute force
    // ------------------------------------------------------------------

    private static void testAgainstBruteForceReference() {
        Random rng = new Random(31337L);
        int positionsChecked = 0;

        for (int trial = 0; trial < 20000; trial++) {

            Board board = randomReachablePosition(rng, rng.nextInt(35));
            long mask = board.mask();

            for (int side = 0; side < 2; side++) {
                long position = (side == 0)
                        ? board.position()
                        : board.opponentPosition();

                long fast = Threats.computeWinningPositions(position, mask);
                long slow = bruteForceWinningPositions(position, mask);

                if (fast != slow) {
                    throw new AssertionError(
                            "Mismatch at ply " + board.getMoves()
                                    + "\n  fast: " + cells(fast)
                                    + "\n  slow: " + cells(slow)
                                    + "\n  only in fast: " + cells(fast & ~slow)
                                    + "\n  only in slow: " + cells(slow & ~fast)
                                    + "\n" + board.render());
                }
            }

            positionsChecked++;
        }

        check(positionsChecked == 20000,
                "Expected 20000 positions, checked " + positionsChecked);
    }

    /*
     * Obviously-correct reference: for every empty cell, place a stone there
     * and ask Board.alignment -- which BoardTest already verifies against all
     * 69 winning patterns -- whether it completes a four.
     */
    private static long bruteForceWinningPositions(long position, long mask) {
        long result = 0L;

        for (int col = 0; col < 7; col++) {
            for (int row = 0; row < 6; row++) {
                long cell = 1L << (col * 7 + row);

                if ((mask & cell) != 0) {
                    continue; // occupied
                }

                if (Board.alignment(position | cell)) {
                    result |= cell;
                }
            }
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Playable cells and move filtering
    // ------------------------------------------------------------------

    private static void testPlayableCells() {
        Board board = new Board();

        check(Threats.playableCells(board.mask()) == Threats.BOTTOM_ALL,
                "Empty board should have exactly the bottom row playable");

        for (int i = 0; i < 6; i++) {
            board.play(3);
        }

        long playable = Threats.playableCells(board.mask());

        check((playable & Board.columnMask(3)) == 0,
                "Full column should contribute no playable cell");
        check(Long.bitCount(playable) == 6,
                "Six columns should remain playable, got " + Long.bitCount(playable));

        // Cross-check against canPlay for many random positions.
        Random rng = new Random(5L);
        for (int trial = 0; trial < 2000; trial++) {
            Board b = randomReachablePosition(rng, rng.nextInt(42));
            long p = Threats.playableCells(b.mask());

            for (int col = 0; col < 7; col++) {
                boolean bitSet = (p & Board.columnMask(col)) != 0;
                check(bitSet == b.canPlay(col),
                        "playableCells disagrees with canPlay on column " + col);
            }
        }
    }

    private static void testNonLosingMovesForcedBlock() {
        // O stacks three in column 3; X to move must block there.
        Board board = build(0, 3, 1, 3, 5, 3);

        long safe = Threats.nonLosingMoves(board.position(), board.mask());

        check(safe != 0, "Position is not lost, should have a legal reply");
        check(safe == Threats.landingCell(board.mask(), 3),
                "Only the block at column 3 should survive, got " + cells(safe));
    }

    private static void testNonLosingMovesDoubleThreatIsLost() {
        /*
         * O holds (1,0), (2,0), (3,0) on the bottom row, so both (0,0) and
         * (4,0) win for O. X cannot cover both.
         *
         * X's filler moves go to columns 5 and 6, well away from the threat
         * squares -- an earlier version of this test had X sitting on (0,0),
         * which quietly reduced it to a single threat.
         */
        Board board = build(5, 1, 6, 2, 5, 3);

        long oppWins = Threats.computeWinningPositions(
                board.opponentPosition(), board.mask());

        check(Long.bitCount(oppWins & Threats.playableCells(board.mask())) >= 2,
                "Test setup should give the opponent two immediate threats");

        long safe = Threats.nonLosingMoves(board.position(), board.mask());

        check(safe == 0, "Double threat should leave no non-losing move");
    }

    private static void testNonLosingMovesAvoidsFeedingOpponent() {
        /*
         * O has stones at (0,0), (1,0), (2,0) with the bottom of column 3
         * still empty... build a case where the opponent's winning cell sits
         * directly above a playable cell, so playing there is suicide.
         */
        Random rng = new Random(808L);
        int found = 0;

        for (int trial = 0; trial < 4000 && found < 50; trial++) {
            Board board = randomReachablePosition(rng, 8 + rng.nextInt(20));

            if (board.getMoves() >= 42) {
                continue;
            }

            long mask = board.mask();
            long oppWins = Threats.computeWinningPositions(
                    board.opponentPosition(), mask);
            long playable = Threats.playableCells(mask);

            // Cells we could play that sit directly under an opponent win.
            long suicide = playable & (oppWins >>> 1);

            if (suicide == 0) {
                continue;
            }

            long safe = Threats.nonLosingMoves(board.position(), mask);

            /*
             * A suicide cell may legitimately survive when it is the forced
             * block -- blocking is still better than losing at once. Outside
             * that case it must be excluded.
             */
            long forced = playable & oppWins;
            if (forced == 0) {
                check((safe & suicide) == 0,
                        "nonLosingMoves kept a move that feeds the opponent:\n"
                                + board.render());
                found++;
            }
        }

        check(found > 10,
                "Too few feed-the-opponent positions exercised: " + found);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /*
     * A position reachable by legal alternating play in which nobody has yet
     * won. Positions where a win already exists on the board are not valid
     * inputs to the search, so they are not valid test inputs either.
     */
    static Board randomReachablePosition(Random rng, int plies) {
        int[] legal = new int[7];

        restart:
        while (true) {
            Board board = new Board();

            for (int i = 0; i < plies; i++) {
                int n = 0;

                for (int col = 0; col < 7; col++) {
                    if (board.canPlay(col) && !board.isWinningMove(col)) {
                        legal[n++] = col;
                    }
                }

                if (n == 0) {
                    continue restart;
                }

                board.play(legal[rng.nextInt(n)]);
            }

            return board;
        }
    }

    private static Board build(int... cols) {
        Board board = new Board();
        for (int col : cols) {
            board.play(col);
        }
        return board;
    }

    private static long bit(int col, int row) {
        return 1L << (col * 7 + row);
    }

    private static String cells(long bits) {
        if (bits == 0) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;

        for (int col = 0; col < 7; col++) {
            for (int row = 0; row < 6; row++) {
                if ((bits & bit(col, row)) != 0) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append('(').append(col).append(',').append(row).append(')');
                    first = false;
                }
            }
        }

        return sb.append('}').toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
