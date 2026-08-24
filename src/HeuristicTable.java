import java.util.Arrays;

/**
 * Transposition table for the depth-limited heuristic search.
 *
 * ---------------------------------------------------------------------------
 * Why this cannot be the existing TranspositionTable
 * ---------------------------------------------------------------------------
 *
 * TranspositionTable stores exact win/draw/loss verdicts. Those are facts
 * about a position and stay true forever, which is why Solver deliberately
 * never clears it between moves.
 *
 * A heuristic score is not a fact about a position. "+120 when searched six
 * ply deep" is a statement about the position AND the depth. Reuse it during a
 * twelve-ply search and you have silently substituted a shallow guess for a
 * deep answer -- a bug that produces no crash, no failing assertion, and a bot
 * that is quietly worse. So every entry here carries the depth it was computed
 * at, and a lookup is only honoured when the stored depth is at least as deep
 * as the one being asked for.
 *
 * ---------------------------------------------------------------------------
 * Layout
 * ---------------------------------------------------------------------------
 *
 * Four parallel primitive arrays, power-of-two size so indexing is a mask
 * rather than a division. No boxing, no per-entry objects, no resizing, and
 * therefore no garbage collection during a search.
 *
 *   keys    long[]   full 49-bit board key; compared exactly, so no false hits
 *   scores  short[]  fits every score the search produces
 *   depths  byte[]   0 means empty
 *   flags   byte[]   EXACT / LOWER / UPPER
 *
 * Depth is stored as depth+1 so that a genuinely-zero-depth entry is
 * distinguishable from an empty slot.
 *
 * ---------------------------------------------------------------------------
 * Replacement policy
 * ---------------------------------------------------------------------------
 *
 * Depth-preferred with an age override. A deeper entry is more expensive to
 * recompute, so it wins ties against a shallower newcomer -- but only within
 * the same search. Once a new move begins, any entry from an older search is
 * replaceable regardless of depth, which stops the table silting up with deep
 * results from positions that can no longer occur.
 */
public final class HeuristicTable {

    public static final int EXACT = 1;
    public static final int LOWER = 2;
    public static final int UPPER = 3;

    /** Sentinel returned by probe() when the entry is unusable. */
    public static final int MISS = Integer.MIN_VALUE;

    private final int mask;
    private final long[] keys;
    private final short[] scores;
    private final byte[] depths;
    private final byte[] flags;
    private final byte[] ages;

    private byte currentAge;

    private long probes;
    private long hits;
    private long cutoffs;

    /**
     * @param sizeLog2 table holds 2^sizeLog2 entries. 22 gives ~4.2M slots and
     *                 about 50 MB, which is comfortable inside a default heap.
     */
    public HeuristicTable(int sizeLog2) {
        if (sizeLog2 < 10 || sizeLog2 > 26) {
            throw new IllegalArgumentException("sizeLog2 out of range: " + sizeLog2);
        }

        int size = 1 << sizeLog2;
        this.mask = size - 1;
        this.keys = new long[size];
        this.scores = new short[size];
        this.depths = new byte[size];
        this.flags = new byte[size];
        this.ages = new byte[size];
    }

    /**
     * Mark the start of a new root search. Entries from previous searches stay
     * readable -- they are still useful -- but become preferred candidates for
     * replacement.
     */
    public void newSearch() {
        currentAge++;
    }

    /**
     * Look up a position.
     *
     * Returns MISS if there is no entry, if the entry was computed at a
     * shallower depth than requested, or if the stored bound cannot resolve
     * the current window. Otherwise returns a usable score.
     *
     * The bound logic matters. An EXACT entry is always usable. A LOWER bound
     * says the true score is at least this, which only settles matters if that
     * already reaches beta. An UPPER bound says at most this, which only
     * settles matters if it already fails to reach alpha.
     */
    public int probe(long key, int depth, int alpha, int beta) {
        probes++;

        int i = (int) (key & mask);

        if (depths[i] == 0 || keys[i] != key) {
            return MISS;
        }

        int storedDepth = depths[i] - 1;
        if (storedDepth < depth) {
            return MISS; // too shallow to trust
        }

        hits++;

        int score = scores[i];

        /*
         * The low two bits are the bound type; the best move is packed above
         * them. Forgetting this mask makes every flag comparison fail and
         * silently disables all TT cutoffs.
         */
        int flag = flags[i] & 0b11;

        if (flag == EXACT) {
            cutoffs++;
            return score;
        }
        if (flag == LOWER && score >= beta) {
            cutoffs++;
            return score;
        }
        if (flag == UPPER && score <= alpha) {
            cutoffs++;
            return score;
        }

        return MISS;
    }

    /**
     * Retrieve the best move recorded for a position regardless of depth.
     *
     * A stored move that is too shallow to trust as a SCORE is still an
     * excellent ordering hint, and trying it first is often worth more than
     * the score would have been.
     *
     * @return column 0-6, or -1 if unknown
     */
    public int probeMove(long key) {
        int i = (int) (key & mask);

        if (depths[i] == 0 || keys[i] != key) {
            return -1;
        }

        int move = ((flags[i] & 0xFF) >>> 2) - 1;
        return (move >= 0 && move < 7) ? move : -1;
    }

    /**
     * Store a result.
     *
     * @param bestMove column that produced this score, or -1 if unknown
     */
    public void store(long key, int score, int depth, int flag, int bestMove) {
        if (depth < 0 || depth > 126) {
            return; // outside what a byte can hold; not worth storing
        }
        if (score < Short.MIN_VALUE || score > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Score does not fit in a short: " + score);
        }

        int i = (int) (key & mask);

        if (depths[i] != 0) {
            boolean sameSearch = ages[i] == currentAge;
            boolean shallower = (depths[i] - 1) > depth;

            if (sameSearch && shallower && keys[i] != key) {
                return; // keep the deeper result from this same search
            }
        }

        int packedMove = (bestMove >= 0 && bestMove < 7) ? (bestMove + 1) : 0;

        keys[i] = key;
        scores[i] = (short) score;
        depths[i] = (byte) (depth + 1);
        flags[i] = (byte) (flag | (packedMove << 2));
        ages[i] = currentAge;
    }

    public void clear() {
        Arrays.fill(depths, (byte) 0);
        probes = 0;
        hits = 0;
        cutoffs = 0;
        currentAge = 0;
    }

    public long getProbes()  { return probes; }
    public long getHits()    { return hits; }
    public long getCutoffs() { return cutoffs; }

    public int capacity() {
        return mask + 1;
    }
}
