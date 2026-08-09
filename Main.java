public class Main {

    public static void main(String[] args) {

        /*
         * Hardcoded game position.
         *
         * Each number is a column in move order:
         *
         *     0 1 2 3 4 5 6
         *
         * Board.play() automatically alternates player perspective.
         * Change this array to test another legal position.
         */
        int[] moves = {
                3,3,3,3,3,4,4
        };

        Board board = new Board();

        for (int i = 0; i < moves.length; i++) {
            int col = moves[i];

            if (col < 0 || col >= 7) {
                throw new IllegalArgumentException(
                        "Invalid column " + col + " at move index " + i
                );
            }

            if (!board.canPlay(col)) {
                throw new IllegalArgumentException(
                        "Column " + col + " is full at move index " + i
                );
            }

            /*
             * Reject move lists that continue after the game should already
             * have ended. This keeps test positions legal.
             */
            if (board.isWinningMove(col)) {
                throw new IllegalArgumentException(
                        "Game already ends with a win at move index "
                                + i + ", column " + col
                );
            }

            board.play(col);
        }

        System.out.println("Position loaded.");
        System.out.println("Moves played: " + board.getMoves());
        System.out.println("Player to move: "
                + (board.getMoves() % 2 == 0 ? "Player 1" : "Player 2"));

        Solver solver = new Solver();

        System.out.println("Running alpha-beta negamax + transposition table...");

        long start = System.nanoTime();
        int result = solver.negamax(board);
        long end = System.nanoTime();

        double seconds = (end - start) / 1_000_000_000.0;
        long nodes = solver.getNodesSearched();

        System.out.println();

        if (result == Solver.WIN) {
            System.out.println("Result: WIN");
        } else if (result == Solver.DRAW) {
            System.out.println("Result: DRAW");
        } else if (result == Solver.LOSS) {
            System.out.println("Result: LOSS");
        } else {
            System.out.println("Unexpected result: " + result);
        }

        System.out.println("Nodes searched: " + nodes);
        System.out.println("TT entries: " + solver.getTableSize()
                + " / " + solver.getTableCapacity());
        System.out.println("TT collision replacements: "
                + solver.getTableCollisionReplacements());
        System.out.println("TT hits: " + solver.getTableHits());
        System.out.println("TT cutoffs: " + solver.getTableCutoffs());
        System.out.printf("Search time: %.6f seconds%n", seconds);

        if (seconds > 0.0) {
            System.out.printf("Nodes/second: %.0f%n", nodes / seconds);
        }
    }
}
