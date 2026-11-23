package dev.starbridge.compat.vs;

import org.joml.Vector3ic;

import java.util.function.Supplier;

/** Substitutes one preloaded empty ship for the allocation in the pinned VS assembler. */
public final class ReservedShipAllocation {
    private static final ThreadLocal<ReservedShipAllocation> ACTIVE = new ThreadLocal<>();
    private static final VsShipBackend BACKEND = new VsShipBackend();
    private final Object world;
    private final Object ship;
    private final String dimension;
    private boolean used;

    ReservedShipAllocation(Object world, Object ship, String dimension) {
        this.world = world;
        this.ship = ship;
        this.dimension = dimension;
    }

    public <T> T duringAssembly(Supplier<T> assemble) {
        var previous = ACTIVE.get();
        ACTIVE.set(this);
        try {
            return assemble.get();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    public boolean used() { return used; }

    public static Object allocate(Object world, Vector3ic center, boolean split, double scale, String dimension) {
        var reserved = ACTIVE.get();
        if (reserved == null || reserved.used || reserved.world != world || !reserved.dimension.equals(dimension)
                || split || scale != 1.0) {
            return BACKEND.allocateEmptyShip(world, center, split, scale, dimension);
        }
        reserved.used = true;
        return reserved.ship;
    }
}
