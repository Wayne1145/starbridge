package dev.starbridge.qa.berth;

import com.google.gson.*;

import dev.starbridge.orbit.OrbitWire;

import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgefleetqa", value = Dist.CLIENT)
public final class BerthPilot {
    private static int clock, changed, round = -1, clickTick;
    private static String action = "";
    private static boolean chart, approach, selected, submitted, rejected, stopping;

    static Button button(Screen sc, String key) {
        var name = Component.translatable(key).getString();
        return sc.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.visible && b.getMessage().getString().equals(name))
                .findFirst()
                .orElse(null);
    }

    static void at(Screen sc, double x, double y) {
        sc.mouseClicked(x, y, 0);
        sc.mouseReleased(x, y, 0);
    }

    static boolean click(Screen sc, String key) throws Exception {
        var b = button(sc, key);
        if (b == null || !b.active) return false;
        at(sc, b.getX() + b.getWidth() / 2., b.getY() + b.getHeight() / 2.);
        log(key);
        return true;
    }

    static void log(String s) throws Exception {
        Files.writeString(
                Path.of("berth-input.log"),
                clock + " " + s + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String wantedProof = "";
    private static OrbitWire.Clearance wantedState;
    private static boolean rendered;

    private static boolean proof(String name, OrbitWire.Clearance state) {
        if (!wantedProof.equals(name)) {
            wantedProof = name;
            wantedState = state;
            rendered = false;
        }
        return rendered;
    }

    @SubscribeEvent
    public static void capture(net.minecraftforge.client.event.ScreenEvent.Render.Post event)
            throws Exception {
        var sc = event.getScreen();
        if (rendered
                || wantedProof.isEmpty()
                || !sc.getClass().getSimpleName().equals("OrbitScreen")) return;
        var f = sc.getClass().getDeclaredField("landingSite");
        f.setAccessible(true);
        var site = (OrbitWire.Site) f.get(sc);
        var button = button(sc, "orbit.starbridge.touchdown");
        if (site == null
                || site.clearance() != wantedState
                || button == null
                || button.active != (wantedState == OrbitWire.Clearance.CLEAR)) return;
        event.getGuiGraphics().flush();
        var mc = Minecraft.getInstance();
        Screenshot.grab(
                mc.gameDirectory, wantedProof + ".png", mc.getMainRenderTarget(), msg -> {});
        rendered = true;
    }

    static void choose(Screen screen, int x, int z) {
        int size = Math.max(52, Math.min(180, Math.min(screen.width / 2 - 28, screen.height - 93)));
        int left = Math.max(12, (screen.width / 2 - size) / 2),
                top = (screen.height - 35 - size) / 2;
        at(screen, left + (x + 512) * (size - 1) / 1024., top + (z + 512) * (size - 1) / 1024.);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (!ConcurrentBerths.ENABLED || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        clock++;
        if (mc.player == null) {
            if (mc.screen instanceof DisconnectedScreen
                    && mc.getOverlay() == null
                    && clock % 40 == 0) {
                String address = "127.0.0.1:26751";
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(address),
                        new ServerData("Berth audit", address, true),
                        false);
            }
            return;
        }
        if (mc.level == null
                || mc.gameMode == null
                || clock % 4 != 0
                || !Files.isRegularFile(Path.of("berth-order.json"))) return;
        var data =
                JsonParser.parseString(Files.readString(Path.of("berth-order.json")))
                        .getAsJsonObject();
        int issued = data.get("round").getAsInt();
        String what = data.get("action").getAsString();
        if (round != issued) {
            round = issued;
            action = "";
            chart = approach = selected = submitted = rejected = stopping = false;
            if (mc.screen != null && !(mc.screen instanceof ReceivingLevelScreen))
                mc.screen.onClose();
        }
        if (!action.equals(what)) {
            action = what;
            selected = submitted = rejected = false;
            changed = clock;
            log(what + " round=" + round);
        }
        if (clock - changed < 12
                || !mc.level.dimension().location().toString().equals("minecraft:overworld"))
            return;
        var screen = mc.screen;
        var origin = data.getAsJsonArray("core");
        if (screen == null && !chart && clock % 12 == 0) {
            var p =
                    new BlockPos(
                            origin.get(0).getAsInt(),
                            origin.get(1).getAsInt(),
                            origin.get(2).getAsInt());
            mc.gameMode.useItemOn(
                    mc.player,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3.atCenterOf(p).add(0, 0, .5), Direction.SOUTH, p, false));
            return;
        }
        if (screen == null) return;
        String kind = screen.getClass().getSimpleName();
        if (kind.equals("ChartScreen") && !chart) {
            String target = data.get("route").getAsString();
            if (button(screen, target) == null) {
                click(screen, "›");
                return;
            }
            if (click(screen, target) && click(screen, "nav.starbridge.cruise")) chart = true;
            return;
        }
        if (!kind.equals("OrbitScreen")) return;
        if (!approach) {
            if (click(screen, "orbit.starbridge.landing")) approach = true;
            return;
        }
        if (!selected) {
            choose(screen, data.get("x").getAsInt(), data.get("z").getAsInt());
            selected = true;
            log("selected " + data.get("x") + "," + data.get("z"));
            return;
        }
        var field = screen.getClass().getDeclaredField("landingSite");
        field.setAccessible(true);
        var site = (OrbitWire.Site) field.get(screen);
        var b = button(screen, "orbit.starbridge.touchdown");
        if (site == null || b == null) return;
        if (!submitted && site.clearance() == OrbitWire.Clearance.CLEAR && b.active) {
            if (!stopping) {
                stopping = click(screen, "orbit.starbridge.brake");
                clickTick = clock;
                return;
            }
            var frameField = screen.getClass().getDeclaredField("frame");
            frameField.setAccessible(true);
            var frame = (OrbitWire.Frame) frameField.get(screen);
            if (frame.velocity().length() > 0.00001 || clock - clickTick < 16) return;
            if (!proof(what + "-ready-" + round, OrbitWire.Clearance.CLEAR)) return;
            if (click(screen, "orbit.starbridge.touchdown")) {
                submitted = true;
                clickTick = clock;
            }
            return;
        }
        if (submitted
                && clock - clickTick >= 12
                && site.clearance() == OrbitWire.Clearance.OCCUPIED
                && !b.active
                && !rejected) {
            if (!proof("occupied-" + round, OrbitWire.Clearance.OCCUPIED)) return;
            ConcurrentBerths.save(
                    "occupied-" + round + ".json",
                    Map.of(
                            "ship",
                            site.ship(),
                            "x",
                            site.x(),
                            "z",
                            site.z(),
                            "clearance",
                            site.clearance(),
                            "buttonEnabled",
                            b.active,
                            "screen",
                            kind));
            rejected = true;
        }
    }
}
