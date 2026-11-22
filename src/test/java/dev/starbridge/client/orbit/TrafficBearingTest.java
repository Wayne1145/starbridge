package dev.starbridge.client.orbit;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitWire;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrafficBearingTest {
    private static final Vec3d STILL = Vec3d.ZERO;
    private static OrbitWire.Contact ship(int tail, Vec3d position) {
        return new OrbitWire.Contact(new UUID(0, tail), position, STILL, 0, 0, "");
    }

    @Test void headingWrapKeepsTheShipAheadOrAsternOnTheCorrectHalfOfTheScope() {
        Vec3d worldNorth = new Vec3d(0, 0, -1250);
        for (double heading : new double[]{180, -180}) {
            var ahead = TrafficBearing.measure(STILL, STILL, heading, worldNorth, STILL, 40);
            assertEquals(0, ahead.x());
            assertEquals(-20, ahead.y());
        }
        var behind = TrafficBearing.measure(STILL, STILL, 0, worldNorth, STILL, 40);
        assertEquals(20, behind.y());
        var right = TrafficBearing.measure(STILL, STILL, -90, worldNorth, STILL, 40);
        assertEquals(20, right.x());
        assertEquals(0, right.y());
    }

    @Test void closureUsesBothShipsAndIgnoresSidewaysMotion() {
        var origin = new Vec3d(23, -5, 0);
        var above = new Vec3d(23, 75, 0);
        var moving = new Vec3d(8, 3, 0);
        var targetMotion = new Vec3d(-1, 1, 0);
        var contact = TrafficBearing.measure(origin, moving, 20, above, targetMotion, 40);
        assertEquals(40.0, contact.closingSpeed(), .00001);
        assertEquals(80.0, contact.altitude());
        assertEquals(80.0, contact.range());
        assertEquals(0, contact.x());
        assertEquals(0, contact.y());
        assertEquals(-40.0, TrafficBearing.measure(origin, targetMotion, 20, above, moving, 40).closingSpeed(), .00001);
        assertEquals(0.0, TrafficBearing.measure(origin, moving, 20, above, moving, 40).closingSpeed());
        assertEquals(0.0, TrafficBearing.measure(above, moving, 0, above, targetMotion, 40).closingSpeed());
    }

    @Test void chosenSignalSurvivesOrderingChangesAndLossUntilPilotChoosesAnother() {
        var scope = new TrafficScope();
        var a = ship(1, new Vec3d(4, 0, 2));
        var b = ship(2, new Vec3d(-6, 0, 1));
        scope.acquire(List.of(a, b));
        scope.next(List.of(a, b));
        assertEquals(b.core(), scope.tracked());
        scope.acquire(List.of(b, a));
        scope.acquire(List.of(a));
        assertEquals(b.core(), scope.tracked());
        scope.next(List.of(a));
        assertEquals(a.core(), scope.tracked());
    }
}
