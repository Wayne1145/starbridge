package dev.starbridge.qa.integrated;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedWriter;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgeintegratedqa", value = Dist.CLIENT)
public final class LanCrew {
    static final boolean HOST = Files.isRegularFile(Path.of("integrated-lan-host.fixture"));
    static final boolean GUEST = Files.isRegularFile(Path.of("integrated-lan-guest.fixture"));
    private static final int PORT = 26753;
    static final String NAME = "FleetVisitor";
    private static final Path HOST_PHASE = Path.of("..", "lan-phase.json");
    private static boolean opened, finished;
    private static int clock, retries, framesWritten;
    private static long previousFrame;
    private static BufferedWriter frames;
    private static String lastWorld = "",
            previousPhase = "",
            previousWorld = "",
            currentPhase = "joining";
    static final Map<String, Object> feet = new LinkedHashMap<>();

    @SubscribeEvent
    public static void client(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || (!HOST && !GUEST) || finished) return;
        var mc = Minecraft.getInstance();
        ++clock;
        if (HOST) {
            if (opened || IntegratedFlight.origin == 0 || mc.getSingleplayerServer() == null)
                return;
            var server = mc.getSingleplayerServer();
            server.setUsesAuthentication(false);
            if (!server.publishServer(GameType.SURVIVAL, false, PORT))
                throw new IllegalStateException("Could not open the local LAN world");
            opened = true;
            Files.writeString(Path.of("lan-ready"), String.valueOf(PORT));
            return;
        }
        mc.setWindowActive(true);
        if (Files.isRegularFile(Path.of("..", "pilot-result.json"))) {
            finished = true;
            if (frames != null) frames.close();
            IntegratedFlight.write(
                    "guest-result.json",
                    Map.of(
                            "frames",
                            framesWritten,
                            "lastWorld",
                            lastWorld,
                            "connections",
                            retries));
            mc.stop();
            return;
        }
        if (mc.player != null && mc.level != null) {
            String world = mc.level.dimension().location().toString();
            if (!world.equals(lastWorld)) {
                lastWorld = world;
                Files.writeString(
                        Path.of("guest-worlds.txt"),
                        world + "\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            if (clock % 2 == 0 && Files.isRegularFile(HOST_PHASE)) {
                currentPhase =
                        com.google.gson.JsonParser.parseString(Files.readString(HOST_PHASE))
                                .getAsString();
            }
            return;
        }
        if ((mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen)
                && mc.getOverlay() == null
                && clock % 60 == 0
                && Files.isRegularFile(Path.of("..", "lan-ready"))) {
            if (++retries > 6) throw new IllegalStateException("LAN guest could not join");
            String host = "127.0.0.1:" + PORT;
            ConnectScreen.startConnecting(
                    mc.screen,
                    mc,
                    ServerAddress.parseString(host),
                    new ServerData("Local flight", host, true),
                    false);
        }
    }

    @SubscribeEvent
    public static void rendered(TickEvent.RenderTickEvent event) throws Exception {
        if (!GUEST || finished || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = System.nanoTime();
        String world = mc.level.dimension().location().toString();
        if (frames == null) {
            frames = Files.newBufferedWriter(Path.of("guest-frames.csv"));
            frames.write("seq,startNs,endNs,startPhase,endPhase,startWorld,endWorld\n");
        }
        if (previousFrame > 0) {
            frames.write(
                    ++framesWritten
                            + ","
                            + previousFrame
                            + ","
                            + now
                            + ","
                            + previousPhase
                            + ","
                            + currentPhase
                            + ","
                            + previousWorld
                            + ","
                            + world
                            + "\n");
            if (framesWritten % 120 == 0) frames.flush();
        }
        previousFrame = now;
        previousWorld = world;
        previousPhase = currentPhase;
    }

    static void seat(MinecraftServer server, ServerPlayer captain) throws Exception {
        var visitor = server.getPlayerList().getPlayerByName(NAME);
        if (visitor == null
                || visitor.connection.connection.isMemoryConnection()
                || !(visitor.connection.connection.getRemoteAddress()
                                instanceof InetSocketAddress socket
                        && socket.getAddress().isLoopbackAddress())
                || visitor.isCreative()
                || visitor.hasPermissions(2)) {
            throw new IllegalStateException("Expected a separate non-operator survival LAN client");
        }
        var equip =
                Class.forName("dev.starbridge.flightqa.OriginalEquipment")
                        .getDeclaredMethod("equip", ServerPlayer.class);
        equip.setAccessible(true);
        equip.invoke(null, visitor);
        visitor.teleportTo(
                server.overworld(), captain.getX(), captain.getY(), captain.getZ() + 1, 0, 0);
        IntegratedFlight.write(
                "lan-connection.json",
                Map.of(
                        "host",
                        captain.getUUID().toString(),
                        "guest",
                        visitor.getUUID().toString(),
                        "address",
                        socket.getAddress().getHostAddress(),
                        "memory",
                        false,
                        "op",
                        visitor.hasPermissions(2),
                        "gamemode",
                        visitor.gameMode.getGameModeForPlayer().getName()));
    }

    static void record(MinecraftServer server, Map<String, Object> flight) {
        for (String leg : List.of("moon", "home")) {
            if (feet.containsKey(leg) || !(flight.get(leg + "Hull") instanceof Map<?, ?> data))
                continue;
            var visitor = server.getPlayerList().getPlayerByName(NAME);
            var core =
                    CoreRegistry.find(UUID.fromString(data.get("core").toString())).orElseThrow();
            net.minecraft.world.level.block.entity.BlockEntity console = core;
            if (visitor == null
                    || visitor.serverLevel() != console.getLevel()
                    || !visitor.isAlive()
                    || !HullTransit.isAboard(visitor, core))
                throw new IllegalStateException("LAN guest is not aboard on " + leg);
            var level = visitor.serverLevel();
            var body = new VsShipBackend().inspect(level, console.getBlockPos()).orElseThrow();
            var local =
                    HullTransit.toShipLocal(level, body.shipId(), visitor.position()).orElseThrow();
            var floor = BlockPos.containing(local.x, local.y - .1, local.z);
            var state = level.getBlockState(floor);
            var hit =
                    state.getCollisionShape(level, floor)
                            .clip(local.add(0, .1, 0), local.add(0, -.1, 0), floor);
            if (hit == null
                    || hit.getDirection() != Direction.UP
                    || Math.abs(local.y - hit.getLocation().y) > .075)
                throw new IllegalStateException("LAN guest lacks actual deck support on " + leg);
            feet.put(
                    leg,
                    Map.of(
                            "world",
                            level.dimension().location().toString(),
                            "health",
                            visitor.getHealth(),
                            "floor",
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                            "gap",
                            local.y - hit.getLocation().y,
                            "uid",
                            visitor.getUUID().toString()));
        }
    }
}
