package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trial-only source chunk preparation. The actual departure still reads every block and complete machine tag. */
final class WideHullPreload {
    static final Path MARKER = Path.of("starbridge-wide-preload.fixture");
    private static final TicketType<ChunkPos> TICKET = TicketType.create("starbridge_qa_preload", Comparator.comparingLong(ChunkPos::toLong));
    private final ServerLevel level;
    private final StorageBounds bounds;
    private final boolean chunksOnly;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private final List<Long> workNs = new ArrayList<>(), intervalsNs = new ArrayList<>();
    private long lastTickNs;
    private int held, cursor, skipped, materialized, checked;
    private LevelChunk current;
    private Iterator<BlockPos> machines;
    private BlockPos cargo;

    WideHullPreload(ServerLevel level, StorageBounds bounds, boolean chunksOnly) {
        this.level = level;
        this.bounds = bounds;
        this.chunksOnly = chunksOnly;
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
            for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) chunks.add(new ChunkPos(x,z));
        }
    }

    boolean step(boolean spareTime) {
        long now = System.nanoTime();
        if (lastTickNs != 0) intervalsNs.add(now-lastTickNs);
        lastTickNs = now;
        if (!spareTime) { skipped++; return false; }
        try {
            ChunkPos pos = chunks.get(cursor);
            var cache = level.getChunkSource();
            if (held == cursor) {
                cache.addRegionTicket(TICKET,pos,0,pos);
                held++;
                return false;
            }
            if (current == null) {
                current = cache.getChunkNow(pos.x,pos.z);
                if (current == null) return false;
                if (chunksOnly) {
                    cursor++;
                    current = null;
                    return cursor == chunks.size();
                }
                machines = current.getBlockEntitiesPos().iterator();
            }
            for (int n=0; machines.hasNext() && n<32 && System.nanoTime()-now<2_000_000L; n++) {
                var p = machines.next();
                if (p.getX()<bounds.minX() || p.getX()>bounds.maxX() || p.getY()<bounds.minY() || p.getY()>bounds.maxY()
                        || p.getZ()<bounds.minZ() || p.getZ()>bounds.maxZ()) continue;
                boolean live = current.getBlockEntities().containsKey(p);
                var entity = current.getBlockEntity(p);
                if (entity == null) throw new IllegalStateException("Missing ship machine at " + p);
                if (!live) materialized++;
                checked++;
                if (entity instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(26).isEmpty()
                        && (cargo == null || p.compareTo(cargo)<0)) cargo=p.immutable();
            }
            if (!machines.hasNext()) { cursor++; current=null; machines=null; }
            return cursor == chunks.size();
        } finally { workNs.add(System.nanoTime()-now); }
    }

    Map<String,Object> finish(boolean lateEdit) {
        if (!chunksOnly && checked!=4530) throw new IllegalStateException("Expected all original ship machine positions, got " + checked);
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("chunksOnly",chunksOnly); values.put("sourceChunks",chunks.size()); values.put("machinePositions",checked); values.put("instantiatedByProbe",materialized);
        values.put("busyTicksSkipped",skipped); values.put("workNs",workNs); values.put("tickIntervalsNs",intervalsNs);
        if (lateEdit) {
            if (cargo==null || !(level.getBlockEntity(cargo) instanceof ChestBlockEntity chest)
                    || !chest.getItem(0).is(Items.DIAMOND) || !chest.getItem(26).isEmpty()) throw new IllegalStateException("Missing late edit cargo");
            var stack = chest.removeItemNoUpdate(0);
            chest.setItem(26,stack); chest.setChanged();
            values.put("edit",Map.of("position",List.of(cargo.getX(),cargo.getY(),cargo.getZ()), "item","minecraft:diamond",
                    "count",stack.getCount(), "oldSlot",0, "newSlot",26));
        }
        return values;
    }

    void release() {
        for (int i=0; i<held; i++) {
            var p = chunks.get(i);
            level.getChunkSource().removeRegionTicket(TICKET,p,0,p);
        }
        held = 0;
    }
}
