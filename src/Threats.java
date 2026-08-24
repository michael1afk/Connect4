/* ===========================================================================
 * PRESENTER GUIDE
 * ===========================================================================
 *
 * THIRTY-SECOND PITCH
 *   One primitive underpins everything the bot knows positionally: given a
 *   player's stones, which empty cells would complete a four? Computed in a
 *   fixed number of shifts. From that one bitboard come the evaluator's
 *   features AND the search's strongest pruning rules.
 *
 * BEATS, IN ORDER          (annotations marked [T1]..[T4] below)
 *   T1  the sentinel argument -- why shifts cannot wrap
 *   T2  the interior-gap terms, which are the easy thing to get wrong
 *   T3  playableCells: the carry trick applied to all seven columns at once
 *   T4  nonLosingMoves: three pruning rules folded into one bitmask
 *
 * THE SHOWPIECE
 *   nonLosingMoves. Three separate pieces of Connect Four theory in twenty
 *   lines: two opponent threats means lost, one means the branching factor
 *   collapses from seven to one, and never play directly beneath a cell the
 *   opponent wins on.
 *
 * LIKELY QUESTION
 *   "How do you know this is correct?"
 *     It was checked against a brute-force reference -- place a stone in
 *     every empty cell and call the already-tested Board.alignment() -- over
 *     tens of thousands of random positions, for both players.
 * =========================================================================== */

/**
 * Bitboard threat computation.
 *
 * Everything the evaluator knows is derived from one primitive: given a
 * player's stones, which empty cells would complete a four-in-a-row? That set
 * is computed here in a fixed number of shifts, with no loops and no branches.
 *
 * ---------------------------------------------------------------------------
 * Why the sentinel row makes this safe
 * ---------------------------------------------------------------------------
 *
 * Board packs each column into 7 bits and leaves bit 6 of every column
 * permanently empty. That single wasted bit per column is what lets us shift
 * across column boundaries without checking anything.
 *
 * Consider the vertical term, which asks for three stones directly below a
 * cell. For a cell at index i we test bits i-1, i-2, i-3. If i is the bottom
 * of column c (index c*7), then i-1 is the sentinel of column c-1, which can
 * never be occupied, so the term dies immediately. Without the sentinel,
 * i-1 would be the TOP of column c-1 and a stack of stones in one column
 * would look like a vertical run continuing into the next.
 *
 * The same argument covers the diagonals. A shift of 6 moves one column right
 * and one row down; a shift of 8 moves one column right and one row up. Both
 * land on a sentinel exactly when they would otherwise have wrapped. Every
 * intermediate value in the computation is finally ANDed against BOARD_MASK,
 * so anything that shifted off the top of the 49-bit board disappears too.
 *
 * This is why the shift amounts are 1, 6, 7 and 8 and why they cannot be
 * changed independently of the column stride.
 */
public final class Threats {

    private Threats() {
    }

    /** Every playable cell on the board: 6 rows in each of 7 columns. */
    public static final long BOARD_MASK = buildBoardMask();

    /** The bottom cell of every column, used to find landing squares. */
    public static final long BOTTOM_ALL = buildBottomAll();

    /**
     * Rows 0, 2 and 4 counting from the bottom.
     *
     * In the classical Connect Four literature these are the "odd" rows,
     * because that text counts rows from one. They are the rows the FIRST
     * player is normally able to convert a waiting threat on.
     */
    public static final long ODD_ROWS = buildRows(0, 2, 4);

    /**
     * Rows 1, 3 and 5 counting from the bottom -- the "even" rows of the
     * literature, which favour the SECOND player.
     */
    public static final long EVEN_ROWS = buildRows(1, 3, 5);

    public static final long CENTER_COLUMN = Board.columnMask(3);
    public static final long INNER_COLUMNS = Board.columnMask(2) | Board.columnMask(4);

    private static long buildBoardMask() {
        long m = 0L;
        for (int col = 0; col < 7; col++) {
            m |= Board.columnMask(col);
        }
        return m;
    }

    private static long buildBottomAll() {
        long m = 0L;
        for (int col = 0; col < 7; col++) {
            m |= Board.bottomMask(col);
        }
        return m;
    }

    private static long buildRows(int... rows) {
        long m = 0L;
        for (int col = 0; col < 7; col++) {
            for (int row : rows) {
                m |= 1L << (col * 7 + row);
            }
        }
        return m;
    }

