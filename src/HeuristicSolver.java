/* ===========================================================================
 * PRESENTER GUIDE
 * ===========================================================================
 *
 * THIRTY-SECOND PITCH
 *   The exact solver cannot stop early -- it either proves a result or runs
 *   forever. This one searches to whatever depth the clock allows and asks
 *   the evaluator at the leaves. Iterative deepening, alpha-beta, killer
 *   moves, transposition-table move ordering. About 9 million nodes/second in
 *   Java; depth 13-16 in half a second from the opening.
 *
 * BEATS, IN ORDER          (annotations marked [S1]..[S6] below)
 *   S1  the two score bands, and why the gap between them is structural
 *   S2  absolute mate scores -- a classic bug avoided by construction
 *   S3  iterative deepening, and the absolute rule about partial depths
 *   S4  mate-distance pruning
 *   S5  the forced-loss check that runs even at depth 0
 *   S6  move ordering, which is worth more than everything else here
 *
 * THE SUBTLE POINT WORTH LEADING WITH
 *   Mate scores are MATE - board.getMoves(), counting from the start of the
 *   GAME rather than from the root of the search. That makes a mate score a
 *   property of the position, not of the path taken to reach it -- which
 *   sidesteps the classic bug where mate distances stored in a transposition
 *   table become wrong when the same position is reached at a different
 *   distance from the root. Nothing needs adjusting on store or probe.
 *
 * LIKELY QUESTIONS
 *   "Isn't iterative deepening wasteful?"
 *     No. The tree grows fast enough that every shallower search combined
 *     costs less than the final one, and the move ordering they leave in the
 *     transposition table makes that final search substantially cheaper than
 *     it would have been cold. It also gives a usable answer at any moment.
 *
 *   "Why a second transposition table?"
 *     A proven win/draw/loss is true forever. A heuristic score is only true
 *     at the depth it was computed -- "+120 at six ply" reused during a
 *     twelve-ply search is a shallow guess masquerading as a deep answer. No
 *     crash, no failing test, just a quietly worse bot. So entries here carry
 *     their depth and a lookup is only honoured when it is deep enough.
 * =========================================================================== */

/**
 * Depth-limited negamax with a heuristic evaluation at the horizon.
 *
 * Solver proves results and cannot stop early. This searches to whatever depth
 * the clock allows and guesses at the leaves, which is what makes it usable in
 * the opening where a proof is out of reach.
 *
 * ---------------------------------------------------------------------------
 * Score scale
 * ---------------------------------------------------------------------------
 *
 *   +/- MATE .. MATE-42   forced win or loss, with distance
 *   +/- 900 and below     heuristic opinion (Evaluator.MAX_EVAL)
 *
 * The gap between the two bands is deliberate: no amount of positional
 * optimism can ever be mistaken for a proof.
 *
 * Mate scores are computed as MATE - board.getMoves(), where getMoves() counts
 * from the start of the GAME, not from the root of the search. That makes a
 * mate score a property of the position rather than of the path taken to reach
 * it, which sidesteps the classic bug where mate distances stored in a
 * transposition table become wrong when the same position is reached at a
 * different distance from the root. Nothing needs adjusting on store or probe.
 *
 * ---------------------------------------------------------------------------
 * Iterative deepening
 * ---------------------------------------------------------------------------
 *
 * Search depth 1, then 2, then 3, until the clock runs out. This looks
 * wasteful and is not: the tree grows fast enough that every shallower search
 * combined costs less than the final one, and the move ordering they leave
 * behind in the transposition table makes that final search substantially
 * cheaper than it would have been cold.
 *
 * The safety rule is absolute. The best move is only updated when a depth
 * completes in full. A depth interrupted halfway has examined the first few
 * moves and nothing else, so its "best" is not a best -- it is whichever move
 * happened to be looked at first. Returning that is worse than returning the
 * previous depth's answer.
 */
public final class HeuristicSolver {

    /* [S1] TWO SCORE BANDS, WITH A DELIBERATE GAP.
     *
     *        +/- 10000 down to 9958   forced win or loss, with distance
     *        +/- 900 and below        heuristic opinion (Evaluator.MAX_EVAL)
     *
     *      Nothing can ever land between them. That is what lets the code
     *      test Math.abs(score) > Evaluator.MAX_EVAL and know for certain it
     *      is looking at a proof rather than an opinion.
     */
    public static final int MATE = 10_000;
    public static final int MAX_DEPTH = 42;

