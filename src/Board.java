/* ===========================================================================
 * PRESENTER GUIDE
 * ===========================================================================
 *
 * THIRTY-SECOND PITCH
 *   The entire game state is two 64-bit integers. Legality, move generation,
 *   undo and win detection are all bit arithmetic -- no arrays, no objects,
 *   no allocation anywhere in the search.
 *
 * BEATS, IN ORDER          (annotations marked [B1]..[B6] below)
 *   B1  two longs hold everything
 *   B2  7 bits per column, and why the wasted bit matters
 *   B3  play(): perspective flip plus carry trick
 *   B4  undo(): why the last piece is recoverable
 *   B5  win detection in four shift pairs
 *   B6  a collision-free key for the transposition table
 *
 * LIKELY QUESTIONS
 *   "Why 7 bits for 6 rows?"
 *     The seventh is a sentinel that is never occupied. It stops a shift
 *     carrying from the top of one column into the bottom of the next. See B2.
 *
 *   "Java has no unsigned types -- is that a problem?"
 *     No. The key uses at most 49 bits, so bit 63 is never set and >>> and >>
 *     behave identically. >>> is used throughout anyway, so the code stays
 *     correct even if the layout is ever widened.
 *
 *   "How do you know win detection is right?"
 *     BoardTest builds all 69 possible four-in-a-row placements and asserts
 *     each is detected, plus a wrap test at every column boundary.
 * =========================================================================== */

/* [B1] THE WHOLE GAME STATE.
 *      'position' is the stones of whoever is TO MOVE -- not a fixed player.
 *      'mask' is every occupied cell. The opponent's stones are always
 *      (mask ^ position), so a third field would be redundant. Because the
 *      meaning of 'position' flips every move, the search never needs to know
 *      or track whose turn it is.
 */
public class Board {
    private long position, mask;
    private int moves;

    private static final long BOTTOM_ALL =
        bottomMask(0) |
        bottomMask(1) |
        bottomMask(2) |
        bottomMask(3) |
        bottomMask(4) |
        bottomMask(5) |
        bottomMask(6);

    public Board() {
        position = 0L;
        mask = 0L;
        moves = 0;
    }

    public Board(long position, long mask, int moves) {
        this.position = position;
        this.mask = mask;
        this.moves = moves;
    }

    /*
     * Kept because it is still convenient outside the search code.
     * Solver no longer creates one Board per child; it uses play/undo instead.
     */
    public Board(Board other) {
        this.position = other.position;
        this.mask = other.mask;
        this.moves = other.moves;
    }

    /* [B2] LAYOUT: 7 BITS PER COLUMN, BIT (col * 7 + row), ROW 0 AT THE BOTTOM.
     *
     *      Row 6 of every column is a sentinel that is never occupied. That
     *      one wasted bit per column is what makes every shift in this file
     *      safe: a shift that would otherwise wrap from one column into the
     *      next lands on a sentinel instead and dies. It is also why the shift
     *      distances are 1, 6, 7 and 8 and cannot be changed independently of
     *      the stride.
     *
     *      topMask is row 5 -- the highest PLAYABLE cell -- so canPlay() is a
     *      single AND against it.
     */
    static long bottomMask(int col) { return 1L << (col * 7); }
    static long topMask(int col)    { return 1L << (col * 7 + 5); }
    static long columnMask(int col) { return 0b111111L << (col * 7); }

    public boolean canPlay(int col) {
        return (mask & topMask(col)) == 0;
    }

    public int getMoves() {
        return moves;
    }

    /*
     * Safe public move operation.
     * Use this when the caller has not already checked canPlay().
     */
    public void play(int col) {
        if (col < 0 || col >= 7) {
            throw new IllegalArgumentException("Invalid column: " + col);
        }
        if (!canPlay(col)) {
            throw new IllegalArgumentException("Column is full");
        }

        playUnchecked(col);
    }

    /*
     * Search-only fast path.
     *
     * Solver already checks canPlay(col), so calling canPlay() again inside
     * play() would duplicate work at every node in the game tree.
     *
     * Package-private on purpose: normal callers should use play().
     */
    /* [B3] TWO TRICKS, ONE LINE EACH.
     *
     *      position ^= mask
     *          XOR-ing your stones with everyone's stones leaves the
     *          opponent's. That single operation swaps whose perspective the
     *          board is in, so the search never branches on side to move.
     *
     *      mask |= mask + bottomMask(col)
     *          Adding 1 at the bottom of a column carries upward through the
     *          filled bits and settles on the lowest empty cell. Gravity, for
     *          free, in one addition.
     */
    public void playUnchecked(int col) {
        position ^= mask;                 // switch perspective
        mask |= mask + bottomMask(col);   // add the piece
        moves++;
    }

