package dev.starbridge.compat.rocketry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Locate a block rejected by the running legacy assembler, in loaded ship cells only. */
final class ForbiddenHullBlocks {
    private final ShipyardHull.Box box;
    private final Object legacyWorld, table;
    private final Class<?> wrappedBlock;
    private final Method legacyBlock, legacyState, idOfBlock, legacyAir;
    private final Set<Integer> rejected = new HashSet<>();
    private final Map<BlockState, Boolean> matches = new IdentityHashMap<>();
    private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ, minSection, maxSection;
    private int cx, cz, sy;
    private boolean done;
    private ShipyardWire.ForbiddenBlock found;

    ForbiddenHullBlocks(ServerLevel level, ShipyardHull.Box box, Object assembler) throws ReflectiveOperationException {
        this.box = box;
        ClassLoader realm = assembler.getClass().getClassLoader();
        var config = realm.loadClass("zmaster587.advancedRocketry.api.Configuration");
        var listed = (Collection<?>) config.getField("blackListRocketBlocks").get(null);
        var bridge = Class.forName("retrofit.forge.world.LegacyBridge");
        idOfBlock = bridge.getMethod("blockId", Object.class);
        for (Object block : listed) rejected.add((int) idOfBlock.invoke(null, block));
        wrappedBlock = Class.forName("retrofit.forge.world.LegacyBlock");
        legacyBlock = wrappedBlock.getMethod("legacyBlock");
        table = OriginalRocketApi.host("LegacyBlockTable", "get", new Class<?>[0]);
        legacyState = table.getClass().getMethod("legacyOf", BlockState.class);
        legacyWorld = OriginalRocketApi.host("LegacyContent", "legacyWorld", new Class<?>[] {Level.class}, level);
        legacyAir = realm.loadClass("net.minecraft.world.World").getMethod("func_147437_c", int.class, int.class, int.class);
        cx = minChunkX = box.min().getX() >> 4;
        maxChunkX = box.max().getX() >> 4;
        cz = minChunkZ = box.min().getZ() >> 4;
        maxChunkZ = box.max().getZ() >> 4;
        sy = minSection = Math.max(box.min().getY(), level.getMinBuildHeight()) >> 4;
        maxSection = Math.min(box.max().getY(), level.getMaxBuildHeight() - 1) >> 4;
        done = rejected.isEmpty() || minSection > maxSection;
    }

    private boolean matches(BlockState state) {
        return matches.computeIfAbsent(state, s -> {
            if (s.isAir()) return false;
            try {
                int id = wrappedBlock.isInstance(s.getBlock())
                        ? (int) idOfBlock.invoke(null, legacyBlock.invoke(s.getBlock()))
                        : (int) legacyState.invoke(table, s) >> 4;
                return rejected.contains(id);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read original block identity", failure);
            }
        });
    }

    boolean done() { return done; }

    ShipyardWire.ForbiddenBlock advance(ServerLevel level) throws ReflectiveOperationException {
        if (found != null) {
            var p = found.position();
            LevelChunk chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (chunk != null) {
                var live = candidate(chunk.getBlockState(p), p);
                if (live != null) return found = live;
            }
            found = null;
            cx = minChunkX; cz = minChunkZ; sy = minSection;
            done = rejected.isEmpty() || minSection > maxSection;
        }
        // Each GUI refresh visits at most eight 16³ sections. Palette rejection avoids reading unrelated cells.
        for (int visit = 0; visit < 8 && !done; visit++) {
            int x = cx, z = cz, y = sy;
            nextSection();
            var chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            var section = chunk.getSection(level.getSectionIndex(y << 4));
            if (section.hasOnlyAir() || !section.maybeHas(this::matches)) continue;
            found = inSection(section, x << 4, y << 4, z << 4);
            if (found != null) return found;
        }
        return null;
    }

    private void nextSection() {
        if (++sy <= maxSection) return;
        sy = minSection;
        if (++cz <= maxChunkZ) return;
        cz = minChunkZ;
        if (++cx > maxChunkX) done = true;
    }

    private ShipyardWire.ForbiddenBlock inSection(LevelChunkSection section, int left, int bottom, int north)
            throws ReflectiveOperationException {
        var pos = new BlockPos.MutableBlockPos();
        for (int y = Math.max(bottom, box.min().getY()); y <= Math.min(bottom + 15, box.max().getY()); y++) {
            for (int x = Math.max(left, box.min().getX()); x <= Math.min(left + 15, box.max().getX()); x++) {
                for (int z = Math.max(north, box.min().getZ()); z <= Math.min(north + 15, box.max().getZ()); z++) {
                    BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                    var result = candidate(state, pos.set(x, y, z));
                    if (result != null) return result;
                }
            }
        }
        return null;
    }

    private ShipyardWire.ForbiddenBlock candidate(BlockState state, BlockPos pos) throws ReflectiveOperationException {
        if (!matches(state) || (boolean) legacyAir.invoke(legacyWorld, pos.getX(), pos.getY(), pos.getZ())) return null;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id == null ? null : new ShipyardWire.ForbiddenBlock(pos.immutable(), id);
    }
}
