package com.cloudkitchen.db;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-transaction bookkeeping for the lease stamp protocol (SDD 7.11.15). {@link Database#inTransaction} binds one
 * context to the running thread; a DAO statement that needs a validity time asks {@link #stamp} for it as the very
 * last action before executing, which registers the earliest stamp {@code s0}. The guard then requires that both the
 * statement and the commit happen within {@code margin / 2} of {@code s0}.
 *
 * <p>A context is used by exactly one thread (one transaction per thread, no connection sharing).
 */
public final class LeaseStampContext {

    /** A validity timestamp and the point after which a lease still counts as usable. */
    public record Stamp(Instant now, Instant usableAfter, Duration margin) {
    }

    private static final ThreadLocal<LeaseStampContext> BOUND = new ThreadLocal<>();

    private final Clock clock;
    private final LeaseStampHook hook;
    private Instant earliest;
    private Duration margin;

    LeaseStampContext(Clock clock, LeaseStampHook hook) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hook = Objects.requireNonNull(hook, "hook");
    }

    static LeaseStampContext bind(Clock clock, LeaseStampHook hook) {
        LeaseStampContext context = new LeaseStampContext(clock, hook);
        BOUND.set(context);
        return context;
    }

    static void unbind() {
        BOUND.remove();
    }

    /**
     * Reads {@code clock} as the validity time for one statement. Inside a transaction the stamp is registered with
     * the bound context; outside one (auto-commit reads) it is simply returned.
     */
    public static Stamp stamp(Clock clock, Duration margin, LeaseStampHook hook) {
        Instant now = clock.instant();
        LeaseStampContext context = BOUND.get();
        if (context != null) {
            context.register(now, margin);
        }
        Stamp stamp = new Stamp(now, now.plus(margin), margin);
        hook.at(LeaseStampHook.Phase.AFTER_STAMP);
        return stamp;
    }

    /**
     * Reads the clock for a statement that uses the <em>free</em> predicate (acquire, reconcile, sweeper): such a
     * statement needs no guard (S-3), so nothing is registered.
     */
    public static Instant freshNow(Clock clock, LeaseStampHook hook) {
        Instant now = clock.instant();
        hook.at(LeaseStampHook.Phase.AFTER_STAMP);
        return now;
    }

    private void register(Instant stamp, Duration stampMargin) {
        if (earliest == null || stamp.isBefore(earliest)) {
            earliest = stamp;
            margin = stampMargin;
        }
    }

    /** Called by the DAO right after a stamped statement returned; throws if the guard budget is exceeded. */
    public static void afterExecute(Clock clock, LeaseStampHook hook) throws SQLException {
        hook.at(LeaseStampHook.Phase.AFTER_EXECUTE);
        LeaseStampContext context = BOUND.get();
        if (context != null) {
            context.verify(clock.instant());
        }
    }

    /** Called by {@link Database} immediately before commit. */
    void beforeCommit() throws SQLException {
        hook.at(LeaseStampHook.Phase.BEFORE_COMMIT);
        verify(clock.instant());
    }

    private void verify(Instant now) throws SQLException {
        if (earliest != null && now.isAfter(earliest.plus(margin.dividedBy(2)))) {
            throw new StaleStampSqlException("lease statement or commit exceeded the guard budget of "
                    + margin.dividedBy(2).toMillis() + " ms after its stamp");
        }
    }
}
