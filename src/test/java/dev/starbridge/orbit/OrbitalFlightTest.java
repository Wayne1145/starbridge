package dev.starbridge.orbit;

import static org.junit.jupiter.api.Assertions.*;

import dev.starbridge.flight.Vec3d;

import org.junit.jupiter.api.Test;

class OrbitalFlightTest {
    @Test
    void manualTurnAndThrustChangeCourseInSharedSpace() {
        var p = OrbitalFlight.launch("home", "moon");
        for (int i = 0; i < 32; i++)
            p =
                    OrbitalFlight.advance(
                                    p,
                                    new OrbitalFlight.Controls(1, 1, 1, false),
                                    OrbitalFlight.body("moon"),
                                    false,
                                    true)
                            .pose();
        assertTrue(p.velocity().x() < -1 && p.velocity().y() > 1 && p.position().z() > 205);
        assertFalse(OrbitalFlight.canEnter(p, OrbitalFlight.body("moon")));
        var drift =
                OrbitalFlight.advance(
                        p,
                        new OrbitalFlight.Controls(-1, 0, 0, true),
                        OrbitalFlight.body("moon"),
                        false,
                        false);
        assertEquals(0, drift.energy());
        assertEquals(p.velocity(), drift.pose().velocity());
        assertEquals(p.position().add(p.velocity()), drift.pose().position());
    }

    @Test
    void anUnmodifiedCruiseReachesApproachAndParksWithinTheAdvertisedEngineBudget() {
        for (String target :
                new String[] {
                    "moon", "mars", "belt", "galaxyspace.nereid", "extraplanets.kepler_22b"
                }) {
            for (boolean returning : new boolean[] {false, true}) {
                String from = returning ? target : "home", to = returning ? "home" : target;
                Vec3d body = OrbitalFlight.body(to);
                var p = OrbitalFlight.launch(from, to);
                int energy = 0, entry = -1;
                for (int tick = 0; tick < 4000; tick++) {
                    var step =
                            OrbitalFlight.advance(
                                    p, OrbitalFlight.Controls.NEUTRAL, body, true, true);
                    p = step.pose();
                    energy += step.energy();
                    if (entry < 0 && OrbitalFlight.canEnter(p, body)) entry = tick;
                }
                assertTrue(entry > 0, from + " -> " + to);
                assertTrue(OrbitalFlight.canEnter(p, body));
                assertEquals(0, p.velocity().length(), 1e-5);
                assertTrue(
                        energy < OrbitalFlight.CRUISE_ALLOWANCE,
                        from + " -> " + to + " used " + energy);
            }
        }
    }

    @Test
    void worldsSharePositionsAndCannotTriggerTouchdownAtCruiseSpeed() {
        var shared = OrbitalFlight.body("galaxyspace.triton");
        assertEquals(shared, OrbitalFlight.body(new String("galaxyspace.triton")));
        Vec3d outward = OrbitalFlight.body("moon").subtract(shared).normalized();
        var visiting = OrbitalFlight.launch("galaxyspace.triton", "moon");
        assertEquals(205, visiting.position().subtract(shared).length(), 1e-6);
        assertEquals(
                0,
                OrbitalFlight.forward(visiting.yaw(), visiting.pitch()).subtract(outward).length(),
                1e-9);
        assertFalse(
                OrbitalFlight.canEnter(
                        new OrbitalFlight.Pose(shared, new Vec3d(5, 0, 0), 0, 0), shared));
        assertTrue(
                OrbitalFlight.canEnter(
                        new OrbitalFlight.Pose(shared, new Vec3d(3, 0, 0), 0, 0), shared));
    }
}
