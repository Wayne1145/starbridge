package dev.starbridge.qa.home;

import com.google.gson.GsonBuilder;
import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VoyageLog;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Integrated server fixture. It prepares a return cabin and observes the ordinary voyage. */
@Mod.EventBusSubscriber(modid = "starbridgeportqa")
public final class RemoteHomeWorld {
    static final boolean ENABLED = "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
            && "Linux".equals(System.getenv("RUNNER_OS")) && "true".equals(System.getenv("GITHUB_ACTIONS"))
            && Files.isRegularFile(Path.of("remote-home.fixture"));
    static final BlockPos BRIDGE = new BlockPos(41, 191, 42);
    static final Vec3 PORT = new Vec3(-40.875, 220.25, 59.625);
    static final int AIM_X = 63, AIM_Z = -38, GEMS = 7;
    record Offset(int x, int z) {}
    static volatile Offset chosen;
    private static final UUID ROCKET = UUID.fromString("7acba5db-d1f6-41ca-8571-ab14d9f45157");
    private static final VsShipBackend VS = new VsShipBackend();
    private static UUID identity;
    private static int fare, beforeLanding = -1, stable;
    private static boolean done;
    static volatile boolean requestLate, latePresent, removeLate, lateRemoved;
    private static final java.util.List<String> confirmations = new java.util.ArrayList<>();

    /** The QA mixin observes completed confirmation surveys without changing their result. */
    public static void observeConfirm(UUID ship, int x, int z, String result) {
        if (ENABLED && ship.equals(identity) && new Offset(x,z).equals(chosen)) confirmations.add(result);
    }

