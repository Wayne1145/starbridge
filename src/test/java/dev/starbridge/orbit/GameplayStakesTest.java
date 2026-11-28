package dev.starbridge.orbit;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.voyage.DriveEconomy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ship rating, drive prices, ship performance and orbital events. */
class GameplayStakesTest {
    private static final int COAL = (int) DriveEconomy.fuelEnergy(1600);
    private static final int MOON_FARE = 12_000;
    /** A small starter ship: ~300 blocks, eight motors, two tanks. */
    private static final ShipRating STARTER = new ShipRating(300, 8, 2, 2, true);
    /** A 100-block-long cargo hull, 100×7×6, with a modest engine room. */
    private static final ShipRating CARGO = new ShipRating(4_200, 60, 6, 4, true);

    private static double roundTripCoal(ShipRating ship) {
        // Out and back: two fares plus the cruising runs actually spent.
        long energy = 2L * DriveEconomy.fare(MOON_FARE, ship) + 4L * DriveEconomy.cruiseAllowance(ship);
        return energy / (double) COAL;
    }

    @Test
    void coalIsWorthThirtyTwoThousandFe() {
        assertEquals(32_000, COAL);
    }

    @Test
    void ratingLimitsAreClamped() {
        assertEquals(1, new ShipRating(50, 0, 0, 0, false).massFactor());
        assertEquals(ShipRating.MAX_MASS_FACTOR, new ShipRating(1_000_000, 0, 0, 0, false).massFactor());
        assertEquals(ShipRating.MIN_PERFORMANCE, new ShipRating(500, 0, 0, 0, false).performance());
        assertEquals(ShipRating.MAX_PERFORMANCE, new ShipRating(40, 100, 0, 0, false).performance());
        assertEquals(ShipRating.MAX_EFFICIENCY, new ShipRating(40, 0, 100, 0, false).efficiency());
        assertEquals(ShipRating.MAX_CAPACITY, new ShipRating(40, 0, 1_000, 0, false).capacity());
        assertTrue(new ShipRating(1_000, 2, 0, 0, false).underpowered());
        assertThrows(IllegalArgumentException.class, () -> new ShipRating(0, 0, 0, 0, false));
    }

    @Test
    void moonRoundTripsLandInTheTargetRange() {
        double starter = roundTripCoal(STARTER), cargo = roundTripCoal(CARGO);
        assertTrue(starter >= 2.5 && starter <= 5, "starter ship should need about four coal, needs " + starter);
        assertTrue(cargo >= 16 && cargo <= 64, "cargo hull should need around a stack, needs " + cargo);
        assertTrue(cargo > starter * 4, "a big hull must cost clearly more");
    }

    @Test
    void tanksMakeTheSameHullCheaperAndRoomier() {
        var bare = new ShipRating(1_200, 20, 0, 1, true);
        var tanked = new ShipRating(1_200, 20, 6, 1, true);
        assertTrue(DriveEconomy.fare(MOON_FARE, tanked) < DriveEconomy.fare(MOON_FARE, bare));
        assertTrue(tanked.capacity() > bare.capacity());
    }

    @Test
    void theBiggestShipCanAlwaysAffordItsFarthestLaunchWithEnoughTanks() {
        int farthest = 12_000 + 8_000 * 5; // tier 6
        var heavy = new ShipRating(50_000, 300, 40, 8, true);
        assertTrue(DriveEconomy.launchEnergy(farthest, farthest, false, heavy) <= heavy.capacity(),
                "a fully tanked heavy hull must fit its launch energy in the core");
    }

    @Test
    void cruiseStopsInsideTheApproachWindowAtAnyPerformance() {
        for (double performance : new double[]{0.5, 1.0, 1.5, 2.0}) {
            Vec3d target = OrbitalFlight.body("moon");
            var pose = OrbitalFlight.launch("home", "moon");
            double closest = Double.MAX_VALUE;
            boolean entered = false;
            for (int tick = 0; tick < 20_000 && !entered; tick++) {
                pose = OrbitalFlight.advance(pose, OrbitalFlight.Controls.NEUTRAL, target, true, true, performance).pose();
                closest = Math.min(closest, pose.position().subtract(target).length());
                entered = OrbitalFlight.canEnter(pose, target);
            }
            assertTrue(entered, "cruise must reach the approach window at performance " + performance);
            assertTrue(closest > 50, "cruise must not overshoot into the body at performance " + performance);
        }
    }