    /**
     * Every empty cell where the given player would complete a four.
     *
     * @param position that player's stones
     * @param mask     all stones on the board, both players
     */
    /* [T1] and [T2] LIVE IN THIS METHOD.
     *
     *  [T1] SENTINELS MAKE THE SHIFTS SAFE. Every term below shifts across
     *       column boundaries with no bounds check. A shift that would wrap
     *       lands on a sentinel bit, which is never occupied, so the term
     *       dies. The final AND against BOARD_MASK cleans up anything that
     *       shifted off the board entirely.
     *
     *  [T2] THE INTERIOR-GAP TERMS ARE THE SUBTLE PART. Three in a row with
     *       an open end is the obvious case. But XX_X and X_XX also complete
     *       a four, and they are not extensions of a run -- they are holes.
     *       A naive implementation silently misses that whole class of
     *       threats and produces a bot that is quietly worse with no failing
     *       test anywhere. That is why each direction needs both an "extend
     *       the run" term and a "fill the hole" term.
     */
    public static long computeWinningPositions(long position, long mask) {

        /*
         * Vertical.
         *
         * Only the upward direction is needed: a cell completes a vertical
         * four exactly when the three cells directly beneath it are occupied
         * by this player. The other three orientations are impossible to
         * complete from below because the cells beneath would have to be
         * empty, and gravity forbids that.
         */
        long r = (position << 1) & (position << 2) & (position << 3);

        /*
         * Horizontal, stride 7.
         *
         * Four cells in a row give four possible positions for the gap, so
         * each direction needs both an "extend the run" term and a "fill the
         * hole" term. p holds two adjacent stones; ANDing with a third stone
         * placed further along, or with one on the opposite side, yields the
         * completing cell.
         */
        long p = (position << 7) & (position << 14);
        r |= p & (position << 21);
        r |= p & (position >>> 7);
        p = (position >>> 7) & (position >>> 14);
        r |= p & (position << 7);
        r |= p & (position >>> 21);

        /* Diagonal, stride 6: one column right, one row down. */
        p = (position << 6) & (position << 12);
        r |= p & (position << 18);
        r |= p & (position >>> 6);
        p = (position >>> 6) & (position >>> 12);
        r |= p & (position << 6);
        r |= p & (position >>> 18);

        /* Diagonal, stride 8: one column right, one row up. */
        p = (position << 8) & (position << 16);
        r |= p & (position << 24);
        r |= p & (position >>> 8);
        p = (position >>> 8) & (position >>> 16);
        r |= p & (position << 8);
        r |= p & (position >>> 24);

        /*
         * Keep only cells that are on the board and currently empty. This one
         * AND is what discards every bit that shifted past the edge or landed
         * on a sentinel.
         */
        return r & (BOARD_MASK ^ mask);
    }

    /**
     * The cells that can be played right now: one per non-full column.
     *
     * Adding the bottom bit of a column to the mask carries upward through
     * that column's occupied bits and lands on the lowest empty cell. Doing it
     * for all seven columns at once works because the sentinel absorbs the
     * carry out of a full column instead of letting it spill into the next.
     */
    /* [T3] GRAVITY FOR ALL SEVEN COLUMNS IN ONE ADDITION.
     *      Same carry trick as Board.play(), applied to every column
     *      simultaneously. It works in parallel only because the sentinel
     *      absorbs the carry out of a full column instead of letting it spill
     *      into the next one.
     */
    public static long playableCells(long mask) {
        return (mask + BOTTOM_ALL) & BOARD_MASK;
    }

    /**
     * Moves that do not lose immediately, as a bitmask of landing cells.
     *
     * Three distinct things happen here, and the search relies on all of them:
     *
     *   1. If the opponent has two or more immediate threats, the position is
     *      lost. Returning 0 tells the caller that.
     *
     *   2. If the opponent has exactly one immediate threat, the only move
     *      worth considering is the block. Every other move loses on the spot,
     *      so the branching factor at this node collapses from seven to one.
     *
     *   3. Never play directly beneath a cell the opponent would win on --
     *      that hands them the square above. This is the cheapest large
     *      pruning rule in Connect Four and it is worth adding even though the
     *      search would eventually discover those moves lose anyway: it prunes
     *      them a full ply earlier.
     *
     * @return bitmask of safe landing cells, or 0 if every move loses
     */
    /* [T4] THE MOST IMPORTANT METHOD IN THE PROJECT.
     *
     *      Three distinct pruning rules, one bitmask:
     *
     *      1. Two immediate opponent threats -> return 0. We can block at
     *         most one, so the position is lost. The caller reads 0 as
     *         "lost in two" and never searches the subtree at all.
     *
     *      2. Exactly one -> the only move worth considering is the block.
     *         Branching factor collapses from seven to one at that node.
     *
     *      3. Never play directly beneath a cell the opponent wins on --
     *         that hands them the square above. This is the cheapest large
     *         pruning rule in Connect Four. The search would eventually
     *         discover those moves lose anyway; this prunes them a full ply
     *         earlier, across the entire tree.
     *
     *      Two bit tricks worth pointing at:
     *        (forced & (forced - 1)) != 0   more than one bit set
     *        opponentWins >>> 1             "the cell directly below"
     */
    public static long nonLosingMoves(long position, long mask) {
        long playable = playableCells(mask);
        long opponent = mask ^ position;
        long opponentWins = computeWinningPositions(opponent, mask);
        long forced = playable & opponentWins;

        if (forced != 0) {
            /*
             * More than one bit set means two separate immediate threats. We
             * can block at most one of them, so the position is lost.
             * (x & (x - 1)) clears the lowest set bit; a nonzero remainder
             * means there was more than one.
             */
            if ((forced & (forced - 1)) != 0) {
                return 0;
            }
            playable = forced;
        }

        return playable & ~(opponentWins >>> 1);
    }

    /**
     * The landing cell for a column, or 0 if the column is full.
     *
     * Used to translate between the bitmask returned by nonLosingMoves and the
     * column indices the rest of the code speaks in.
     */
    public static long landingCell(long mask, int col) {
        return (mask + Board.bottomMask(col)) & Board.columnMask(col);
    }
}