    private final Evaluator evaluator;
    private final HeuristicTable table;

    /* Two killer moves per ply: quiet moves that caused a cutoff elsewhere. */
    private final int[][] killers = new int[MAX_DEPTH + 2][2];

    private long deadline;
    private long nodes;
    private int selDepth;

    /*
     * Checking the clock costs a syscall-ish read, so it happens once every
     * 2048 nodes rather than at every node. At a few million nodes per second
     * that bounds overshoot to well under a millisecond.
     */
    private static final int TIME_CHECK_MASK = 2047;

    private int rootBestMove = -1;
    private int rootBestScore;
    private int completedDepth;

    public HeuristicSolver() {
        this(new Evaluator(), 22);
    }

    public HeuristicSolver(Evaluator evaluator, int tableSizeLog2) {
        this.evaluator = evaluator;
        this.table = new HeuristicTable(tableSizeLog2);
    }

    public long getNodes()          { return nodes; }
    public int getCompletedDepth()  { return completedDepth; }
    public int getSelDepth()        { return selDepth; }
    public int getRootScore()       { return rootBestScore; }
    public HeuristicTable getTable() { return table; }

    // ------------------------------------------------------------------
    // Root
    // ------------------------------------------------------------------

    /**
     * Choose a move under a time budget.
     *
     * @param board       position to move in; not modified
     * @param millis      wall-clock budget
     * @param depthCap    hard ceiling on depth, or MAX_DEPTH for none
     * @return a legal column
     */
    public int chooseMove(Board board, long millis, int depthCap) {

        if (board.getMoves() >= 42) {
            throw new IllegalStateException("chooseMove on a full board");
        }

        nodes = 0;
        selDepth = 0;
        completedDepth = 0;
        rootBestMove = -1;
        rootBestScore = 0;
        deadline = System.nanoTime() + millis * 1_000_000L;

        table.newSearch();
        for (int[] pair : killers) {
            pair[0] = -1;
            pair[1] = -1;
        }

        /*
         * An immediate win short-circuits everything. This also preserves the
         * invariant the recursion depends on: it is never entered in a
         * position where the game is already over.
         */
        for (int col : Solver.ORDER) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                rootBestMove = col;
                rootBestScore = MATE - board.getMoves();
                completedDepth = 1;
                return col;
            }
        }

        /*
         * If every reply loses, nonLosingMoves returns nothing. Play the
         * centre-most legal column and hope the opponent errs -- there is
         * nothing better available.
         */
        long safe = Threats.nonLosingMoves(board.position(), board.mask());
        if (safe == 0) {
            for (int col : Solver.ORDER) {
                if (board.canPlay(col)) {
                    rootBestMove = col;
                    rootBestScore = -(MATE - board.getMoves());
                    return col;
                }
            }
        }

        int fallback = firstLegal(board, safe);
        rootBestMove = fallback;

        Board working = new Board(board);
        int maxDepth = Math.min(depthCap, 42 - board.getMoves());

        /* [S3] ITERATIVE DEEPENING, AND THE ONE ABSOLUTE RULE.
         *
         *      Search depth 1, then 2, then 3, until the clock runs out.
         *
         *      The rule: the best move is committed ONLY when a depth
         *      completes in full. A depth interrupted halfway has examined
         *      the first few moves and nothing else, so its "best" is not a
         *      best -- it is whichever move happened to be looked at first.
         *      Returning that is worse than returning the previous depth's
         *      answer. That is why the commit happens after searchRoot
         *      returns, inside the try, and never in the catch.
         */
        for (int depth = 1; depth <= maxDepth; depth++) {
            try {
                int score = searchRoot(working, depth, safe);

                /* Only now, with the depth fully complete, is it safe to commit. */
                completedDepth = depth;
                rootBestScore = score;

                /*
                 * A forced result cannot improve with more depth, so stop.
                 * Distinguishing mate scores from heuristic ones is exactly
                 * what the gap in the score scale is for.
                 */
                if (Math.abs(score) > Evaluator.MAX_EVAL) {
                    break;
                }
            } catch (SearchAbortedException e) {
                break;
            }
        }

        return rootBestMove;
    }

    /*
     * One full-width pass over the root moves.
     *
     * The root is kept separate from the recursion because it is the only
     * place that needs to remember WHICH move was best, and because a partial
     * result here must never leak out. rootBestMove is written to a local
     * first and only copied out once every root move has been searched.
     */
    private int searchRoot(Board board, int depth, long safeMoves) {

        int alpha = -MATE;
        final int beta = MATE;

        int bestMove = -1;
        int bestScore = Integer.MIN_VALUE;

        int[] order = new int[7];
        int n = orderMoves(board, order, safeMoves, table.probeMove(board.key()), 0);

        for (int i = 0; i < n; i++) {
            int col = order[i];

            board.playUnchecked(col);
            int score;
            try {
                score = -negamax(board, -beta, -alpha, depth - 1, 1);
            } finally {
                board.undo(col);
            }

            if (score > bestScore) {
                bestScore = score;
                bestMove = col;
            }
            if (score > alpha) {
                alpha = score;
            }
        }

        if (bestMove == -1) {
            throw new IllegalStateException("Root found no moves to search");
        }

        rootBestMove = bestMove;
        table.store(board.key(), bestScore, depth, HeuristicTable.EXACT, bestMove);
        return bestScore;
    }

    // ------------------------------------------------------------------
    // Recursion
    // ------------------------------------------------------------------

    /**
     * @param depth plies still to search
     * @param ply   distance from the root, used to index killer moves
     */
    private int negamax(Board board, int alpha, int beta, int depth, int ply) {

        if ((++nodes & TIME_CHECK_MASK) == 0 && System.nanoTime() > deadline) {
            throw SearchAbortedException.INSTANCE;
        }

        if (ply > selDepth) {
            selDepth = ply;
        }

        int moves = board.getMoves();

        if (moves == 42) {
            return 0; // board full, drawn
        }

        long position = board.position();
        long mask = board.mask();

        /*
         * A win available right now. Checked before anything else so the
         * recursion never continues past the end of a game, and so that no
         * table entry or evaluation is ever produced for a finished position.
         */
        long playable = Threats.playableCells(mask);
        long myWins = Threats.computeWinningPositions(position, mask) & playable;
        if (myWins != 0) {
            return MATE - moves;
        }

        /*
         * Mate-distance pruning. The best conceivable outcome is winning on
         * the very next ply; if even that does not beat alpha, this whole
         * subtree is irrelevant.
         */
        /* [S4] MATE-DISTANCE PRUNING.
         *      The best conceivable outcome from here is winning on the very
         *      next ply. If even that does not beat alpha, nothing in this
         *      subtree can matter and we return immediately.
         *
         *      [S2] Note the form: MATE - moves, where moves counts from the
         *      start of the game. Absolute, not relative to the search root.
         *      That is the property that makes these scores safe to store in
         *      a transposition table without adjustment.
         */
        int bestPossible = MATE - moves;
        if (bestPossible <= alpha) {
            return bestPossible;
        }
        if (bestPossible < beta) {
            beta = bestPossible;
            if (alpha >= beta) {
                return beta;
            }
        }

        long key = board.key();
        int alphaOriginal = alpha;

        int cached = table.probe(key, depth, alpha, beta);
        if (cached != HeuristicTable.MISS) {
            return cached;
        }

        /*
         * Moves that do not lose on the spot. Returning nothing means every
         * reply hands the opponent a win, so the position is lost in two.
         */
        /* [S5] FORCED-LOSS CHECK, DELIBERATELY ABOVE THE DEPTH-0 RETURN.
         *      This runs even when depth has been exhausted, which makes it a
         *      cheap quiescence: rather than handing a position to the
         *      evaluator when the opponent has an unstoppable threat, the
         *      search returns the loss. Without it the evaluator would be
         *      asked to judge positions that are already decided.
         */
        long safe = Threats.nonLosingMoves(position, mask);
        if (safe == 0) {
            return -(MATE - (moves + 2));
        }

        if (depth <= 0) {
            return evaluator.evaluate(position, mask, moves);
        }

        int ttMove = table.probeMove(key);
        int[] order = new int[7];
        int n = orderMoves(board, order, safe, ttMove, ply);

        int bestScore = Integer.MIN_VALUE;
        int bestMove = -1;

        for (int i = 0; i < n; i++) {
            int col = order[i];

            board.playUnchecked(col);
            int score;
            try {
                score = -negamax(board, -beta, -alpha, depth - 1, ply + 1);
            } finally {
                board.undo(col);
            }

            if (score > bestScore) {
                bestScore = score;
                bestMove = col;
            }

            if (score > alpha) {
                alpha = score;
            }

            if (alpha >= beta) {
                recordKiller(ply, col);
                break;
            }
        }

        int flag;
        if (bestScore <= alphaOriginal) {
            flag = HeuristicTable.UPPER;
        } else if (bestScore >= beta) {
            flag = HeuristicTable.LOWER;
        } else {
            flag = HeuristicTable.EXACT;
        }

        table.store(key, bestScore, depth, flag, bestMove);
        return bestScore;
    }

    // ------------------------------------------------------------------
    // Move ordering
    // ------------------------------------------------------------------

    /*
     * Alpha-beta prunes far more when good moves come first, so this is worth
     * more than almost anything else in the file. Priority, highest first:
     *
     *   1. The transposition table's move. It was best last time this position
     *      came up, very often at greater depth. Even when the score is too
     *      shallow to trust, the move is an excellent hint.
     *   2. Killer moves -- quiet moves that caused a cutoff at this same ply
     *      elsewhere in the tree. Cheap to track and surprisingly effective.
     *   3. Moves that create a new threat.
     *   4. Centre proximity, which is the static fallback.
     *
     * Insertion sort over at most seven entries beats anything cleverer here.
     */
    /* [S6] MOVE ORDERING IS WORTH MORE THAN EVERYTHING ELSE IN THIS FILE.
     *
     *      Alpha-beta prunes in proportion to how early the best move is
     *      tried. Priority here, highest first:
     *
     *        1. The transposition table's move -- best last time this
     *           position came up, often at greater depth. Even when the
     *           SCORE is too shallow to trust, the MOVE is an excellent hint.
     *        2. Killer moves -- quiet moves that caused a cutoff at this same
     *           ply elsewhere in the tree. Two per ply, nearly free to track.
     *        3. Moves that create the most new threats.
     *        4. Centre proximity, as the static fallback.
     *
     *      Insertion sort over at most seven entries beats anything cleverer.
     */
    private int orderMoves(Board board, int[] out, long safeMoves, int ttMove, int ply) {

        long mask = board.mask();
        long position = board.position();

        int[] scores = new int[7];
        int n = 0;

        for (int col = 0; col < 7; col++) {
            if (!board.canPlay(col)) {
                continue;
            }

            long cell = Threats.landingCell(mask, col);
            if ((safeMoves & cell) == 0) {
                continue; // pruned as losing
            }

            int score;
            if (col == ttMove) {
                score = 1_000_000;
            } else if (col == killers[ply][0]) {
                score = 900_000;
            } else if (col == killers[ply][1]) {
                score = 800_000;
            } else {
                /*
                 * How many winning cells would this move create for us?
                 * Cheap approximation of "is this move constructive".
                 */
                long newPosition = position | cell;
                long newMask = mask | cell;
                int threatsAfter = Long.bitCount(
                        Threats.computeWinningPositions(newPosition, newMask));

                score = threatsAfter * 100 + CENTER_BONUS[col];
            }

            /* Insertion sort, descending. */
            int j = n - 1;
            while (j >= 0 && scores[j] < score) {
                scores[j + 1] = scores[j];
                out[j + 1] = out[j];
                j--;
            }
            scores[j + 1] = score;
            out[j + 1] = col;
            n++;
        }

        return n;
    }

    private static final int[] CENTER_BONUS = {0, 1, 2, 3, 2, 1, 0};

    private void recordKiller(int ply, int col) {
        if (killers[ply][0] != col) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = col;
        }
    }

    private int firstLegal(Board board, long safeMoves) {
        for (int col : Solver.ORDER) {
            if (board.canPlay(col)
                    && (safeMoves & Threats.landingCell(board.mask(), col)) != 0) {
                return col;
            }
        }
        for (int col : Solver.ORDER) {
            if (board.canPlay(col)) {
                return col;
            }
        }
        throw new IllegalStateException("No legal move on a non-full board");
    }
}
