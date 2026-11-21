package dev.starbridge.orbit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the live build in bounded slices. Neither block entity nor inventory data goes over the
 * wire.
 */
final class HullSurvey {
    static final int MAX_SAMPLES = 768;
    private final StorageBounds bounds;
    private final int width, height, depth, stride, columns, layers, cells;
    private final List<OrbitWire.Voxel> voxels = new ArrayList<>();
    private int cell, offset;

    static void validate(StorageBounds b) {
        long w = (long) b.maxX() - b.minX() + 1,
                h = (long) b.maxY() - b.minY() + 1,
                d = (long) b.maxZ() - b.minZ() + 1;
        if (w < 1 || h < 1 || d < 1 || Math.max(w, Math.max(h, d)) > 320 || w * h * d > 2_025_000)
            throw new IllegalArgumentException("orbit.starbridge.bounds");
    }

    HullSurvey(StorageBounds bounds) {
        validate(bounds);
        this.bounds = bounds;
        width = bounds.maxX() - bounds.minX() + 1;
        height = bounds.maxY() - bounds.minY() + 1;
        depth = bounds.maxZ() - bounds.minZ() + 1;
        int step = 1;
        while ((long) ceil(width, step) * ceil(height, step) * ceil(depth, step) > MAX_SAMPLES)
            step++;
        stride = step;
        columns = ceil(width, stride);
        layers = ceil(height, stride);
        cells = columns * layers * ceil(depth, stride);
    }

    private static int ceil(int n, int d) {
        return (n + d - 1) / d;
    }

    boolean progress(ServerLevel level) {
        var at = new BlockPos.MutableBlockPos();
        int remaining = 4096;
        while (cell < cells && remaining > 0) {
            int x = cell % columns * stride,
                    y = cell / columns % layers * stride,
                    z = cell / (columns * layers) * stride;
            int w = Math.min(stride, width - x),
                    h = Math.min(stride, height - y),
                    d = Math.min(stride, depth - z);
            int volume = w * h * d;
            while (offset < volume && remaining > 0) {
                at.set(
                        bounds.minX() + x + offset % w,
                        bounds.minY() + y + offset / (w * d),
                        bounds.minZ() + z + offset / w % d);
                if (!level.hasChunkAt(at)) return false;
                var state = level.getBlockState(at);
                offset++;
                remaining--;
                if (!state.isAir()) {
                    voxels.add(new OrbitWire.Voxel(x, y, z, w, h, d, Block.getId(state)));
                    offset = volume;
                }
            }
            if (offset == volume) {
                offset = 0;
                cell++;
            }
        }
        return cell == cells;
    }

    OrbitWire.Hull result(UUID ship) {
        return new OrbitWire.Hull(ship, width, height, depth, List.copyOf(voxels));
    }
}
