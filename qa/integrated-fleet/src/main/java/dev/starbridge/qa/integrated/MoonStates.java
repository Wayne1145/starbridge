package dev.starbridge.qa.integrated;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Mod.EventBusSubscriber(modid = "starbridgeintegratedqa")
public final class MoonStates {
    private static final BlockPos SPAWNER = new BlockPos(396, 27, 193);
    private static final boolean DUNGEON =
            Files.isRegularFile(Path.of("original-dungeon-ticks.fixture"));
    private static int age, startTick, before;
    private static Object originalSpawner;
    private static boolean finished;

    private static ServerLevel moon(MinecraftServer server) {
        return server.getLevel(
                ResourceKey.create(
                        Registries.DIMENSION, ResourceLocation.parse("retrofit:legacy_dim_-28")));
    }

    static void check(MinecraftServer server) throws Exception {
        if (!Files.isRegularFile(Path.of("check-moon-tickers.fixture"))) return;
        var block =
                ForgeRegistries.BLOCKS.getValue(
                        ResourceLocation.parse("galacticraftcore:tile.moonblock"));
        var type =
                ForgeRegistries.BLOCK_ENTITY_TYPES.getValue(
                        ResourceLocation.parse("retrofit:legacy_tile"));
        var prop = block.getStateDefinition().getProperty("meta");
        var original = block.getClass().getMethod("legacyBlock").invoke(block);
        var declaresTile = original.getClass().getMethod("hasTileEntity", int.class);
        var rows = new TreeMap<Integer, Object>();
        for (var state : block.getStateDefinition().getPossibleStates()) {
            int meta = ((Number) state.getValue(prop)).intValue();
            boolean expected = (boolean) declaresTile.invoke(original, meta);
            boolean ticks = ((EntityBlock) block).getTicker(moon(server), state, type) != null;
            rows.put(meta, Map.of("originalHasTile", expected, "ticks", ticks));
            if (expected != (meta == 15) || ticks != expected)
                throw new IllegalStateException("Incorrect lunar ticker for " + meta);
        }
        if (rows.size() != 16) throw new IllegalStateException("Missing lunar block states");
        IntegratedFlight.write("native-moon-tickers.json", rows);
    }

    /**
     * Only the separate dungeon run loads this natural room. No fixture calls the original update
     * method.
     */
    @SubscribeEvent
    public static void dungeon(TickEvent.ServerTickEvent event) {
        if (!DUNGEON || finished || event.phase != TickEvent.Phase.END) return;
        try {
            var server = event.getServer();
            if (!server.isDedicatedServer() || server.overworld().getSeed() != 576618501L)
                throw new IllegalStateException("Wrong dungeon save");
            var world = moon(server);
            int cx = SPAWNER.getX() >> 4, cz = SPAWNER.getZ() >> 4;
            if (++age == 5) {
                check(server);
                world.setChunkForced(cx, cz, true);
                // Terrain population for this original dungeon spans neighbouring chunks.
                for (int x = cx - 1; x <= cx + 1; ++x)
                    for (int z = cz - 1; z <= cz + 1; ++z) world.getChunk(x, z);
                return;
            }
            if (age < 35) return;
            var state = world.getBlockState(SPAWNER);
            var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (!ResourceLocation.parse("galacticraftcore:tile.moonblock").equals(id))
                throw new IllegalStateException("Natural dungeon cell contains " + state);
            int meta =
                    ((Number)
                                    state.getValue(
                                            state.getBlock()
                                                    .getStateDefinition()
                                                    .getProperty("meta")))
                            .intValue();
            var wrapper = world.getBlockEntity(SPAWNER);
            if (meta != 15 || wrapper == null)
                throw new IllegalStateException("Missing original dungeon wrapper " + state);
            var tile = wrapper.getClass().getMethod("legacyTile").invoke(wrapper);
            if (tile == null
                    || !tile.getClass()
                            .getName()
                            .equals(
                                    "micdoodle8.mods.galacticraft.core.tile.TileEntityDungeonSpawner"))
                throw new IllegalStateException("Unexpected original dungeon tile " + tile);
            int count = tile.getClass().getField("ticks").getInt(tile);
            if (originalSpawner == null) {
                originalSpawner = tile;
                startTick = server.getTickCount();
                before = count;
                return;
            }
            int elapsed = server.getTickCount() - startTick;
            if (elapsed < 80) return;
            if (tile != originalSpawner || count - before != elapsed)
                throw new IllegalStateException(
                        "Natural spawner counter advanced "
                                + (count - before)
                                + " in "
                                + elapsed
                                + " ticks");
            var result = new LinkedHashMap<String, Object>();
            result.put("world", world.dimension().location().toString());
            result.put("seed", server.overworld().getSeed());
            result.put(
                    "position", java.util.List.of(SPAWNER.getX(), SPAWNER.getY(), SPAWNER.getZ()));
            result.put("registry", id.toString());
            result.put("metadata", meta);
            result.put("class", tile.getClass().getName());
            result.put("counterStart", before);
            result.put("counterEnd", count);
            result.put("serverTicks", elapsed);
            result.put("legacyData", wrapper.saveWithoutMetadata().toString());
            IntegratedFlight.write("dungeon-ticks.json", result);
            world.setChunkForced(cx, cz, false);
            finished = true;
        } catch (Exception | LinkageError ex) {
            finished = true;
            IntegratedFlight.fail(ex);
        }
    }
}
