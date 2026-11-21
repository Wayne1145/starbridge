package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A position near the deck may be inside a chest or on the planet below it. Confirm real footing before delivery. */
final class ArrivalDeck {
    private static final Map<UUID, Map<UUID, Check>> checks = new HashMap<>();
    private static final double CONTACT = .075;
    private static final int SETTLE_TICKS = 20;
    private static final class Check {
        ServerPlayer player;
        int since = -1, last = -1, attempts;
    }
    static void clear() { checks.clear(); }
    static void forget(UUID voyage) { checks.remove(voyage); }

    static boolean settled(ServerPlayer player, ServerLevel destination, TransitJournal.Leg leg, TransitJournal.Rider rider) {
        var byPlayer = checks.computeIfAbsent(leg.id(), ignored -> new HashMap<>());
        var check = byPlayer.computeIfAbsent(player.getUUID(), ignored -> new Check());
        int now = destination.getServer().getTickCount();
        if (check.player != player || now - check.last > 10) {
            check.since = -1;
            check.attempts = 0;
            check.player = player;
        }
        check.last = now;
        var current = player.level() == destination ? HullTransit.toShipLocal(destination, leg.ship(), player.position()).orElse(null) : null;
        if (current != null && supported(destination, leg.bounds(), current)) {
            if (check.since < 0) check.since = now;
            return now - check.since >= SETTLE_TICKS;
        }
        check.since = -1;
        var landing = find(destination, leg.bounds(), rider.local(), check.attempts > 0);
        if (landing == null) return false;
        var world = HullTransit.toWorld(destination, leg.ship(), landing).orElse(null);
        if (world == null) return false;
        ++check.attempts;
        player.stopRiding();
        player.teleportTo(destination, world.x, world.y, world.z, rider.yaw(), rider.pitch());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        return false;
    }

    private static boolean inside(StorageBounds b, BlockPos p) {
        return p.getX() >= b.minX() && p.getX() <= b.maxX() && p.getY() >= b.minY() && p.getY() <= b.maxY()
                && p.getZ() >= b.minZ() && p.getZ() <= b.maxZ();
    }

    /** Return the top of a collision box under these coordinates in the ship's own block grid. */
    private static double top(ServerLevel level, StorageBounds bounds, BlockPos block, double x, double z, double maxY) {
        if (!inside(bounds, block) || !level.hasChunkAt(block)) return Double.NEGATIVE_INFINITY;
        double best = Double.NEGATIVE_INFINITY;
        for (var part : level.getBlockState(block).getCollisionShape(level, block).toAabbs()) {
            double localX = x - block.getX(), localZ = z - block.getZ(), y = block.getY() + part.maxY;
            if (localX >= part.minX && localX <= part.maxX && localZ >= part.minZ && localZ <= part.maxZ && y <= maxY) best = Math.max(best,y);
        }
        return best;
    }

    static boolean supported(ServerLevel level, StorageBounds bounds, Vec3 feet) {
        var block = BlockPos.containing(feet.x, feet.y + CONTACT, feet.z);
        for (int step = 0; step <= 2; ++step) {
            double y = top(level, bounds, block.below(step), feet.x, feet.z, feet.y + CONTACT);
            if (Math.abs(feet.y - y) <= CONTACT) return true;
        }
        return false;
    }

    private static boolean headroom(ServerLevel level, StorageBounds bounds, Vec3 point) {
        // Use ship storage shapes. A world-coordinate collision query tests a different world column.
        var body = new AABB(point.x-.29, point.y+.002, point.z-.29, point.x+.29, point.y+1.798, point.z+.29);
        for (var pos : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(body.minY)-1, Mth.floor(body.minZ), Mth.floor(body.maxX), Mth.floor(body.maxY), Mth.floor(body.maxZ))) {
            if (!inside(bounds, pos)) continue;
            if (!level.hasChunkAt(pos)) return false;
            for (var part : level.getBlockState(pos).getCollisionShape(level,pos).toAabbs())
                if (part.move(pos).intersects(body)) return false;
        }
        return true;
    }

    static Vec3 find(ServerLevel level, StorageBounds b, Vec3 requested, boolean preferFullBlock) {
        var foot = BlockPos.containing(requested);
        Vec3 nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (var block : BlockPos.betweenClosed(foot.offset(-2,-3,-2),foot.offset(2,2,2))) {
            if (!inside(b,block)) continue;
            boolean sameColumn = block.getX() == foot.getX() && block.getZ() == foot.getZ();
            double x = sameColumn ? requested.x : block.getX()+.5, z = sameColumn ? requested.z : block.getZ()+.5;
            double y = top(level,b,block,x,z,Double.POSITIVE_INFINITY);
            if (!Double.isFinite(y)) continue;
            var test = new Vec3(x,y,z);
            double cost = requested.distanceToSqr(test);
            // If a client fell through a partial floor during native chunk loading, a neighbouring
            // full floor provides an alternative while preserving the same deck where possible.
            if (preferFullBlock && y != Math.rint(y)) cost += 9;
            if (cost < best && headroom(level,b,test)) { nearest=test; best=cost; }
        }
        return nearest;
    }
}
