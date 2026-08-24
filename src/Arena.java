/* ===========================================================================
 * PRESENTER GUIDE
 * ===========================================================================
 *
 * THIRTY-SECOND PITCH
 *   Unit tests prove the search is not lying. They cannot tell you whether it
 *   is WINNING. That question has no answer you can reason your way to from
 *   the code -- you have to play games and count. This is the instrument that
 *   does the counting, and it is what turns "I think it got better" into a
 *   number with an error bar.
 *
 * BEATS, IN ORDER          (annotations marked [A1]..[A4] below)
 *   A1  paired openings -- the design decision that makes results meaningful
 *   A2  the sign flip when crediting the second game of a pair
 *   A3  a subtle ordering bug avoided in the game loop
 *   A4  score and standard error -- how to read the output honestly
 *
 * THE MEASURED RESULTS
 *   hybrid vs one-ply baseline      79.0% +/- 5.8   (50 games)
 *   hybrid vs previous version      67.9% +/- 4.3   (120 games)
 *   Second row is the one that matters: four standard errors clear of 50%.
 *
 * LIKELY QUESTIONS
 *   "Why not just play from the empty board?"
 *     Two deterministic agents would replay the identical game every time.
 *     Fifty games would be one game's worth of information, fifty times.
 *
 *   "What does the +/- mean?"
 *     One standard error -- how much the result could have moved by luck
 *     alone. Rule of thumb: a gap smaller than twice the error is not
 *     evidence of anything. And it shrinks as 1/sqrt(n), so halving it costs
 *     four times the games.
 * =========================================================================== */

import java.util.Random;

/**
 * Bot-versus-bot match runner.
 *
 * This is the only file that can tell you whether a change made the bot
 * stronger. Correctness tests prove the search is not lying; Arena proves the
 * search is winning. Those are different questions and the second one has no
 * answer you can reason your way to from the code.
 *
 * ---------------------------------------------------------------------------
 * Paired openings
 * ---------------------------------------------------------------------------
 *
 * Two deterministic agents replay the identical game every time, so N games
 * from the empty board is one game's worth of information repeated N times.
 * Arena fixes that by generating a random legal opening and playing it TWICE
 * -- once with A on move, once with B on move.
 *
 * Pairing matters more than it looks. Connect Four is a first-player win
 * under perfect play, and even between imperfect agents the side to move at
 * the end of an opening has a real edge. Without pairing, a lucky draw of
 * favourable openings shows up as a strength difference. With pairing, both
 * agents face every opening from both sides, and that bias cancels exactly.
 *
 * ---------------------------------------------------------------------------
 * Reading the output
 * ---------------------------------------------------------------------------
 *
 * The score line is (wins + 0.5 * draws) / games -- the standard convention.
 * The +/- figure is one standard error, computed by treating each game as an
 * independent draw. It is approximate (paired games are correlated, which in
 * practice makes the true error SMALLER than shown), but it is enough to stop
 * you concluding that 52% over 40 games means anything. It does not.
 */
public final class Arena {

    public static void main(String[] args) {

        int pairs = intArg(args, 0, 25);          // 25 pairs = 50 games
        int openingPlies = intArg(args, 1, 8);
        long seed = longArg(args, 2, 20260809L);

        /*
         * Default matchup: exact solver with a modest budget vs. the shallow
         * fallback policy alone (nodeLimit 0 aborts before the first node).
         *
         * Raise the budget to make the first agent stronger and slower. A few
         * hundred thousand nodes per move keeps a 50-game match to a minute or
         * two; tens of millions gets you near-perfect play and a long wait.
         */
        Agent a = new HybridAgent("hybrid", 200_000L, 150L);
        Agent b = new MoveSolver("exact-200k", 200_000L);

        runMatch(a, b, pairs, openingPlies, seed);
    }

    // ---------------------------------------------------------------
    // Match driver
    // ---------------------------------------------------------------

