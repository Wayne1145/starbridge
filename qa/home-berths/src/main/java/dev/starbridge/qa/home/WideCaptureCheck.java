package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Compare both departure paths on the saved, returned 300-block VS ship in one dedicated-server tick. */
final class WideCaptureCheck {
    static final Path MARKER = Path.of("starbridge-wide-capture.fixture");
    private static final int WARMUP_PAIRS = 4, MEASURED_PAIRS = 16;
    private record Machine(BlockState state, CompoundTag data) {}
    private record Run(Object seal, Map<?, ?> machines, long nanos) {}

    private final Method inspect, capture, seal, machines, data, state, digest, blocks;

    WideCaptureCheck() throws ReflectiveOperationException {
        Class<?> manifest = Class.forName("dev.starbridge.transit.HullManifest");
        inspect = method(manifest, "inspect", ServerLevel.class, StorageBounds.class);
        capture = method(manifest, "capture", ServerLevel.class, StorageBounds.class);
        seal = method(capture.getReturnType(), "seal");
        machines = method(capture.getReturnType(), "machines");
        Class<?> machine = Class.forName(manifest.getName() + "$Machine");
        data = method(machine, "data");
        state = method(machine, "state");
        digest = method(inspect.getReturnType(), "digest");
        blocks = method(inspect.getReturnType(), "blocks");
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        Method value = type.getDeclaredMethod(name, args);
        value.setAccessible(true);
        return value;
    }

    // The independent machine pass from the parent revision, immediately before its regular digest scan.
    private static Map<BlockPos, Machine> previousMachines(ServerLevel level, StorageBounds box) {
        Map<BlockPos, Machine> result = new LinkedHashMap<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                var chunk = level.getChunk(cx, cz);
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = entry.getKey();
                    if (p.getX() < box.minX() || p.getX() > box.maxX() || p.getY() < box.minY() || p.getY() > box.maxY()
                            || p.getZ() < box.minZ() || p.getZ() > box.maxZ()) continue;
                    result.put(p.immutable(), new Machine(chunk.getBlockState(p), entry.getValue().saveWithFullMetadata()));
                }
            }
        }
        return result;
    }

    private Run previous(ServerLevel level, StorageBounds box) throws Exception {
        long start = System.nanoTime();
        var saved = previousMachines(level, box);
        Object fingerprint = inspect.invoke(null, level, box);
        return new Run(fingerprint, saved, System.nanoTime() - start);
    }

    private Run current(ServerLevel level, StorageBounds box) throws Exception {
        long start = System.nanoTime();
        Object departure = capture.invoke(null, level, box);
        long elapsed = System.nanoTime() - start;
        return new Run(seal.invoke(departure), (Map<?, ?>) machines.invoke(departure), elapsed);
    }

    static Map<String, Object> run(ServerLevel level, VsShipBackend vs) throws Exception {
        long shipId = Long.parseLong(Files.readString(MARKER).trim());
        StorageBounds box = vs.storageBoundsById(level, shipId).orElseThrow(() -> new IllegalStateException("Missing saved home ship " + shipId));
        return new WideCaptureCheck().compare(level, shipId, box);
    }

    private Map<String, Object> compare(ServerLevel level, long shipId, StorageBounds box) throws Exception {
        // Loading and materialising the saved chunks is outside the timed window; each measured pair sees the same live hull.
        current(level, box);
        List<Long> previousNs = new ArrayList<>(), currentNs = new ArrayList<>();
        Map<String, Integer> types = new TreeMap<>();
        int diamonds = 0, furnaceItems = 0;
        String stableDigest = null;
        for (int i = 0; i < WARMUP_PAIRS + MEASURED_PAIRS; i++) {
            Run before, after;
            if ((i & 1) == 0) { before = previous(level, box); after = current(level, box); }
            else { after = current(level, box); before = previous(level, box); }
            String observed = (String) digest.invoke(after.seal());
            if (!before.seal().equals(after.seal()) || !before.machines().keySet().equals(after.machines().keySet())
                    || !Integer.valueOf(19522).equals(blocks.invoke(after.seal())) || after.machines().size() != 4530
                    || (stableDigest != null && !stableDigest.equals(observed))) throw new IllegalStateException("Ship or seal mismatch at pair " + i);
            stableDigest = observed;
            for (var entry : before.machines().entrySet()) {
                Machine old = (Machine) entry.getValue();
                Object saved = after.machines().get(entry.getKey());
                if (!old.state().equals(state.invoke(saved)) || !old.data().equals(data.invoke(saved)))
                    throw new IllegalStateException("Machine state or complete NBT mismatch at " + entry.getKey());
                if (i != 0) continue;
                String id = old.data().getString("id");
                types.merge(id, 1, Integer::sum);
                for (Tag tag : old.data().getList("Items", Tag.TAG_COMPOUND)) {
                    CompoundTag stack = (CompoundTag) tag;
                    String item = stack.getString("id");
                    if (id.equals("minecraft:chest") && item.equals("minecraft:diamond")) diamonds += stack.getByte("Count");
                    if (id.equals("minecraft:furnace") && (item.equals("minecraft:iron_ore") || item.equals("minecraft:iron_ingot")))
                        furnaceItems += stack.getByte("Count");
                }
            }
            if (i >= WARMUP_PAIRS) { previousNs.add(before.nanos()); currentNs.add(after.nanos()); }
        }
        if (diamonds != 221 || furnaceItems != 104) throw new IllegalStateException("Saved freight changed: " + diamonds + ", " + furnaceItems);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipId", shipId);
        result.put("storageBounds", List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        result.put("occupiedBlocks", 19522);
        result.put("machinesById", types);
        result.put("diamonds", diamonds);
        result.put("furnaceItems", furnaceItems);
        result.put("seal", stableDigest);
        result.put("warmupPairs", WARMUP_PAIRS);
        result.put("measuredPairs", MEASURED_PAIRS);
        result.put("previousNanoseconds", previousNs);
        result.put("captureNanoseconds", currentNs);
        return result;
    }
}
