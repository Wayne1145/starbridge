package dev.starbridge.voyage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteTest {
    @Test void inboundFareNeverConsumesANonexistentExtraReserve() {
        int bank = Route.HOME.fareTo(Route.MOON) + Route.MOON.returnReserve();
        assertThrows(IllegalStateException.class, () -> Route.HOME.checkFuel(Route.MOON, bank - 1));
        Route.HOME.checkFuel(Route.MOON, bank);
        Route.MOON.checkFuel(Route.HOME, bank - Route.HOME.fareTo(Route.MOON));
    }
    @Test void everyApprovedItineraryCanStillPayForTheDirectReturn() {
        for (Route origin : Route.values()) for (Route target : Route.values()) {
            if (origin == target) { assertThrows(IllegalArgumentException.class, () -> origin.fareTo(target)); continue; }
            assertEquals(origin.fareTo(target), target.fareTo(origin));
            if (target != Route.HOME) assertEquals(target.fareTo(Route.HOME), target.returnReserve());
            assertSame(target, Route.named(target.key()));
        }
        assertThrows(IllegalArgumentException.class, () -> Route.named("minecraft:the_end"));
    }
}
