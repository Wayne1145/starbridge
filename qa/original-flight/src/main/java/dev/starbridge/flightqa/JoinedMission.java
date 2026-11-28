package dev.starbridge.flightqa;

import static dev.starbridge.flightqa.MissionSignals.*;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VoyageModule;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

/**
 * Verifies native construction, both GUI flight legs, actual reward ownership, and a reconnect on
 * one ship.
 */
@Mod.EventBusSubscriber(modid = "starbridge_flightqa")
public final class JoinedMission {
    private enum Phase {
        SHIPYARD,
        OUTBOUND,
        DUNGEON,
        UNLOCK,
        TAKE,
        DEPOSIT,
        RETURN,
        RECONNECT,
        DOCK,
        DONE
    }

    private static Phase phase = Phase.SHIPYARD;
    private static final VsShipBackend VS = new VsShipBackend();
    private static final Map<String, Object> report = new LinkedHashMap<>();
    private static int since, started, beforeMoon, beforeHome, rewardBase;
    private static UUID coreId, rocketId, firstReceipt;
    private static boolean sawBoss, sawKeyDrop, confirmedOffline, sawDisconnect;
    private static ItemStack loot = ItemStack.EMPTY;
    private static Vec3 engineerMoonPosition;

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (!JOINED || event.phase != TickEvent.Phase.END || phase == Phase.DONE) return;
        var server = event.getServer();
        if (RestartCheck.afterRestart(server)) return;
        var captain = server.getPlayerList().getPlayerByName(PILOT);
        var engineer = server.getPlayerList().getPlayerByName(ENGINEER);
        if (captain == null) return;
        int now = server.getTickCount();
        if (started == 0) {
            started = since = now;
            report.put("events", new ArrayList<String>());
        }
        try {
            if (now - since > 2600) throw new IllegalStateException("Timed out during " + phase);
            if (engineer == null) {
                if (phase == Phase.RECONNECT) confirmedOffline = true;
                return;
            }
            require(captain.isAlive() && engineer.isAlive(), "Crew member died during " + phase);
            if (phase == Phase.SHIPYARD) {
                Path log = Path.of("shipyard-gui-server.txt");
                if (!Files.isRegularFile(log)) return;
                String dock = Files.readString(log);
                require(dock.startsWith("PASS"), dock);
                for (String player : List.of(PILOT, ENGINEER)) {
                    Path gui = Path.of("..", player, "shipyard-gui-client.txt");
                    if (!Files.isRegularFile(gui)) return;
                    require(
                            Files.readString(gui).startsWith("PASS"),
                            "Original GUI failed for " + player);
                }
                var core =
                        CoreRegistry.all().stream()
                                .filter(
                                        c ->
                                                c.originalRocket() != null
                                                        && OrbitModule.aboard(captain, c))
                                .findFirst()
                                .orElseThrow();
                require(
                        OrbitModule.aboard(engineer, core)
                                && !captain.hasPermissions(2)
                                && !engineer.hasPermissions(2)
                                && !captain.isCreative()
                                && !engineer.isCreative(),
                        "Expected two ordinary crew on the assembled cabin");
                coreId = core.id();
                rocketId = core.originalRocket();
                report.put("nativeDock", dock.strip());
                report.put("core", coreId);
                report.put("rocket", rocketId);
                var fuel =
                        core.getCapability(ForgeCapabilities.ENERGY)
                                .orElseThrow(IllegalStateException::new);
                fuel.receiveEnergy(100000, false);
                report.put("initialFE", core.energy());
                report.put(
                        "suits",
                        List.of(
                                OriginalEquipment.equip(captain),
                                OriginalEquipment.equip(engineer)));
                captain.getInventory().setItem(0, new ItemStack(VoyageModule.SLATE.get()));
                captain.getInventory().selected = 0;
                var sword = new ItemStack(Items.NETHERITE_SWORD);
                sword.enchant(Enchantments.SHARPNESS, 5);
                engineer.getInventory().setItem(2, sword);
                engineer.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
                for (var buff :
                        List.of(
                                MobEffects.DAMAGE_RESISTANCE,
                                MobEffects.REGENERATION,
                                MobEffects.DAMAGE_BOOST))
                    engineer.addEffect(new MobEffectInstance(buff, 10000, 1));
                tell(ENGINEER, "wait");
                tell(PILOT, "outbound");
                advance(Phase.OUTBOUND, now, "Native two-seat ascent and cargo job passed");
                return;
            }
            var core = CoreRegistry.find(coreId).orElse(null);
            if (phase == Phase.OUTBOUND && now % 40 == 0) {
                var witness = new LinkedHashMap<String, Object>();
                witness.put("tick", now);
                witness.put("captainWorld", captain.serverLevel().dimension().location().toString());
                witness.put("engineerWorld", engineer.serverLevel().dimension().location().toString());
                witness.put("captainFeet", captain.position().toString());
                witness.put("engineerFeet", engineer.position().toString());
                witness.put("pending", HullTransit.inProgress(server,coreId));
                witness.put("coreLocated", core != null);
                if (core != null) {
                    witness.put("activeOrbit", OrbitModule.active(core));
                    witness.put("coreWorld", core.getLevel().dimension().location().toString());
                    witness.put("coreStorage",core.getBlockPos().toShortString());
                    witness.put("captainOnDeck",HullTransit.isAboard(captain,core));
                    witness.put("engineerOnDeck",HullTransit.isAboard(engineer,core));
                    witness.put("energy", core.energy());
                    witness.put("sameRocket", rocketId.equals(core.originalRocket()));
                }
                MissionSignals.write(Path.of("arrival-witness.json"), witness);
            }
            if (core == null) return;
            var level = (ServerLevel) core.getLevel();
            boolean active = OrbitModule.active(core);
            if (active && phase == Phase.OUTBOUND) beforeMoon = core.energy();
            if (active
                    && (phase == Phase.RETURN || phase == Phase.RECONNECT || phase == Phase.DOCK))
                beforeHome = core.energy();
            if (phase == Phase.OUTBOUND) {
                if (!MOON.equals(level.dimension().location().toString())
                        || !arrived(server, captain, engineer, core)) return;
                require(beforeMoon - core.energy() == 12000, "Incorrect original Moon debit");
                firstReceipt = receipt(core);
                checkCargo(core, engineer, false);
                report.put("moonFE", core.energy());
                report.put("moonReceipt", firstReceipt);
                report.put("moonPort", OriginalStarMap.ports(server).get("moon").provider());
                engineerMoonPosition = engineer.position();
                report.put("moonShipPosition", captain.position().toString());
                var spawner = legacy(level, SPAWNER);
                var treasure = legacy(level, REWARD);
                require(
                        spawner.contains("bossClass")
                                || spawner.toString().contains("SkeletonBoss"),
                        "Natural boss room missing at pinned seed: " + spawner);
                require(treasure.getBoolean("isLocked"), "Expected an unopened original chest");
                report.put("naturalSpawner", spawner.toString());
                report.put("unopenedTreasure", treasure.toString());
                tell(PILOT, "wait");
                engineer.closeContainer();
                engineer.teleportTo(
                        level,
                        SPAWNER.getX() + 1.5,
                        SPAWNER.getY() - 2,
                        SPAWNER.getZ() + 2.5,
                        135,
                        0);
                tell(ENGINEER, "battle");
                advance(
                        Phase.DUNGEON,
                        now,
                        "Both crew landed on the original moon; fixture placed engineer inside the"
                                + " natural boss room");
                return;
            }
            if (phase == Phase.DUNGEON) {
                for (var entity :
                        level.getEntities(
                                (Entity) null, new AABB(SPAWNER).inflate(30), e -> true)) {
                    if (entity instanceof LivingEntity
                            && "galacticraftcore:evolvedskeletonboss"
                                    .equals(
                                            String.valueOf(
                                                    ForgeRegistries.ENTITY_TYPES.getKey(
                                                            entity.getType())))) sawBoss = true;
                    if (entity instanceof ItemEntity floor && isKey(floor.getItem()))
                        sawKeyDrop = true;
                }
                if (!sawBoss
                        || !sawKeyDrop
                        || !engineer.getInventory().hasAnyMatching(JoinedMission::isKey)) return;
                report.put("originalBossAndDroppedKey", true);
                engineer.teleportTo(
                        level, REWARD.getX() + .5, REWARD.getY(), REWARD.getZ() + 2.5, 180, 12);
                tell(ENGINEER, "unlock");
                advance(
                        Phase.UNLOCK,
                        now,
                        "Engineer collected the original boss key; fixture placed engineer at the"
                                + " original treasure chest");
                return;
            }
            if (phase == Phase.UNLOCK) {
                if (engineer.containerMenu == engineer.inventoryMenu
                        || legacy(level, REWARD).getBoolean("isLocked")) return;
                for (Slot slot : engineer.containerMenu.slots) {
                    if (slot.container == engineer.getInventory() || !slot.hasItem()) continue;
                    loot = slot.getItem().copy();
                    rewardBase = engineer.getInventory().countItem(loot.getItem());
                    report.put(
                            "originalReward",
                            Map.of(
                                    "id",
                                    id(loot),
                                    "count",
                                    loot.getCount(),
                                    "slot",
                                    slot.index,
                                    "menu",
                                    engineer.containerMenu.getClass().getName()));
                    tell(ENGINEER, "take", "slot", slot.index);
                    advance(Phase.TAKE, now, "Original first-tier chest opened with its key");
                    break;
                }
                return;
            }
            if (phase == Phase.TAKE) {
                if (engineer.getInventory().countItem(loot.getItem())
                        < rewardBase + loot.getCount()) return;
                engineer.closeContainer();
                var ship = VS.inspect(level, core.getBlockPos()).orElseThrow();
                var box = core.getBlockPos().offset(CHEST_FROM_CORE);
                var pos =
                        HullTransit.toWorld(level, ship.shipId(), Vec3.atCenterOf(box))
                                .orElseThrow();
                engineer.teleportTo(
                        level,
                        engineerMoonPosition.x,
                        engineerMoonPosition.y,
                        engineerMoonPosition.z,
                        0,
                        25);
                tell(
                        ENGINEER,
                        "deposit",
                        "x",
                        box.getX(),
                        "y",
                        box.getY(),
                        "z",
                        box.getZ(),
                        "worldX",
                        pos.x,
                        "worldY",
                        pos.y,
                        "worldZ",
                        pos.z,
                        "item",
                        id(loot));
                advance(
                        Phase.DEPOSIT,
                        now,
                        "Engineer took the genuine reward; fixture returned engineer to the same"
                                + " lunar deck");
                return;
            }
            if (phase == Phase.DEPOSIT) {
                if (countLoot(chest(core)) < loot.getCount()
                        || engineer.getInventory().countItem(loot.getItem()) != rewardBase) return;
                tell(ENGINEER, "wait");
                tell(PILOT, "home");
                advance(
                        Phase.RETURN,
                        now,
                        "Engineer transferred the reward to the original ship chest via its GUI");
                return;
            }
            if (phase == Phase.RETURN && active) {
                if (!OrbitModule.aboard(engineer, core)) return;
                sawDisconnect = true;
                engineer.connection.disconnect(
                        Component.literal("Starbridge test: reconnect to your cabin"));
                advance(
                        Phase.RECONNECT,
                        now,
                        "Disconnected the engineer's actual network connection during the homeward"
                                + " cruise");
                return;
            }
            if (phase == Phase.RECONNECT) {
                if (!confirmedOffline || !active || !OrbitModule.aboard(engineer, core)) return;
                tell(PILOT, "finish");
                tell(ENGINEER, "wait");
                advance(Phase.DOCK, now, "Engineer rejoined the same fixed cabin before approach");
                return;
            }
            if (phase == Phase.DOCK
                    && level.dimension() == Level.OVERWORLD
                    && arrived(server, captain, engineer, core)) {
                UUID paid = receipt(core);
                require(
                        !paid.equals(firstReceipt) && beforeHome - core.energy() == 12000,
                        "Return receipt or debit mismatch");
                checkCargo(core, engineer, true);
                require(sawDisconnect && confirmedOffline, "No real disconnect observed");
                report.put("homeFE", core.energy());
                report.put("outboundAndHomeFares", List.of(12000, 12000));
                report.put("homeReceipt", paid);
                report.put("homePosition", captain.position().toString());
                report.put("pendingPassage", HullTransit.inProgress(server, coreId));
                report.put("returnLoot", countLoot(chest(core)));
                report.put("bothAliveAboard", true);
                report.put("disconnectAndRecovery", true);
                report.put("pass", true);
                advance(
                        Phase.DONE,
                        now,
                        "Both living crew recovered at home on their original ship");
                write(Path.of("flight-result.json"), report);
                server.halt(false);
                return;
            }
            if (now % 40 == 0) {
                report.put(
                        "progress",
                        Map.of(
                                "stage",
                                phase,
                                "FE",
                                core.energy(),
                                "captain",
                                captain.position().toString(),
                                "engineer",
                                engineer.position().toString(),
                                "world",
                                level.dimension().location().toString()));
                write(Path.of("flight-live.json"), report);
            }
        } catch (Exception e) {
            report.put("pass", false);
            report.put("failure", e.toString());
            report.put("failedStage", phase);
            write(Path.of("flight-result.json"), report);
            phase = Phase.DONE;
            server.halt(false);
        }
    }

    private static boolean isKey(ItemStack stack) {
        return id(stack).startsWith("galacticraftcore:") && id(stack).contains("key");
    }

    private static boolean arrived(
            MinecraftServer server,
            ServerPlayer captain,
            ServerPlayer engineer,
            ShipCoreBlockEntity core) {
        return !OrbitModule.active(core)
                && !HullTransit.inProgress(server, core.id())
                && core.originalRocket().equals(rocketId)
                && HullTransit.isAboard(captain, core)
                && HullTransit.isAboard(engineer, core);
    }

    private static UUID receipt(ShipCoreBlockEntity core) {
        var nbt = core.saveWithoutMetadata();
        require(nbt.hasUUID("PaidVoyage"), "No payment receipt on ship");
        return nbt.getUUID("PaidVoyage");
    }

    private static net.minecraft.nbt.CompoundTag legacy(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        return be == null
                ? new net.minecraft.nbt.CompoundTag()
                : be.saveWithoutMetadata().getCompound("legacy");
    }

    private static ChestBlockEntity chest(ShipCoreBlockEntity core) {
        return (ChestBlockEntity)
                core.getLevel().getBlockEntity(core.getBlockPos().offset(CHEST_FROM_CORE));
    }

    private static int countLoot(ChestBlockEntity chest) {
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++)
            if (ItemStack.isSameItemSameTags(chest.getItem(i), loot))
                count += chest.getItem(i).getCount();
        return count;
    }

    private static void checkCargo(
            ShipCoreBlockEntity core, ServerPlayer engineer, boolean withLoot) {
        var chest = chest(core);
        var oven =
                (AbstractFurnaceBlockEntity)
                        core.getLevel()
                                .getBlockEntity(core.getBlockPos().offset(FURNACE_FROM_CORE));
        require(
                chest != null
                        && oven != null
                        && chest.countItem(Items.DIAMOND) == 16
                        && engineer.getInventory().countItem(Items.DIAMOND) == 1
                        && oven.getItem(0).getCount() + oven.getItem(2).getCount() == 8,
                "Native chest, crew or furnace inventory mismatch");
        if (withLoot) require(countLoot(chest) == loot.getCount(), "Reward count differs at home");
    }

    @SuppressWarnings("unchecked")
    private static void advance(Phase next, int tick, String message) throws Exception {
        phase = next;
        since = tick;
        ((List<String>) report.get("events")).add(tick + " " + message);
        write(Path.of("flight-live.json"), report);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
