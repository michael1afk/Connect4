import java.util.Random;

/**
 * Uniformly random legal move.
 *
 * This exists as a floor, not as a real opponent. A working solver should beat
 * it in essentially every game; if it does not, something is broken in the
 * search or in Arena's game loop, and you want to find that out before you
 * start comparing two solver configurations that are close in strength.
 */
public final class RandomAgent implements Agent {

    private final Random rng;
    private final String name;

    public RandomAgent(String name, long seed) {
        this.name = name;
        this.rng = new Random(seed);
    }

    @Override
    public int chooseMove(Board board) {
        int[] legal = new int[7];
        int n = 0;

        for (int col = 0; col < 7; col++) {
            if (board.canPlay(col)) {
                legal[n++] = col;
            }
        }

        if (n == 0) {
            throw new IllegalStateException("chooseMove called on a full board");
        }

        return legal[rng.nextInt(n)];
    }

    @Override
    public String name() {
        return name;
    }
}
