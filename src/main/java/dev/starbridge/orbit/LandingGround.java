package dev.starbridge.orbit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Read real blocks: an original lunar chunk's motion heightmap can omit its native surface. */
public record LandingGround(double top, boolean liquid, boolean empty) {
    public static LandingGround read(ServerLevel level, LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos position) {
        var sections = chunk.getSections();
        for (int index = sections.length - 1; index >= 0; index--) {
            var section = sections[index];
            if (section.hasOnlyAir()) continue;
            int base = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = 15; y >= 0; y--) {
                var state = section.getBlockState(x & 15, y, z & 15);
                if (state.isAir()) continue;
                position.set(x, base + y, z);
                if (!state.getFluidState().isEmpty()) return new LandingGround(base + y + 1, true, false);
                var shape = state.getCollisionShape(level, position);
                if (!shape.isEmpty()) return new LandingGround(base + y + shape.max(Direction.Axis.Y), false, false);
            }
        }
        return new LandingGround(level.getMinBuildHeight(), false, true);
    }
}
