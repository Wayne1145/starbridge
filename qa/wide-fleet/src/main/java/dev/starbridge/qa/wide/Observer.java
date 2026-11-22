package dev.starbridge.qa.wide;

import com.google.gson.Gson;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod("starbridgewideqa")
@Mod.EventBusSubscriber(modid = "starbridgewideqa")
public final class Observer {
    private static final boolean HOST = Files.isRegularFile(Path.of("wide-fleet-host.fixture"));
    private static final Gson JSON = new Gson();
    private static final Map<String, Object> seen = new LinkedHashMap<>();
    private static final List<Object> positions = new ArrayList<>();
    private static BlockPos bridge;
    private static boolean done, below, backUp;

    @SubscribeEvent
    public static void check(TickEvent.ServerTickEvent event) throws Exception {
        if (!HOST || done || event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        int t = server.getTickCount();
        if (t % 2 != 0) return;
        var visitor = server.getPlayerList().getPlayerByName("FleetVisitor");
        var pilot = server.getPlayerList().getPlayerByName("FleetCaptain");
        if (pilot == null || visitor == null) return;
        Path start = Path.of("fleet-flight-start");
        if (bridge == null) {
            if (!Files.isRegularFile(start)) return;
            String[] p = Files.readString(start).trim().split(" +");
            bridge =
                    new BlockPos(
                            Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        }
        var world = server.overworld();
        if (visitor.level() != world
                || !(world.getBlockEntity(bridge) instanceof ShipCoreBlockEntity core)
                || !OrbitModule.active(core)) return;
        var upper = (BarrelBlockEntity) world.getBlockEntity(bridge.offset(3, 0, 2));
        var lower = (BlastFurnaceBlockEntity) world.getBlockEntity(bridge.offset(3, -3, 7));
        if (upper == null || lower == null)
            throw new IllegalStateException(
                    "Wide ship workstations missing at the registered bridge");
        var pos = List.of(visitor.getX(), visitor.getY(), visitor.getZ());
        positions.add(
                Map.of(
                        "tick",
                        t,
                        "position",
                        pos,
                        "menu",
                        visitor.containerMenu.getClass().getSimpleName(),
                        "core",
                        core.id().toString()));
        seen.putIfAbsent("orbitStart", t);
        if (visitor.containerMenu instanceof ChestMenu
                && Math.abs(visitor.getY() - bridge.getY()) < .2) {
            seen.putIfAbsent("upperBarrelScreen", t);
            if (below) backUp = true;
        }
        if (visitor.containerMenu instanceof BlastFurnaceMenu
                && Math.abs(visitor.getY() - (bridge.getY() - 3)) < .2) {
            below = true;
            seen.putIfAbsent("lowerBlastFurnaceScreen", t);
        }
        if (lower.getItem(0).is(Items.RAW_COPPER)) seen.putIfAbsent("oreInLowerFurnace", t);
        if (visitor.getInventory().countItem(Items.COPPER_INGOT) == 1)
            seen.putIfAbsent("ingotCarried", t);
        if (!Files.isRegularFile(Path.of("visitor", "wide-engineer-complete.json"))) return;
        if (!below
                || !backUp
                || !seen.containsKey("oreInLowerFurnace")
                || !seen.containsKey("ingotCarried")
                || !upper.getItem(0).is(Items.COPPER_INGOT)
                || upper.getItem(0).getCount() != 1
                || !lower.getItem(0).isEmpty()
                || !lower.getItem(2).isEmpty()
                || visitor.getInventory().countItem(Items.COPPER_INGOT) != 0)
            throw new IllegalStateException(
                    "In-flight cross-deck cargo job was incomplete: " + seen);
        seen.put("orbitEnd", t);
        seen.put("rocket", String.valueOf(core.originalRocket()));
        seen.put("core", core.id().toString());
        seen.put("captain", pilot.getUUID().toString());
        seen.put("crew", visitor.getUUID().toString());
        seen.put("inputConsumed", true);
        seen.put("ingotsInUpperBarrel", upper.getItem(0).getCount());
        seen.put("upper", bridge.offset(3, 0, 2).toShortString());
        seen.put("lower", bridge.offset(3, -3, 7).toShortString());
        seen.put("positions", positions);
        var suits = new HashMap<String, Object>();
        for (ServerPlayer rider : List.of(pilot, visitor)) {
            var worn = new ArrayList<String>();
            for (var slot :
                    List.of(
                            EquipmentSlot.HEAD,
                            EquipmentSlot.CHEST,
                            EquipmentSlot.LEGS,
                            EquipmentSlot.FEET))
                worn.add(
                        ForgeRegistries.ITEMS
                                .getKey(rider.getItemBySlot(slot).getItem())
                                .toString());
            suits.put(rider.getScoreboardName(), worn);
        }
        seen.put("suits", suits);
        Path tmp = Path.of("wide-job.tmp");
        Files.writeString(tmp, JSON.toJson(seen));
        Files.move(tmp, Path.of("wide-job-verified.json"), StandardCopyOption.ATOMIC_MOVE);
        done = true;
    }
}
