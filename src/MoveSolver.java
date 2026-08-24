/**
 * Turns Solver (which answers "is this position won, drawn, or lost?") into an
 * Agent (which answers "which column should I play?").
 *
 * Solver never tells you a move. It returns a W/D/L value for the side to
 * move. To get a move you have to try each legal column yourself, ask Solver
 * to value the resulting position from the OPPONENT's point of view, and
 * negate. That loop lives here.
 *
 * ---------------------------------------------------------------------------
 * The node budget
 * ---------------------------------------------------------------------------
 *
 * An exact proof from the opening is enormous. With a budget, the agent has
 * two modes:
 *
 *   - Budget not exhausted: the returned move is provably optimal. Not
 *     "good" -- optimal. lastWasExact() reports true.
 *
 *   - Budget exhausted: SearchAbortedException unwinds out of the recursion
 *     and we fall back to a shallow tactical policy (win / block / do not
 *     hand over a win / center). lastWasExact() reports false.
 *
 * In practice the exact mode starts succeeding somewhere in the midgame and
 * the bot is perfect from that point to the end of the game. The whole reason
 * for the evaluator you are about to write is to make the FIRST mode's
 * fallback smarter than the crude policy below.
 *
 * ---------------------------------------------------------------------------
 * Why each child gets a fresh Board copy
 * ---------------------------------------------------------------------------
 *
 * Solver mutates the board as it recurses and relies on play/undo pairing to
 * restore it. An abort throws out of the middle of that recursion, so the
 * undos never run and the board is left with junk moves on it. Searching a
 * throwaway copy means an abort costs us nothing -- we drop the copy and the
 * caller's board was never touched. Seven Board allocations per move is
 * irrelevant next to a multi-million-node search.
 */
public final class MoveSolver implements Agent {

    private final Solver solver = new Solver();
    private final String name;
    private final long nodeLimit;

    private boolean lastWasExact;
    private int lastScore;
    private long lastNodes;

    /**
     * @param nodeLimit maximum nodes per move before falling back.
     *                  Long.MAX_VALUE searches to a proof no matter how long
     *                  it takes. Zero disables search entirely, which turns
     *                  this into a pure shallow-tactics agent -- a useful
     *                  Arena baseline.
     */
    public MoveSolver(String name, long nodeLimit) {
        if (nodeLimit < 0) {
            throw new IllegalArgumentException("nodeLimit must be >= 0");
        }
        this.name = name;
        this.nodeLimit = nodeLimit;
    }

    @Override
    public String name() {
        return name;
    }

    /** True if the most recent chooseMove() returned a provably optimal move. */
    public boolean lastWasExact() {
        return lastWasExact;
    }

    /** W/D/L value of the move chosen, valid only when lastWasExact(). */
    public int lastScore() {
        return lastScore;
    }

    public long lastNodes() {
        return lastNodes;
    }

    @Override
    public int chooseMove(Board board) {
        if (board.getMoves() >= 42) {
            throw new IllegalStateException("chooseMove called on a full board");
        }

        lastNodes = 0;

        /*
         * An immediate win is optimal and costs nothing to find, so take it
         * before spending any of the budget. This also guarantees we never
         * recurse into a position where the game is already over, which is
         * the invariant Solver's negamax depends on.
         */
        for (int col : Solver.ORDER) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                lastWasExact = true;
                lastScore = Solver.WIN;
                return col;
            }
        }

        try {
            return exactBestMove(board);
        } catch (SearchAbortedException e) {
            lastWasExact = false;
            lastScore = 0;
            return fallbackMove(board);
        }
    }

    /*
     * Root search.
     *
     * alpha carries across siblings: once we know one move is at least a
     * draw, later moves only need to be searched well enough to prove they
     * are not better. Giving every child a fresh full window instead would
     * discard most of the pruning and cost several times the nodes.
     *
     * beta stays at 2 (above any reachable score), so any child that raises
     * alpha returns a value strictly inside the window and is therefore
     * exact -- which is what makes the comparison between children valid.
     */
    private int exactBestMove(Board board) {
        solver.resetCounters();
        solver.setNodeLimit(nodeLimit);

        int alpha = -2;
        final int beta = 2;

        int bestCol = -1;
        int bestScore = Integer.MIN_VALUE;

        try {
            for (int col : Solver.ORDER) {
                if (!board.canPlay(col)) {
                    continue;
                }

                Board child = new Board(board);
                child.playUnchecked(col);

                int score;
                if (child.getMoves() == 42) {
                    // Board full and nobody won: the game is drawn.
                    score = Solver.DRAW;
                } else {
                    score = -solver.negamax(child, -beta, -alpha);
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestCol = col;
                }

                if (bestScore > alpha) {
                    alpha = bestScore;
                }

                if (bestScore == Solver.WIN) {
                    break; // cannot do better than a forced win
                }
            }
        } finally {
            lastNodes = solver.getNodesSearched();
        }

        if (bestCol == -1) {
            throw new IllegalStateException("No legal move found on a non-full board");
        }

        lastWasExact = true;
        lastScore = bestScore;
        return bestCol;
    }

    /*
     * ------------------------------------------------------------------
     * Shallow fallback policy, used when the budget runs out.
     * ------------------------------------------------------------------
     *
     * This is deliberately crude. It sees exactly one ply of tactics and has
     * no positional judgement whatsoever, which is precisely the hole the
     * evaluator fills. Keep it as the reference point to beat: any evaluator
     * that cannot beat this in Arena is not earning its place.
     */
    private int fallbackMove(Board board) {

        // 1. Win now if possible. (Already handled by the caller; kept so
        //    that this method is correct if called on its own.)
        for (int col : Solver.ORDER) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                return col;
            }
        }

        // 2. Block an immediate threat. If the opponent has two, we can only
        //    block one -- we are lost anyway, so blocking either is fine.
        for (int col : Solver.ORDER) {
            if (board.canPlay(col) && board.isOpponentWinningMove(col)) {
                return col;
            }
        }

        // 3. Center-most move that does not hand the opponent a win on the
        //    square directly above it.
        for (int col : Solver.ORDER) {
            if (!board.canPlay(col)) {
                continue;
            }

            Board child = new Board(board);
            child.playUnchecked(col);

            if (!opponentCanWinImmediately(child)) {
                return col;
            }
        }

        // 4. Every move loses. Take the center-most legal column and hope the
        //    opponent misses it.
        for (int col : Solver.ORDER) {
            if (board.canPlay(col)) {
                return col;
            }
        }

        throw new IllegalStateException("No legal moves available");
    }

    /*
     * After playUnchecked(), the board's "current player" is the opponent, so
     * isWinningMove() on that board answers whether the OPPONENT wins.
     */
    private static boolean opponentCanWinImmediately(Board board) {
        for (int col = 0; col < 7; col++) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                return true;
            }
        }
        return false;
    }
}
