package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Measures each ship block by block, a few thousand blocks per tick, and keeps the last result.
 * The helm asks for a rating when the star chart opens; a launch needs one taken recently from the
 * same hull.
 */
public final class ShipRatings {
    /** A rating older than this (ticks) is measured again before launch. */
    public static final long FRESH_TICKS = 1_200;
    private static final int BLOCKS_PER_TICK = 4_096;
    private static final ResourceLocation MOTOR = new ResourceLocation("advancedrocketry", "rocketmotor");
    private static final ResourceLocation TANK = new ResourceLocation("advancedrocketry", "fueltank");
    private static final ResourceLocation SEAT = new ResourceLocation("advancedrocketry", "seat");
    private static final ResourceLocation GUIDANCE = new ResourceLocation("advancedrocketry", "tile.guidancecomputer");
    private static final VsShipBackend SHIPS = new VsShipBackend();

    private record Measured(ShipRating rating, String dimension, StorageBounds bounds, long tick) {}
    private static final Map<UUID, Measured> RESULTS = new HashMap<>();
    private static final Map<UUID, Scan> SCANS = new HashMap<>();

    private ShipRatings() {}

    /** A rating of this hull, as it stands now, taken within {@link #FRESH_TICKS}. */
    public static Optional<ShipRating> fresh(ShipCoreBlockEntity core) {
        var measured = RESULTS.get(core.id());
        var bounds = bounds(core);
        if (measured == null || bounds.isEmpty() || !(core.getLevel() instanceof ServerLevel level)) return Optional.empty();
        boolean same = measured.bounds().equals(bounds.get()) && measured.dimension().equals(level.dimension().location().toString());
        return same && level.getGameTime() - measured.tick() <= FRESH_TICKS ? Optional.of(measured.rating()) : Optional.empty();
    }

    /** The most recent rating for display, even if it is old. */
    public static Optional<ShipRating> last(ShipCoreBlockEntity core) {
        return Optional.ofNullable(RESULTS.get(core.id())).map(Measured::rating);
    }

    /** Fresh rating, or starts measuring and returns empty. */
    public static Optional<ShipRating> request(ShipCoreBlockEntity core) {
        var fresh = fresh(core);
        if (fresh.isPresent()) return fresh;
        if (!SCANS.containsKey(core.id()) && core.getLevel() instanceof ServerLevel level)
            bounds(core).ifPresent(b -> SCANS.put(core.id(), new Scan(core, level, b)));
        return Optional.empty();
    }

    public static boolean measuring(ShipCoreBlockEntity core) { return SCANS.containsKey(core.id()); }

    static void tick(MinecraftServer server) {
        if (SCANS.isEmpty()) return;
        int budget = BLOCKS_PER_TICK;
        for (var iterator = SCANS.entrySet().iterator(); iterator.hasNext() && budget > 0; ) {
            var scan = iterator.next().getValue();
            if (scan.core.isRemoved() || scan.core.getLevel() != scan.level) { iterator.remove(); continue; }
            budget = scan.advance(budget);
            if (scan.done()) {
                iterator.remove();
                var rating = scan.rating();
                RESULTS.put(scan.core.id(), new Measured(rating, scan.level.dimension().location().toString(), scan.bounds, scan.level.getGameTime()));
                scan.core.applyRating(rating);
            } else if (scan.blocked) {
                iterator.remove(); // A chunk unloaded; measure again on the next request.
            }
        }
    }

    static void clear() {
        RESULTS.clear();
        SCANS.clear();
    }

    /** The blocks that fly: the fixed orbital cabin, or the physical VS ship before lift-off. */
    private static Optional<StorageBounds> bounds(ShipCoreBlockEntity core) {
        var cabin = OrbitModule.cabinBounds(core);
        if (cabin.isPresent()) return cabin;
        if (!(core.getLevel() instanceof ServerLevel level)) return Optional.empty();
        try {
            if (!SHIPS.probe().available()) return Optional.empty();
            return SHIPS.inspect(level, core.getBlockPos()).flatMap(ship -> SHIPS.storageBoundsById(level, ship.shipId()));
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    private static final class Scan {
        final ShipCoreBlockEntity core;
        final ServerLevel level;
        final StorageBounds bounds;
        final long volume;
        long index;
        int hull, engines, tanks, seats;
        boolean guidance, blocked;

        Scan(ShipCoreBlockEntity core, ServerLevel level, StorageBounds bounds) {
            this.core = core;
            this.level = level;
            this.bounds = bounds;
            volume = (long) (bounds.maxX() - bounds.minX() + 1) * (bounds.maxY() - bounds.minY() + 1) * (bounds.maxZ() - bounds.minZ() + 1);
        }

        int advance(int budget) {
            int width = bounds.maxX() - bounds.minX() + 1, depth = bounds.maxZ() - bounds.minZ() + 1;
            var at = new BlockPos.MutableBlockPos();
            while (budget > 0 && index < volume) {
                long layer = index / ((long) width * depth), rest = index % ((long) width * depth);
                at.set(bounds.minX() + (int) (rest % width), bounds.minY() + (int) layer, bounds.minZ() + (int) (rest / width));
                if (!level.hasChunkAt(at)) { blocked = true; return budget; }
                var state = level.getBlockState(at);
                index++;
                budget--;
                if (state.isAir()) continue;
                hull++;
                var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                if (MOTOR.equals(id)) engines++;
                else if (TANK.equals(id)) tanks++;
                else if (SEAT.equals(id)) seats++;
                else if (GUIDANCE.equals(id)) guidance = true;
            }
            return budget;
        }

        boolean done() { return index >= volume; }

        ShipRating rating() { return new ShipRating(Math.max(1, hull), engines, tanks, seats, guidance); }
    }
}
