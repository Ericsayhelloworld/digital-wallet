package com.example.wallet.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * CON-3: if PostgreSQL aborts a transaction because of a deadlock or a serialization failure,
 * the whole transaction is run again (up to 5 times), so the client never sees that error.
 * Spring turns both PostgreSQL errors (40P01, 40001) into a ConcurrencyFailureException.
 */
public final class Retry {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);
    private static final int MAX_ATTEMPTS = 5;

    private Retry() {
    }

    /** Runs a transaction and returns its result; repeats it after a database conflict. */
    public static <T> T onConflict(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Database conflict ({}); running the transaction again, attempt {} of {}",
                        e.getClass().getSimpleName(), attempt + 1, MAX_ATTEMPTS);
                try {
                    // a short random pause, so that the two conflicting transactions do not collide again
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Same, for a transaction without a result. */
    public static void onConflictRun(Runnable transaction) {
        onConflict(() -> {
            transaction.run();
            return null;
        });
    }
}
