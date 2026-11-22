package dev.starbridge.qa.fuel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod("starbridgenativefuelqa")
@Mod.EventBusSubscriber(modid = "starbridgenativefuelqa")
public final class FactoryTrial {
    record Order(int seq, String action, long pos, String input, String output) {}

    static final String FUEL = "advancedrocketry:item.bucketrocketfuel";
    static final Path MARK = Path.of("starbridge-native-fuel.fixture");
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final BlockPos ASSEMBLER = new BlockPos(19, 191, 17);
    private static final Map<String, Object> report = new LinkedHashMap<>();
    private static final ArrayList<Object> progress = new ArrayList<>();
    // Only the disposable factory launcher writes this file, after reading the previous saved
    // world.
    record Checkpoint(UUID pilot, int emptyBuckets, int fuel, long[] splitter, long[] reactor) {
        NativeMachine.Site site(long[] coordinates, int inputs) {
            var positions = java.util.Arrays.stream(coordinates).mapToObj(BlockPos::of).toList();
            require(positions.size() == 5, "Incomplete saved station");
            return new NativeMachine.Site(
                    positions.get(0),
                    List.copyOf(positions.subList(1, 1 + inputs)),
                    List.copyOf(positions.subList(1 + inputs, 4)),
                    positions.get(4));
        }
    }

    private static Checkpoint checkpoint;
    private static NativeMachine.Site split, mix;
    private static int started, stage, lastOrder = -1, lastSample;
    private static boolean finished, dockSetup;

