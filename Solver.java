public class Solver {

    static final int WIN = 1;
    static final int DRAW = 0;
    static final int LOSS = -1;

    /*
     * Optimization 1: center-first move ordering.
     *
     * Center moves are usually stronger in Connect Four because they
     * participate in more possible four-in-a-row lines. Searching promising
     * moves first also helps alpha-beta establish a strong alpha earlier,
     * which causes more pruning.
     */
    static final int[] ORDER = {3, 2, 4, 1, 5, 0, 6};

    private long nodesSearched;

    /*
     * Public entry point.
     *
     * Actual W/D/L values are only [-1, 1], so [-2, 2] acts like our
     * initial (-infinity, +infinity) alpha-beta window.
     */
    public int negamax(Board board) {
        nodesSearched = 0;
        return negamax(board, -2, 2);
    }

    public long getNodesSearched() {
        return nodesSearched;
    }

    private int negamax(Board board, int alpha, int beta) {
        nodesSearched++;

        /*
         * Optimization 2: immediate-win cutoff.
         *
         * If the current player can win now, there is no reason to examine
         * any deeper continuation. WIN is also the maximum possible score.
         */
        for (int col : ORDER) {
            if (board.canPlay(col) && board.isWinningMove(col)) {
                return WIN;
            }
        }

        // No legal cells remain and nobody already won -> draw.
        if (board.getMoves() == 42) {
            return DRAW;
        }

        /*
         * Optimization 3: forced-block / double-threat pruning.
         *
         * Look for cells where the opponent would win immediately on their
         * next turn.
         *
         * 0 threats -> search normal candidate moves.
         * 1 threat  -> only the blocking column needs to be searched.
         * 2 threats -> current player is lost: one piece cannot block both.
         *
         * This is exact pruning, not a heuristic. We already checked whether
         * the current player can win immediately above.
         */
        int forcedColumn = -1;

        for (int col : ORDER) {
            if (!board.canPlay(col)) {
                continue;
            }

            if (board.isOpponentWinningMove(col)) {
                if (forcedColumn != -1) {
                    return LOSS;
                }
                forcedColumn = col;
            }
        }

        /*
         * If there is exactly one immediate threat, every non-blocking move
         * loses on the next ply, so search only the forced block.
         */
        if (forcedColumn != -1) {
            board.playUnchecked(forcedColumn);

            int score = -negamax(board, -beta, -alpha);

            board.undo(forcedColumn);
            return score;
        }

        int best = LOSS;

        for (int col : ORDER) {
            if (!board.canPlay(col)) {
                continue;
            }

            /*
             * Optimization 4: mutate + undo instead of new Board(board).
             *
             * This removes one Board allocation per searched child and avoids
             * putting large amounts of short-lived garbage pressure on the JVM.
             */
            board.playUnchecked(col);

            /*
             * Negamax perspective flip:
             *   score = -childScore
             *
             * Alpha-beta window also flips perspective:
             *   [alpha, beta] -> [-beta, -alpha]
             */
            int score = -negamax(board, -beta, -alpha);

            // Always restore the exact parent state before examining next move.
            board.undo(col);

            if (score > best) {
                best = score;
            }

            /*
             * Optimization 5: maximum-score cutoff.
             * WIN is +1, and nothing can ever be better than +1.
             */
            if (best == WIN) {
                return WIN;
            }

            /*
             * Optimization 6: alpha-beta pruning.
             *
             * alpha = best lower bound the current player has established.
             * beta  = cutoff inherited from the parent search.
             */
            if (best > alpha) {
                alpha = best;
            }

            if (alpha >= beta) {
                break;
            }
        }

        return best;
    }
}
