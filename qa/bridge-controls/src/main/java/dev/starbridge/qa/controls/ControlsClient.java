package dev.starbridge.qa.controls;

import net.minecraft.client.Screenshot;

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



import java.nio.file.*;

@Mod.EventBusSubscriber(modid = "starbridgecontrolsqa", value = Dist.CLIENT)
public final class ControlsClient {
    static int t, nextJoin, last, step;
    static int pairingStep, dismissTick;
    static boolean checkedDismiss;
    static boolean enabled;

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

    private static com.mojang.blaze3d.platform.InputConstants.Key throttle(Minecraft game) {
        return java.util.Arrays.stream(game.options.keyMappings)
                .filter(m -> m.getName().equals("key.starbridge.orbit.forward"))
                .findFirst().orElseThrow().getKey();
    }

    static boolean hasButton(Screen screen, String key) {
        return screen.children().stream().anyMatch(c -> c instanceof Button b
                && b.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key));
    }

    static void picture(Minecraft game, String file) {
        Screenshot.grab(game.gameDirectory, file, game.getMainRenderTarget(), ignored -> {});
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END
                || !Files.isRegularFile(Path.of("starbridge-controls.fixture"))) return;
        var mc = Minecraft.getInstance();
        if (Files.isRegularFile(Path.of("controls.stop"))) { mc.stop(); return; }
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
        if (!Files.exists(directory.resolve("mcp-initial-hidden.ok")) || t - last < 12) return;
        if (dismissTick != 0 && !checkedDismiss) {
            if (t - dismissTick < 30) return;
            if (mc.screen != null) {
                Files.writeString(Path.of("device-dismiss-failed.txt"), mc.screen.getClass().getName());
                return;
            }
            checkedDismiss = true;
            log("refresh reply left the closed helm closed for 30 client ticks");
        }
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
            if (!hasButton(chart, "nav.starbridge.devices.refresh")) {
                click(chart, "nav.starbridge.systems");
                return;
            }
            if (dismissTick == 0 && hasButton(chart, "nav.starbridge.devices.pair")) {
                if (click(chart, "nav.starbridge.devices.refresh")) {
                    chart.onClose();
                    dismissTick = t;
                }
                return;
            }
            if (pairingStep < 3) {
                if (pairingStep == 1) {
                    if (!hasButton(chart, "nav.starbridge.devices.current")) return;
                    picture(mc, "02-computer-paired.png");
                    if (click(chart, "nav.starbridge.unpair")) pairingStep++;
                } else if (hasButton(chart, "nav.starbridge.devices.pair")) {
                    if (chart.children().stream().anyMatch(c -> c instanceof EditBox))
                        throw new IllegalStateException("The graphical device page requested a typed ID");
                    picture(mc, pairingStep == 0 ? "01-computer-found.png" : "03-computer-revoked.png");
                    if (click(chart, "nav.starbridge.devices.pair")) pairingStep++;
                } else if (t % 30 == 0) click(chart, "nav.starbridge.devices.refresh");
                return;
            }
            if (!hasButton(chart, "nav.starbridge.devices.current")) return;
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
            var key = throttle(mc);
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) helm.mouseClicked(0, 0, key.getValue());
            else helm.keyPressed(key.getValue(), 0, 0);
            log("manual thrust in graphical helm");
            last = t;
            step = 2;
            return;
        }
        if (inHelm
                && step == 2
                && Files.exists(directory.resolve("mcp-denied.ok"))
                && Files.exists(directory.resolve("controls-lua-denied.ok"))) {
            var key = throttle(mc);
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) helm.mouseReleased(0, 0, key.getValue());
            else helm.keyReleased(key.getValue(), 0, 0);
            if (click(helm, "orbit.starbridge.cruise")) step = 3;
        }
    }
}
