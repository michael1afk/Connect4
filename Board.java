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

        long lastPiece = Long.highestOneBit(occupiedInColumn);

        mask ^= lastPiece;    // restore old mask
        position ^= mask;     // restore old position
        moves--;
    }

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
    public long key() {
        return position + mask + BOTTOM_ALL;
    }
}
