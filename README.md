# Connect Four bot

Bitboard search with a threat-based evaluator. Pure search, no machine learning.

## Running it

```bash
javac *.java

java -Xmx2g Play              # you vs the bot (1000 ms/move)
java -Xmx2g Play first 2000   # bot moves first, 2 s/move
java -Xmx2g Arena             # 50 bot-vs-bot games
java -Xmx2g WeightTuner ablate 20
```

`-Xmx2g` matters: the two transposition tables together want roughly 100 MB, and
the default heap on some machines is smaller than that.

## The two engines

| | `Solver` | `HeuristicSolver` |
|---|---|---|
| answers | win / draw / loss, proven | a score, at a depth |
| searches to | end of game | clock runs out |
| value good | forever | only at that depth |
| table | `TranspositionTable` (no depth field) | `HeuristicTable` (depth field) |

`HybridAgent` tries the exact solver first under a node budget. If it aborts, it
falls back to iterative-deepening heuristic search on the clock. Early game every
move takes the heuristic route; somewhere in the midgame the exact proof starts
landing and the bot is perfect from there to the end. `Play` prints which route
each move took.

The two tables are deliberately separate. A proven win/draw/loss is true forever,
which is why `Solver`'s table is never cleared and why the aborted early searches
are not wasted — the finished subtrees stay cached and are largely why the first
successful proof comes back so cheaply. A heuristic score is only true at the
depth it was computed, so it needs a depth field and cannot share that table.

## Files

**Core** — `Board` (bitboard, 7-bit column stride, sentinel row), `Threats`
(threat bitboards), `Evaluator` (positional scoring), `Solver` + `TranspositionTable`
(exact), `HeuristicSolver` + `HeuristicTable` (heuristic), `HybridAgent` (the bot).

**Drivers** — `Play` (human vs bot), `Arena` (bot vs bot), `WeightTuner`
(ablation and sweeps), `Agent` / `MoveSolver` / `RandomAgent` (baselines).

**Tests** — `BoardTest`, `ThreatsTest`, `TranspositionTableTest`, `SolverTest`,
`MoveSolverTest`, `HeuristicSolverTest`. All pass.

## Evaluator

Everything derives from `Threats.computeWinningPositions()`: given a player's
stones, which empty cells complete a four. Verified against a brute-force
reference on tens of thousands of random positions.

- **Threat parity.** Rows 0, 2, 4 (from the bottom) tend to fall to the first
  player, rows 1, 3, 5 to the second, because the board holds 42 cells and play
  strictly alternates. A waiting threat on your own parity is close to a slow
  forced win; the same threat one row up can be worth almost nothing. Exact in
  zugzwang endings, approximate in the middlegame.
- **Buried threats.** A threat above an opponent threat in the same column is
  mostly dead — they reach theirs first.
- **Immediate threats.** Weighted lightly on purpose: the search sees these at
  depth 2 without help.
- **Centre control.** Small tie-breaker.

Output is clamped to ±900, well below the mate scores, so a proven win always
outranks positional optimism.

## Measured results

Arena, paired openings (each opening played twice, colours swapped), 8 random
opening plies. The ± is one standard error; treat differences under about two of
those as noise.

| matchup | score |
|---|---|
| hybrid vs one-ply shallow baseline | 79.0% ±5.8 (50 games) |
| **hybrid vs previous bot (exact-200k)** | **67.9% ±4.3 (120 games)** |

Feature ablation, 40 games each, heuristic-only so the exact solver cannot mask
differences:

| feature removed | score vs default |
|---|---|
| null evaluator (everything off) | 30.0% ±7.2 |
| parity distinction | 32.5% ±7.4 |
| all threat terms | 32.5% ±7.4 |
| buried-threat discount | 40.0% ±7.7 |
| centre control | 47.5% ±7.9 |

Parity and threat detection carry the evaluator. **Centre control is not
measurably earning its place** — it lands within one standard error of 50% and
moved between 43.8% and 47.5% across runs. It is cheap and theoretically
motivated, so it stays, but it should not be described as a proven win. Anything
under about 20 games per side cannot resolve it either way.

## Search

~9M nodes/sec in Java. Depth 13–16 in ~550 ms from the opening.

Alpha-beta negamax with iterative deepening, mate-distance pruning, absolute mate
scores (`MATE - ply`, so no distance adjustment is needed when storing to the
table), forced-block and double-threat detection, "never play directly beneath an
opponent's winning cell" pruning, and move ordering by TT move, then killers,
then threat creation, then centre proximity.

## Tuning

Every weight in `Evaluator.DEFAULT` is a guess. `WeightTuner` is how you find out
whether a different guess is better:

```bash
java -Xmx2g WeightTuner ablate 25
java -Xmx2g WeightTuner sweep parity 20,30,46,70 25
```

Twenty-five pairs resolves to roughly ±7%. That separates "clearly better" from
"clearly worse" and cannot separate 52% from 48%. Changing a weight from 46 to 44
is not measurable at that sample size.
