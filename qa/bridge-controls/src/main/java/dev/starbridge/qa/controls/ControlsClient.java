package dev.starbridge.qa.controls;

import com.google.gson.JsonParser;

import dev.starbridge.client.ChartScreen;

import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

import java.nio.file.*;

@Mod.EventBusSubscriber(modid = "starbridgecontrolsqa", value = Dist.CLIENT)
public final class ControlsClient {
    static int t, nextJoin, last, step;
    static boolean paired, enabled;

    static void log(String what) throws Exception {
        Files.writeString(
                Path.of("controls-input.log"),
                what + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static boolean click(Screen sc, String key) throws Exception {
        for (var child : sc.children())
            if (child instanceof Button b
                    && b.active
                    && b.getMessage().getContents() instanceof TranslatableContents msg
                    && msg.getKey().equals(key)) {
                int x = b.getX() + b.getWidth() / 2, y = b.getY() + b.getHeight() / 2;
                sc.mouseClicked(x, y, 0);
                sc.mouseReleased(x, y, 0);
                log("clicked " + key);
                last = t;
                return true;
            }
        return false;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END
                || !Files.isRegularFile(Path.of("starbridge-controls.fixture"))) return;
        var mc = Minecraft.getInstance();
        mc.setWindowActive(true);
        ++t;
        if (mc.player == null) {
            if (mc.getOverlay() == null
                    && (mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen)
                    && t > nextJoin + 70) {
                nextJoin = t + 150;
                String addr = Files.readString(Path.of("lunar-server.txt")).trim();
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(addr),
                        new ServerData("Ship computer lab", addr, true),
                        false);
            }
            return;
        }
        var directory = Path.of("../server");
        var ticket = directory.resolve("controls-ticket.json");
        if (!Files.exists(ticket)
                || !Files.exists(directory.resolve("mcp-initial-hidden.ok"))
                || t - last < 12) return;
        int id =
                JsonParser.parseString(Files.readString(ticket))
                        .getAsJsonObject()
                        .get("computer")
                        .getAsInt();
        if (step == 0 && mc.screen == null) {
            var pos = ControlsFixture.CORE;
            mc.gameMode.useItemOn(
                    mc.player,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3.atCenterOf(pos).add(0, 0, .49), Direction.SOUTH, pos, false));
            last = t;
            return;
        }
        if (mc.screen instanceof ChartScreen chart) {
            if (chart.children().stream().noneMatch(c -> c instanceof EditBox)) {
                click(chart, "nav.starbridge.systems");
                return;
            }
            if (!paired) {
                var box =
                        (EditBox)
                                chart.children().stream()
                                        .filter(c -> c instanceof EditBox)
                                        .findFirst()
                                        .orElseThrow();
                chart.mouseClicked(box.getX() + 3, box.getY() + 5, 0);
                for (char ch : Integer.toString(id).toCharArray()) chart.charTyped(ch, 0);
                paired = click(chart, "nav.starbridge.pair");
                return;
            }
            if (!enabled) {
                enabled = click(chart, "nav.starbridge.agent_on");
                if (enabled) step = 1;
                return;
            }
            step = 1;
            chart.onClose();
            last = t;
            log("owner returned to the deck after pairing and granting agent access");
            return;
        }
        Screen helm = mc.screen;
        boolean inHelm =
                helm != null
                        && helm.getClass()
                                .getName()
                                .equals("dev.starbridge.client.orbit.OrbitScreen");
        if (inHelm && step == 1 && Files.exists(directory.resolve("mcp-cruise.ok"))) {
            helm.keyPressed(GLFW.GLFW_KEY_W, 0, 0);
            log("manual thrust in graphical helm");
            last = t;
            step = 2;
            return;
        }
        if (inHelm
                && step == 2
                && Files.exists(directory.resolve("mcp-denied.ok"))
                && Files.exists(directory.resolve("controls-lua-denied.ok"))) {
            helm.keyReleased(GLFW.GLFW_KEY_W, 0, 0);
            if (click(helm, "orbit.starbridge.cruise")) step = 3;
        }
    }
}
