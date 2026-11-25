package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Exercise actual VS records and the landing journal over two dedicated server lifetimes. */
final class ReservationRecoveryCheck {
    static final Path MARKER = Path.of("starbridge-storage-recovery.fixture");
    private static final UUID CANCELLED = UUID.fromString("f3afda1e-a9d8-492e-afc0-e67401c06539");
    private static final UUID ORPHAN = UUID.fromString("bdf78131-79b7-4d2d-901a-9be06803f25f");
    private static final StorageBounds HULL = new StorageBounds(0, 128, 0, 18, 135, 299);
    private final Class<?> storage = Class.forName("dev.starbridge.orbit.LandingStorage");
    private final Method start = method("start", ServerLevel.class, UUID.class, StorageBounds.class);
    private final Method tick = method("tick", int.class);
    private AutoCloseable pending;
    private long cancelled;

    ReservationRecoveryCheck() throws ReflectiveOperationException {}

    private Method method(String name, Class<?>... parameters) throws ReflectiveOperationException {
        var method = storage.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    boolean tick(ServerLevel level, VsShipBackend vs, Map<String, Object> proof) throws Exception {
        String phase = Files.readString(MARKER).strip();
        if (!phase.equals("prepare")) {
            long id = Long.parseLong(phase);
            require(!vs.belongsTo(level, id), "Orphaned empty native ship remains after restart");
            try (var replacement = begin(level, ORPHAN)) {
                proof.put("replacement", shipId(replacement));
                require(shipId(replacement) != id, "Recovered lease must allocate a new ship");
            }
            proof.put("recovered", id);
            return true;
        }
        if (pending == null) {
            pending = begin(level, CANCELLED);
            cancelled = shipId(pending);
            require(vs.belongsTo(level, cancelled), "Expected the actual empty native reservation");
        }
        tick.invoke(pending, 4);
        var ready = storage.getDeclaredField("ready");
        ready.setAccessible(true);
        if (ready.getInt(pending) < 4) {
            require(level.getServer().getTickCount() < 300, "Cancellation never acquired its first four chunks");
            return false;
        }
        proof.put("cancelledAfterChunksReady", ready.getInt(pending));
        pending.close();
        require(!vs.belongsTo(level, cancelled), "Cancelled reservation left a native ship");
        // Leave a journal entry and a real empty VS ship in the stopped save. No GUI or players are needed.
        var interrupted = begin(level, ORPHAN);
        long orphan = shipId(interrupted);
        require(vs.belongsTo(level, orphan), "Interrupted reservation must be present before shutdown");
        proof.put("cancelled", cancelled);
        proof.put("orphan", orphan);
        return true;
    }

    private AutoCloseable begin(ServerLevel level, UUID core) throws Exception {
        var reservation = (AutoCloseable) start.invoke(null, level, core, HULL);
        require(reservation != null, "Expected the test to allocate an empty VS ship");
        return reservation;
    }

    private long shipId(AutoCloseable lease) throws Exception {
        var field = storage.getDeclaredField("reserved");
        field.setAccessible(true);
        return ((VsShipBackend.AssemblyReservation) field.get(lease)).shipId();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
