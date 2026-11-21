package dev.starbridge.flightqa;

import dev.starbridge.Starbridge;

import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;

@Mod.EventBusSubscriber(modid = "starbridge_flightqa", value = Dist.CLIENT)
public final class FlightClient {
    static int clock, next, lastUse, trip;
    static boolean waitingLanding, visitedMoon, usedChart, openedDisplay, landed;
    static String lastScreen = "";

    static Button find(Screen screen, String key) {
        return screen == null
                ? null
                : screen.children().stream()
                        .filter(
                                n ->
                                        n instanceof Button b
                                                && b.getMessage().getContents()
                                                        instanceof TranslatableContents t
                                                && t.getKey().equals(key))
                        .map(x -> (Button) x)
                        .findFirst()
                        .orElse(null);
    }

    static boolean click(Screen sc, String key) throws Exception {
        var b = find(sc, key);
        if (b == null || !b.active) return false;
        int x = b.getX() + b.getWidth() / 2, y = b.getY() + b.getHeight() / 2;
        sc.mouseClicked(x, y, 0);
        sc.mouseReleased(x, y, 0);
        log("clicked " + key);
        return true;
    }

    static void log(String text) throws Exception {
        Files.writeString(
                Path.of("flight-input.log"),
                text + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        Starbridge.LOGGER.info("ORIGINAL_CLIENT {}", text);
    }

    static void snap(Minecraft mc, String name) {
        Screenshot.grab(
                mc.gameDirectory,
                name + ".png",
                mc.getMainRenderTarget(),
                s -> Starbridge.LOGGER.info("ORIGINAL_CLIENT {}", s.getString()));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (Files.isRegularFile(Path.of("starbridge-joined.fixture"))
                || event.phase != TickEvent.Phase.END
                || !Files.isRegularFile(Path.of("starbridge-original-crew.fixture"))) return;
        var mc = Minecraft.getInstance();
        clock++;
        String sc = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        if (!sc.equals(lastScreen)) {
            log(sc + " " + (mc.screen == null ? "" : mc.screen.getNarrationMessage().getString()));
            lastScreen = sc;
        }
        if (mc.player == null) {
            if ((mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen)
                    && mc.getOverlay() == null
                    && clock > next
                    && clock > 75) {
                next = clock + 350;
                String addr =
                        Files.exists(Path.of("qa-server.txt"))
                                ? Files.readString(Path.of("qa-server.txt")).trim()
                                : "127.0.0.1:25628";
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(addr),
                        new ServerData("Original world test", addr, true),
                        false);
            }
            return;
        }
        if (mc.level == null) return;
        mc.setWindowActive(true);
        String dim = mc.level.dimension().location().toString();
        boolean onMoon = dim.equals("retrofit:legacy_dim_-28");
        if (onMoon && !visitedMoon) {
            visitedMoon = true;
            next = clock + 80;
            log("entered original Moon: " + dim);
            snap(mc, "real-moon");
        }
        if (Files.isRegularFile(Path.of("starbridge-oxygen.fixture"))) {
            if (onMoon
                    && mc.getUser().getName().equals("OrbitCaptain")
                    && !landed
                    && clock - lastUse > 8) {
                if (mc.screen == null && mc.player.position().distanceToSqr(62.5, 129, 62.5) < 3) {
                    var pos = new BlockPos(61, 130, 60);
                    mc.gameMode.useItemOn(
                            mc.player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    Vec3.atCenterOf(pos).add(0, 0, .5),
                                    Direction.SOUTH,
                                    pos,
                                    false));
                    lastUse = clock;
                    log("used the cabin oxygen vent");
                } else if (sc.equals("VentScreen") && click(mc.screen, "vent.starbridge.fill")) {
                    snap(mc, "sealed-vent-gauge");
                    landed = true;
                    log("loaded one oxygen cartridge using the original-world cabin panel");
                }
            }
            return;
        }
        if (!mc.getUser().getName().equals("OrbitCaptain")) return;
        if (onMoon && trip == 1 && !landed) {
            trip = 2;
            landed = true;
            usedChart = waitingLanding = openedDisplay = false;
            next = clock + 80;
        }
        if (visitedMoon && dim.equals("minecraft:overworld") && trip >= 3) {
            if (!Files.exists(Path.of("home-verified"))) {
                Files.writeString(Path.of("home-verified"), "returned");
                snap(mc, "returned");
            }
            return;
        }
        if (clock < next || clock - lastUse < 15) return;
        if (mc.screen == null && !usedChart) {
            if (!visitedMoon && mc.player.position().distanceToSqr(1.5, 129, 2.5) < 12) {
                var p = new BlockPos(1, 129, 1);
                mc.gameMode.useItemOn(
                        mc.player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3.atCenterOf(p).add(0, 0, .5), Direction.SOUTH, p, false));
                lastUse = clock;
                log("used live ship core");
            } else if (onMoon) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                lastUse = clock;
                log("used paired navigation slate aboard Moon ship");
            }
            return;
        }
        if (sc.equals("ChartScreen")) {
            String key = onMoon ? "home" : "moon";
            var marker = find(mc.screen, "route.starbridge." + key);
            if (marker == null) {
                log("missing target " + key);
                next = clock + 90;
                return;
            }
            if (!click(mc.screen, "route.starbridge." + key)) return;
            snap(mc, onMoon ? "home-chart" : "real-moon-chart");
            if (click(mc.screen, "nav.starbridge.cruise")) {
                trip = onMoon ? 3 : 1;
                usedChart = true;
                lastUse = clock;
                next = clock + 25;
            } else {
                log("selected target cannot depart");
                next = clock + 40;
            }
            return;
        }
        if (sc.equals("OrbitScreen")) {
            if (!openedDisplay) {
                snap(mc, onMoon ? "return-flight" : "outbound-flight");
                if (click(mc.screen, "orbit.starbridge.landing")) {
                    openedDisplay = true;
                    lastUse = clock;
                }
                return;
            }
            if (!onMoon && LandingCheckClient.beforeApproach(mc,mc.screen)) return;
            var descend = find(mc.screen, "orbit.starbridge.touchdown");
            if (descend != null && descend.active && !waitingLanding) {
                snap(mc, onMoon ? "home-approach" : "lunar-approach");
                if (click(mc.screen, "orbit.starbridge.touchdown")) {
                    waitingLanding = true;
                    lastUse = clock;
                    next = clock + 85;
                }
            } else if (waitingLanding && clock - lastUse > 90) {
                log("still on landing display after requesting descent");
                snap(mc, "pending-landing");
                waitingLanding = false;
                lastUse = clock;
            }
        }
    }
}
