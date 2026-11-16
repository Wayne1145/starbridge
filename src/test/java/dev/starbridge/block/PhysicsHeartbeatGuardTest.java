package dev.starbridge.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhysicsHeartbeatGuardTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void acceptedSubmissionsCannotExtendTheStartupGraceWithoutRealPhysics() {
        PhysicsHeartbeatGuard guard = new PhysicsHeartbeatGuard(3 * SECOND);
        guard.submitted(100);
        for (int tick = 1; tick < 60; tick++) {
            long now = 100 + tick * 50_000_000L;
            assertTrue(guard.allowRefresh(now, false));
            guard.submitted(now);
        }
        assertFalse(guard.allowRefresh(100 + 3 * SECOND, false));
        guard.submitted(100 + 4 * SECOND);
        assertFalse(guard.allowRefresh(100 + 4 * SECOND, false));
    }

    @Test
    void aPreviouslyWorkingShipCannotReuseStartupGraceAfterItsAcknowledgementsExpire() {
        PhysicsHeartbeatGuard guard = new PhysicsHeartbeatGuard(3 * SECOND);
        guard.submitted(0);
        assertTrue(guard.allowRefresh(50_000_000L, true));
        assertTrue(guard.allowRefresh(SECOND, true));
        assertFalse(guard.allowRefresh(SECOND + 100_000_000L, false),
                "The backend reports this ship's last acknowledgement older than one second");
    }

    @Test
    void healthyAcknowledgementsPermitLongRunningFlightBeyondStartupGrace() {
        PhysicsHeartbeatGuard guard = new PhysicsHeartbeatGuard(3 * SECOND);
        guard.submitted(0);
        for (int elapsedSeconds = 1; elapsedSeconds <= 3_600; elapsedSeconds++) {
            long now = elapsedSeconds * SECOND;
            assertTrue(guard.allowRefresh(now, true));
            guard.submitted(now);
        }
        assertFalse(guard.allowRefresh(3_601 * SECOND, false));
    }

    @Test
    void releasingAControlSessionAllowsAFreshBoundedLaunchAndNanoTimeWrapIsSafe() {
        PhysicsHeartbeatGuard guard = new PhysicsHeartbeatGuard(3 * SECOND);
        long start = Long.MAX_VALUE - SECOND;
        guard.submitted(start);
        assertTrue(guard.allowRefresh(start + 2 * SECOND, false));
        assertFalse(guard.allowRefresh(start + 3 * SECOND, false));
        guard.reset();
        guard.submitted(start + 4 * SECOND);
        assertTrue(guard.allowRefresh(start + 5 * SECOND, false));
        assertFalse(guard.allowRefresh(start + 7 * SECOND, false));
    }
}