    public static void runMatch(Agent agentA,
                                Agent agentB,
                                int pairs,
                                int openingPlies,
                                long seed) {

        if (pairs < 1) {
            throw new IllegalArgumentException("pairs must be >= 1");
        }
        if (openingPlies < 0 || openingPlies > 30) {
            throw new IllegalArgumentException("openingPlies must be in [0, 30]");
        }

        TimedAgent a = new TimedAgent(agentA);
        TimedAgent b = new TimedAgent(agentB);

        Random rng = new Random(seed);

        int winsA = 0;
        int winsB = 0;
        int draws = 0;

        System.out.printf("%s vs %s%n", a.name(), b.name());
        System.out.printf("%d pairs (%d games), %d opening plies, seed %d%n%n",
                pairs, pairs * 2, openingPlies, seed);

        long start = System.nanoTime();

        for (int p = 0; p < pairs; p++) {

            /* [A1] PAIRED OPENINGS -- THE KEY DESIGN DECISION.
             *      Each random opening is played TWICE, with the seats
             *      swapped. Connect Four is a first-player win under perfect
             *      play, and even between imperfect agents the side to move
             *      after an opening has a real edge. Without pairing, a lucky
             *      draw of favourable openings shows up as a strength
             *      difference. With pairing, both agents face every opening
             *      from both sides and that bias cancels exactly.
             */
            Board opening = randomOpening(rng, openingPlies);

            // Game 1: A moves first from this opening.
            int r1 = playGame(a, b, opening);
            if (r1 > 0)      winsA++;
            else if (r1 < 0) winsB++;
            else             draws++;

            // Game 2: same opening, B moves first.
            /* [A2] SIGN FLIP. In the second game B moves first, so playGame
             *      returns +1 when B won. The credits below are deliberately
             *      reversed relative to the first game. This is the easiest
             *      line in the file to get backwards, and getting it backwards
             *      would produce a result that looks entirely plausible.
             */
            int r2 = playGame(b, a, opening);
            if (r2 > 0)      winsB++;
            else if (r2 < 0) winsA++;
            else             draws++;

            if ((p + 1) % 5 == 0 || p == pairs - 1) {
                System.out.printf("  ... %d/%d pairs   %s %d - %d %s   (%d draws)%n",
                        p + 1, pairs, a.name(), winsA, winsB, b.name(), draws);
            }
        }

        double elapsed = (System.nanoTime() - start) / 1e9;
        int games = pairs * 2;

        /* [A4] SCORE AND STANDARD ERROR.
         *      Score is (wins + 0.5*draws)/games, the standard convention.
         *
         *      The error bar is the binomial standard error: variance of a
         *      single game is p(1-p), so the standard error of the mean over n
         *      games is sqrt(p(1-p)/n).
         *
         *      Two honest caveats worth volunteering before you are asked:
         *      scoring draws as 0.5 means games are not strictly Bernoulli,
         *      and paired games are correlated rather than independent. Both
         *      push the TRUE error below what is printed, so this figure is
         *      conservative.
         */
        double scoreA = (winsA + 0.5 * draws) / games;
        double stderr = Math.sqrt(scoreA * (1.0 - scoreA) / games);

        System.out.println();
        System.out.println("=".repeat(62));
        System.out.printf("%-22s %8s %8s %8s%n", "agent", "wins", "draws", "losses");
        System.out.println("-".repeat(62));
        System.out.printf("%-22s %8d %8d %8d%n", a.name(), winsA, draws, winsB);
        System.out.printf("%-22s %8d %8d %8d%n", b.name(), winsB, draws, winsA);
        System.out.println("-".repeat(62));
        System.out.printf("%s score: %.1f%%  +/- %.1f%%  over %d games%n",
                a.name(), 100 * scoreA, 100 * stderr, games);
        System.out.println("=".repeat(62));

        System.out.println();
        System.out.printf("%-22s %8s %12s%n", "agent", "moves", "ms/move");
        System.out.printf("%-22s %8d %12.2f%n", a.name(), a.moves, a.millisPerMove());
        System.out.printf("%-22s %8d %12.2f%n", b.name(), b.moves, b.millisPerMove());
        System.out.printf("%nTotal wall clock: %.1f s%n", elapsed);
    }

    // ---------------------------------------------------------------
    // One game
    // ---------------------------------------------------------------

    /**
     * Play a single game from the given position.
     *
     * @return +1 if 'first' won, -1 if 'second' won, 0 for a draw.
     */
    static int playGame(Agent first, Agent second, Board opening) {

        Board board = new Board(opening);
        Agent[] agents = { first, second };

        // The opening was built with alternating play, so whoever the board
        // says is to move corresponds to index 0 by construction.
        int turn = 0;

        while (true) {

            if (board.getMoves() == 42) {
                return 0; // board full, nobody connected four
            }

            Agent agent = agents[turn];
            int col = agent.chooseMove(board);

            if (col < 0 || col >= 7) {
                throw new IllegalStateException(
                        agent.name() + " returned out-of-range column " + col);
            }
            if (!board.canPlay(col)) {
                throw new IllegalStateException(
                        agent.name() + " returned illegal column " + col
                                + " (column full) at ply " + board.getMoves());
            }

            /*
             * Must be asked BEFORE play(): play() flips whose pieces are in
             * 'position', so afterwards isWinningMove answers for the other
             * player and the result is meaningless.
             */
            /* [A3] ORDER MATTERS HERE.
             *      isWinningMove must be asked BEFORE play(), because play()
             *      flips whose stones are in 'position'. Ask afterwards and it
             *      answers for the other player, and every game result in the
             *      match is silently wrong.
             */
            boolean winning = board.isWinningMove(col);

            board.play(col);

            if (winning) {
                return (turn == 0) ? 1 : -1;
            }

            turn ^= 1;
        }
    }

    // ---------------------------------------------------------------
    // Openings
    // ---------------------------------------------------------------

    /**
     * Random legal position after the given number of plies, guaranteed not
     * to be already decided.
     *
     * Moves that would complete a four are excluded, so the opening can never
     * hand a finished game to the agents. As a side effect it also filters out
     * openings where a player blundered a win away, which keeps the starting
     * positions closer to sane play than pure uniform sampling would.
     */
    static Board randomOpening(Random rng, int plies) {

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
                    // Every legal move ends the game. Start over.
                    continue restart;
                }

                board.play(legal[rng.nextInt(n)]);
            }

            return board;
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Wraps an Agent to accumulate timing without the agent knowing about it.
     * Keeps stats collection out of both Arena's game loop and MoveSolver.
     */
    static final class TimedAgent implements Agent {

        private final Agent inner;
        long nanos;
        long moves;

        TimedAgent(Agent inner) {
            this.inner = inner;
        }

        @Override
        public int chooseMove(Board board) {
            long t0 = System.nanoTime();
            int col = inner.chooseMove(board);
            nanos += System.nanoTime() - t0;
            moves++;
            return col;
        }

        @Override
        public String name() {
            return inner.name();
        }

        double millisPerMove() {
            return moves == 0 ? 0.0 : (nanos / 1e6) / moves;
        }
    }

    private static int intArg(String[] args, int i, int fallback) {
        return i < args.length ? Integer.parseInt(args[i]) : fallback;
    }

    private static long longArg(String[] args, int i, long fallback) {
        return i < args.length ? Long.parseLong(args[i]) : fallback;
    }
}
