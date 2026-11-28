package dev.starbridge.compat.rocketry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** The bottom of the original assembler's scan volume is one block above its launchpad. */
final class PadFloor {
    record Inspection(BlockPos gap, boolean missingChunks) {
        void requireComplete() {
            if (gap != null) throw new IllegalStateException("pad_gap");
            if (missingChunks) throw new IllegalStateException("chunks");
        }
    }

    private PadFloor() {}

    static Inspection inspect(ServerLevel level, ShipyardHull.Box box, Object assembler)
            throws ReflectiveOperationException {
        // Resolve both original pad blocks by identity, including the docking pad's legacy alias.
        Class<?> types = assembler.getClass().getClassLoader().loadClass(
                "zmaster587.advancedRocketry.api.AdvancedRocketryBlocks");
        Block launch = hostBlock(types, "blockLaunchpad");
        Block landing = hostBlock(types, "blockLandingPad");
        int floorY = box.min().getY() - 1;
        var pos = new BlockPos.MutableBlockPos();
        boolean missingChunks = false;
        for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
            for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    missingChunks = true;
                    continue;
                }
                int endX = Math.min(box.max().getX(), (cx << 4) + 15);
                int endZ = Math.min(box.max().getZ(), (cz << 4) + 15);
                for (int x = Math.max(box.min().getX(), cx << 4); x <= endX; x++) {
                    for (int z = Math.max(box.min().getZ(), cz << 4); z <= endZ; z++) {
                        Block block = chunk.getBlockState(pos.set(x, floorY, z)).getBlock();
                        if (block != launch && block != landing) {
                            return new Inspection(pos.immutable(), missingChunks);
                        }
                    }
                }
            }
        }
        return new Inspection(null, missingChunks);
    }

    private static Block hostBlock(Class<?> types, String name) throws ReflectiveOperationException {
        var legacy = types.getField(name).get(null);
        var block = OriginalRocketApi.host("LegacyContent", "byLegacy", new Class<?>[] {Object.class}, legacy);
        if (!(block instanceof Block result)) throw new IllegalStateException("unavailable");
        return result;
    }
}
