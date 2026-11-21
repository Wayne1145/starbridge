package dev.starbridge.orbit;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.orbit.OrbitWire.Clearance;
import dev.starbridge.orbit.OrbitWire.Site;
import dev.starbridge.voyage.Voyages.ApproachTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A pilot's survey holds only the selected rectangle. The helm shares one work slice per tick
 * across all ships.
 */
final class LandingSurvey {
    private static final TicketType<UUID> TICKET =
            TicketType.create("starbridge_landing", Comparator.<UUID>naturalOrder());
    private static final VsShipBackend VS = new VsShipBackend();
    static final int MAX_HELD_CHUNKS = 512;
    final UUID id;
    final int x, z, cx, cz, width, height, depth;
    final StorageBounds hull;
    final ServerLevel level;
    final Vec3 home;
    final int minX, minZ, maxX, maxZ, mapX, mapZ, spanX, spanZ;
    final List<ChunkPos> chunks = new ArrayList<>();
    private final int[] heights = new int[Site.GRID * Site.GRID];
    private final byte[] surfaces = new byte[heights.length];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int held, cursor, low, high, markX, markZ;
    private double support;
    private Clearance hazard;
    boolean finished, confirm, lastClear;
    int touched;
    Clearance result = Clearance.PENDING;

    LandingSurvey(UUID id, int x, int z, StorageBounds bounds, ApproachTarget port, int tick) {
        this.id = id;
        this.x = x;
        this.z = z;
        hull = bounds;
        level = port.level();
        home = port.home();
        cx = home == null ? x : Mth.floor(home.x);
        cz = home == null ? z : Mth.floor(home.z);
        width = bounds.maxX() - bounds.minX() + 1;
        height = bounds.maxY() - bounds.minY() + 1;
        depth = bounds.maxZ() - bounds.minZ() + 1;
        // Old recorded ports store the native ship's mass center. Before assembly its exact offset
        // is unknown, so check the whole possible envelope. Outbound hulls are grid-aligned
        // instead.
        minX = home == null ? cx - width / 2 : Mth.floor(home.x - width);
        minZ = home == null ? cz - depth / 2 : Mth.floor(home.z - depth);
        maxX = home == null ? minX + width : Mth.ceil(home.x + width);
        maxZ = home == null ? minZ + depth : Mth.ceil(home.z + depth);
        int padding = 2;
        spanX = Math.max(Site.GRID, maxX - minX + 2 * padding);
        spanZ = Math.max(Site.GRID, maxZ - minZ + 2 * padding);
        mapX = minX - (spanX - (maxX - minX)) / 2;
        mapZ = minZ - (spanZ - (maxZ - minZ)) / 2;
        touched = tick;
        reset(false);
        var border = level.getWorldBorder();
        if (!border.isWithinBounds(minX, minZ)
                || !border.isWithinBounds(
                        Math.nextDown((double) maxX), Math.nextDown((double) maxZ))) {
            end(Clearance.BORDER);
            markX =
                    !border.isWithinBounds(minX, cz)
                            ? minX
                            : !border.isWithinBounds(Math.nextDown((double) maxX), cz)
                                    ? maxX - 1
                                    : cx;
            markZ =
                    !border.isWithinBounds(cx, minZ)
                            ? minZ
                            : !border.isWithinBounds(cx, Math.nextDown((double) maxZ))
                                    ? maxZ - 1
                                    : cz;
            return;
        }
        for (int a = mapX >> 4; a <= (mapX + spanX - 1) >> 4; ++a)
            for (int b = mapZ >> 4; b <= (mapZ + spanZ - 1) >> 4; ++b)
                chunks.add(new ChunkPos(a, b));
    }

    void reset(boolean confirm) {
        this.confirm = confirm;
        cursor = 0;
        low = Integer.MAX_VALUE;
        high = Integer.MIN_VALUE;
        support = Double.NEGATIVE_INFINITY;
        markX = cx;
        markZ = cz;
        Arrays.fill(heights, Integer.MIN_VALUE);
        Arrays.fill(surfaces, (byte) 0);
        hazard = Clearance.CLEAR;
        result = Clearance.PENDING;
        finished = false;
    }

    void end(Clearance state) {
        result = state;
        lastClear = state == Clearance.CLEAR;
        finished = true;
    }

    void release() {
        for (int i = 0; i < held; i++)
            level.getChunkSource().removeRegionTicket(TICKET, chunks.get(i), 0, id);
        held = 0;
    }

