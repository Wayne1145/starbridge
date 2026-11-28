package dev.starbridge.transit;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;

/** Evidence available to an old paid voyage that predates the per-chunk transfer receipt. */
final class LegacyPaidHull {
    private LegacyPaidHull() {}

    static boolean matches(ServerLevel level, ShipCoreBlockEntity core, StorageBounds bounds) {
        var rocket = core.originalRocket();
        if (rocket == null || core.owner() == null) return false;
        var archive = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/starbridge-original-ships").resolve(rocket + ".nbt");
        if (!Files.isRegularFile(archive)) return false;
        try {
            CompoundTag assembly = NbtIo.readCompressed(archive.toFile());
            if (!assembly.hasUUID("Rocket") || !rocket.equals(assembly.getUUID("Rocket"))
                    || !assembly.hasUUID("Owner") || !core.owner().equals(assembly.getUUID("Owner"))) return false;
            CompoundTag hull = assembly.getCompound("Hull");
            if (!hull.hasUUID("Core") || !core.id().equals(hull.getUUID("Core")) || !hull.contains("Bridge",Tag.TAG_LONG)) return false;
            var cells = hull.getList("Cells",Tag.TAG_COMPOUND);
            if (cells.isEmpty() || cells.size() != HullManifest.inspect(level,bounds).blocks()) return false;
            BlockPos shift = core.getBlockPos().subtract(BlockPos.of(hull.getLong("Bridge")));
            Set<BlockPos> seen = new HashSet<>();
            for (Tag value : cells) {
                var row = (CompoundTag) value;
                var pos = BlockPos.of(row.getLong("P")).offset(shift);
                if (!inside(pos,bounds) || !seen.add(pos)) return false;
                var state = level.getBlockState(pos);
                var block = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                if (block == null || !row.getCompound("State").getString("Name").equals(block.toString())) return false;
                var machine = level.getBlockEntity(pos);
                if (machine == null) {
                    if (row.contains("Tile")) return false;
                } else {
                    var type = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(machine.getType());
                    if (type == null || !row.getCompound("Tile").getString("id").equals(type.toString())) return false;
                }
            }
            // Cargo was verified before this exact debit was issued. The original assembler certifies every
            // occupied cell and machine here. Their current contents, including loot and furnace output, are authoritative.
            return true;
        } catch (IOException | RuntimeException failure) {
            Starbridge.LOGGER.warn("Cannot read original assembly evidence for a paid voyage on rocket {}",rocket,failure);
            return false;
        }
    }

    private static boolean inside(BlockPos p,StorageBounds b) {
        return p.getX()>=b.minX() && p.getX()<=b.maxX() && p.getY()>=b.minY() && p.getY()<=b.maxY() && p.getZ()>=b.minZ() && p.getZ()<=b.maxZ();
    }
}
