import java.util.Scanner;

/**
 * Human versus bot on the command line.
 *
 * Usage:
 *   java Play                       bot second, 1 second per move
 *   java Play first                 bot moves first
 *   java Play second 3000           bot second, 3 seconds per move
 *
 * X is player one (whoever moves first), O is player two.
 *
 * After each bot move it prints how the move was found:
 *
 *   EXACT      proven optimal by full search to the end of the game
 *   d12 +145   heuristic search, completed depth 12, evaluation +145
 *   d12 MATE   heuristic search that found a forced win inside its horizon
 *
 * Watch the tag flip from a depth to EXACT partway through the game. From that
 * point on the bot is playing perfectly and the result is already decided.
 */
public final class Play {

    public static void main(String[] args) {

        boolean botFirst = args.length > 0 && args[0].equalsIgnoreCase("first");
        long millis = args.length > 1 ? Long.parseLong(args[1]) : 1000L;

        /*
         * Exact budget scaled to the time budget: roughly the number of nodes
         * the exact solver gets through in the same wall clock, so neither
         * stage dominates the move time.
         */
        long exactBudget = Math.max(50_000L, millis * 3_000L);

        HybridAgent bot = new HybridAgent("bot", exactBudget, millis);
        Board board = new Board();
        Scanner in = new Scanner(System.in);

        int botTurn = botFirst ? 0 : 1;

        System.out.println("You are " + (botFirst ? "O (second)" : "X (first)"));
        System.out.println("Bot budget: " + millis + " ms/move, "
                + exactBudget + " exact nodes");
        System.out.println("Enter a column 0-6, or 'q' to quit.\n");

        while (true) {

            System.out.println(board.render());

            if (board.getMoves() == 42) {
                System.out.println("Board full. Draw.");
                return;
            }

            int turn = board.getMoves() % 2;
            boolean isBot = (turn == botTurn);
            int col;

            if (isBot) {
                long t0 = System.nanoTime();
                col = bot.chooseMove(board);
                double ms = (System.nanoTime() - t0) / 1e6;

                System.out.printf("Bot plays %d   [%s, %d nodes, %.0f ms]%n",
                        col, describe(bot), bot.lastNodes(), ms);
            } else {
                col = readColumn(in, board);
                if (col == -1) {
                    System.out.println("Bye.");
                    return;
                }
            }

            boolean winning = board.isWinningMove(col);
            board.play(col);

            if (winning) {
                System.out.println(board.render());
                System.out.println(isBot ? "Bot wins." : "You win.");
                return;
            }
        }
    }

    private static String describe(HybridAgent bot) {
        if (bot.lastWasExact()) {
            if (bot.lastScore() == Solver.WIN)  return "EXACT: bot wins";
            if (bot.lastScore() == Solver.LOSS) return "EXACT: bot is lost";
            return "EXACT: draw";
        }

        int score = bot.lastScore();

        if (Math.abs(score) > Evaluator.MAX_EVAL) {
            return String.format("d%d %s",
                    bot.lastDepth(), score > 0 ? "MATE for bot" : "MATE against bot");
        }

        return String.format("d%d %+d", bot.lastDepth(), score);
    }

    private static int readColumn(Scanner in, Board board) {
        while (true) {
            System.out.print("Your move: ");

            if (!in.hasNext()) {
                return -1;
            }

            String token = in.next().trim();

            if (token.equalsIgnoreCase("q")) {
                return -1;
            }

            int col;
            try {
                col = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                System.out.println("  Not a number. Try 0-6.");
                continue;
            }

            if (col < 0 || col >= 7) {
                System.out.println("  Out of range. Columns are 0-6.");
                continue;
            }
            if (!board.canPlay(col)) {
                System.out.println("  Column " + col + " is full.");
                continue;
            }

            return col;
        }
    }
}
