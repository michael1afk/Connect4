/* ===========================================================================
 * PRESENTER GUIDE -- START HERE
 * ===========================================================================
 *
 * THIRTY-SECOND PITCH
 *   Connect Four is solved but the proof is too expensive to run from the
 *   opening. So the bot carries two engines: an exact solver that proves
 *   win/draw/loss, and a heuristic search that guesses under a time budget.
 *   It tries the proof first with a node budget. When that budget runs out it
 *   falls back to the heuristic. Early game it is always guessing; somewhere
 *   in the midgame the proof starts landing and from that point the bot is
 *   perfect to the end of the game.
 *
 * BEATS, IN ORDER          (annotations marked [H1]..[H6] below)
 *   H1  two engines, one interface
 *   H2  free win short-circuit
 *   H3  the budget is the switch between engines
 *   H4  why each child gets a throwaway Board
 *   H5  alpha carried between siblings
 *   H6  what the caller can observe
 *
 * DEMO
 *   java Play        then watch the tag flip from "d14 +7" to "EXACT".
 *
 * LIKELY QUESTION
 *   "Isn't the failed exact search wasted work?"
 *   No. Solver's transposition table is never cleared, so every subtree that
 *   FINISHED before the abort stays cached. Those cached results are largely
 *   why the first successful proof comes back in a few thousand nodes instead
 *   of millions.
 * =========================================================================== */

/**
 * The finished bot.
 *
 * Two engines, used where each is strongest:
 *
 *   1. Try Solver for an exact win/draw/loss proof, under a node budget. When
 *      it succeeds the move is provably optimal -- not good, optimal -- and
 *      nothing else can improve on it.
 *
 *   2. When the budget runs out, fall back to HeuristicSolver: iterative
 *      deepening to whatever depth the clock allows, with Evaluator supplying
 *      judgement at the horizon.
 *
 * Early in the game every move takes route 2. Somewhere in the midgame the
 * exact solve starts landing, and from that point on the bot plays perfectly
 * to the end. Watching that switch happen is the clearest signal that the
 * whole thing is working; Play prints it after every move.
 *
 * The exact solver's transposition table persists across moves and is never
 * cleared, so the work spent on the aborted attempts is not entirely wasted --
 * every subtree that finished is cached, and it is largely those cached
 * results that let the first successful proof come back so cheaply.
 */
/* [H1] TWO ENGINES, ONE INTERFACE.
 *      Both Solver and HeuristicSolver are held as fields, and this class
 *      implements Agent -- the same interface RandomAgent and MoveSolver
 *      implement. That is what lets Arena measure any of them against any
 *      other without knowing which is which.
 */
public final class HybridAgent implements Agent {

    private final String name;
    private final Solver exact = new Solver();
    private final HeuristicSolver heuristic;

    private final long exactNodeBudget;
    private final long heuristicMillis;
    private final int depthCap;

    private boolean lastWasExact;
    private int lastScore;
    private long lastNodes;
    private int lastDepth;

    public HybridAgent(String name, long exactNodeBudget, long heuristicMillis) {
        this(name, exactNodeBudget, heuristicMillis,
                HeuristicSolver.MAX_DEPTH, new Evaluator());
    }

    public HybridAgent(String name,
                       long exactNodeBudget,
                       long heuristicMillis,
                       int depthCap,
                       Evaluator evaluator) {
        this.name = name;
        this.exactNodeBudget = exactNodeBudget;
        this.heuristicMillis = heuristicMillis;
        this.depthCap = depthCap;
        this.heuristic = new HeuristicSolver(evaluator, 22);
    }

    @Override
    public String name() {
        return name;
    }

    /* [H6] OBSERVABILITY.
     *      These four accessors are what Play prints after every move. They
     *      are the reason the exact/heuristic handover is visible rather than
     *      something you have to take on trust.
     */
    /** True if the last move returned was proven optimal. */
    public boolean lastWasExact() { return lastWasExact; }

