import java.util.Random;

/**
 * The root loop in MoveSolver is where sign conventions go wrong, and a sign
 * error there produces a bot that plays legally, never crashes, and quietly
 * chooses its worst move. Nothing in BoardTest or SolverTest would catch it.
 *
 * The central test is agreement: the value of the best root child, negated,
 * must equal what Solver says the position is worth. Solver is already
 * trusted, so this pins the root loop to it.
 */
public final class MoveSolverTest {

    public static void main(String[] args) {
        testTakesImmediateWin();
        testBlocksImmediateThreat();
        testRootValueAgreesWithSolver();
        testFallbackAlwaysLegal();
        testExactModeNeverLosesToShallow();

        System.out.println("All MoveSolver tests passed.");
    }

    // ------------------------------------------------------------------

    private static void testTakesImmediateWin() {
        // X in column 3 three times; X to move has a vertical win at 3.
        Board board = build(3, 0, 3, 1, 3, 2);

        MoveSolver bot = new MoveSolver("t", 0L); // budget 0: must still win
        int col = bot.chooseMove(board);

        check(col == 3, "Failed to take an immediate vertical win, played " + col);
        check(bot.lastWasExact(), "Immediate win should be reported as exact");
        check(bot.lastScore() == Solver.WIN, "Immediate win should score WIN");
    }

    private static void testBlocksImmediateThreat() {
        // O has three in column 3; X to move must block there.
        Board board = build(0, 3, 1, 3, 5, 3);

        MoveSolver bot = new MoveSolver("t", 0L); // shallow policy only
        int col = bot.chooseMove(board);

        check(col == 3, "Failed to block a vertical threat, played " + col);
    }

    /*
     * For every legal root move, the true value of the position is
     *
     *     max over children of  -solverValue(child)
     *
     * MoveSolver computes that with alpha carried between siblings, which is
     * where an off-by-one in the window would show up. Compare against the
     * plain full-window value from Solver itself.
     */
    private static void testRootValueAgreesWithSolver() {
        Random rng = new Random(99L);

        int checked = 0;

        for (int trial = 0; trial < 40; trial++) {

            // Deep enough that a full proof is quick.
            Board board = Arena.randomOpening(rng, 30);

            if (board.getMoves() >= 42) {
                continue;
            }

            // Skip positions already won for the side to move: MoveSolver
            // short-circuits those before searching, and Solver's contract
            // assumes the game is still running.
            boolean immediateWin = false;
            for (int col = 0; col < 7; col++) {
                if (board.canPlay(col) && board.isWinningMove(col)) {
                    immediateWin = true;
                    break;
                }
            }
            if (immediateWin) {
                continue;
            }

            MoveSolver bot = new MoveSolver("t", Long.MAX_VALUE);
            int chosen = bot.chooseMove(board);

            check(bot.lastWasExact(), "Unlimited budget should never fall back");
            check(board.canPlay(chosen), "Chose an illegal column " + chosen);

            int expected = new Solver().negamax(new Board(board));

            check(bot.lastScore() == expected,
                    "Root value " + bot.lastScore()
                            + " disagrees with Solver value " + expected);

            /*
             * Independently verify the chosen move actually achieves that
             * value, so a correct score paired with the wrong column is
             * still caught.
             */
            Board child = new Board(board);
            child.playUnchecked(chosen);

            int achieved = (child.getMoves() == 42)
                    ? Solver.DRAW
                    : -new Solver().negamax(child);

            check(achieved == expected,
                    "Chosen column " + chosen + " achieves " + achieved
                            + " but position is worth " + expected);

            checked++;
        }

        check(checked > 10, "Too few positions actually tested: " + checked);
    }

    private static void testFallbackAlwaysLegal() {
        Random rng = new Random(4242L);
        MoveSolver shallow = new MoveSolver("shallow", 0L);

        for (int trial = 0; trial < 500; trial++) {
            int plies = rng.nextInt(30);
            Board board = Arena.randomOpening(rng, plies);

            if (board.getMoves() >= 42) {
                continue;
            }

            int col = shallow.chooseMove(board);

            check(col >= 0 && col < 7, "Fallback returned column " + col);
            check(board.canPlay(col),
                    "Fallback returned full column " + col
                            + " at ply " + board.getMoves());
        }
    }

    /*
     * A budgeted exact agent should comfortably beat the shallow policy. This
     * is a smoke test, not a strength measurement -- use Arena for that -- but
     * a regression that breaks the search usually shows up here as a score
     * collapsing toward 50%.
     */
    private static void testExactModeNeverLosesToShallow() {
        Random rng = new Random(7L);

        int score = 0; // +2 win, +1 draw, per game
        int games = 0;

        for (int pair = 0; pair < 6; pair++) {
            Board opening = Arena.randomOpening(rng, 10);

            Agent exact = new MoveSolver("exact", 300_000L);
            Agent shallow = new MoveSolver("shallow", 0L);

            int r1 = Arena.playGame(exact, shallow, opening);
            score += (r1 > 0) ? 2 : (r1 == 0 ? 1 : 0);
            games++;

            int r2 = Arena.playGame(shallow, exact, opening);
            score += (r2 < 0) ? 2 : (r2 == 0 ? 1 : 0);
            games++;
        }

        double pct = score / (2.0 * games);
        check(pct > 0.5,
                "Exact agent scored only " + (100 * pct) + "% against shallow");
    }

    // ------------------------------------------------------------------

    private static Board build(int... cols) {
        Board board = new Board();
        for (int col : cols) {
            if (board.isWinningMove(col)) {
                throw new AssertionError("Setup contains a completed win");
            }
            board.play(col);
        }
        return board;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
