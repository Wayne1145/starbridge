package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

@Mod.EventBusSubscriber(modid = Starbridge.ID)
public final class OriginalTerrainSeeds {
    private record Session(MinecraftServer server, Set<Integer> keepLegacy) {}
    private static volatile Session current;
    private OriginalTerrainSeeds() {}

    @SubscribeEvent public static void starting(ServerAboutToStartEvent event) {
        var mods = ModList.get();
        if (!mods.isLoaded("starbridgelunarseedprobe") && mods.getModContainerById("retrofit")
                .filter(mod -> "0.6.2-beta".equals(mod.getModInfo().getVersion().toString())).isPresent()) {
            load(event.getServer());
        }
    }

    private static synchronized Session load(MinecraftServer server) {
        if (current != null && current.server == server) return current;
        Path world = server.getWorldPath(LevelResource.ROOT);
        try {
            Set<Integer> previous = OriginalTerrainPolicy.load(world);
            Starbridge.LOGGER.info("Legacy terrain uses the host seed. Existing dimensions retaining the previous generation: {}", previous);
            current = new Session(server, previous);
            return current;
        } catch (IOException problem) {
            throw new IllegalStateException("Could not preserve original planetary terrain settings in " + world, problem);
        }
    }

    /** Same source as 1.20.1 ServerLevel.getSeed(), including when its configured seed is zero. */
    public static long seed(int legacyDimension) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IllegalStateException("Generating legacy terrain without its host server");
        Session session = current;
        if (session == null || session.server != server) session = load(server);
        return session.keepLegacy.contains(legacyDimension) ? 0L : server.getWorldData().worldGenOptions().seed();
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (current != null && current.server == event.getServer()) current = null;
    }
}
