package dev.starbridge.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlOwnershipTest {
    @Test
    void rejectedCoreCannotReleaseTheWinnersLease() {
        IdentityControlLeases<Object> leases = new IdentityControlLeases<>();
        Object captain = new Object();
        Object rejectedCore = new Object();
        assertTrue(leases.claim("minecraft:overworld", 123, captain));
        assertFalse(leases.claim("minecraft:overworld", 123, rejectedCore));
        leases.release(rejectedCore);
        assertTrue(leases.owns("minecraft:overworld", 123, captain));
        assertFalse(leases.owns("minecraft:overworld", 123, rejectedCore));
    }

    @Test
    void copiedIdentifiersDoNotGrantInstanceOwnership() {
        IdentityControlLeases<String> leases = new IdentityControlLeases<>();
        String original = new String("copied-core-uuid");
        String clone = new String("copied-core-uuid");
        assertEquals(original, clone);
        assertTrue(leases.claim("minecraft:overworld", 4, original));
        assertFalse(leases.claim("minecraft:overworld", 4, clone));
        leases.release(clone);
        assertTrue(leases.owns("minecraft:overworld", 4, original));
    }

    @Test
    void unloadingOneOwnerReleasesAllItsShipsWithoutAffectingOthers() {
        IdentityControlLeases<Object> leases = new IdentityControlLeases<>();
        Object first = new Object();
        Object second = new Object();
        leases.claim("minecraft:overworld", 1, first);
        leases.claim("starbridge:luna", 1, first);
        leases.claim("minecraft:overworld", 2, second);
        leases.release(first);
        assertTrue(leases.owner("minecraft:overworld", 1).isEmpty());
        assertTrue(leases.owner("starbridge:luna", 1).isEmpty());
        assertTrue(leases.owns("minecraft:overworld", 2, second));
        assertTrue(leases.claim("minecraft:overworld", 1, second));
    }
}