    static void write(Path file, Object data) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.toJson(data));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || !Files.isRegularFile(MARK)) return;
        var server = event.getServer();
        if (!server.isDedicatedServer() || server.getTickCount() % 4 != 0) return;
        try {
            var level = server.overworld();
            if (Files.isRegularFile(Path.of("inspect-saved-rocket.fixture"))) {
                inspectSavedRocket(level);
                return;
            }
            if (split == null) {
                level.setChunkForced(1, 2, true);
                level.setChunkForced(2, 2, true);
                var resume = Path.of("factory-resume.json");
                if (Files.isRegularFile(resume)) {
                    checkpoint = JSON.fromJson(Files.readString(resume), Checkpoint.class);
                    split = checkpoint.site(checkpoint.splitter(), 1);
                    mix = checkpoint.site(checkpoint.reactor(), 2);
                    int restoredFuel = NativeMachine.amount(level, mix.outputs().get(0));
                    require(
                            restoredFuel >= checkpoint.fuel() && checkpoint.fuel() > 0,
                            "Saved reactor did not restore its fuel");
                    report.put("restoredTank", restoredFuel);
                    report.put("restoredPilot", checkpoint.pilot());
                    stage = 9;
                } else {
                    for (int x = 24; x <= 40; x++)
                        for (int z = 25; z <= 40; z++)
                            level.setBlockAndUpdate(
                                    new BlockPos(x, 146, z), Blocks.IRON_BLOCK.defaultBlockState());
                    split =
                            NativeMachine.place(
                                    level,
                                    "advancedrocketry:tile.electrolyser",
                                    new BlockPos(28, 149, 32));
                    mix =
                            NativeMachine.place(
                                    level,
                                    "advancedrocketry:tile.chemreactor",
                                    new BlockPos(36, 150, 32));
                }
                require(
                        split.inputs().size() == 1
                                && split.outputs().size() == 2
                                && mix.inputs().size() == 2
                                && mix.outputs().size() == 1,
                        "Original port count");
                // Block positions use their packed format in the action stream. Gson cannot inspect
                // MC's vector superclass reliably.
                report.put("splitter", split.toString());
                report.put("reactor", mix.toString());
                write(Path.of("factory-start.json"), report);
            }
            if (server.getPlayerList().getPlayers().isEmpty()) {
                if (checkpoint != null && server.getTickCount() % 100 == 0) {
                    int fuel = NativeMachine.amount(level, mix.outputs().get(0));
                    write(
                            Path.of("factory-offline-tank.json"),
                            Map.of(
                                    "tick",
                                    server.getTickCount(),
                                    "fuel",
                                    fuel,
                                    "mixEnabled",
                                    enabled(level, mix.controller()),
                                    "power",
                                    power(level, mix.controller())));
                }
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            if (started == 0) {
                started = server.getTickCount();
                require(
                        !player.hasPermissions(2) && !player.isCreative() && !player.isSpectator(),
                        "Ordinary survival client required");
                if (checkpoint == null) {
                    require(
                            count(player, FUEL) == 0 && player.getInventory().isEmpty(),
                            "Expected empty initial player inventory");
                    require(
                            NativeMachine.amount(level, mix.outputs().get(0)) == 0
                                    && NativeMachine.amount(level, split.inputs().get(0)) == 0,
                            "Empty original machine tanks required");
                    player.getInventory().setItem(9, new ItemStack(Items.COAL, 16));
                    player.getInventory().setItem(10, new ItemStack(Items.COAL, 16));
                    player.getInventory().setItem(11, new ItemStack(Items.WATER_BUCKET));
                    player.getInventory().setItem(12, new ItemStack(Items.BUCKET, 3));
                    report.put("initialPropellant", 0);
                } else {
                    require(
                            player.getUUID().equals(checkpoint.pilot())
                                    && count(player, FUEL) == 0
                                    && count(player, "minecraft:bucket")
                                            == checkpoint.emptyBuckets(),
                            "Saved survival pilot or inventory mismatch");
                    report.put("restoredEmptyBuckets", checkpoint.emptyBuckets());
                }
                report.put("ordinarySurvival", true);
                report.put("startTick", started);
            }
            int now = server.getTickCount();
            require(now - started < 20500, "Timed out at stage " + stage);
            if (now - lastSample >= 100) {
                lastSample = now;
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", now);
                row.put("stage", stage);
                row.put("water", NativeMachine.amount(level, split.inputs().get(0)));
                row.put("splitA", NativeMachine.amount(level, split.outputs().get(0)));
                row.put("splitB", NativeMachine.amount(level, split.outputs().get(1)));
                row.put("mixA", NativeMachine.amount(level, mix.inputs().get(0)));
                row.put("mixB", NativeMachine.amount(level, mix.inputs().get(1)));
                row.put("fuel", NativeMachine.amount(level, mix.outputs().get(0)));
                row.put("splitEnabled", enabled(level, split.controller()));
                row.put("mixEnabled", enabled(level, mix.controller()));
                row.put("splitPower", power(level, split.controller()));
                row.put("mixPower", power(level, mix.controller()));
                row.put("fuelBuckets", count(player, FUEL));
                progress.add(row);
                write(Path.of("factory-progress.json"), progress);
            }
            if (stage == 10
                    && dockSetup
                    && !Files.isRegularFile(Path.of("empty-original-rocket.json"))
                    && rocketFuel(level) == 0)
                write(Path.of("empty-original-rocket.json"), Map.of("tick", now, "fuel", 0));
            if ((stage == 3 || stage == 4)
                    && lastOrder == stage
                    && enabled(level, stage == 3 ? split.controller() : mix.controller())) {
                var ok = Path.of("factory-enabled-" + stage + ".json");
                if (!Files.exists(ok)) write(ok, Map.of("enabled", true, "serverTick", now));
            }
            if (lastOrder == stage) {
                Path done = Path.of("../LunarTester/factory-done-" + stage + ".json");
                if (!Files.isRegularFile(done)) return;
                if (stage == 2)
                    require(
                            count(player, "minecraft:water_bucket") == 0
                                    && count(player, "minecraft:bucket") >= 4,
                            "Water bucket was not emptied");
                if (stage == 5 || stage == 6)
                    require(
                            count(player, "advancedrocketry:item.bucketoxygen")
                                            + count(player, "advancedrocketry:item.buckethydrogen")
                                    >= stage - 4,
                            "Expected native gas bucket");
                if (stage == 9)
                    require(
                            count(player, FUEL) == 1,
                            "Processed fuel was not in player's inventory");
                if (stage == 10) {
                    int loaded = rocketFuel(level);
                    require(
                            loaded == 1000 && count(player, FUEL) == 0,
                            "Expected 1000 mB in original rocket and no remaining filled bucket");
                    report.put("originalRocketFuel", loaded);
                    report.put("finalEmptyBuckets", count(player, "minecraft:bucket"));
                    report.put("endTick", now);
                    report.put("pass", true);
                    write(Path.of("factory-result.json"), report);
                    finished = true;
                    return;
                }
                stage++;
            }
            switch (stage) {
                case 0 -> issue(level, player, split.generator(), "machine", "minecraft:coal", "");
                case 1 -> issue(level, player, mix.generator(), "machine", "minecraft:coal", "");
                case 2 -> issue(
                        level,
                        player,
                        split.inputs().get(0),
                        "machine",
                        "minecraft:water_bucket",
                        "minecraft:bucket");
                case 3, 4 -> {
                    var controller = stage == 3 ? split.controller() : mix.controller();
                    require(!enabled(level, controller), "Expected initially switched-off machine");
                    issue(level, player, controller, "switch", "", "");
                }
                case 5, 6 -> {
                    BlockPos outlet = split.outputs().get(stage - 5);
                    if (NativeMachine.amount(level, outlet) < 1000) return;
                    issue(level, player, outlet, "machine", "minecraft:bucket", "gas");
                }
                case 7, 8 -> issue(
                        level,
                        player,
                        mix.inputs().get(stage - 7),
                        "machine",
                        stage == 7
                                ? "advancedrocketry:item.bucketoxygen"
                                : "advancedrocketry:item.buckethydrogen",
                        "minecraft:bucket");
                case 9 -> {
                    if (NativeMachine.amount(level, mix.outputs().get(0)) < 1000) return;
                    report.put(
                            "fuelAvailableBeforeBucket",
                            NativeMachine.amount(level, mix.outputs().get(0)));
                    issue(level, player, mix.outputs().get(0), "machine", "minecraft:bucket", FUEL);
                }
                case 10 -> {
                    if (!dockSetup) {
                        var type =
                                Class.forName(
                                        "dev.starbridge.compat.rocketry.OriginalShipyardFixture");
                        var setup =
                                type.getDeclaredMethod(
                                        "setup", ServerLevel.class, java.util.UUID.class);
                        setup.setAccessible(true);
                        setup.invoke(null, level, player.getUUID());
                        for (int x = 16; x <= 22; x++)
                            for (int z = 14; z <= 17; z++)
                                level.setBlockAndUpdate(
                                        new BlockPos(x, 190, z),
                                        Blocks.IRON_BLOCK.defaultBlockState());
                        dockSetup = true;
                        report.put("fuelAtDockBeforeAssembly", count(player, FUEL));
                    }
                    issue(level, player, ASSEMBLER, "rocket", FUEL, "");
                }
            }
        } catch (Exception | LinkageError error) {
            finished = true;
            report.put("pass", false);
            report.put("stage", stage);
            report.put("error", error.toString() + " / " + error.getCause());
            try {
                write(Path.of("factory-result.json"), report);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Reopen the real rocket left by the fuel production trial, without furnishing any equipment.
     */
    private static void inspectSavedRocket(ServerLevel level) throws Exception {
        if (started == 0) {
            level.setChunkForced(1, 1, true);
            started = level.getGameTime() > 0 ? (int) level.getGameTime() : 1;
            write(Path.of("factory-start.json"), Map.of("mode", "inspect saved original rocket"));
        }
        var players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var player = players.get(0);
        require(
                !player.hasPermissions(2) && !player.isCreative() && !player.isSpectator(),
                "Expected non-operator survival pilot");
        if (lastOrder == -1) {
            require(
                    rocketFuel(level) == 1000 && count(player, FUEL) == 0,
                    "The refuelled rocket or empty buckets were lost");
            stage = 11;
            issue(level, player, ASSEMBLER, "inspect", "", "");
        } else if (Files.isRegularFile(Path.of("../LunarTester/factory-done-11.json"))) {
            write(
                    Path.of("factory-result.json"),
                    Map.of(
                            "pass",
                            true,
                            "savedRocketFuel",
                            rocketFuel(level),
                            "pilot",
                            player.getUUID(),
                            "emptyBuckets",
                            count(player, "minecraft:bucket")));
            finished = true;
        }
    }

    static void issue(
            ServerLevel level,
            ServerPlayer player,
            BlockPos pos,
            String action,
            String input,
            String output)
            throws Exception {
        if (lastOrder == stage) return;
        if (action.equals("rocket") || action.equals("inspect"))
            player.teleportTo(level, 19.5, 191, 16.5, 0, 7);
        else positionAt(level, player, pos);
        write(Path.of("factory-order.json"), new Order(stage, action, pos.asLong(), input, output));
        lastOrder = stage;
    }

    /**
     * Position beside a face that the real client can actually reach and see. The factory test
     * supplies its stations.
     */
    private static void positionAt(ServerLevel level, ServerPlayer player, BlockPos station) {
        Vec3 center = Vec3.atCenterOf(station);
        for (int radius = 1; radius <= 3; radius++)
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL)
                for (int y = 0; y >= -2; y--) {
                    BlockPos feet = station.relative(d, radius).offset(0, y, 0);
                    if (!level.isEmptyBlock(feet) || !level.isEmptyBlock(feet.above())) continue;
                    Vec3 spot = Vec3.atBottomCenterOf(feet), eye = spot.add(0, 1.62, 0);
                    if (eye.distanceTo(center) > 4.35) continue;
                    var ray =
                            level.clip(
                                    new ClipContext(
                                            eye,
                                            center,
                                            ClipContext.Block.OUTLINE,
                                            ClipContext.Fluid.NONE,
                                            player));
                    if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(station))
                        continue;
                    if (level.isEmptyBlock(feet.below()))
                        level.setBlockAndUpdate(
                                feet.below(), Blocks.IRON_BLOCK.defaultBlockState());
                    player.teleportTo(level, spot.x, spot.y, spot.z, 0, 0);
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, center);
                    return;
                }
        throw new IllegalStateException("No visible station face at " + station.toShortString());
    }

    static boolean enabled(ServerLevel level, BlockPos pos) throws Exception {
        Object tile = NativeMachine.tile(level, pos);
        return (boolean) tile.getClass().getMethod("getMachineEnabled").invoke(tile);
    }

    static int power(ServerLevel level, BlockPos pos) throws Exception {
        Object tile = NativeMachine.tile(level, pos);
        Object banks = tile.getClass().getMethod("getBatteries").invoke(tile);
        for (var m : banks.getClass().getMethods())
            if (m.getName().equals("getEnergyStored") && m.getParameterCount() == 0)
                return ((Number) m.invoke(banks)).intValue();
        return -1;
    }

    static int count(ServerPlayer player, String item) {
        return player.getInventory()
                .countItem(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)));
    }

    static int rocketFuel(ServerLevel level) throws Exception {
        for (Entity e : level.getAllEntities()) {
            java.lang.reflect.Method core;
            try {
                core = e.getClass().getMethod("core");
            } catch (NoSuchMethodException unrelated) {
                continue;
            }
            Object host = core.invoke(e);
            Object old = host.getClass().getMethod("legacy").invoke(host);
            if (old != null
                    && old.getClass()
                            .getName()
                            .equals("zmaster587.advancedRocketry.entity.EntityRocket"))
                return (int) old.getClass().getMethod("getFuelAmount").invoke(old);
        }
        return -1;
    }

    static void require(boolean condition, String msg) {
        if (!condition) throw new IllegalStateException(msg);
    }
}
