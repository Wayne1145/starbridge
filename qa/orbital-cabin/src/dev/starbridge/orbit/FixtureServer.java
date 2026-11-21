package dev.starbridge.orbit;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;

import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

/** Isolated test build only: stage a fixed two-deck cabin and a launched orbital leg. */
@Mod.EventBusSubscriber(modid = Starbridge.ID)
public class FixtureServer {
    static final int BASE = 128;
    static final BlockPos CORE = new BlockPos(3, BASE + 1, 1),
            CHEST = new BlockPos(2, BASE + 3, 9),
            FURNACE = new BlockPos(3, BASE + 3, 9);
    static int start = -1;
    static ShipCoreBlockEntity core;
    static boolean turn, cruise, brake, climb, chestOpen, ovenOpen, gotCargo;
    static int maximumY, produced;
    static int lastCheck, distantProduced, hullBlocks;
    static final BlockPos FAR = new BlockPos(2, BASE + 1, 299);

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END
                || !Boolean.getBoolean("starbridge.orbitFixture")
                || !Files.isRegularFile(Path.of("starbridge-orbit.fixture"))) return;
        var s = event.getServer();
        ServerLevel level = s.overworld();
        var captain = s.getPlayerList().getPlayerByName("OrbitCaptain");
        var engineer = s.getPlayerList().getPlayerByName("OrbitEngineer");
        if (captain == null || engineer == null) return;
        if (start < 0) {
            if (captain.hasPermissions(2)
                    || engineer.hasPermissions(2)
                    || captain.isCreative()
                    || engineer.isCreative())
                throw new AssertionError("Both test players must be non-op survival");
            for (int z = 0; z < 300; z++)
                for (int x = 0; x < 6; x++) {
                    level.setBlockAndUpdate(
                            new BlockPos(x, BASE, z), Blocks.IRON_BLOCK.defaultBlockState());
                    if (z <= 1 || z >= 10 || x == 0 || x == 5)
                        level.setBlockAndUpdate(
                                new BlockPos(x, BASE, z), Blocks.CYAN_CONCRETE.defaultBlockState());
                    if (z >= 4 && z <= 9 && x >= 1 && x <= 4)
                        level.setBlockAndUpdate(
                                new BlockPos(x, BASE + 1, z),
                                z == 4
                                        ? Blocks.QUARTZ_STAIRS
                                                .defaultBlockState()
                                                .setValue(StairBlock.FACING, Direction.SOUTH)
                                        : Blocks.QUARTZ_BLOCK.defaultBlockState());
                    if (z >= 6 && z <= 9 && x >= 1 && x <= 4)
                        level.setBlockAndUpdate(
                                new BlockPos(x, BASE + 2, z),
                                z == 6
                                        ? Blocks.QUARTZ_STAIRS
                                                .defaultBlockState()
                                                .setValue(StairBlock.FACING, Direction.SOUTH)
                                        : Blocks.QUARTZ_BLOCK.defaultBlockState());
                }
            level.setBlockAndUpdate(CORE, Starbridge.SHIP_CORE.get().defaultBlockState());
            core = (ShipCoreBlockEntity) level.getBlockEntity(CORE);
            core.setOwner(captain.getUUID());
            core.getCapability(ForgeCapabilities.ENERGY)
                    .orElseThrow(IllegalStateException::new)
                    .receiveEnergy(100000, false);
            core.register();
            level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) level.getBlockEntity(CHEST))
                    .setItem(0, new ItemStack(Items.AMETHYST_SHARD, 3));
            level.setBlockAndUpdate(FURNACE, Blocks.FURNACE.defaultBlockState());
            var furnace = (FurnaceBlockEntity) level.getBlockEntity(FURNACE);
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 4));
            furnace.setItem(1, new ItemStack(Items.COAL, 2));
            level.setBlockAndUpdate(FAR, Blocks.FURNACE.defaultBlockState());
            var remote = (FurnaceBlockEntity) level.getBlockEntity(FAR);
            remote.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
            remote.setItem(1, new ItemStack(Items.COAL, 2));
            for (BlockPos pos :
                    BlockPos.betweenClosed(
                            new BlockPos(0, BASE, 0), new BlockPos(5, BASE + 5, 299)))
                if (!level.getBlockState(pos).isAir()) hullBlocks++;
            core.bindOriginalRocket(UUID.randomUUID());
            OrbitModule.bindCabin(core, new BlockPos(0, BASE, 0), new BlockPos(5, BASE + 5, 299));
            var data = OrbitLedger.get(s);
            var flight =
                    new OrbitLedger.Leg(
                            core.id(),
                            captain.getUUID(),
                            "home",
                            "moon",
                            OrbitalFlight.launch("home", "moon"));
            flight.reserve = 25024;
            data.legs.put(core.id(), flight);
            data.setDirty();
            captain.teleportTo(level, 4.2, BASE + 1, 1.7, 90, 0);
            engineer.teleportTo(level, 2.5, BASE + 1, 1.5, 0, 0);
            start = s.getTickCount();
            Starbridge.LOGGER.info(
                    "ORBIT_FIXTURE staged real two-deck cabin, furnace and chest for {} / {}",
                    captain.getUUID(),
                    engineer.getUUID());
        }
        if (s.getTickCount() - start == 35) OrbitModule.open(captain, core);
        var ledger = OrbitLedger.get(s);
        var flight = ledger.legs.get(core.id());
        if (flight == null) return;
        double speed = flight.pose.velocity().length();
        turn |= Math.abs(flight.pose.yaw()) > 8 && speed > .3;
        cruise |= flight.cruise;
        if (turn && cruise && !flight.cruise && speed < .01 && s.getTickCount() - start > 100)
            brake = true;
        climb |= engineer.getZ() > 6.5 && engineer.getY() > BASE + 2.4 && speed > .1;
        chestOpen |= engineer.containerMenu instanceof ChestMenu;
        ovenOpen |= engineer.containerMenu instanceof FurnaceMenu;
        gotCargo |= engineer.getInventory().countItem(Items.AMETHYST_SHARD) == 3;
        produced =
                Math.max(
                        produced,
                        ((FurnaceBlockEntity) level.getBlockEntity(FURNACE)).getItem(2).getCount());
        if (level.hasChunkAt(FAR) && level.getBlockEntity(FAR) instanceof FurnaceBlockEntity aft)
            distantProduced = Math.max(distantProduced, aft.getItem(2).getCount());
        int age = s.getTickCount() - start;
        if (age - lastCheck >= 80) {
            lastCheck = age;
            Starbridge.LOGGER.info(
                    "ORBIT_SAMPLE age={} yaw={} speed={} cruise={} engineer={},{},{} chest={}"
                        + " furnace={} count={}",
                    age,
                    flight.pose.yaw(),
                    speed,
                    flight.cruise,
                    engineer.getX(),
                    engineer.getY(),
                    engineer.getZ(),
                    chestOpen,
                    ovenOpen,
                    gotCargo);
        }
        boolean clients =
                Files.exists(Path.of("../OrbitCaptain/actions-complete"))
                        && Files.exists(Path.of("../OrbitEngineer/actions-complete"));
        boolean pass =
                clients
                        && turn
                        && cruise
                        && brake
                        && climb
                        && chestOpen
                        && ovenOpen
                        && gotCargo
                        && produced > 0
                        && distantProduced > 0
                        && core.energy() >= flight.reserve;
        if (pass || age > 1200) {
            String result =
                    "{\"length\":300,\"width\":6,\"heightBounds\":6,\"actualNonAir\":"
                            + hullBlocks
                            + ",\"aftFurnaceStone\":"
                            + distantProduced
                            + ",\"pass\":"
                            + pass
                            + ",\"nonOpSurvival\":true,\"stage\":\"fixed cabin and orbit injected,"
                            + " unmodified crew traffic\",\"manual\":"
                            + turn
                            + ",\"cruise\":"
                            + cruise
                            + ",\"brake\":"
                            + brake
                            + ",\"climbedUnderWay\":"
                            + climb
                            + ",\"chestOpened\":"
                            + chestOpen
                            + ",\"furnaceOpened\":"
                            + ovenOpen
                            + ",\"cargo\":"
                            + gotCargo
                            + ",\"smelted\":"
                            + produced
                            + ",\"remainingFE\":"
                            + core.energy()
                            + ",\"reserveFE\":"
                            + flight.reserve
                            + ",\"bothClientScriptsFinished\":"
                            + clients
                            + "}";
            Files.writeString(Path.of("orbit-result.json"), result);
            Starbridge.LOGGER.info("ORBIT_FIXTURE {}", result);
            s.halt(false);
        }
    }
}
