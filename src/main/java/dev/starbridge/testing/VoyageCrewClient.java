package dev.starbridge.testing;

import dev.starbridge.Starbridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loopback-only unattended crew clients. No global input or foreground focus is needed. */
@Mod.EventBusSubscriber(modid = Starbridge.ID, value = Dist.CLIENT)
public final class VoyageCrewClient {
    private static int ticks;
    private static int attempts, nextTry;
    private static Class<?> previous;
    private VoyageCrewClient() {}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("starbridge.voyageCrew") || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        String name = mc.getUser().getName();
        if (!(name.equals("OrbitCaptain") || name.equals("OrbitEngineer")) || !Files.isRegularFile(Path.of("starbridge-disposable-voyage.fixture"))) return;
        if (++ticks % 10 != 0) return;
        var current = mc.screen;
        Class<?> seen = current == null ? null : current.getClass();
        if (previous != seen) {
            previous = seen;
            Starbridge.LOGGER.info("STARBRIDGE_CREW {} screen={} message={}", name, seen, current == null ? "" : current.getNarrationMessage().getString());
        }
        if (mc.player != null || mc.getOverlay() != null || attempts >= 4 || ticks < Math.max(80, nextTry)) return;
        if (current instanceof TitleScreen || current instanceof DisconnectedScreen) {
            attempts++;
            nextTry = ticks + 300;
            String addr = "127.0.0.1:25583";
            ConnectScreen.startConnecting(current, mc, ServerAddress.parseString(addr), new ServerData("Starbridge voyage fixture", addr, true), false);
        }
    }
}
