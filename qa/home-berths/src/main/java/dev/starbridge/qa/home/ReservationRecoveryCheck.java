package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Exercise actual VS records and the landing journal over two dedicated server lifetimes. */
final class ReservationRecoveryCheck {
    static final Path MARKER = Path.of("starbridge-storage-recovery.fixture");
    private static final UUID CANCELLED = UUID.fromString("f3afda1e-a9d8-492e-afc0-e67401c06539");
    private static final long LEGACY_ORPHAN = Long.MAX_VALUE - 7;
    private static final UUID ORPHAN = UUID.fromString("bdf78131-79b7-4d2d-901a-9be06803f25f");
    private static final StorageBounds HULL = new StorageBounds(0, 128, 0, 18, 135, 299);
    private final Class<?> storage = Class.forName("dev.starbridge.orbit.LandingStorage");
    private final Method start = method("start", ServerLevel.class, UUID.class, StorageBounds.class, int.class);
    private final Method tick = method("tick", int.class);
    private AutoCloseable pending;
    private net.minecraft.core.BlockPos preview;

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
                proof.put("previewAfterRestart", level.getServer().getTickCount());
            }
            proof.put("recovered", id);
            return true;
        }
        if (pending == null) {
            pending = begin(level, CANCELLED);
            preview = vs.previewAssemblyPlot(level, 0);
            require(!preview.equals(vs.previewAssemblyPlot(level, 1)), "The next two native plots must be distinct");
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
        require(preview.equals(vs.previewAssemblyPlot(level, 0)), "Preview and cancellation must not advance the native allocator");
        proof.put("nextPlot", preview.toShortString());
        // Simulate a saved entry from an older build whose empty VS record was omitted by the serializer.
        var legacy = new SavedData() {
            @Override public CompoundTag save(CompoundTag root) {
                var entry = new CompoundTag();
                entry.putUUID("Core", ORPHAN);
                entry.putString("World", level.dimension().location().toString());
                entry.putLong("Ship", LEGACY_ORPHAN);
                var plots = new ListTag();
                plots.add(entry);
                root.put("Plots", plots);
                return root;
            }
        };
        legacy.setDirty();
        level.getDataStorage().set("starbridge_assembly_storage", legacy);
        proof.put("orphan", LEGACY_ORPHAN);
        return true;
    }

    private AutoCloseable begin(ServerLevel level, UUID core) throws Exception {
        var reservation = (AutoCloseable) start.invoke(null, level, core, HULL, 0);
        require(reservation != null, "Expected the test to warm a predicted native plot");
        return reservation;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
