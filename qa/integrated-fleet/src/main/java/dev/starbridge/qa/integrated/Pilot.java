package dev.starbridge.qa.integrated;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgeintegratedqa", value = Dist.CLIENT)
public final class Pilot {
    static volatile String phase = "empty-world";
    static boolean started, moon, returned, waiting, opened, selected;
    static int clock, stageTick, next;
    static BlockPos bridge;

    static void event(String kind) throws Exception {
        Files.writeString(
                Path.of("input.csv"),
                System.nanoTime() + "," + clock + "," + kind + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static void phase(String name) throws Exception {
        if (!name.equals(phase)) {
            phase = name;
            stageTick = clock;
            event(name);
            if (LanCrew.HOST) IntegratedFlight.write("lan-phase.json", name);
        }
    }

    static boolean click(Screen sc, String key) throws Exception {
        var label = Component.translatable(key).getString();
        for (var widget : sc.children())
            if (widget instanceof Button b
                    && b.active
                    && b.visible
                    && b.getMessage().getString().equals(label)) {
                double x = b.getX() + b.getWidth() * .5, y = b.getY() + b.getHeight() * .5;
                boolean used = sc.mouseClicked(x, y, 0);
                sc.mouseReleased(x, y, 0);
                if (used) event("click-" + key);
                return used;
            }
        return false;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (!IntegratedFlight.ENABLED || e.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        ++clock;
        try {
            mc.setWindowActive(true);
            if (IntegratedFlight.failed) {
                Frames.finish();
                mc.stop();
                return;
            }
            if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                started = true;
                var name = "IntegratedOrbit" + IntegratedFlight.LENGTH;
                var settings =
                        new LevelSettings(
                                name,
                                GameType.SURVIVAL,
                                false,
                                Difficulty.NORMAL,
                                false,
                                new GameRules(),
                                WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows()
                        .createFreshLevel(
                                name,
                                settings,
                                new WorldOptions(576618501L, true, false),
                                WorldPresets::createNormalWorldDimensions);
                return;
            }
            if (mc.player == null
                    || mc.level == null
                    || mc.gameMode == null
                    || !IntegratedFlight.ready) return;
            if (bridge == null) {
                var f = Path.of("fleet-flight-start");
                if (!Files.isRegularFile(f)) return;
                var a = Files.readString(f).trim().split(" +");
                bridge =
                        new BlockPos(
                                Integer.parseInt(a[1]),
                                Integer.parseInt(a[2]),
                                Integer.parseInt(a[3]));
                phase("fixed-cabin");
                mc.player.setYRot(0);
                mc.player.setXRot(-5);
                return;
            }
            boolean lunar =
                    mc.level.dimension().location().toString().equals("retrofit:legacy_dim_-28");
            if (lunar && !moon) {
                moon = true;
                waiting = opened = selected = false;
                phase("lunar-deck");
            }
            if (moon && !lunar && !returned) {
                returned = true;
                phase("home-deck");
            }
            if (returned) {
                if (IntegratedFlight.settled && clock - stageTick >= 140) {
                    Frames.finish();
                    Screenshot.grab(
                            mc.gameDirectory,
                            "returned-deck.png",
                            mc.getMainRenderTarget(),
                            msg -> {});
                    event("end-after-home-sample");
                    IntegratedFlight.write(
                            "pilot-result.json",
                            Map.of(
                                    "pass",
                                    true,
                                    "length",
                                    IntegratedFlight.LENGTH,
                                    "frames",
                                    Frames.seq));
                    mc.stop();
                }
                return;
            }
            if (clock < next) return;
            if (mc.screen == null && !selected) {
                if (!moon
                        && phase.equals("fixed-cabin")
                        && clock - stageTick >= 160
                        && mc.player.distanceToSqr(Vec3.atCenterOf(bridge)) < 20) {
                    mc.gameMode.useItemOn(
                            mc.player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    Vec3.atCenterOf(bridge).add(0, 0, .5),
                                    Direction.SOUTH,
                                    bridge,
                                    false));
                    event("used-original-bridge");
                    next = clock + 18;
                }
                if (moon
                        && phase.equals("lunar-deck")
                        && clock - stageTick >= 160
                        && Files.isRegularFile(Path.of("fleet-return-ready"))) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    event("used-paired-terminal");
                    next = clock + 25;
                }
                return;
            }
            if (mc.screen == null) return;
            var sc = mc.screen;
            String screen = sc.getClass().getSimpleName(), direction = moon ? "return" : "outbound";
            if (screen.equals("ChartScreen")) {
                if (selected) {
                    sc.onClose();
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    next = clock + 30;
                    return;
                }
                if (click(sc, "route.starbridge." + (moon ? "home" : "moon"))
                        && click(sc, "nav.starbridge.cruise")) {
                    selected = true;
                    phase(direction + "-space");
                    next = clock + 15;
                }
                return;
            }
            if (!screen.equals("OrbitScreen")) return;
            if (!opened && clock - stageTick >= 160 && click(sc, "orbit.starbridge.landing")) {
                opened = true;
                phase(direction + "-approach");
                return;
            }
            if (opened
                    && !waiting
                    && clock - stageTick >= 65
                    && click(sc, "orbit.starbridge.touchdown")) {
                waiting = true;
                phase(direction + "-handoff");
                next = clock + 80;
                return;
            }
            if (waiting && clock - stageTick > 130 && click(sc, "orbit.starbridge.touchdown"))
                next = clock + 100;
        } catch (Exception | LinkageError ex) {
            IntegratedFlight.fail(ex);
        }
    }
}
