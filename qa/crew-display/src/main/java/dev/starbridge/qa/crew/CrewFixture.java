package dev.starbridge.qa.crew;

import com.google.gson.Gson;
import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.orbit.OrbitalFlight;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

@Mod("starbridgecrewqa")
@Mod.EventBusSubscriber(modid = "starbridgecrewqa")
public final class CrewFixture {
    static final boolean ENABLED =
            "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                    && "Linux".equals(System.getenv("RUNNER_OS"))
                    && "true".equals(System.getenv("GITHUB_ACTIONS"))
                    && Files.isRegularFile(Path.of("crew-display.fixture"));
    static final String CAPTAIN = "OrbitCaptain", ENGINEER = "OrbitEngineer";
    static final BlockPos CORE = new BlockPos(0, 150, 0), OVEN = CORE.east();
    static final Gson JSON = new Gson();
    private static final Set<String> boarded = new HashSet<>();
    private static final List<Object> uses = new ArrayList<>(), motion = new ArrayList<>();
    private static int expectedReserve, fare, smelted;
    private static double firstMachineDistance = -1, lastMachineDistance = -1;
    private static boolean begun, done;
    private static String lastMode = "";

    static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static void write(String filename, Object value) throws Exception {
        Path dest = Path.of(filename), tmp = Path.of(filename + ".pending");
        Files.writeString(tmp, JSON.toJson(value));
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent e) throws Exception {
        if (!ENABLED) return;
        var server = e.getServer();
        check(
                server.isDedicatedServer()
                        && "127.0.0.1".equals(server.getLocalIp())
                        && server.getPort() == 26751,
                "Isolated dedicated test server");
        var world = server.overworld();
        for (int x = -3; x <= 4; x++)
            for (int z = -2; z <= 4; z++)
                world.setBlockAndUpdate(
                        CORE.offset(x, -1, z), Blocks.IRON_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(CORE, Starbridge.SHIP_CORE.get().defaultBlockState());
        world.setBlockAndUpdate(OVEN, Blocks.FURNACE.defaultBlockState());
        write(
                "crew-server-ready.json",
                Map.of(
                        "port",
                        server.getPort(),
                        "cheats",
                        server.getWorldData().getAllowCommands()));
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.RightClickBlock e) {
        if (!ENABLED
                || !(e.getEntity() instanceof ServerPlayer player)
                || !player.getGameProfile().getName().equals(ENGINEER)) return;
        if ((e.getPos().equals(CORE) || e.getPos().equals(OVEN))
                && e.getHand() == InteractionHand.MAIN_HAND)
            uses.add(
                    Map.of(
                            "block",
                            e.getPos().equals(CORE) ? "helm" : "furnace",
                            "tick",
                            player.getServer().getTickCount(),
                            "emptyHand",
                            e.getItemStack().isEmpty()));
    }

    @SubscribeEvent
    public static void smelt(PlayerEvent.ItemSmeltedEvent e) {
        if (ENABLED
                && e.getEntity() instanceof ServerPlayer p
                && p.getGameProfile().getName().equals(ENGINEER)
                && e.getSmelting().is(Items.IRON_INGOT)) smelted += e.getSmelting().getCount();
    }

    static void board(ServerPlayer p, boolean captain, ShipCoreBlockEntity core) throws Exception {
        if (!boarded.add(p.getGameProfile().getName())) return;
        check(
                !p.hasPermissions(2) && p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "Ordinary survival crew");
        p.teleportTo(
                p.serverLevel().getServer().overworld(), captain ? -1.5 : 1.0, 150, 2.5, 180, 25);
        if (captain) {
            core.setOwner(p.getUUID());
            core.getCapability(ForgeCapabilities.ENERGY)
                    .orElseThrow(IllegalStateException::new)
                    .receiveEnergy(90000, false);
            core.register();
            OrbitModule.bindCabin(core, CORE.offset(-3, -1, -2), CORE.offset(4, 2, 4));
        } else {
            p.getInventory().setItem(9, new ItemStack(Items.RAW_IRON));
            p.getInventory().setItem(10, new ItemStack(Items.COAL));
        }
        write(
                "crew-boarded-" + p.getGameProfile().getName() + ".json",
                Map.of(
                        "uuid",
                        p.getUUID(),
                        "position",
                        p.position().toString(),
                        "op",
                        p.hasPermissions(2)));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) throws Exception {
        if (!ENABLED || done || e.phase != TickEvent.Phase.END) return;
        var server = e.getServer();
        var world = server.overworld();
        try {
            var core = (ShipCoreBlockEntity) world.getBlockEntity(CORE);
            if (core == null) return;
            var captain = server.getPlayerList().getPlayerByName(CAPTAIN);
            var engineer = server.getPlayerList().getPlayerByName(ENGINEER);
            if (captain != null) board(captain, true, core);
            if (engineer != null) board(engineer, false, core);
            if (captain == null
                    || engineer == null
                    || !Files.exists(Path.of("../OrbitCaptain/crew-online.json"))
                    || !Files.exists(Path.of("../OrbitEngineer/crew-online.json"))) return;
            check(
                    !server.getWorldData().getAllowCommands()
                            && core.authorized(captain)
                            && !core.authorized(engineer),
                    "Only captain owns the helm");
            if (!begun) {
                var chart = OriginalStarMap.ports(server);
                var home = chart.get("home");
                var moon = chart.get("moon");
                check(
                        moon.dimension() != null && "Galacticraft".equals(moon.provider()),
                        "Original moon from installed pack");
                fare = home.fareTo(moon);
                expectedReserve = home.requiredEnergy(moon) + OrbitalFlight.CRUISE_ALLOWANCE;
                OrbitModule.begin(captain, core, "home", "moon", true);
                begun = true;
            }
            var state = OrbitModule.status(core);
            check(
                    "moon".equals(state.get("target")) && "home".equals(state.get("source")),
                    "Original route stayed active");
            var mode =
                    Boolean.TRUE.equals(state.get("manual"))
                            ? "manual"
                            : Boolean.TRUE.equals(state.get("cruise")) ? "cruise" : "other";
            if (!lastMode.equals(mode)) {
                motion.add(
                        Map.of(
                                "mode",
                                mode,
                                "tick",
                                server.getTickCount(),
                                "distance",
                                state.get("distance"),
                                "reserve",
                                state.get("protectedFE")));
                lastMode = mode;
            }
            if (engineer.containerMenu instanceof AbstractFurnaceMenu && "cruise".equals(mode)) {
                double distance = (double) state.get("distance");
                if (firstMachineDistance < 0) firstMachineDistance = distance;
                lastMachineDistance = distance;
            }
            if (!Files.isRegularFile(Path.of("../OrbitEngineer/crew-machine.json"))
                    || !Files.isRegularFile(Path.of("../OrbitCaptain/crew-controls.json"))) return;
            check(
                    smelted >= 1
                            && firstMachineDistance - lastMachineDistance > 5
                            && OrbitModule.aboard(engineer, core),
                    "Engineer used the furnace while the ship moved");
            check(
                    engineer.getInventory().contains(new ItemStack(Items.IRON_INGOT))
                            && "cruise".equals(mode),
                    "Engineer kept the iron and captain retained control");
            var out = new LinkedHashMap<String, Object>();
            out.put("pass", true);
            out.put("ship", core.id());
            out.put("captain", captain.getUUID());
            out.put("engineer", engineer.getUUID());
            out.put("nonOp", !captain.hasPermissions(2) && !engineer.hasPermissions(2));
            out.put("engineerOwnsHelm", core.authorized(engineer));
            out.put("uses", uses);
            out.put("motion", motion);
            out.put("fare", fare);
            out.put("reserve", expectedReserve);
            out.put("smelted", smelted);
            out.put("machineDistances", List.of(firstMachineDistance, lastMachineDistance));
            out.put("target", state.get("target"));
            write("crew-server.json", out);
            done = true;
        } catch (Exception | AssertionError problem) {
            done = true;
            write("crew-server.json", Map.of("pass", false, "error", problem.toString()));
        }
    }
}
