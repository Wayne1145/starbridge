package dev.starbridge.flightqa;

import com.google.gson.GsonBuilder;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VoyageModule;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridge_flightqa")
public final class RealFlight {
    static final BlockPos BRIDGE = new BlockPos(1, 129, 1),
            BOX = new BlockPos(3, 129, 3),
            OVEN = new BlockPos(0, 129, 4);
    static final VsShipBackend vs = new VsShipBackend();
    static UUID id, rocket;
    static final Map<String, Integer> preLandingEnergy = new LinkedHashMap<>();
    static final Map<String, String> receipts = new LinkedHashMap<>();
    static int start = -1, inspect, legs;
    static boolean inOrbit, moonArrival, earthArrival;
    static final Map<String, Object> out = new LinkedHashMap<>();

    static void write(String name) throws Exception {
        Files.writeString(
                Path.of(name), new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (Files.isRegularFile(Path.of("starbridge-joined.fixture"))
                || event.phase != TickEvent.Phase.END
                || Files.isRegularFile(Path.of("starbridge-oxygen.fixture"))
                || !Files.isRegularFile(Path.of("starbridge-original-crew.fixture"))) return;
        var server = event.getServer();
        var earth = server.overworld();
        var captain = server.getPlayerList().getPlayerByName("OrbitCaptain");
        var engineer = server.getPlayerList().getPlayerByName("OrbitEngineer");
        if (captain == null || engineer == null) return;
        if (start == -1) {
            var port = OriginalStarMap.ports(server).get("moon");
            if (port == null || port.dimension() == null)
                throw new IllegalStateException("No original GC Moon");
            var target = server.getLevel(port.dimension());
            LandingCheck.prepare(target);
            out.put("registeredWorld", target.dimension().location().toString());
            out.put("provider", port.provider());
            out.put("generator", target.getChunkSource().getGenerator().getClass().getName());
            if (captain.hasPermissions(2)
                    || engineer.hasPermissions(2)
                    || captain.isCreative()
                    || engineer.isCreative()) throw new AssertionError("Non-op survival required");
            var nativeEngine =
                    ForgeRegistries.BLOCKS.getValue(
                            new ResourceLocation("advancedrocketry", "rocketmotor"));
            if (nativeEngine == null || nativeEngine == Blocks.AIR)
                throw new IllegalStateException("Original AR engine not present");
            for (int x = 0; x < 5; x++)
                for (int z = 0; z < 6; z++)
                    earth.setBlockAndUpdate(
                            new BlockPos(x, 128, z), Blocks.IRON_BLOCK.defaultBlockState());
            earth.setBlockAndUpdate(new BlockPos(2, 128, 0), nativeEngine.defaultBlockState());
            earth.setBlockAndUpdate(BRIDGE, Starbridge.SHIP_CORE.get().defaultBlockState());
            earth.setBlockAndUpdate(BOX, Blocks.CHEST.defaultBlockState());
            earth.setBlockAndUpdate(OVEN, Blocks.FURNACE.defaultBlockState());
            var core = (ShipCoreBlockEntity) earth.getBlockEntity(BRIDGE);
            core.setOwner(captain.getUUID());
            rocket = UUID.randomUUID();
            core.bindOriginalRocket(rocket);
            core.getCapability(ForgeCapabilities.ENERGY)
                    .orElseThrow(IllegalStateException::new)
                    .receiveEnergy(100000, false);
            core.register();
            id = core.id();
            OrbitModule.bindCabin(core, new BlockPos(0, 128, 0), new BlockPos(4, 129, 5));
            ((ChestBlockEntity) earth.getBlockEntity(BOX))
                    .setItem(0, new ItemStack(Items.DIAMOND, 11));
            var oven = (FurnaceBlockEntity) earth.getBlockEntity(OVEN);
            oven.setItem(0, new ItemStack(Items.IRON_ORE, 6));
            oven.setItem(1, new ItemStack(Items.COAL, 3));
            out.put(
                    "spaceSuits",
                    List.of(OriginalEquipment.equip(captain), OriginalEquipment.equip(engineer)));
            captain.getInventory().setItem(0, new ItemStack(VoyageModule.SLATE.get()));
            captain.getInventory().selected = 0;
            captain.teleportTo(earth, 1.5, 129, 2.5, 180, 30);
            engineer.teleportTo(earth, 3.5, 129, 2.5, 0, 0);
            start = server.getTickCount();
            out.put(
                    "originScenario",
                    "pre-staged fixed cabin with upstream AR motor, ship owner, rocket identity,"
                            + " two crew and two machines; no native assembly in this stage");
            out.put("nonOpSurvival", true);
            out.put("core", id.toString());
            out.put("rocket", rocket.toString());
            out.put("initialFE", core.energy());
            write("flight-live.json");
            Starbridge.LOGGER.info(
                    "ORIGINAL_FLIGHT fixture prepared native engine, real {} and {}",
                    port.dimension(),
                    id);
        }
        var core = CoreRegistry.find(id).orElse(null);
        if (LandingCheck.ENABLED)
            LandingCheck.permitRetry(
                    captain,
                    server.getLevel(OriginalStarMap.ports(server).get("moon").dimension()),
                    core);
        if (core != null && core.loadedForControl()) {
            boolean active = OrbitModule.active(core);
            if (active && !inOrbit) legs++;
            inOrbit = active;
            if (active) preLandingEnergy.put(legs == 1 ? "moon" : "home", core.energy());
            var cs = (net.minecraft.server.level.ServerLevel) core.getLevel();
            var key = cs.dimension().location().toString();
            if (server.getTickCount() - inspect >= 60) {
                inspect = server.getTickCount();
                out.put(
                        "progress",
                        Map.of(
                                "age",
                                inspect - start,
                                "dimension",
                                key,
                                "captain",
                                captain.level().dimension().location().toString(),
                                "engineer",
                                engineer.level().dimension().location().toString(),
                                "FE",
                                core.energy(),
                                "orbit",
                                OrbitModule.status(core),
                                "pilotDeck",
                                HullTransit.isAboard(captain, core),
                                "legs",
                                legs));
                write("flight-live.json");
            }
            if (!active
                    && !HullTransit.inProgress(server, id)
                    && captain.isAlive()
                    && engineer.isAlive()
                    && captain.level() == cs
                    && engineer.level() == cs
                    && HullTransit.isAboard(captain, core)
                    && HullTransit.isAboard(engineer, core)) {
                var ship = vs.inspect(cs, core.getBlockPos()).orElse(null);
                var cargo = cs.getBlockEntity(core.getBlockPos().offset(BOX.subtract(BRIDGE)));
                var machine = cs.getBlockEntity(core.getBlockPos().offset(OVEN.subtract(BRIDGE)));
                if (ship != null
                        && cargo instanceof ChestBlockEntity chest
                        && machine instanceof FurnaceBlockEntity furnace) {
                    var summary =
                            Map.of(
                                    "dimension",
                                    key,
                                    "core",
                                    core.id().toString(),
                                    "rocket",
                                    Objects.toString(core.originalRocket()),
                                    "vsId",
                                    ship.shipId(),
                                    "crew",
                                    2,
                                    "cargo",
                                    chest.getItem(0).getCount(),
                                    "orePlusIngots",
                                    furnace.getItem(0).getCount() + furnace.getItem(2).getCount(),
                                    "FE",
                                    core.energy());
                    var nbt = core.saveWithoutMetadata();
                    if (nbt.hasUUID("PaidVoyage"))
                        receipts.put(
                                cs.dimension() == Level.OVERWORLD ? "home" : "moon",
                                nbt.getUUID("PaidVoyage").toString());
                    if (!moonArrival && key.equals(out.get("registeredWorld"))) {
                        moonArrival = true;
                        LandingCheck.arrived(captain, core, ship.shipId());
                        out.put("moon", summary);
                        write("flight-live.json");
                        Starbridge.LOGGER.info("ORIGINAL_FLIGHT physical Moon arrival {}", summary);
                    } else if (moonArrival && !earthArrival && cs.dimension() == Level.OVERWORLD) {
                        earthArrival = true;
                        out.put("home", summary);
                        write("flight-live.json");
                        Starbridge.LOGGER.info("ORIGINAL_FLIGHT physical Earth return {}", summary);
                    }
                }
            }
        }
        int age = server.getTickCount() - start;
        if ((earthArrival && (core == null || core.energy() <= 76_000)) || age > 7000) {
            boolean pass = false;
            if (earthArrival) {
                var m = (Map<?, ?>) out.get("moon");
                var h = (Map<?, ?>) out.get("home");
                pass =
                        legs >= 2
                                && m.get("cargo").equals(11)
                                && h.get("cargo").equals(11)
                                && m.get("orePlusIngots").equals(6)
                                && h.get("orePlusIngots").equals(6)
                                && m.get("rocket").equals(rocket.toString())
                                && h.get("rocket").equals(rocket.toString());
            }
            boolean settled = core != null && !HullTransit.inProgress(server, id);
            out.put("passageCleared", settled);
            out.put("receipts", receipts);
            out.put("beforeLandingFE", preLandingEnergy);
            boolean exactDebits = false;
            if (earthArrival) {
                var moon = (Map<?, ?>) out.get("moon");
                var home = (Map<?, ?>) out.get("home");
                int outbound = preLandingEnergy.getOrDefault("moon", 0) - (int) moon.get("FE"),
                        inbound = preLandingEnergy.getOrDefault("home", 0) - (int) home.get("FE");
                out.put("outboundFareFE", outbound);
                out.put("returnFareFE", inbound);
                exactDebits = outbound == 12000 && inbound == 12000;
            }
            out.put("finalCoreFE", core == null ? -1 : core.energy());
            out.put(
                    "pass",
                    pass
                            && settled
                            && exactDebits
                            && receipts.size() == 2
                            && !Objects.equals(receipts.get("moon"), receipts.get("home"))
                            && captain.isAlive()
                            && engineer.isAlive());
            out.put("legs", legs);
            out.put("age", age);
            write("flight-result.json");
            server.halt(false);
        }
    }
}
