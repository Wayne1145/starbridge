package dev.starbridge.benchmark;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.AbstractList;
import java.util.List;
import java.util.UUID;

/** Only blocks actually placed by the generator, suitable for a later assembly request. */
public final class CompletedBuild {
    private final UUID owner;
    private final BlockPos origin;
    private final BlockPos core;
    private final int length, width, height, machines, blockEntities;
    private final long[] positions;
    private final long elapsedMillis;

    CompletedBuild(UUID owner, BlockPos origin, BlockPos core, int length, int width, int height,
                   int machines, int blockEntities, long[] positions, long elapsedMillis) {
        this.owner = owner;
        this.origin = origin.immutable();
        this.core = core.immutable();
        this.length = length;
        this.width = width;
        this.height = height;
        this.machines = machines;
        this.blockEntities = blockEntities;
        this.positions = positions.clone();
        this.elapsedMillis = elapsedMillis;
    }

    public UUID owner() { return owner; }
    public BlockPos origin() { return origin; }
    public BlockPos core() { return core; }
    public int length() { return length; }
    public int width() { return width; }
    public int height() { return height; }
    public int machineCount() { return machines; }
    public int blockEntityCount() { return blockEntities; }
    public int blockCount() { return positions.length; }
    public long elapsedMillis() { return elapsedMillis; }

    /** Read-only view that materializes each BlockPos only when requested. */
    public List<BlockPos> positions() {
        return new AbstractList<>() {
            @Override public BlockPos get(int index) { return BlockPos.of(positions[index]); }
            @Override public int size() { return positions.length; }
        };
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Owner", owner);
        tag.putLong("Origin", origin.asLong());
        tag.putLong("Core", core.asLong());
        tag.putInt("Length", length);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        tag.putInt("Machines", machines);
        tag.putInt("BlockEntities", blockEntities);
        tag.putLongArray("Positions", positions);
        tag.putLong("ElapsedMillis", elapsedMillis);
        return tag;
    }

    static CompletedBuild load(CompoundTag tag) {
        return new CompletedBuild(tag.getUUID("Owner"), BlockPos.of(tag.getLong("Origin")),
                BlockPos.of(tag.getLong("Core")), tag.getInt("Length"), tag.getInt("Width"),
                tag.getInt("Height"), tag.getInt("Machines"), tag.getInt("BlockEntities"),
                tag.getLongArray("Positions"), tag.getLong("ElapsedMillis"));
    }
}
