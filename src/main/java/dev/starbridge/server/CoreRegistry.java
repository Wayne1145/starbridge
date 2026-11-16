package dev.starbridge.server;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.network.PilotModule;
import java.util.*;

/** Main-server-thread registry of loaded cores. No world/chunk is loaded by a remote request. */
public final class CoreRegistry {
    private static final Map<UUID, ShipCoreBlockEntity> CORES = new HashMap<>();
    private static final IdentityControlLeases<ShipCoreBlockEntity> CONTROLLERS = new IdentityControlLeases<>();
    private CoreRegistry() {}
    public static void register(ShipCoreBlockEntity core) {
        if (core.isRemoved()) return;
        ShipCoreBlockEntity existing = CORES.get(core.id());
        if (existing != null && existing != core && !existing.isRemoved()) {
            UUID replacement;
            do { replacement = UUID.randomUUID(); } while (CORES.containsKey(replacement));
            // Copied NBT may contain an existing identity and its old remote grants.
            // Never replace the original live entry or share its control lease.
            core.assignFreshIdentity(replacement);
        }
        CORES.put(core.id(), core);
    }
    public static void unregister(ShipCoreBlockEntity core) {
        CORES.remove(core.id(), core);
        CONTROLLERS.release(core);
    }
    public static Optional<ShipCoreBlockEntity> find(UUID id) {
        ShipCoreBlockEntity core = CORES.get(id);
        return core == null || core.isRemoved() ? Optional.empty() : Optional.of(core);
    }
    public static List<ShipCoreBlockEntity> all() { return CORES.values().stream().filter(c -> !c.isRemoved()).toList(); }
    public static boolean claim(String dimension, long ship, ShipCoreBlockEntity core) {
        ShipCoreBlockEntity current = CONTROLLERS.owner(dimension, ship).orElse(null);
        if (current != null && current != core && (current.isRemoved()
                || (PilotModule.isBeingPiloted(core.id()) && !PilotModule.isBeingPiloted(current.id())))) {
            // Human takeover may preempt an automatic core on the same physical ship.
            current.release();
        }
        return CONTROLLERS.claim(dimension, ship, core);
    }
    public static boolean owns(String dimension, long ship, ShipCoreBlockEntity core) {
        return CONTROLLERS.owns(dimension, ship, core);
    }
    public static void release(ShipCoreBlockEntity core) { CONTROLLERS.release(core); }
    public static void clear() {
        for (ShipCoreBlockEntity core : List.copyOf(CORES.values())) core.release();
        CORES.clear();
        CONTROLLERS.clear();
    }
}

/** Instance identity is intentional: copied UUIDs or equal objects cannot cancel another owner's force. */
final class IdentityControlLeases<T> {
    private record ShipKey(String dimension, long ship) {}
    private final Map<ShipKey, T> owners = new HashMap<>();

    boolean claim(String dimension, long ship, T claimant) {
        Objects.requireNonNull(claimant, "claimant");
        ShipKey key = new ShipKey(Objects.requireNonNull(dimension, "dimension"), ship);
        T current = owners.get(key);
        if (current != null && current != claimant) return false;
        owners.put(key, claimant);
        return true;
    }

    Optional<T> owner(String dimension, long ship) { return Optional.ofNullable(owners.get(new ShipKey(dimension, ship))); }
    boolean owns(String dimension, long ship, T claimant) { return owners.get(new ShipKey(dimension, ship)) == claimant; }
    void release(T claimant) { owners.values().removeIf(owner -> owner == claimant); }
    void clear() { owners.clear(); }
}