    @Test
    void betterEnginesArriveSooner() {
        assertTrue(ticksToMoon(2.0) < ticksToMoon(1.0));
        assertTrue(ticksToMoon(1.0) < ticksToMoon(0.5));
    }

    private static int ticksToMoon(double performance) {
        Vec3d target = OrbitalFlight.body("moon");
        var pose = OrbitalFlight.launch("home", "moon");
        for (int tick = 0; tick < 50_000; tick++) {
            pose = OrbitalFlight.advance(pose, OrbitalFlight.Controls.NEUTRAL, target, true, true, performance).pose();
            if (OrbitalFlight.canEnter(pose, target)) return tick;
        }
        return Integer.MAX_VALUE;
    }

    @Test
    void debrisIsTheSameEveryFlightAndKeepsClearOfBodies() {
        var first = OrbitEvents.debris("home", "mars");
        assertEquals(first, OrbitEvents.debris("home", "mars"));
        for (var field : first) {
            assertTrue(field.center().subtract(OrbitalFlight.body("home")).length() > 600 + field.radius());
            assertTrue(field.center().subtract(OrbitalFlight.body("mars")).length() > 600 + field.radius());
        }
        int routesWithDebris = 0;
        for (String to : new String[]{"moon", "mars", "belt", "venus", "ceres", "io", "europa", "titan"})
            if (!OrbitEvents.debris("home", to).isEmpty()) routesWithDebris++;
        assertTrue(routesWithDebris > 0, "some routes must have debris");
    }

    @Test
    void debrisOnlyHurtsShipsAboveTheSafeSpeed() {
        var field = new OrbitEvents.Debris(new Vec3d(0, 0, 0), 100);
        var fields = java.util.List.of(field);
        assertEquals(0, OrbitEvents.damage(fields, new Vec3d(10, 0, 0), OrbitEvents.SAFE_DEBRIS_SPEED));
        assertEquals(0, OrbitEvents.damage(fields, new Vec3d(500, 0, 0), 17));
        assertTrue(OrbitEvents.damage(fields, new Vec3d(10, 0, 0), 17) > 0);
    }

    @Test
    void conditionSlowsDamagedOrFaultyShips() {
        assertEquals(1, OrbitEvents.condition(100, false));
        assertEquals(0.5, OrbitEvents.condition(20, false));
        assertEquals(0.5, OrbitEvents.condition(100, true));
        assertEquals(0.25, OrbitEvents.condition(20, true));
    }

    @Test
    void beaconsAreStablePerFlightAndClaimedOnlyWhenHeld() {
        UUID flight = UUID.nameUUIDFromBytes("flight".getBytes());
        assertEquals(OrbitEvents.beacon(flight, "home", "mars"), OrbitEvents.beacon(flight, "home", "mars"));
        int found = 0;
        for (int i = 0; i < 200; i++)
            if (OrbitEvents.beacon(UUID.nameUUIDFromBytes(("f" + i).getBytes()), "home", "mars").isPresent()) found++;
        assertTrue(found > 30 && found < 110, "about a third of flights should see a beacon, saw " + found);
        Vec3d beacon = new Vec3d(0, 0, 0);
        int held = 0;
        for (int i = 0; i < OrbitEvents.BEACON_HOLD_TICKS; i++) held = OrbitEvents.holdAtBeacon(held, beacon, new Vec3d(10, 0, 0), 0.1);
        assertEquals(OrbitEvents.BEACON_HOLD_TICKS, held);
        assertEquals(0, OrbitEvents.holdAtBeacon(held, beacon, new Vec3d(10, 0, 0), 3));
        assertEquals(0, OrbitEvents.holdAtBeacon(held, beacon, new Vec3d(200, 0, 0), 0.1));
    }
}
