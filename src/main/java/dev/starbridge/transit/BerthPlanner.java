package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Chooses a surface berth whose entire rotated hull clears terrain, other ships, players and the border. */
final class BerthPlanner {
    private BerthPlanner() {}
    static Vec3 find(ServerLevel source, ServerLevel destination, long ship, VsShipBackend.StorageBounds bounds,
                     Vec3 origin, Vec3 requested, boolean exact, VsShipBackend vs) {
        if (!Double.isFinite(requested.x) || !Double.isFinite(requested.y) || !Double.isFinite(requested.z)
                || Math.abs(requested.x)>29_999_984 || Math.abs(requested.z)>29_999_984) {
            throw new IllegalArgumentException("Invalid destination berth");
        }
        AABB hull = null;
        for (int mask=0; mask<8; mask++) {
            Vec3 corner=new Vec3((mask&1)==0 ? bounds.minX() : bounds.maxX()+1.,
                    (mask&2)==0 ? bounds.minY() : bounds.maxY()+1., (mask&4)==0 ? bounds.minZ() : bounds.maxZ()+1.);
            Vec3 world=HullTransit.toWorld(source,ship,corner).orElseThrow(() -> new IllegalStateException("Ship transform unavailable"));
            AABB point=new AABB(world,world);
            hull=hull==null ? point : hull.minmax(point);
        }
        AABB box=hull.move(requested.subtract(origin));
        if (box.getXsize()>450 || box.getYsize()>450 || box.getZsize()>450) throw new IllegalStateException("nav.starbridge.flight.hull_size");
        boolean originalSurface = !exact && dev.starbridge.compat.galacticraft.OriginalStarMap.byLevel(destination) != null;
        Vec3 berth=requested;
        if (originalSurface) {
            // Reassembled cabins are axis-aligned. Align the footprint, independently of which
            // engine or cargo block moved the native mass center toward the bow.
            int width = Math.max(1, Mth.ceil(box.getXsize() - .00001));
            int depth = Math.max(1, Mth.ceil(box.getZsize() - .00001));
            double dx = Mth.floor(requested.x) - width / 2 - box.minX;
            double dz = Mth.floor(requested.z) - depth / 2 - box.minZ;
            box = box.move(dx, 0, dz);
            berth = berth.add(dx, 0, dz);
        }
        if (!destination.getWorldBorder().isWithinBounds(box.minX,box.minZ) || !destination.getWorldBorder().isWithinBounds(Math.nextDown(box.maxX),Math.nextDown(box.maxZ)))
            throw new IllegalStateException("nav.starbridge.flight.border");
        // Height data and collision tests may otherwise silently skip unloaded chunks. Load each footprint column once.
        for (int x=Mth.floor(box.minX)>>4; x<=Mth.floor(Math.nextDown(box.maxX))>>4; x++) {
            for (int z=Mth.floor(box.minZ)>>4; z<=Mth.floor(Math.nextDown(box.maxZ))>>4; z++) destination.getChunk(x,z);
        }
        if (!exact) {
            double top=destination.getMinBuildHeight();
            var column = new BlockPos.MutableBlockPos();
            for (int x=Mth.floor(box.minX); x<=Mth.floor(Math.nextDown(box.maxX)); x++) {
                for (int z=Mth.floor(box.minZ); z<=Mth.floor(Math.nextDown(box.maxZ)); z++) {
                    if (originalSurface) {
                        var ground=dev.starbridge.orbit.LandingGround.read(destination,destination.getChunk(x>>4,z>>4),x,z,column);
                        if (ground.empty() || ground.liquid()) throw new IllegalStateException("nav.starbridge.flight.survey_changed");
                        top=Math.max(top,ground.top());
                    } else top=Math.max(top,destination.getHeight(Heightmap.Types.WORLD_SURFACE,x,z));
                }
            }
            double lift=originalSurface ? top-box.minY : Math.max(0,top+3-box.minY);
            berth=berth.add(0,lift,0); box=box.move(0,lift,0);
        }
        if (box.minY<destination.getMinBuildHeight() || box.maxY>destination.getMaxBuildHeight())
            throw new IllegalStateException("nav.starbridge.flight.ceiling");
        // For a recorded home berth, verify the actual occupied volume (a floating dock below is legal).
        // Surface berths are already above the topmost non-air block in every column.
        if ((exact && destination.getBlockCollisions(null,box.deflate(.001)).iterator().hasNext())
                || vs.intersectsShip(destination,box) || !destination.getEntities((net.minecraft.world.entity.Entity)null,box, e -> !e.isSpectator()).isEmpty())
            throw new IllegalStateException("nav.starbridge.flight.occupied");
        return berth;
    }
}