    /*
     * Undo the MOST RECENT move, which must have been played in 'col'.
     *
     * This lets negamax reuse one Board object instead of allocating a new
     * Board at every child node.
     *
     * In one column, occupied bits are contiguous from the bottom:
     *
     *     0001111
     *
     * The highest occupied bit is therefore the piece that was most recently
     * added to that column. Long.highestOneBit() isolates it.
     *
     * After removing that bit, mask is exactly the old mask. Because play()
     * changed perspective using:
     *
     *     newPosition = oldPosition ^ oldMask
     *
     * XORing with oldMask again restores oldPosition.
     */
    public void undo(int col) {
        long occupiedInColumn = mask & columnMask(col);

        if (occupiedInColumn == 0) {
            throw new IllegalStateException("Cannot undo an empty column");
        }

        /* [B4] WHY UNDO IS POSSIBLE AT ALL.
         *      Within a column the occupied bits are always contiguous from
         *      the bottom, so the highest one is necessarily the most recent
         *      piece. Removing it restores the old mask exactly, and XOR-ing
         *      with that old mask undoes the perspective flip.
         *
         *      This is what lets negamax reuse ONE Board object for the whole
         *      tree instead of allocating a child at every node.
         */
        long lastPiece = Long.highestOneBit(occupiedInColumn);

        mask ^= lastPiece;    // restore old mask
        position ^= mask;     // restore old position
        moves--;
    }

    /* [B5] WIN DETECTION: FOUR SHIFT PAIRS, NO LOOPS, NO BRANCHES.
     *
     *      pos & (pos >>> 7) marks every cell that has a friendly stone one
     *      column away. AND that against itself shifted 14 more and you have
     *      four in a row. Same idea at stride 1 (vertical), 6 and 8 (the two
     *      diagonals).
     *
     *      Eight shifts and eight ANDs replaces checking 69 windows. Called at
     *      essentially every node, so this is the hottest code in the project.
     */
    static boolean alignment(long pos) {
        long m = pos & (pos >>> 7);
        if ((m & (m >>> 14)) != 0) return true; // horizontal

        m = pos & (pos >>> 6);
        if ((m & (m >>> 12)) != 0) return true; // diagonal /

        m = pos & (pos >>> 8);
        if ((m & (m >>> 16)) != 0) return true; // diagonal \

        m = pos & (pos >>> 1);
        if ((m & (m >>> 2)) != 0) return true;  // vertical

        return false;
    }

    /*
     * Would the CURRENT player win by dropping in col?
     * Caller should first verify canPlay(col).
     */
    public boolean isWinningMove(int col) {
        long newPiece = (mask + bottomMask(col)) & columnMask(col);
        return alignment(position | newPiece);
    }

    /*
     * Would the OPPONENT win immediately in col if it were their turn?
     *
     * Current-player stones are 'position'. All occupied stones are 'mask'.
     * Therefore opponent stones are:
     *
     *     mask ^ position
     *
     * This is used for exact forced-block pruning in Solver.
     */
    boolean isOpponentWinningMove(int col) {
        long opponent = mask ^ position;
        long newPiece = (mask + bottomMask(col)) & columnMask(col);
        return alignment(opponent | newPiece);
    }

    /*
     * Collision-free encoding of a legal board position/perspective.
     * This is useful later as the key for a transposition table.
     */
    /* [B6] TRANSPOSITION TABLE KEY.
     *      position + mask + BOTTOM_ALL is a known collision-free encoding: it
     *      identifies both the occupancy AND whose turn it is, in one number
     *      under 2^49. That bound is what the transposition table's
     *      key-truncation argument relies on.
     */
    public long key() {
        return position + mask + BOTTOM_ALL;
    }

    /*
     * Human-readable board, row 5 (top) down to row 0 (bottom).
     *
     * 'position' holds the pieces of whoever is TO MOVE, which alternates, so
     * it cannot be printed directly as one fixed player. Player 1 moves on
     * even plies, so on an even ply 'position' is Player 1's and on an odd ply
     * it is Player 2's.
     */
    public String render() {
        long playerOne = (moves % 2 == 0) ? position : (mask ^ position);
        long playerTwo = mask ^ playerOne;

        StringBuilder sb = new StringBuilder();

        for (int row = 5; row >= 0; row--) {
            sb.append('|');
            for (int col = 0; col < 7; col++) {
                long cell = 1L << (col * 7 + row);

                if ((playerOne & cell) != 0) {
                    sb.append('X');
                } else if ((playerTwo & cell) != 0) {
                    sb.append('O');
                } else {
                    sb.append('.');
                }
                sb.append('|');
            }
            sb.append('\n');
        }

        sb.append(" 0 1 2 3 4 5 6\n");
        return sb.toString();
    }

    /* ------------------------------------------------------------------
     * Raw bitboard accessors, needed by Threats and Evaluator.
     * Read-only views: state still changes only through play/undo.
     * ------------------------------------------------------------------ */

    /** Stones of the player whose turn it is. */
    public long position() {
        return position;
    }

    /** All occupied cells, both players. */
    public long mask() {
        return mask;
    }

    /** Stones of the player who is not to move. */
    public long opponentPosition() {
        return mask ^ position;
    }
}
