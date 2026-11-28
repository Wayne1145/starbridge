package dev.starbridge.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DriveLeaseMeterTest {
    private static final long MS = 1_000_000L;

    @Test
    void firstRefreshPrepaysTheWholeLease() {
        DriveLeaseMeter meter = new DriveLeaseMeter(250 * MS);
        assertEquals(250 * MS, meter.uncoveredNanos(0));
    }

    @Test
    void steadyRefreshesPayOnlyForNewCoverage() {
        DriveLeaseMeter meter = new DriveLeaseMeter(250 * MS);
        long total = meter.uncoveredNanos(0);
        meter.paid(0);
        for (int tick = 1; tick <= 20; tick++) {
            long now = tick * 50 * MS;
            total += meter.uncoveredNanos(now);
            meter.paid(now);
        }
        // One second of refreshes plus the prepaid tail, not 21 full leases.
        assertEquals(1_250 * MS, total);
    }

    @Test
    void aStallLongerThanTheLeaseStartsAFreshLease() {
        DriveLeaseMeter meter = new DriveLeaseMeter(250 * MS);
        meter.paid(0);
        assertEquals(250 * MS, meter.uncoveredNanos(2_000 * MS));
    }

    @Test
    void resetForgetsPreviousCoverage() {
        DriveLeaseMeter meter = new DriveLeaseMeter(250 * MS);
        meter.paid(0);
        meter.reset();
        assertEquals(250 * MS, meter.uncoveredNanos(10 * MS));
    }
}
