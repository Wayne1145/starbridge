package dev.starbridge.block;

/**
 * Bills a refreshed wall-clock thrust lease once for each interval it actually covers.
 *
 * <p>The drive refreshes a 250 ms physics lease every game tick. Earlier builds charged the full
 * lease on every refresh, so overlapping leases cost about five times the delivered thrust. This
 * meter keeps the safety property (the first refresh prepays the whole tail, so a stalled server
 * never receives unpaid force) while a steady stream only pays for newly covered time.</p>
 */
final class DriveLeaseMeter {
    private final long leaseNanos;
    private long paidUntilNanos;
    private boolean active;

    DriveLeaseMeter(long leaseNanos) {
        if (leaseNanos <= 0) throw new IllegalArgumentException("Lease must be positive");
        this.leaseNanos = leaseNanos;
    }

    /** Nanoseconds of not-yet-paid coverage that a refresh at {@code now} would add. */
    long uncoveredNanos(long now) {
        long end = now + leaseNanos;
        long start = active && paidUntilNanos - now > 0 ? paidUntilNanos : now;
        return Math.max(0, end - start);
    }

    void paid(long now) {
        paidUntilNanos = now + leaseNanos;
        active = true;
    }

    void reset() { active = false; }
}
