public class TranspositionTableTest {

    public static void main(String[] args) {
        testBasicPutGet();
        testReplacementForSameKey();
        testClear();
        testPacking();
        testFiniteCollisionReplacement();

        System.out.println("All TranspositionTable tests passed.");
    }

    private static void testBasicPutGet() {
        TranspositionTable table = new TranspositionTable();
        long key = 12345L;

        check(table.get(key) == 0, "New table should miss");

        table.put(key, 0, TranspositionTable.EXACT);
        int packed = table.get(key);

        check(packed != 0, "Stored entry was not found");
        check(TranspositionTable.score(packed) == 0, "Stored score was incorrect");
        check(TranspositionTable.bound(packed) == TranspositionTable.EXACT,
                "Stored bound was incorrect");
        check(table.size() == 1, "Expected one occupied slot");
    }

    private static void testReplacementForSameKey() {
        TranspositionTable table = new TranspositionTable();
        long key = 987654321L;

        table.put(key, 0, TranspositionTable.LOWER);
        table.put(key, 1, TranspositionTable.LOWER);

        int packed = table.get(key);
        check(TranspositionTable.score(packed) == 1,
                "Stronger LOWER bound should replace weaker one");

        table.put(key, 0, TranspositionTable.EXACT);
        table.put(key, -1, TranspositionTable.UPPER);

        packed = table.get(key);
        check(TranspositionTable.score(packed) == 0,
                "Exact score should not be weakened");
        check(TranspositionTable.bound(packed) == TranspositionTable.EXACT,
                "Exact bound should be preserved");
    }

    private static void testClear() {
        TranspositionTable table = new TranspositionTable();
        table.put(1L, 1, TranspositionTable.EXACT);
        table.clear();

        check(table.get(1L) == 0, "clear() did not invalidate entry");
        check(table.size() == 0, "clear() did not reset occupied count");
    }

    private static void testPacking() {
        TranspositionTable table = new TranspositionTable();
        long key = 333L;

        int[] scores = {-1, 0, 1};
        int[] bounds = {
                TranspositionTable.EXACT,
                TranspositionTable.LOWER,
                TranspositionTable.UPPER
        };

        for (int score : scores) {
            for (int bound : bounds) {
                table.clear();
                table.put(key, score, bound);
                int packed = table.get(key);

                check(TranspositionTable.score(packed) == score,
                        "Score packing failed for " + score);
                check(TranspositionTable.bound(packed) == bound,
                        "Bound packing failed for " + bound);
            }
        }
    }

    private static void testFiniteCollisionReplacement() {
        TranspositionTable table = new TranspositionTable();

        // Same index because key2 = key1 + CAPACITY, but different low 32-bit key.
        long key1 = 123456L;
        long key2 = key1 + TranspositionTable.CAPACITY;

        table.put(key1, -1, TranspositionTable.EXACT);
        table.put(key2, 1, TranspositionTable.EXACT);

        check(table.get(key1) == 0,
                "Old colliding key should have been replaced");

        int packed = table.get(key2);
        check(packed != 0, "Newest colliding key should remain");
        check(TranspositionTable.score(packed) == 1,
                "Collision replacement stored wrong score");
        check(table.getCollisionReplacements() == 1,
                "Expected one collision replacement");
        check(table.size() == 1,
                "Collision replacement should still occupy one slot");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
