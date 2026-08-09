public class Solver {

    static final int WIN = 1;
    static final int DRAW = 0;
    static final int LOSS = -1;

    /*
     * Optimization 1: center-first move ordering.
     * Better moves found earlier -> stronger alpha earlier -> more pruning.
     */
    static final int[] ORDER = {3, 2, 4, 1, 5, 0, 6};

    private long nodesSearched;

    /*
     * Optimization 7: fixed-size primitive transposition table.
     *
     * Unlike HashMap<Long, TTEntry>, this table performs no boxing or entry
     * allocation and never resizes. Each lookup is one modulo + primitive
     * array accesses.
     */
    private final TranspositionTable table = new TranspositionTable();
    private long tableHits;
    private long tableCutoffs;
    private long collisionReplacementsAtSearchStart;

    public int negamax(Board board) {
        nodesSearched = 0;
        tableHits = 0;
        tableCutoffs = 0;
        collisionReplacementsAtSearchStart = table.getCollisionReplacements();

        /*
         * Do NOT clear the table here.
         *
         * A cached Board.key() result remains valid forever for this game, so
         * keeping the finite table across top-level searches lets an interactive
         * game reuse work from previous moves. New entries naturally replace old
         * colliding entries as the table fills.
         */

        // W/D/L lives in [-1, 1], so [-2, 2] is our initial open window.
        return negamax(board, -2, 2);
    }

    public void clearTranspositionTable() {
        table.clear();
    }

    public long getNodesSearched() {
        return nodesSearched;
    }

    public long getTableHits() {
        return tableHits;
    }

    public long getTableCutoffs() {
        return tableCutoffs;
    }

    public int getTableSize() {
        return table.size();
    }

    public int getTableCapacity() {
        return table.capacity();
    }

    public long getTableCollisionReplacements() {
        return table.getCollisionReplacements() - collisionReplacementsAtSearchStart;
    }

    private int negamax(Board board, int alpha, int beta) {
        nodesSearched++;

        int alphaOriginal = alpha;
        int betaOriginal = beta;
        long key = board.key();

        /*
         * Allocation-free TT lookup.
         * packed == 0 means miss.
         */
        int packed = table.get(key);

        if (packed != 0) {
            tableHits++;

            int cachedScore = TranspositionTable.score(packed);
            int cachedBound = TranspositionTable.bound(packed);

            if (cachedBound == TranspositionTable.EXACT) {
                return cachedScore;
            }

            if (cachedBound == TranspositionTable.LOWER) {
                if (cachedScore > alpha) {
                    alpha = cachedScore;
                }
            } else { // UPPER
                if (cachedScore < beta) {
                    beta = cachedScore;
                }
            }

            if (alpha >= beta) {
                tableCutoffs++;
                return cachedScore;
            }
        }

        /* Optimization 2: immediate winning move. */
        for (int col : ORDER) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                table.put(key, WIN, TranspositionTable.EXACT);
                return WIN;
            }
        }

        if (board.getMoves() == 42) {
            table.put(key, DRAW, TranspositionTable.EXACT);
            return DRAW;
        }

        /*
         * Optimization 3: forced block / double threat.
         * One threat -> only one legal useful response.
         * Two threats -> current player cannot block both and loses.
         */
        int forcedColumn = -1;

        for (int col : ORDER) {
            if (!board.canPlay(col)) {
                continue;
            }

            if (board.isOpponentWinningMove(col)) {
                if (forcedColumn != -1) {
                    table.put(key, LOSS, TranspositionTable.EXACT);
                    return LOSS;
                }
                forcedColumn = col;
            }
        }

        if (forcedColumn != -1) {
            board.playUnchecked(forcedColumn);
            int score = -negamax(board, -beta, -alpha);
            board.undo(forcedColumn);

            storeResult(key, score, alphaOriginal, betaOriginal);
            return score;
        }

        int best = LOSS;

        for (int col : ORDER) {
            if (!board.canPlay(col)) {
                continue;
            }

            /* Optimization 4: mutate/undo; no Board allocation per child. */
            board.playUnchecked(col);

            int score = -negamax(board, -beta, -alpha);

            board.undo(col);

            if (score > best) {
                best = score;
            }

            /* Optimization 5: +1 is the maximum possible W/D/L value. */
            if (best == WIN) {
                table.put(key, WIN, TranspositionTable.EXACT);
                return WIN;
            }

            /* Optimization 6: alpha-beta pruning. */
            if (best > alpha) {
                alpha = best;
            }

            if (alpha >= beta) {
                break;
            }
        }

        storeResult(key, best, alphaOriginal, betaOriginal);
        return best;
    }

    private void storeResult(long key, int score, int alphaOriginal, int betaOriginal) {
        int bound;

        if (score <= alphaOriginal) {
            bound = TranspositionTable.UPPER;
        } else if (score >= betaOriginal) {
            bound = TranspositionTable.LOWER;
        } else {
            bound = TranspositionTable.EXACT;
        }

        table.put(key, score, bound);
    }
}
