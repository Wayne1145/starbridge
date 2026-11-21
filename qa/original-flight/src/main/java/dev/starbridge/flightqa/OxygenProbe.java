package dev.starbridge.flightqa;

import com.google.gson.GsonBuilder;

import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.voyage.CabinPressure;
import dev.starbridge.voyage.VoyageModule;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridge_flightqa")
public class OxygenProbe {
    static final BlockPos VENT = new BlockPos(61, 130, 60), HATCH = new BlockPos(62, 130, 60);
    static final Map<String, Object> out = new LinkedHashMap<>();
    static final List<Map<String, Object>> samples = new ArrayList<>();
    static int born = -1;
    static boolean sealed,
            leak,
            protectedHealth,
            outsideHurt,
            pressureLost,
            recovered,
            cleanAir,
            refilled;
    static float sealedHealth, damageStart, afterLeak;

    static Map<String, Object> original(ServerPlayer p) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("health", p.getHealth());
        sample.put(
                "vacuumFatigue", p.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN));
        sample.put(
                "vacuumSlowness",
                p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN));
        sample.put("vanillaAir", p.getAirSupply());
        sample.put("pos", p.position().toString());
        sample.put("sealedChargedVent", CabinPressure.breathable(p));
        try {
            Object legacy =
                    Class.forName("retrofit.forge.world.LegacyPlayers")
                            .getMethod("mirror", Player.class, boolean.class)
                            .invoke(null, p, true);
            ClassLoader realm = legacy.getClass().getClassLoader();
            var actor = realm.loadClass("net.minecraft.entity.player.EntityPlayerMP");
            if (!actor.isInstance(legacy)) {
                sample.put("legacyPlayerType", legacy.getClass().getName());
                return sample;
            }
            var type =
                    realm.loadClass(
                            "micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats");
            Object stat = type.getMethod("get", actor).invoke(null, legacy);
            if (stat != null) {
                for (String k : List.of("airRemaining", "airRemaining2", "oxygenSetupValid"))
                    sample.put(k, type.getField(k).get(stat));
            } else sample.put("gcStats", "missing");
            Object nativeAir =
                    realm.loadClass("micdoodle8.mods.galacticraft.core.util.OxygenUtil")
                            .getMethod(
                                    "isAABBInBreathableAirBlock",
                                    realm.loadClass("net.minecraft.entity.EntityLivingBase"))
                            .invoke(null, legacy);
            sample.put("originalBreathableAir", nativeAir);
        } catch (Throwable e) {
            sample.put("legacyObservation", e.toString() + " " + Objects.toString(e.getCause()));
        }
        return sample;
    }

    static final List<Map<String, Object>> hits = new ArrayList<>();

    @SubscribeEvent
    public static void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (born < 0
                || hits.size() >= 55
                || !(event.getEntity() instanceof ServerPlayer p)
                || !Files.isRegularFile(Path.of("starbridge-oxygen.fixture"))) return;
        if (hits.size() < 4)
            out.put(
                    "damageSourceStack_" + hits.size(),
                    java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                            .map(StackTraceElement::toString)
                            .filter(
                                    f ->
                                            f.contains("galacticraft")
                                                    || f.contains("advancedRocketry")
                                                    || f.contains("retrofit")
                                                    || f.contains("Ship")
                                                    || f.contains("Air"))
                            .toList());
        hits.add(
                Map.of(
                        "name",
                        p.getGameProfile().getName(),
                        "tick",
                        p.serverLevel().getServer().getTickCount() - born,
                        "message",
                        event.getSource().getMsgId(),
                        "type",
                        p.level()
                                .registryAccess()
                                .registryOrThrow(
                                        net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                                .getKey(event.getSource().type())
                                .toString(),
                        "amount",
                        event.getAmount()));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) throws Exception {
        if (e.phase != TickEvent.Phase.END
                || !Files.isRegularFile(Path.of("starbridge-oxygen.fixture"))) return;
        var server = e.getServer();
        var a = server.getPlayerList().getPlayerByName("OrbitCaptain");
        var b = server.getPlayerList().getPlayerByName("OrbitEngineer");
        if (a == null || b == null) return;
        var moon = server.getLevel(OriginalStarMap.ports(server).get("moon").dimension());
        if (moon == null) return;
        if (born < 0) {
            if (a.isCreative() || b.isCreative() || a.hasPermissions(2) || b.hasPermissions(2))
                throw new IllegalStateException("Expected non-op survival");
            for (int x = 60; x <= 64; x++)
                for (int z = 60; z <= 64; z++)
                    for (int y = 128; y <= 132; y++)
                        moon.setBlockAndUpdate(
                                new BlockPos(x, y, z),
                                x == 60 || x == 64 || z == 60 || z == 64 || y == 128 || y == 132
                                        ? Blocks.GLASS.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState());
            for (int x = 65; x <= 67; x++)
                for (int z = 60; z <= 64; z++)
                    moon.setBlockAndUpdate(
                            new BlockPos(x, 128, z), Blocks.QUARTZ_BLOCK.defaultBlockState());
            moon.setBlockAndUpdate(VENT, VoyageModule.VENT.get().defaultBlockState());
            var vent = moon.getBlockEntity(VENT);
            var n = vent.saveWithoutMetadata();
            n.putInt("Oxygen", 120);
            vent.load(n);
            vent.setChanged();
            a.getInventory()
                    .setItem(
                            5,
                            new net.minecraft.world.item.ItemStack(
                                    dev.starbridge.content.ContentModule.OXYGEN_CANISTER.get()));
            a.teleportTo(moon, 62.5, 129, 62.5, 180, 0);
            b.teleportTo(moon, 66.5, 129, 62.5, 90, 0);
            born = server.getTickCount();
            out.put("world", moon.dimension().location().toString());
            out.put(
                    "setup",
                    "Prepared one sealed glass room with 120 vent person-seconds and an adjacent"
                        + " outdoor platform. Two non-op survival clients, no equipment or"
                        + " effects.");
            out.put("samples", samples);
            out.put("damageEvents", hits);
        }
        int age = server.getTickCount() - born;
        if (age == 200) moon.setBlockAndUpdate(HATCH, Blocks.AIR.defaultBlockState());
        if (age == 280) {
            moon.setBlockAndUpdate(HATCH, Blocks.GLASS.defaultBlockState());
            var nativeAir =
                    ForgeRegistries.BLOCKS.getValue(
                            new ResourceLocation("galacticraftcore", "tile.brightbreathableair"));
            if (nativeAir != null && nativeAir != Blocks.AIR) {
                moon.setBlockAndUpdate(new BlockPos(66, 129, 62), nativeAir.defaultBlockState());
                moon.setBlockAndUpdate(new BlockPos(66, 130, 62), nativeAir.defaultBlockState());
            }
        }
        if (age % 40 == 0) {
            var one = original(a);
            var two = original(b);
            var vent = moon.getBlockEntity(VENT);
            samples.add(
                    Map.of(
                            "tick",
                            age,
                            "inside",
                            one,
                            "outside",
                            two,
                            "ventCharge",
                            vent.saveWithoutMetadata().getInt("Oxygen")));
            if (age == 160)
                sealed =
                        Boolean.TRUE.equals(one.get("sealedChargedVent"))
                                && !Boolean.TRUE.equals(two.get("sealedChargedVent"));
            refilled |=
                    vent.saveWithoutMetadata().getInt("Oxygen") > 120
                            && a.getInventory()
                                            .countItem(
                                                    dev.starbridge.content.ContentModule
                                                            .OXYGEN_CANISTER
                                                            .get())
                                    == 0;
            if (age == 0) sealedHealth = a.getHealth();
            if (age == 160) {
                cleanAir =
                        !Boolean.TRUE.equals(one.get("vacuumFatigue"))
                                && !Boolean.TRUE.equals(one.get("vacuumSlowness"));
                protectedHealth = a.getHealth() >= sealedHealth && a.isAlive();
                outsideHurt = b.getHealth() < sealedHealth;
                damageStart = a.getHealth();
            }
            if (age == 240) {
                leak = !Boolean.TRUE.equals(one.get("sealedChargedVent"));
                pressureLost = a.getHealth() < damageStart;
            }
            if (age == 320) afterLeak = a.getHealth();
            if (age == 400)
                recovered =
                        a.isAlive()
                                && Boolean.TRUE.equals(one.get("sealedChargedVent"))
                                && a.getHealth() >= afterLeak;
            Files.writeString(
                    Path.of("oxygen-live.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(out));
        }
        if (age >= 400) {
            out.put("ventSealedControl", sealed);
            out.put("ventLeakControl", leak);
            out.put("sealedCrewHealthStable", protectedHealth);
            out.put("exposedCrewLostHealth", outsideHurt);
            out.put("openHatchCausedDamage", pressureLost);
            out.put("resealedCrewHealthStable", recovered);
            out.put("sealedCrewFreeOfVacuumEffects", cleanAir);
            out.put("ventMenuRefilledFromInventory", refilled);
            out.put(
                    "pass",
                    sealed
                            && leak
                            && protectedHealth
                            && outsideHurt
                            && pressureLost
                            && recovered
                            && cleanAir
                            && refilled);
            Files.writeString(
                    Path.of("flight-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(out));
            server.halt(false);
        }
    }
}
