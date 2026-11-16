package dev.starbridge.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LaunchHandshakeTest {
    @Test
    void staticAcknowledgementDelayDoesNotCancelLaunchPrematurely() {
        LaunchHandshake launch = new LaunchHandshake(40);
        launch.begin(100);
        for (long tick = 100; tick < 140; tick++) {
            assertEquals(LaunchHandshake.State.WAITING, launch.observe(tick, true));
            assertTrue(launch.pending());
        }
        assertEquals(LaunchHandshake.State.READY, launch.observe(140, false));
        assertTrue(launch.pending(), "A dynamic body still needs a successful paid hover refresh before commit");
        launch.complete();
        assertFalse(launch.pending());
    }

    @Test
    void stalledPhysicsTimesOutButRemainsPendingUntilTheCallerRollsBack() {
        LaunchHandshake launch = new LaunchHandshake(40);
        launch.begin(0);
        assertEquals(LaunchHandshake.State.TIMED_OUT, launch.observe(40, true));
        assertTrue(launch.pending(), "Core cleanup still needs to identify a launch eligible for static rollback");
        launch.cancel();
        assertFalse(launch.pending());
    }

    @Test
    void lateDynamicAcknowledgementDoesNotSilentlyStartAnExpiredLaunch() {
        LaunchHandshake launch = new LaunchHandshake(40);
        launch.begin(10);
        assertEquals(LaunchHandshake.State.TIMED_OUT, launch.observe(51, false));
        assertTrue(launch.pending());
    }

    @Test
    void cancellationAndServerTickRewindCannotLeaveAnUnboundedHandshake() {
        LaunchHandshake launch = new LaunchHandshake(40);
        launch.begin(100);
        assertThrows(IllegalStateException.class, () -> launch.begin(101));
        assertEquals(LaunchHandshake.State.TIMED_OUT, launch.observe(99, true));
        launch.cancel();
        assertThrows(IllegalStateException.class, () -> launch.observe(101, false));
        launch.begin(200);
        assertEquals(LaunchHandshake.State.READY, launch.observe(201, false));
    }
}