    void work() {
        if (finished) return;
        var cache = level.getChunkSource();
        if (held > 0) {
            ChunkPos last = chunks.get(held - 1);
            if (cache.getChunkNow(last.x, last.z) == null) return;
        }
        if (held < chunks.size()) {
            cache.addRegionTicket(TICKET, chunks.get(held++), 0, id);
            return;
        }
        // Each column reads at most the world's build height, even if all upper sections contain isolated blocks.
        int budget = Math.max(2, 16384 / (level.getHeight() + (home == null ? 0 : 2 * height)));
        int end = Math.min(cursor + budget, spanX * spanZ);
        while (cursor < end) {
            int a = mapX + cursor % spanX, b = mapZ + cursor / spanX;
            LevelChunk chunk = cache.getChunkNow(a >> 4, b >> 4);
            if (chunk == null) return;
            readColumn(chunk, a, b);
            cursor++;
        }
        if (cursor < spanX * spanZ) return;
        if (hazard == Clearance.CLEAR) {
            AABB box = box();
            if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight())
                hazard = Clearance.HEIGHT;
            else if (!level.getWorldBorder().isWithinBounds(minX, minZ)
                    || !level.getWorldBorder()
                            .isWithinBounds(
                                    Math.nextDown((double) maxX), Math.nextDown((double) maxZ)))
                hazard = Clearance.BORDER;
            else if (VS.probe().available() && VS.intersectsShip(level, box.deflate(.001)))
                hazard = Clearance.OCCUPIED;
            else {
                var occupants =
                        level.getEntities(
                                (Entity) null,
                                box.deflate(.001),
                                entity -> !entity.isSpectator() && entity.isAlive());
                if (!occupants.isEmpty()) {
                    var occupant = occupants.get(0);
                    hazard = Clearance.OCCUPIED;
                    markX = occupant.getBlockX();
                    markZ = occupant.getBlockZ();
                }
            }
        }
        end(hazard);
    }

    private void readColumn(LevelChunk chunk, int a, int b) {
        var ground = LandingGround.read(level, chunk, a, b, pos);
        int surface = Mth.ceil(ground.top());
        boolean empty = ground.empty();
        boolean wet = ground.liquid();
        int cell = (a - mapX) * Site.GRID / spanX + (b - mapZ) * Site.GRID / spanZ * Site.GRID;
        heights[cell] = Math.max(heights[cell], surface);
        surfaces[cell] = (byte) Math.max(surfaces[cell], wet ? 3 : empty ? 1 : 2);
        if (a < minX || b < minZ || a >= maxX || b >= maxZ) return;
        low = Math.min(low, surface);
        high = Math.max(high, surface);
        support = Math.max(support, ground.top());
        if (hazard != Clearance.CLEAR) return;
        if (home == null) {
            if (wet || empty) {
                hazard = wet ? Clearance.LIQUID : Clearance.VOID;
                markX = a;
                markZ = b;
            }
        } else if (surface >= home.y - height) {
            int bottom = Math.max(level.getMinBuildHeight(), Mth.floor(home.y - height));
            int top = Math.min(level.getMaxBuildHeight(), Mth.ceil(home.y + height));
            for (int y = bottom; y < top; ++y) {
                var block = chunk.getBlockState(pos.set(a, y, b));
                if (!block.getCollisionShape(level, pos).isEmpty()) {
                    hazard = Clearance.OCCUPIED;
                    markX = a;
                    markZ = b;
                    return;
                }
            }
        }
    }

    AABB box() {
        double base = home == null ? support : home.y - height;
        return new AABB(
                minX, base, minZ, maxX, home == null ? base + height : home.y + height, maxZ);
    }

    Vec3 anchor() {
        return home == null ? new Vec3(cx + .5, support, cz + .5) : home;
    }

    Site packet() {
        byte[] raster = new byte[heights.length];
        int relief = high < low ? 0 : high - low;
        for (int i = 0; i < raster.length; ++i) {
            if (surfaces[i] == 3) raster[i] = 2;
            else if (surfaces[i] == 1) raster[i] = 1;
            else if (surfaces[i] == 2)
                raster[i] =
                        (byte)
                                (3
                                        + Mth.clamp(
                                                (heights[i] - low) * 15 / Math.max(relief, 1),
                                                0,
                                                15));
        }
        return new Site(
                id,
                x,
                z,
                cx,
                cz,
                width,
                height,
                depth,
                mapX,
                mapZ,
                spanX,
                spanZ,
                home == null ? (high < low ? 0 : high) : Mth.floor(home.y),
                relief,
                result,
                markX,
                markZ,
                raster);
    }
}
