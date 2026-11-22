package dev.starbridge.compat.rocketry;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;

import dev.starbridge.Starbridge;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exercises the actual 1.7 assembler's geometry and original storage packet in the isolated 1.20.1
 * server.
 */
public final class LargeDockFixture {
    private LargeDockFixture() {}

    public static void register() {
        if (!Boolean.getBoolean("starbridge.largeDockFixture")) return;
        MinecraftForge.EVENT_BUS.addListener(LargeDockFixture::exercise);
    }

    private static void exercise(TickEvent.ServerTickEvent event) {
        var server = event.getServer();
        if (event.phase != TickEvent.Phase.END
                || server.getTickCount() != 65
                || !server.isDedicatedServer()
                || !Files.isRegularFile(Path.of("starbridge-original.fixture"))) return;
        record Result(String name, int baseline, int upgraded, int decodedLength, int bytes) {}
        List<Result> observations = new ArrayList<>();
        List<Map<String, Object>> floorRepairs = new ArrayList<>();
        try {
            var level = server.overworld();
            var pilot =
                    FakePlayerFactory.get(
                            level,
                            new GameProfile(
                                    UUID.fromString("91f81386-9317-4231-b1d0-cd53641525eb"),
                                    "DockSurveyor"));
            for (int length : new int[] {100, 200, 300}) {
                var pos = new BlockPos(length / 3, 191, 40);
                int oy = pos.getY() - 1, oz = pos.getZ() + 1, ox = pos.getX() - 1;
                for (int z = oz; z < oz + length; z++)
                    for (int x = ox; x < ox + 3; x++)
                        level.setBlock(
                                new BlockPos(x, oy, z), block("launchpad").defaultBlockState(), 2);
                for (int h = 0; h < 7; h++)
                    level.setBlock(
                            new BlockPos(ox - 1, oy + h, oz + 1),
                            block("structuretower").defaultBlockState(),
                            2);
                var builder = block("rocketbuilder");
                var meta = (IntegerProperty) builder.getStateDefinition().getProperty("meta");
                level.setBlock(pos, builder.defaultBlockState().setValue(meta, 2), 3);
                var tile = OriginalRocketApi.builder(level.getBlockEntity(pos)).orElseThrow();
                var small = ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, pos));
                level.setBlock(pos.above(), block("blockmonitoringstation").defaultBlockState(), 3);
                var large = ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, pos));
                if (small == null || small.sz() != 16 || large == null || large.sz() != length)
                    throw new IllegalStateException(
                            "Unexpected assembler extents: " + small + " / " + large);
                // Choose an interior floor tile outside both axes used to derive the original bounds.
                var hole = new BlockPos(ox + 2, oy, oz + length - 3);
                PadFloor.inspect(level, large, tile).requireComplete();
                level.removeBlock(hole, false);
                var withHole = ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, pos));
                if (!large.equals(withHole) || !hole.equals(PadFloor.inspect(level, large, tile).gap()))
                    throw new IllegalStateException("Interior hole lost its original pad extent or its cell");
                // An upstream success cached before the hole appeared must not start a hull journal.
                tile.getClass().getMethod("setStatus", int.class).invoke(tile, 0);
                String refusal = "";
                try { RocketTransfer.build(pilot, pos, tile, large); }
                catch (IllegalStateException e) { refusal = e.getMessage(); }
                if (!"pad_gap".equals(refusal) || RocketTransfer.job(level, pos) != null)
                    throw new IllegalStateException("Assembly did not stop safely at the gap: " + refusal);
                level.setBlock(hole, block("tile.dockingpad").defaultBlockState(), 2);
                PadFloor.inspect(level, large, tile).requireComplete();
                floorRepairs.add(Map.of("length", length, "position", List.of(hole.getX(), hole.getY(), hole.getZ()),
                        "refusal", refusal, "originalCachedStatus", "SUCCESS", "journalCreated", false,
                        "repairedWith", "advancedrocketry:tile.dockingpad"));
                int bytes = exerciseStorage(tile.getClass().getClassLoader(), length);
                observations.add(
                        new Result("Advanced Rocketry", small.sz(), large.sz(), length, bytes));
            }
            Files.writeString(
                    Path.of("starbridge-dock.json"),
                    new GsonBuilder()
                            .setPrettyPrinting()
                            .create()
                            .toJson(Map.of("passed", true, "rows", observations, "floorRepairs", floorRepairs)));
        } catch (Exception | LinkageError failure) {
            Starbridge.LOGGER.error("Original large dock fixture failed", failure);
            try {
                Files.writeString(
                        Path.of("starbridge-dock.json"),
                        new GsonBuilder()
                                .create()
                                .toJson(
                                        Map.of(
                                                "passed",
                                                false,
                                                "error",
                                                failure.toString(),
                                                "rows",
                                                observations)));
            } catch (Exception ignored) {
            }
        } finally {
            server.halt(false);
        }
    }

    private static Block block(String name) {
        var b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("advancedrocketry", name));
        if (b == null || b == Blocks.AIR)
            throw new IllegalStateException("Missing original block " + name);
        return b;
    }

    private static int exerciseStorage(ClassLoader loader, int length)
            throws ReflectiveOperationException {
        var storage = loader.loadClass("zmaster587.advancedRocketry.util.StorageChunk");
        var constructor = storage.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        var ship = constructor.newInstance(3, 4, length);
        var copy = storage.getConstructor().newInstance();
        Class<?> byteBuf = loader.loadClass("io.netty.buffer.ByteBuf");
        Object buffer =
                loader.loadClass("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
        try {
            storage.getMethod("writeToNetwork", byteBuf).invoke(ship, buffer);
            int used = (int) byteBuf.getMethod("readableBytes").invoke(buffer);
            storage.getMethod("readFromNetwork", byteBuf).invoke(copy, buffer);
            if (OriginalRocketApi.number(copy, "getSizeZ") != length
                    || OriginalRocketApi.number(copy, "getSizeX") != 3
                    || OriginalRocketApi.number(copy, "getSizeY") != 4
                    || (int) byteBuf.getMethod("readableBytes").invoke(buffer) != 0)
                throw new IllegalStateException(
                        "Incorrect storage packet after " + length + "-block round trip");
            return used;
        } finally {
            byteBuf.getMethod("release").invoke(buffer);
        }
    }
}
