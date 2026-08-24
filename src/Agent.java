/**
 * Anything that can pick a column.
 *
 * This is the seam that makes Arena useful. Today the only implementations
 * are MoveSolver and RandomAgent; when the evaluator lands, the heuristic
 * search implements this too and can be measured against the exact solver
 * without changing a line of Arena.
 *
 * Contract: chooseMove() must return a column c with 0 <= c < 7 and
 * board.canPlay(c) == true. It must not mutate the board it is given.
 */
public interface Agent {

    int chooseMove(Board board);

    String name();
}
