/**
 * Measures evaluator weights against each other.
 *
 * Every number in Evaluator.DEFAULT is a guess. Nobody -- not you, not me, not
 * the person who wrote the textbook -- can reason their way to whether the
 * parity weight should be 46 or 30. The only way to find out is to play games
 * and count.
 *
 * Two modes:
 *
 *   ablate   turn each feature off in turn and measure the damage. A feature
 *            whose removal costs nothing is not doing anything, and carrying
 *            it is worse than useless because it adds noise to every future
 *            experiment.
 *
 *   sweep    vary one weight across a range against the current default.
 *
 * Usage:
 *   java WeightTuner ablate [pairs]
 *   java WeightTuner sweep parity 20,30,46,70 [pairs]
 *
 * A word on reading the results: the +/- figure Arena prints is one standard
 * error. Differences smaller than about two of those are noise. Twenty-five
 * pairs gives roughly +/- 7%, which can separate "clearly better" from
 * "clearly worse" but cannot separate 52% from 48%. Turning a weight from 46
 * to 44 is not measurable at this sample size and is not worth your time.
 */
public final class WeightTuner {

    /* Kept short so a full ablation finishes in a couple of minutes. */
    private static final long EXACT_BUDGET = 0L;   // heuristic only, for isolation
    private static final long MILLIS = 20L;

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "ablate";

        switch (mode) {
            case "ablate" -> ablate(args.length > 1 ? Integer.parseInt(args[1]) : 20);
            case "sweep"  -> sweep(args);
            default -> {
                System.out.println("Usage:");
                System.out.println("  java WeightTuner ablate [pairs]");
                System.out.println("  java WeightTuner sweep <name> <v1,v2,...> [pairs]");
            }
        }
    }

    // ------------------------------------------------------------------

    private static void ablate(int pairs) {
        Evaluator.Weights d = Evaluator.DEFAULT;

        System.out.println("Ablation study -- each variant plays the full default.");
        System.out.println("A variant scoring near 50% means that feature is doing nothing.");
        System.out.println("Baseline weights: " + d);
        System.out.println();

        /*
         * Parity off: waiting threats count the same regardless of which row
         * they sit on. This is the single most important feature to verify,
         * because it is the one piece of Connect Four theory the search cannot
         * rediscover for itself.
         */
        run("no parity distinction", pairs,
                new Evaluator.Weights(d.offParityThreat, d.offParityThreat,
                        d.buriedThreat, d.immediateThreat, d.centerStone, d.innerStone));

        /* All threat awareness off: only centre control remains. */
        run("no threats at all", pairs,
                new Evaluator.Weights(0, 0, 0, 0, d.centerStone, d.innerStone));

        /* Centre control off. */
        run("no centre control", pairs,
                new Evaluator.Weights(d.parityThreat, d.offParityThreat,
                        d.buriedThreat, d.immediateThreat, 0, 0));

        /* Buried-threat discount off: a threat above an opponent's counts full. */
        run("no buried discount", pairs,
                new Evaluator.Weights(d.parityThreat, d.offParityThreat,
                        d.parityThreat, d.immediateThreat, d.centerStone, d.innerStone));

        /* Nothing at all: pure search with a constant evaluation. */
        run("null evaluator", pairs,
                new Evaluator.Weights(0, 0, 0, 0, 0, 0));
    }

    private static void sweep(String[] args) {
        if (args.length < 3) {
            System.out.println("sweep needs a weight name and a comma-separated value list");
            return;
        }

        String name = args[1];
        String[] values = args[2].split(",");
        int pairs = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        Evaluator.Weights d = Evaluator.DEFAULT;

        System.out.println("Sweeping '" + name + "' against the default " + d);
        System.out.println();

        for (String v : values) {
            int value = Integer.parseInt(v.trim());
            Evaluator.Weights w = withWeight(d, name, value);
            run(name + "=" + value, pairs, w);
        }
    }

    private static Evaluator.Weights withWeight(Evaluator.Weights d, String name, int v) {
        return switch (name) {
            case "parity"    -> new Evaluator.Weights(v, d.offParityThreat, d.buriedThreat,
                                                      d.immediateThreat, d.centerStone, d.innerStone);
            case "offparity" -> new Evaluator.Weights(d.parityThreat, v, d.buriedThreat,
                                                      d.immediateThreat, d.centerStone, d.innerStone);
            case "buried"    -> new Evaluator.Weights(d.parityThreat, d.offParityThreat, v,
                                                      d.immediateThreat, d.centerStone, d.innerStone);
            case "immediate" -> new Evaluator.Weights(d.parityThreat, d.offParityThreat, d.buriedThreat,
                                                      v, d.centerStone, d.innerStone);
            case "centre", "center"
                             -> new Evaluator.Weights(d.parityThreat, d.offParityThreat, d.buriedThreat,
                                                      d.immediateThreat, v, d.innerStone);
            case "inner"     -> new Evaluator.Weights(d.parityThreat, d.offParityThreat, d.buriedThreat,
                                                      d.immediateThreat, d.centerStone, v);
            default -> throw new IllegalArgumentException("Unknown weight: " + name);
        };
    }

    /*
     * The variant plays FIRST so that Arena's reported score belongs to it.
     * Below 50% means the variant is worse than the default, which for an
     * ablation is the expected and desirable result.
     */
    private static void run(String label, int pairs, Evaluator.Weights variant) {
        Agent a = new HybridAgent(label, EXACT_BUDGET, MILLIS,
                HeuristicSolver.MAX_DEPTH, new Evaluator(variant));

        Agent b = new HybridAgent("default", EXACT_BUDGET, MILLIS,
                HeuristicSolver.MAX_DEPTH, new Evaluator(Evaluator.DEFAULT));

        Arena.runMatch(a, b, pairs, 8, 20260809L);
        System.out.println();
    }
}