    static void write(String file, Object value) throws Exception {
        var temporary = Path.of(file + ".partial");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(value));
        Files.move(temporary, Path.of(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (!ENABLED || done || event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        if (server.isDedicatedServer() || server.getPlayerList().getPlayers().isEmpty()) return;
        var pilot = server.getPlayerList().getPlayers().get(0);
        try {
            var ports = OriginalStarMap.ports(server);
            var moon = ports.get("moon");
            if (moon == null || moon.dimension() == null) return;
            var away = server.getLevel(moon.dimension());
            require(away != null && "Galacticraft".equals(moon.provider()), "Original Galacticraft moon");
            if (identity == null) {
                require(VS.probe().available(), "Live VS runtime");
                require(pilot.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && !pilot.hasPermissions(2)
                        && !server.getWorldData().getAllowCommands(), "Non-operator survival world");
                prepare(pilot, away, server.overworld());
                fare = moon.fareTo(ports.get("home"));
                return;
            }
            var late = chosen == null ? null : BlockPos.containing(PORT.add(chosen.x(), 0, chosen.z()));
            if (requestLate && late != null && !latePresent) {
                require(server.overworld().getBlockState(late).isAir(), "Selected alternate berth initially open");
                server.overworld().setBlockAndUpdate(late, Blocks.GOLD_BLOCK.defaultBlockState());
                latePresent = true;
            }
            if (removeLate && late != null && !lateRemoved) {
                require(server.overworld().getBlockState(late).is(Blocks.GOLD_BLOCK), "Late obstruction survived rejection");
                server.overworld().removeBlock(late, false);
                lateRemoved = true;
            }
            var core = CoreRegistry.find(identity).orElse(null);
            if (core == null) return;
            if (core.getLevel() == away && OrbitModule.active(core)) {
                var status = OrbitModule.status(core);
                RadarTraffic.update(server, identity, status);
                require("home".equals(status.get("target")) && "moon".equals(status.get("source")), "Original lunar return leg");
                if (Boolean.TRUE.equals(status.get("approach")) && !core.saveWithoutMetadata().hasUUID("PaidVoyage")) {
                    beforeLanding = core.energy();
                }
                return;
            }
            var home = server.overworld();
            if (beforeLanding < 0 || pilot.serverLevel() != home || core.getLevel() != home || HullTransit.inProgress(server, identity)) return;
            var body = VS.inspect(home, core.getBlockPos()).orElse(null);
            if (body == null || !HullTransit.isAboard(pilot, core)) return;
            var position = body.worldPosition();
            var actual = new Vec3(position.x(), position.y(), position.z());
            var barrelPos = core.getBlockPos().east();
            if (!(home.getBlockEntity(barrelPos) instanceof BarrelBlockEntity barrel)) return;
            require(actual.distanceTo(PORT.add(chosen.x(), 0, chosen.z())) < .05, "Same ship at selected offset and original altitude");
            require(core.owner().equals(pilot.getUUID()) && ROCKET.equals(core.originalRocket()), "Ship and pilot identity");
            require(core.saveWithoutMetadata().hasUUID("PaidVoyage") && beforeLanding - core.energy() == fare, "Single production landing fare");
            require(home.getBlockState(BlockPos.containing(PORT)).is(Blocks.OBSIDIAN), "Original obstruction retained");
            require(away.getBlockState(BRIDGE).isAir() && barrel.getItem(0).is(Items.DIAMOND) && barrel.getItem(0).getCount() == GEMS,
                    "Source cabin cleared and cargo present");
            require(pilot.isAlive() && !pilot.isSpectator() && !OrbitModule.active(core), "Living pilot settled on the deck");
            require(confirmations.equals(List.of("OCCUPIED", "CLEAR")), "Two fresh confirmation surveys: " + confirmations);
            if (++stable < 20) return;
            dev.starbridge.qa.home.landing.ApproachTiming.stop();
            write("home-server.json", Map.ofEntries(Map.entry("pass", true), Map.entry("source", moon.dimension().location().toString()),
                    Map.entry("destination", home.dimension().location().toString()), Map.entry("pilot", pilot.getUUID()), Map.entry("core", identity),
                    Map.entry("rocket", ROCKET), Map.entry("ship", body.shipId()), Map.entry("nativePosition", List.of(actual.x, actual.y, actual.z)), Map.entry("offset", List.of(chosen.x(),chosen.z())),
                    Map.entry("storageCore", List.of(core.getBlockPos().getX(), core.getBlockPos().getY(), core.getBlockPos().getZ())),
                    Map.entry("confirmations", List.copyOf(confirmations)), Map.entry("fare", fare), Map.entry("beforeFE", beforeLanding), Map.entry("afterFE", core.energy()),
                    Map.entry("receipt", core.saveWithoutMetadata().getUUID("PaidVoyage")), Map.entry("diamonds", GEMS),
                    Map.entry("obstructionIntact", true), Map.entry("sourceCleared", true), Map.entry("aboard", true)));
            done = true;
        } catch (Exception | AssertionError failure) {
            done = true;
            write("home-server.json", Map.of("pass", false, "error", failure.toString()));
        }
    }

    private static void prepare(ServerPlayer pilot, ServerLevel moon, ServerLevel home) {
        for (int x = -1; x <= 2; x++) for (int z = -1; z <= 1; z++) {
            moon.setBlockAndUpdate(BRIDGE.offset(x, -1, z), Blocks.IRON_BLOCK.defaultBlockState());
        }
        moon.setBlockAndUpdate(BRIDGE, Starbridge.SHIP_CORE.get().defaultBlockState());
        moon.setBlockAndUpdate(BRIDGE.east(), Blocks.BARREL.defaultBlockState());
        var core = (ShipCoreBlockEntity) moon.getBlockEntity(BRIDGE);
        var barrel = (BarrelBlockEntity) moon.getBlockEntity(BRIDGE.east());
        require(core != null && barrel != null, "Prepared deck");
        barrel.setItem(0, new ItemStack(Items.DIAMOND, GEMS));
        core.setOwner(pilot.getUUID());
        core.bindOriginalRocket(ROCKET);
        core.getCapability(ForgeCapabilities.ENERGY).resolve().orElseThrow().receiveEnergy(90000, false);
        core.register();
        OrbitModule.bindCabin(core, BRIDGE.offset(-1, -1, -1), BRIDGE.offset(2, 0, 1));
        identity = core.id();
        VoyageLog.get(home.getServer()).rememberHome(identity, PORT);
        home.setBlockAndUpdate(BlockPos.containing(PORT), Blocks.OBSIDIAN.defaultBlockState());
        // Survival, with damage resistance as a fixture for the lunar exposure; this is a map test.
        pilot.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 12000, 4, false, false));
        pilot.getPersistentData().putUUID("starbridge:selected_core", identity);
        pilot.getInventory().selected = 0;
        pilot.getInventory().setItem(0, new ItemStack(VoyageModule.SLATE.get()));
        pilot.teleportTo(moon, BRIDGE.getX() + .5, BRIDGE.getY(), BRIDGE.getZ() + 1.5, 0f, -75f);
    }
}