    /** Exact W/D/L when lastWasExact(), otherwise a heuristic score. */
    public int lastScore()  { return lastScore; }
    public long lastNodes() { return lastNodes; }

    /** Depth fully completed by the heuristic search, 0 when exact. */
    public int lastDepth()  { return lastDepth; }

    @Override
    public int chooseMove(Board board) {

        if (board.getMoves() >= 42) {
            throw new IllegalStateException("chooseMove on a full board");
        }

        lastDepth = 0;

        /* [H2] FREE WIN.
         *      Checked before either engine runs. Two reasons: it costs
         *      nothing, and it guarantees neither search is ever entered in a
         *      position where the game is already over -- an invariant both
         *      recursions depend on.
         */
        /* Free win, no search needed. */
        for (int col : Solver.ORDER) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                lastWasExact = true;
                lastScore = Solver.WIN;
                lastNodes = 0;
                return col;
            }
        }

        /* [H3] THE SWITCH.
         *      This try/catch is the whole architecture. If the exact solver
         *      finishes inside its node budget, the move it returns is not
         *      "good" -- it is optimal, and nothing can improve on it. If the
         *      budget is exhausted, SearchAbortedException unwinds the entire
         *      recursion and we drop through to the heuristic search.
         */
        if (exactNodeBudget > 0) {
            try {
                return exactBestMove(board);
            } catch (SearchAbortedException e) {
                /* Budget exhausted; fall through to the heuristic search. */
            }
        }

        lastWasExact = false;
        int col = heuristic.chooseMove(board, heuristicMillis, depthCap);
        lastNodes = heuristic.getNodes();
        lastScore = heuristic.getRootScore();
        lastDepth = heuristic.getCompletedDepth();
        return col;
    }

    /*
     * Root loop for the exact solver.
     *
     * Each child is searched on a throwaway Board copy. Solver relies on
     * play/undo pairing to restore state as it unwinds, and an abort throws
     * straight through those undos -- so the copy absorbs the damage and the
     * caller's board is never touched.
     *
     * alpha carries between siblings. Once one move is known to be at least a
     * draw, later moves only need refuting, not evaluating.
     */
    private int exactBestMove(Board board) {
        exact.resetCounters();
        exact.setNodeLimit(exactNodeBudget);

        int alpha = -2;
        final int beta = 2;

        int bestCol = -1;
        int bestScore = Integer.MIN_VALUE;

        try {
            for (int col : Solver.ORDER) {
                if (!board.canPlay(col)) {
                    continue;
                }

                /* [H4] THROWAWAY COPY.
                 *      Solver mutates the board as it recurses and relies on
                 *      play/undo pairing to restore it. An abort throws
                 *      straight through those undos, so the board would be
                 *      left with junk moves on it. The copy absorbs that
                 *      damage. Seven allocations per move is nothing next to
                 *      a multi-million-node search.
                 */
                Board child = new Board(board);
                child.playUnchecked(col);

                int score = (child.getMoves() == 42)
                        ? Solver.DRAW
                        : -exact.negamax(child, -beta, -alpha);

                if (score > bestScore) {
                    bestScore = score;
                    bestCol = col;
                }
                /* [H5] ALPHA CARRIES BETWEEN SIBLINGS.
                 *      Once one move is known to be at least a draw, later
                 *      moves only need refuting, not evaluating. Giving each
                 *      root child a fresh full window instead would throw
                 *      away most of the pruning.
                 */
                if (bestScore > alpha) {
                    alpha = bestScore;
                }
                if (bestScore == Solver.WIN) {
                    break;
                }
            }
        } finally {
            lastNodes = exact.getNodesSearched();
        }

        if (bestCol == -1) {
            throw new IllegalStateException("No legal move on a non-full board");
        }

        lastWasExact = true;
        lastScore = bestScore;
        return bestCol;
    }
}
