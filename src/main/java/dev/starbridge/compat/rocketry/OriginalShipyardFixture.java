package dev.starbridge.compat.rocketry;

import com.mojang.authlib.GameProfile;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Disposable ordinary-Forge-server regression for the 1.7 inventory-proxy null tile during original
 * assembly.
 */
final class OriginalShipyardFixture {
    private static final BlockPos MACHINE = new BlockPos(19, 191, 17);
    private static final BlockPos CHEST = new BlockPos(20, 191, 19),
            FURNACE =
                    Boolean.getBoolean("starbridge.shipyardPair")
                            ? new BlockPos(18, 192, 18)
                            : CHEST.above(),
            CORE = new BlockPos(18, 192, 20);
    private static final UUID OWNER = UUID.fromString("37ad5f67-9570-427d-8121-16aa7d7f5ea6");
    private static int phase, age;
    private static FakePlayer pilot;
    private static UUID expectedCore, expectedRocket;

    static void register() {
        LoadedShipyardFixture.register();
        if (Boolean.getBoolean("starbridge.shipyardFixture"))
            MinecraftForge.EVENT_BUS.addListener(OriginalShipyardFixture::tick);
        else if (Boolean.getBoolean("starbridge.shipyardGuiFixture"))
            MinecraftForge.EVENT_BUS.addListener(OriginalShipyardFixture::realTick);
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException(msg);
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase < 0) return;
        var server = event.getServer();
        if (!server.isDedicatedServer()
                || !Files.exists(Path.of("starbridge-disposable-shipyard.fixture"))) {
            phase = -1;
            return;
        }
        var l = server.overworld();
        try {
            if (++age > 550) throw new IllegalStateException("fixture timeout in phase " + phase);
            if (phase == 0) {
                pilot = FakePlayerFactory.get(l, new GameProfile(OWNER, "DockTester"));
                pilot.setPos(19.5, 191, 16.5);
                setup(l, OWNER);
                phase++;
                return;
            }
            var tile = OriginalRocketApi.builder(l.getBlockEntity(MACHINE)).orElseThrow();
            if (phase == 1) {
                var box = ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, MACHINE));
                check(
                        box != null
                                && box.min().equals(new BlockPos(18, 191, 18))
                                && box.max().equals(new BlockPos(20, 195, 20)),
                        "actual pad recognition");
                Origin.log("recognized pad and 6-block tower");
                OriginalRocketApi.press(tile, pilot, false);
                check(
                        (boolean) OriginalRocketApi.call(tile, "isScanning"),
                        "upstream scan button did not start job");
                phase++;
                return;
            }
            if (phase == 2 && !(boolean) OriginalRocketApi.call(tile, "isScanning")) {
                String status = ((Enum<?>) OriginalRocketApi.call(tile, "getStatus")).name();
                check(status.equals("SUCCESS"), "scan returned " + status);
                Origin.log(
                        "native scan success, weight="
                                + OriginalRocketApi.number(tile, "getWeight")
                                + ", thrust="
                                + OriginalRocketApi.number(tile, "getThrust"));
                RocketTransfer.build(
                        pilot,
                        MACHINE,
                        tile,
                        ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, MACHINE)));
                ((ChestBlockEntity) l.getBlockEntity(CHEST))
                        .setItem(1, new ItemStack(Items.AMETHYST_SHARD, 5));
                phase++;
                return;
            }
            if (phase == 3) {
                var job = RocketTransfer.job(l, MACHINE);
                check(job != null, "assembly job disappeared");
                check(job.stage != RocketTransfer.Stage.INSPECT, "assembly paused: " + job.problem);
                if (!job.ready()) return;
                var entity = l.getEntity(job.rocket);
                check(
                        entity != null && OriginalRocketApi.rocket(entity) != null,
                        "genuine native rocket");
                check(
                        l.getBlockState(CHEST).isAir()
                                && l.getBlockState(FURNACE).isAir()
                                && l.getBlockState(CORE).isAir(),
                        "old cargo and core remained");
                check(
                        job.hull.matches(
                                OriginalRocketApi.field(
                                        OriginalRocketApi.rocket(entity), "storage")),
                        "packed storage differs");
                var cargo =
                        job.hull.cells.stream()
                                .map(t -> (net.minecraft.nbt.CompoundTag) t)
                                .filter(t -> t.getLong("P") == CHEST.asLong())
                                .findFirst()
                                .orElseThrow()
                                .getCompound("Tile")
                                .getList("Items", 10);
                check(
                        cargo.size() == 2
                                && cargo.getCompound(0).getByte("Count") == 17
                                && cargo.getCompound(1).getByte("Count") == 5,
                        "last-minute chest mutation lost");
                Origin.log(
                        "genuine native carrier="
                                + job.rocket
                                + "; packed 17 diamonds and 5 amethyst added after Build; source"
                                + " clear");
                // A checkpoint round-trip tests the persisted hull, independently of the native
                // launch and the GUI.
                var recovery = ShipyardHull.load(job.hull.save());
                var off = new BlockPos(0, 32, 0);
                check(recovery.clear(l, off), "fixture recovery position occupied");
                recovery.refreshNativeTiles(
                        OriginalRocketApi.field(OriginalRocketApi.rocket(entity), "storage"));
                recovery.restore(l, off, false);
                var chest = (ChestBlockEntity) l.getBlockEntity(CHEST.offset(off));
                var furnace = (AbstractFurnaceBlockEntity) l.getBlockEntity(FURNACE.offset(off));
                check(
                        chest.getItem(0).is(Items.DIAMOND)
                                && chest.getItem(0).getCount() == 17
                                && chest.getItem(1).is(Items.AMETHYST_SHARD)
                                && chest.getItem(1).getCount() == 5,
                        "restored cargo mismatch");
                check(
                        furnace.getItem(0).getCount() + furnace.getItem(2).getCount() == 8
                                && furnace.saveWithFullMetadata().getShort("CookTime") > 0,
                        "working furnace lost progress");
                check(
                        ((ShipCoreBlockEntity) l.getBlockEntity(CORE.offset(off)))
                                .id()
                                .equals(recovery.core),
                        "restored wrong core");
                Origin.log(
                        "checkpoint restore: 17 diamonds, 5 amethyst, 8 ore/products, CookTime="
                                + furnace.saveWithFullMetadata().getShort("CookTime")
                                + ", same core");
                // Destroy only the fixture's checkpoint copy; never use it as a substitute for the
                // launch transfer.
                for (BlockPos pos : recovery.box.cells()) {
                    var test = pos.offset(off);
                    l.removeBlockEntity(test);
                    l.setBlock(test, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
                }
                check(recovery.clear(l, off), "fixture recovery copy remained");
                // Run the original seat action and a real native launch. Fuel supply is
                // deliberately pre-seeded in this backend fixture.
                var nativeRocket = OriginalRocketApi.rocket(entity);
                nativeRocket
                        .getClass()
                        .getMethod("addFuelAmount", int.class)
                        .invoke(nativeRocket, 3000);
                pilot.setPos(entity.getX() + 1, entity.getY(), entity.getZ());
                RocketTransfer.board(pilot, MACHINE);
                check(pilot.getVehicle() == entity, "real seat missing");
                RocketTransfer.launch(pilot, MACHINE);
                check(
                        (boolean) OriginalRocketApi.call(nativeRocket, "isInFlight"),
                        "upstream rejected launch");
                Origin.log(
                        "native seat and launch accepted with fixture fuel; startY=" + job.launchY);
                expectedCore = job.hull.core;
                expectedRocket = job.rocket;
                phase++;
                return;
            }
            if (phase == 4) {
                var j = RocketTransfer.job(l, MACHINE);
                if (j != null) {
                    check(
                            j.stage != RocketTransfer.Stage.INSPECT,
                            "landing handoff paused: " + j.problem);
                    return;
                }
                var core = dev.starbridge.server.CoreRegistry.find(expectedCore).orElseThrow();
                check(expectedRocket.equals(core.originalRocket()), "same native rocket ID");
                var bounds = OriginalShipyard.cabinBounds(core).orElseThrow();
                check(bounds.minX() < -20000, "cabin occupies pad");
                var off = core.getBlockPos().subtract(CORE);
                var chest = (ChestBlockEntity) l.getBlockEntity(CHEST.offset(off));
                var furnace = (AbstractFurnaceBlockEntity) l.getBlockEntity(FURNACE.offset(off));
                check(
                        chest.getItem(0).getCount() == 17
                                && chest.getItem(1).getCount() == 5
                                && furnace.getItem(0).getCount() + furnace.getItem(2).getCount()
                                        == 8,
                        "cargo in actual transferred cabin");
                check(
                        dev.starbridge.orbit.OrbitModule.aboard(pilot, core),
                        "pilot missed fixed cabin");
                check(l.getEntity(expectedRocket) == null, "old rocket still live");
                var home = dev.starbridge.voyage.VoyageLog.get(l.getServer()).home(core.id());
                check(
                        home != null
                                && home.x == CORE.getX() + .5
                                && home.y > 195
                                && home.x != core.getBlockPos().getX(),
                        "wrong return berth");
                Origin.log(
                        "powered ascent, one fixed cabin, original ID and pilot, cargo 17/5/8, home"
                                + " above pad. Cabin="
                                + core.getBlockPos()
                                + " home="
                                + home);
                phase = -1;
                Files.writeString(
                        Path.of("shipyard-fixture-result.txt"),
                        "PASS original scan, build, cargo, native seat, native launch and"
                            + " fixed-cabin handoff with a fake player. Fuel and machinery power"
                            + " seeded by fixture. No real GUI clicks.\n");
                server.halt(false);
            }
        } catch (Throwable e) {
            phase = -1;
            Starbridge.LOGGER.error("SHIPYARD FIXTURE FAILED", e);
            try {
                Files.writeString(Path.of("shipyard-fixture-result.txt"), "FAIL " + e + "\n");
            } catch (Exception ignored) {
            }
            server.halt(false);
        }
    }

    private static final BlockPos GUIDANCE = new BlockPos(19, 192, 19);
    private static boolean sawMissingGuidance, sawPlayerRepair;
    private static int closeDelay;
    private static boolean movingCrew, cabinCrew, reachedWhileDisconnected;

    private static void realTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase < 0) return;
        var server = event.getServer();
        if (!server.isDedicatedServer()
                || !Files.exists(Path.of("starbridge-disposable-shipyard.fixture"))) {
            phase = -1;
            return;
        }
        if (phase == 2) {
            if (Boolean.getBoolean("starbridge.shipyardLinked")) {
                phase = -1;
            } else if (++closeDelay > 160) {
                phase = -1;
                server.halt(false);
            }
            return;
        }
        var person =
                server.getPlayerList()
                        .getPlayerByName(
                                System.getProperty("starbridge.shipyardPilot", "ShipyardPilot"));
        if (person == null) return;
        boolean paired = Boolean.getBoolean("starbridge.shipyardPair");
        var engineer =
                server.getPlayerList()
                        .getPlayerByName(
                                System.getProperty(
                                        "starbridge.shipyardEngineer", "ShipyardEngineer"));
        var level = server.overworld();
        boolean reconnect = Boolean.getBoolean("starbridge.shipyardReconnect");
        if (paired && engineer == null) {
            if (reconnect && phase == 1 && movingCrew && !reachedWhileDisconnected) {
                var core = dev.starbridge.server.CoreRegistry.find(expectedCore).orElse(null);
                if (core != null
                        && core.originalRocket() != null
                        && dev.starbridge.orbit.OrbitModule.aboard(person, core)) {
                    reachedWhileDisconnected = true;
                    Origin.log(
                            "RECONNECT captain aboard the fixed cabin while engineer is offline;"
                                    + " original="
                                    + core.originalRocket());
                }
            }
            return;
        }
        try {
            if (++age > 3600) throw new IllegalStateException("GUI fixture timeout phase " + phase);
            if (phase == 0) {
                setup(level, person.getUUID());
                if (paired) {
                    check(
                            !engineer.isCreative() && !engineer.hasPermissions(2),
                            "Engineer must be ordinary survival player");
                    put(level, 20, 193, 20, "seat");
                    put(level, 20, 192, 20, "fueltank");
                    put(level, 19, 191, 20, "fueltank");
                    put(level, 18, 194, 18, "fueltank");
                    engineer.getInventory().clearContent();
                }
                check(
                        !person.isCreative() && !person.hasPermissions(2),
                        "Pilot must be ordinary survival player");
                if (Boolean.getBoolean("starbridge.shipyardRepair")) {
                    var guidance = level.getBlockState(GUIDANCE).getBlock().asItem();
                    check(
                            guidance != Items.AIR,
                            "Original guidance computer has no placeable item");
                    level.setBlockAndUpdate(GUIDANCE, Blocks.AIR.defaultBlockState());
                    person.getInventory().setItem(1, new ItemStack(guidance));
                }
                expectedCore = ((ShipCoreBlockEntity) level.getBlockEntity(CORE)).id();
                for (int x = 16; x <= 22; x++)
                    for (int z = 14; z <= 17; z++)
                        level.setBlock(
                                new BlockPos(x, 190, z), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                var bucket =
                        ForgeRegistries.ITEMS.getValue(
                                new ResourceLocation("advancedrocketry", "item.bucketrocketfuel"));
                check(bucket != null && bucket != Items.AIR, "upstream propellant bucket missing");
                person.getInventory().setItem(0, new ItemStack(bucket));
                person.teleportTo(level, 19.5, 191, 16.5, 0, 7);
                if (paired) engineer.teleportTo(level, 18.5, 191, 16.5, 0, 7);
                phase++;
                Origin.log(
                        "GUI original pad supplied; "
                                + person.getGameProfile().getName()
                                + " in survival without OP, holding"
                                + " original propellant bucket");
                return;
            }
            if (phase == 1) {
                if (Boolean.getBoolean("starbridge.shipyardRepair") && !sawPlayerRepair) {
                    var builder =
                            OriginalRocketApi.builder(level.getBlockEntity(MACHINE)).orElseThrow();
                    var status = ((Enum<?>) OriginalRocketApi.call(builder, "getStatus")).name();
                    if (!sawMissingGuidance
                            && status.equals("NOGUIDANCE")
                            && level.getBlockState(GUIDANCE).isAir()) {
                        sawMissingGuidance = true;
                        Origin.log(
                                "REPAIR original scan rejected a ship missing its guidance"
                                    + " computer");
                    }
                    if (sawMissingGuidance
                            && !level.getBlockState(GUIDANCE).isAir()
                            && person.getInventory().getItem(1).isEmpty()
                            && status.equals("SUCCESS")) {
                        sawPlayerRepair = true;
                        Origin.log(
                                "REPAIR survival client placed the supplied original computer;"
                                    + " native rescan succeeded");
                    }
                }
                if (paired && !movingCrew) {
                    var job = RocketTransfer.job(level, MACHINE);
                    if (job != null
                            && job.rocket != null
                            && job.stage == RocketTransfer.Stage.ASCENT) {
                        var ship = level.getEntity(job.rocket);
                        if (ship != null
                                && ship.getY() - job.launchY > 2
                                && RocketTransfer.riding(person, MACHINE)
                                && RocketTransfer.riding(engineer, MACHINE)
                                && engineer.getY() > job.launchY + 2) {
                            movingCrew = true;
                            Origin.log(
                                    "PAIR both seated on ascending native ship "
                                            + job.rocket
                                            + " pilot="
                                            + person.position()
                                            + " engineer="
                                            + engineer.position());
                        }
                    }
                }
                var core = dev.starbridge.server.CoreRegistry.find(expectedCore).orElse(null);
                if (core == null
                        || core.originalRocket() == null
                        || !dev.starbridge.orbit.OrbitModule.aboard(person, core)) return;
                BlockPos off = core.getBlockPos().subtract(CORE);
                var chest = (ChestBlockEntity) level.getBlockEntity(CHEST.offset(off));
                var furnace =
                        (AbstractFurnaceBlockEntity) level.getBlockEntity(FURNACE.offset(off));
                check(
                        furnace.getItem(0).getCount() + furnace.getItem(2).getCount() == 8,
                        "GUI furnace inventory");
                check(level.getEntity(core.originalRocket()) == null, "duplicate original rocket");
                if (paired) {
                    if (!dev.starbridge.orbit.OrbitModule.aboard(engineer, core)) return;
                    if (!cabinCrew) {
                        if (reconnect)
                            check(
                                    reachedWhileDisconnected,
                                    "Engineer did not stay offline through fixed-cabin handoff");
                        check(movingCrew, "second passenger was never observed in ascent");
                        check(person.distanceToSqr(engineer) > .1, "crew positions collapsed");
                        cabinCrew = true;
                        Origin.log(
                                "PAIR both at distinct cabin positions pilot="
                                        + person.position()
                                        + " engineer="
                                        + engineer.position());
                    }
                    int diamonds = engineer.getInventory().countItem(Items.DIAMOND);
                    if (!(engineer.containerMenu instanceof net.minecraft.world.inventory.ChestMenu)
                            || diamonds != 1
                            || !engineer.containerMenu.getCarried().isEmpty()) return;
                    check(
                            chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 16,
                            "GUI crew transfer from container");
                } else
                    check(
                            chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 17,
                            "GUI single pilot cargo");
                if (Boolean.getBoolean("starbridge.shipyardRepair"))
                    check(sawPlayerRepair, "Missing graphical repair and rescan witness");
                String log =
                        "PASS "
                                + (reconnect
                                        ? "engineer disconnected during powered ascent and rejoined"
                                                + " the correct cabin, "
                                        : "")
                                + (paired
                                        ? "two real non-OP survival clients physically observed in"
                                                + " native ascent, distinct fixed-cabin seats,"
                                                + " engineer opened chest and moved one diamond;"
                                                + " chest=16, engineer=1"
                                        : "real non-OP survival client aboard fixed cabin;"
                                                + " diamond=17")
                                + ", original="
                                + core.originalRocket()
                                + ", furnace=8, source retired";
                Origin.log(log);
                Files.writeString(Path.of("shipyard-gui-server.txt"), log + "\n");
                phase++;
            }
        } catch (Exception e) {
            phase = -1;
            Starbridge.LOGGER.error("SHIPYARD GUI FIXTURE FAILED", e);
            try {
                Files.writeString(Path.of("shipyard-gui-server.txt"), "FAIL " + e + "\n");
            } catch (Exception ignored) {
            }
            server.halt(false);
        }
    }

    private static class Origin {
        static void log(String message) {
            Starbridge.LOGGER.info("SHIPYARD FIXTURE {}", message);
        }
    }

    private static void setup(ServerLevel l, UUID owner) throws ReflectiveOperationException {
        l.setChunkForced(1, 1, true);
        for (int x = 18; x <= 20; x++)
            for (int z = 18; z <= 20; z++) put(l, x, 190, z, "launchpad");
        for (int y = 190; y < 196; y++) put(l, 17, y, 19, "structuretower");
        var builder =
                ForgeRegistries.BLOCKS.getValue(OriginalRocketApi.ASSEMBLER).defaultBlockState();
        var meta = (IntegerProperty) builder.getBlock().getStateDefinition().getProperty("meta");
        l.setBlock(MACHINE, builder.setValue(meta, 2), 3);
        l.setBlock(new BlockPos(19, 191, 19), Blocks.IRON_BLOCK.defaultBlockState(), 3);
        put(l, 19, 192, 19, "tile.guidancecomputer");
        put(l, 19, 193, 19, "seat");
        put(l, 18, 191, 19, "rocketmotor");
        put(l, 20, 191, 18, "rocketmotor");
        put(l, 18, 191, 18, "rocketmotor");
        put(l, 18, 192, 19, "fueltank");
        for (int x = 18; x <= 20; x++) {
            put(l, x, 193, 18, "fueltank");
            put(l, x, 193, 20, "fueltank");
        }
        put(l, 19, 194, 18, "fueltank");
        put(l, 19, 194, 20, "fueltank");
        l.setBlock(CHEST, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) l.getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.DIAMOND, 17));
        l.setBlock(FURNACE, Blocks.FURNACE.defaultBlockState(), 3);
        var furnace = (AbstractFurnaceBlockEntity) l.getBlockEntity(FURNACE);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE, 8));
        furnace.setItem(1, new ItemStack(Items.COAL, 3));
        l.setBlock(CORE, Starbridge.SHIP_CORE.get().defaultBlockState(), 3);
        ((ShipCoreBlockEntity) l.getBlockEntity(CORE)).setOwner(owner);
        Object tile = OriginalRocketApi.builder(l.getBlockEntity(MACHINE)).orElseThrow();
        tile.getClass().getMethod("setEnergyStored", int.class).invoke(tile, 100000);
    }

    private static void put(ServerLevel l, int x, int y, int z, String name) {
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("advancedrocketry", name));
        check(block != null && block != Blocks.AIR, "missing " + name);
        l.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }
}
