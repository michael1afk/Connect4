# Connect Four — 30-minute presentation kit

Theme you're selling: **engineering judgment & rigor**, not algorithm trivia.

---

## Files in this kit

- `Board_ANNOTATED.java` — presenter notes marked `// ▶`
- `HeuristicSolver_ANNOTATED.java` — presenter notes marked `// ▶`
- `Arena_ANNOTATED.java` — presenter notes marked `// ▶` (your closer)
- `Demo.java` — polished CLI for the live demo (drives the real exact solver)

The `// ▶` lines say **what to say**, not what the code does. Read them while
sharing your screen. Delete them from your real repo afterwards — they're
presentation scaffolding.

> Note: the annotated files are your ORIGINALS plus comments. `Board` and
> `Arena` compile as-is. `HeuristicSolver` as uploaded calls `board.position()`
> / `board.mask()`, which your uploaded `Board` doesn't expose — so present it
> as code-you-walk-through, **don't try to run it live**. Demo runs the exact
> solver, which is fully working.

---

## Running the demo

Compile once (from the folder with your .java files + Demo.java):

```
javac -d out Demo.java Board.java Agent.java MoveSolver.java Solver.java TranspositionTable.java SearchAbortedException.java
```

Run it:

```
java -cp out Demo first 200000          # bot moves first, 200k node budget
java -cp out Demo first 200000 ascii    # X/O/. instead of ● discs — USE THIS IF UNSURE
java -cp out Demo second 2000000        # you first, deeper/slower bot
java -cp out Demo first 200000 nocolor ascii   # plainest possible, safest for Zoom
```

**Before the call: run it once in the exact terminal you'll screen-share.**
If the discs show as `?`, add `ascii`. If you see `[1;33m` garbage, add
`nocolor`. `nocolor ascii` together is the bulletproof combo.

Make the terminal font BIG (Ctrl-+ a few times) before sharing.

---

## The 30-minute shape

| Time  | Segment | The one thing to land |
|-------|---------|-----------------------|
| 0–2m  | Framing | Two questions: "is it correct?" vs "is it strong?" They're different. |
| 2–7m  | `Board` | The whole game is two 64-bit ints. Show the perspective flip + shift-based win check. |
| 7–17m | `HeuristicSolver` | Iterative deepening + the "throw away a half-finished depth" rule. |
| 17–27m| `Arena` | **The peak.** Three measurement problems you backed into. |
| 27–30m| Demo + close | Run `Demo`, show shallow→PROVEN flip, end on a known limitation. |

You can also open with a 60-second `Demo` game to hook them, then go to code.
Either works; don't do the demo twice.

---

## Arena as three problems (your strongest 10 minutes)

Tell it as a story where each fix creates the next problem:

1. **"Two deterministic bots replay the identical game — 50 games is 1 game of
   info, 50 times."** → Fix: random openings.
2. **"But now a lucky favorable opening looks like strength."** → Fix: play each
   opening twice with the seats swapped. Bias hits both agents, cancels exactly.
3. **"I see 52% over 40 games — did my change work?"** → Fix: the error bar.
   71% ± 6.4%. *"This is what stops me fooling myself. 52% over 40 games usually
   means nothing."*

That progression is the whole "judgment" thesis in three beats.

---

## The close (do NOT skip this)

End on a limitation you found yourself — it's the highest-credibility way to
finish a rigor-themed talk. Pick ONE:

- "The heuristic solver allocates two `int[7]` arrays at every node — that's GC
  pressure I'd preallocate by `ply`, the same way I already do killers. I know
  it's there; I benchmarked correctness before optimizing it."
- "The exact transposition table has no depth field. Fine for W/D/L proofs,
  which are depth-independent — but the moment it stores a heuristic score it
  becomes a bug. That's why the heuristic solver has its own depth-aware table."

Either sentence says: *I see my own work clearly.* That's what they're grading.

---

## Questions you should have a crisp answer to

- **"Does the evaluator's sign match negamax?"** — Yes: `evaluate` scores from
  the mover's view because `position` is always the mover. Wrong sign = every
  other level inverted = confident nonsense. It's the one silent bug; I'd test it.
- **"How do you know the bot plays optimally late-game?"** — The exact `Solver`
  proves W/D/L. Open it here if pushed; that's its cameo.
- **"Why Java, not C++?"** — JVM is within ~1.5–2× here; the bottleneck is the
  search tree, not the language. Time budget was the real constraint.
- **"Why bitboards?"** — Win check is ~8 bit ops with no loops; play/undo is a
  few XORs with zero allocation. It's what makes millions of nodes/sec possible.

---

## Panic buttons

- Demo won't compile on the call → you have stale `.class` files. `find . -name '*.class' -delete` then recompile. Or just present the annotated code; the demo is a bonus, not the spine.
- Discs look wrong on share → add `ascii`. Colors look wrong → add `nocolor`.
- Someone asks about a file you cut (`TranspositionTable`, `Threats`, etc.) →
  "That's supporting cast for what I just showed — happy to open it if useful."
