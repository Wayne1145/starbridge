package dev.starbridge.qa.fleet;

import com.google.gson.Gson;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Observe ten network identities on every server tick during the hosted fleet smoke. */
@Mod.EventBusSubscriber(modid = "starbridgefleetqa", value = Dist.DEDICATED_SERVER)
public final class LinuxWitness {
    private static final Path SIGNAL = Path.of("linux-fleet.fixture");
    private static final boolean ENABLED = Files.isRegularFile(SIGNAL);
    private static final List<String> NAMES = java.util.stream.IntStream.rangeClosed(1, 10)
            .mapToObj(i -> String.format(Locale.ROOT, "FleetQA%02d", i)).toList();
    private static final Map<String, Double> previousX = new LinkedHashMap<>();
    private static final Map<String, Integer> moves = new LinkedHashMap<>();
    private static final Map<String, String> identities = new LinkedHashMap<>();
    private static int firstTick;
    private static long firstNanos;
    private static String firstUtc;
    private static boolean done;

    private static Map<String, String> providers(MinecraftServer server) {
        var chart = OriginalStarMap.ports(server);
        var worlds = new LinkedHashMap<String, String>();
        for (String key : List.of("moon", "extraplanets.venusep", "galaxyspace.ceres")) {
            var port = chart.get(key);
            if (port == null || port.dimension() == null || server.getLevel(port.dimension()) == null)
                throw new IllegalStateException("Original world missing from server chart: " + key);
            worlds.put(key, port.provider() + "/" + port.dimension().location());
        }
        if (!ForgeRegistries.BLOCKS.containsKey(new ResourceLocation("advancedrocketry", "rocketbuilder"))
                || !ForgeRegistries.BLOCKS.containsKey(new ResourceLocation("libvulpes", "tile.coalgenerator"))
                || !ModList.get().isLoaded("computercraft") || !ModList.get().isLoaded("valkyrienskies"))
            throw new IllegalStateException("Required shipyard, physics or computer missing at runtime");
        return worlds;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void sample(TickEvent.ServerTickEvent event) throws IOException {
        if (!ENABLED || done || event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        if (!server.isDedicatedServer() || !"127.0.0.1".equals(server.getLocalIp()) || server.getPort() != 26750)
            throw new IllegalStateException("Expected the loopback soak server");
        var players = server.getPlayerList().getPlayers();
        if (players.size() != NAMES.size() || !Files.isRegularFile(Path.of("soak-start.json"))) {
            if (firstNanos != 0) throw new IllegalStateException("A measured identity left the server");
            return;
        }
        if (firstNanos == 0) {
            firstTick = server.getTickCount();
            firstNanos = System.nanoTime();
            firstUtc = Instant.now().toString();
        }
        for (ServerPlayer player : players) {
            String name = player.getGameProfile().getName();
            var expected = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (!NAMES.contains(name) || !expected.equals(player.getUUID()) || player instanceof FakePlayer
                    || !(player.connection.connection.getRemoteAddress() instanceof InetSocketAddress address)
                    || !address.getAddress().isLoopbackAddress() || !player.isAlive())
                throw new IllegalStateException("Unexpected participant during the ten-identity measurement");
            Double prior = previousX.put(name, player.getX());
            if (prior != null && Math.abs(player.getX() - prior) > .001) moves.merge(name, 1, Integer::sum);
            identities.put(name, player.getUUID().toString());
        }
        long elapsed = System.nanoTime() - firstNanos;
        if (elapsed < 75_000_000_000L) return;
        if (moves.size() != 10 || moves.values().stream().anyMatch(count -> count < 10))
            throw new IllegalStateException("Not all ten network players moved during the sample");
        var observation = Map.of("firstTick", firstTick, "lastTick", server.getTickCount(), "elapsedSeconds", elapsed/1e9,
                "firstUtc", firstUtc, "endUtc", Instant.now().toString(), "pid", ProcessHandle.current().pid(),
                "moves", moves, "identities", identities, "originalWorlds", providers(server));
        Path temp = Path.of("linux-witness.tmp");
        Files.writeString(temp,new Gson().toJson(observation));
        Files.move(temp,Path.of("linux-witness.json"),StandardCopyOption.ATOMIC_MOVE);
        done = true;
    }
}
