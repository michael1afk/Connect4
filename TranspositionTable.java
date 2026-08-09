import java.util.Arrays;

/**
 * Fixed-size, allocation-free transposition table specialized for this 7x6
 * Connect Four solver.
 *
 * Why this is faster than HashMap<Long, TTEntry>:
 *   1. No Long boxing.
 *   2. No TTEntry object allocation.
 *   3. No buckets / linked nodes / resizing.
 *   4. Direct array indexing.
 *   5. Only 5 bytes of payload per slot: 4-byte partial key + 1-byte value.
 *
 * The board's unique key uses at most 49 bits. We index the table with:
 *
 *     index = key % TABLE_SIZE
 *
 * and store only the low 32 bits of the key. TABLE_SIZE is an odd prime just
 * above 2^23. Because TABLE_SIZE and 2^32 are coprime and
 * TABLE_SIZE * 2^32 is much larger than every 49-bit board key, the pair
 *
 *     (key % TABLE_SIZE, key % 2^32)
 *
 * uniquely identifies every possible Board.key(). Therefore truncating the
 * stored key to 32 bits cannot create a false cache hit.
 *
 * Different full keys can still map to the same table INDEX. This is an
 * ordinary finite-table collision. We use the common transposition-table
 * policy of replacing the old slot with the newest entry.
 */
public final class TranspositionTable {

    /*
     * Smallest prime >= 2^23.
     *
     * Payload memory:
     *   int[]  keys   -> ~32 MiB
     *   byte[] values -> ~ 8 MiB
     *                  --------
     *                  ~40 MiB
     */
    public static final int CAPACITY = 8_388_617;

    /*
     * Bound codes. Zero is intentionally unused because an encoded value of
     * zero marks an empty table slot.
     */
    public static final int EXACT = 1;
    public static final int LOWER = 2;
    public static final int UPPER = 3;

    private final int[] keys = new int[CAPACITY];
    private final byte[] values = new byte[CAPACITY];

    private int entries;
    private long collisionReplacements;

    /**
     * Return the packed table value for key, or 0 on a miss.
     *
     * Hot-path design: returning an int avoids allocating a TTEntry object.
     */
    public int get(long key) {
        int index = index(key);
        int packed = values[index] & 0xFF;

        if (packed != 0 && keys[index] == (int) key) {
            return packed;
        }

        return 0;
    }

    /**
     * Store score/bound information for key.
     *
     * A finite direct-mapped table has one slot per index. If a different key
     * is already there, the newest result replaces it. Recent search states
     * tend to be the most useful states to keep during DFS.
     */
    public void put(long key, int score, int bound) {
        int index = index(key);
        int oldPacked = values[index] & 0xFF;
        int partialKey = (int) key;

        if (oldPacked == 0) {
            entries++;
        } else if (keys[index] != partialKey) {
            collisionReplacements++;
        } else {
            /*
             * Same position already cached. Avoid replacing exact information
             * with a weaker bound. For equal bound types, keep the stronger
             * bound when possible.
             */
            int oldBound = bound(oldPacked);
            int oldScore = score(oldPacked);

            if (oldBound == EXACT && bound != EXACT) {
                return;
            }

            if (bound != EXACT && oldBound == bound) {
                if (bound == LOWER && oldScore >= score) {
                    return; // larger LOWER bound is stronger
                }
                if (bound == UPPER && oldScore <= score) {
                    return; // smaller UPPER bound is stronger
                }
            }

            /* Opposite bounds meeting at the same value prove exactness. */
            if (oldBound != EXACT && bound != EXACT
                    && oldBound != bound && oldScore == score) {
                bound = EXACT;
            }
        }

        keys[index] = partialKey;
        values[index] = (byte) pack(score, bound);
    }

    /** Number of currently occupied slots, not number of puts ever made. */
    public int size() {
        return entries;
    }

    public int capacity() {
        return CAPACITY;
    }

    public long getCollisionReplacements() {
        return collisionReplacements;
    }

    /**
     * Zeroing values is enough. A zero value means empty, so the stale key
     * array never gets consulted. This clears ~8 MiB instead of ~40 MiB.
     */
    public void clear() {
        Arrays.fill(values, (byte) 0);
        entries = 0;
        collisionReplacements = 0;
    }

    /** Decode score from a nonzero packed value. */
    public static int score(int packed) {
        return (packed & 0b11) - 1;
    }

    /** Decode bound type from a nonzero packed value. */
    public static int bound(int packed) {
        return packed >>> 2;
    }

    /**
     * score is one of -1, 0, +1 -> encoded as 0, 1, 2 in the low two bits.
     * bound is 1..3 and occupies the higher bits, guaranteeing packed != 0.
     */
    private static int pack(int score, int bound) {
        if (score < -1 || score > 1) {
            throw new IllegalArgumentException("Score must be -1, 0, or 1");
        }
        if (bound < EXACT || bound > UPPER) {
            throw new IllegalArgumentException("Invalid bound: " + bound);
        }

        return (bound << 2) | (score + 1);
    }

    private static int index(long key) {
        // Board.key() is always non-negative and below 2^49.
        return (int) (key % CAPACITY);
    }
}
