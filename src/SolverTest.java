public class SolverTest {

    public static void main(String[] args) {
        testTablePersistsAcrossTopLevelSearches();
        System.out.println("All Solver tests passed.");
    }

    private static void testTablePersistsAcrossTopLevelSearches() {
        Board board = new Board();

        // Late legal position so the first exact solve is tiny.
        int[] moves = {
                4, 4, 1, 6, 3, 0, 6, 0,
                5, 3, 5, 4, 0, 0, 3, 2,
                1, 3, 4, 0, 4, 2, 6, 1,
                4, 6, 6, 0, 2, 3, 5, 1
        };

        for (int col : moves) {
            if (board.isWinningMove(col)) {
                throw new AssertionError("Test setup accidentally contains a win");
            }
            board.play(col);
        }

        Solver solver = new Solver();
        int first = solver.negamax(board);
        int entriesAfterFirst = solver.getTableSize();

        int second = solver.negamax(board);
        long secondNodes = solver.getNodesSearched();
        long secondHits = solver.getTableHits();

        check(first == second, "Repeated solve changed W/D/L result");
        check(entriesAfterFirst > 0, "First solve did not populate TT");
        check(secondNodes == 1, "Second solve should hit exact root entry");
        check(secondHits == 1, "Second solve should have exactly one root TT hit");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
