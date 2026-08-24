/**
 * Thrown when a search exceeds its node budget.
 *
 * This is control flow, not an error, and it can be thrown from deep inside a
 * hot recursion. Two things make it cheap:
 *
 *   1. Stack trace capture is disabled via the four-argument Throwable
 *      constructor (writableStackTrace = false). Filling in a stack trace is
 *      by far the most expensive part of creating an exception in Java, and
 *      at a depth of 20+ frames it is not free.
 *
 *   2. A single shared INSTANCE is reused, so throwing allocates nothing.
 *      This is safe here because the exception carries no state and is caught
 *      immediately by the root driver on the same thread.
 *
 * If you ever run searches on multiple threads, the shared instance is still
 * fine (it is immutable), but you lose the ability to attach per-search
 * information to it.
 */
public final class SearchAbortedException extends RuntimeException {

    public static final SearchAbortedException INSTANCE =
            new SearchAbortedException();

    private SearchAbortedException() {
        super("Search exceeded its node budget", null, false, false);
    }
}
