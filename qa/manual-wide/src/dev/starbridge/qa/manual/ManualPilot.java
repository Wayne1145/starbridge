package dev.starbridge.qa.manual;

import com.google.gson.Gson;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.orbit.OrbitalFlight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Exercise the captain's rendered controls. Only read the latest frame, never change the flight or
 * send control packets.
 */
@Mod.EventBusSubscriber(modid = "starbridgewideqa", value = Dist.CLIENT)
public final class ManualPilot {
    private enum Step {
        TAKE,
        STOP,
        STOPPED,
        TURN,
        ALIGN,
        BURN,
        FAST_TURN,
        RECOVER_COURSE,
        BRAKING,
        CHOOSE_GO_AROUND,
        LEAVING_ATMOSPHERE,
        ARRIVED
    }

    private static final boolean HOST = Files.isRegularFile(Path.of("wide-fleet-host.fixture"));
    private static final boolean BOUNDARY = Boolean.getBoolean("starbridge.qa.manualBoundary");
    private static final Gson JSON = new Gson();
    private static final Set<String> ready = new HashSet<>();
    private static final Map<String, Integer> held = new HashMap<>();
    private static String target = "";
    private static int tick, due;
    private static Step step = Step.TAKE;
    private static boolean pulsing, firstAlignment, changedCourse, aborted;

    private static Object field(Screen screen, String name) throws ReflectiveOperationException {
        Field f = screen.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(screen);
    }

    public static boolean ready(Screen screen) {
        if (!HOST) return true;
        try {
            var f = (OrbitWire.Frame) field(screen, "frame");
            return ready.contains(f.target());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void trace(String action, OrbitWire.Frame f) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("tick", tick);
        row.put("action", action);
        row.put("stage", step.name());
        row.put("target", f.target());
        row.put("position", f.position());
        row.put("distance", f.body().subtract(f.position()).length());
        row.put("speed", f.velocity().length());
        row.put("velocity", f.velocity());
        row.put("yaw", f.yaw());
        row.put("pitch", f.pitch());
        row.put("automatic", f.cruise());
        row.put("held", Map.copyOf(held));
        Files.writeString(
                Path.of("manual-pilot.jsonl"),
                JSON.toJson(row) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static boolean click(Screen screen, String action, OrbitWire.Frame f) throws Exception {
        String label = Component.translatable("orbit.starbridge." + action).getString();
        for (var child : screen.children()) {
            if (child instanceof Button b
                    && b.active
                    && b.visible
                    && b.getMessage().getString().equals(label)) {
                double x = b.getX() + b.getWidth() / 2., y = b.getY() + b.getHeight() / 2.;
                if (screen.mouseClicked(x, y, 0)) {
                    screen.mouseReleased(x, y, 0);
                    trace("click:" + action, f);
                    return true;
                }
            }
        }
        return false;
    }

    private static void press(Screen screen, String name, OrbitWire.Frame f) throws Exception {
        if (held.containsKey(name)) return;
        var binding =
                Arrays.stream(Minecraft.getInstance().options.keyMappings)
                        .filter(k -> k.getName().equals("key.starbridge.orbit." + name))
                        .findFirst()
                        .orElseThrow();
        int key = binding.getKey().getValue();
        if (!screen.keyPressed(key, 0, 0))
            throw new IllegalStateException("Pilot input was not handled: " + name);
        held.put(name, key);
        trace("press:" + name, f);
    }

    private static void release(Screen screen, OrbitWire.Frame f) throws Exception {
        for (var item : Map.copyOf(held).entrySet()) {
            screen.keyReleased(item.getValue(), 0, 0);
            held.remove(item.getKey());
            trace("release:" + item.getKey(), f);
        }
    }

    private static void capture(Minecraft game, String action) {
        Screenshot.grab(
                game.gameDirectory,
                "manual-" + target + "-" + action + ".png",
                game.getMainRenderTarget(),
                message -> {});
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (!HOST || event.phase != TickEvent.Phase.END) return;
        tick++;
        var game = Minecraft.getInstance();
        var screen = game.screen;
        if (screen == null || !screen.getClass().getSimpleName().equals("OrbitScreen")) return;
        OrbitWire.Frame f = (OrbitWire.Frame) field(screen, "frame");
        if (!f.target().equals(target)) {
            target = f.target();
            step = Step.TAKE;
            due = tick;
            held.clear();
            pulsing = false;
            firstAlignment = true;
            trace("opened-third-person", f);
        }
        if (step == Step.ARRIVED) return;
        if ((boolean) field(screen, "approach") && step != Step.CHOOSE_GO_AROUND) return;
        if (tick % 40 == 0) trace("frame", f);
        if (tick < due) return;
        Vec3d delta = f.body().subtract(f.position());
        double distance = delta.length(), speed = f.velocity().length();
        switch (step) {
            case TAKE -> {
                if (f.cruise() && click(screen, "take", f)) {
                    step = Step.STOP;
                    due = tick + 8;
                }
            }
            case STOP -> {
                if (!f.cruise() && click(screen, "brake", f)) {
                    step = Step.STOPPED;
                    due = tick + 8;
                }
            }
            case STOPPED -> {
                if (speed > .05) return;
                capture(game, "helm");
                press(screen, "left", f);
                press(screen, "up", f);
                step = Step.TURN;
                due = tick + 9;
            }
            case TURN -> {
                release(screen, f);
                step = Step.ALIGN;
                due = tick + 12;
            }
            case ALIGN -> {
                if (pulsing) {
                    release(screen, f);
                    pulsing = false;
                    due = tick + 10;
                    return;
                }
                double yaw = OrbitalFlight.wrap(OrbitalFlight.yaw(delta) - f.yaw());
                double pitch = OrbitalFlight.pitch(delta) - f.pitch();
                // After a diversion the bearing need not be an exact multiple of a two-tick key
                // pulse.
                double yawTolerance = changedCourse || aborted ? 2.6 : 1.25;
                double pitchTolerance = changedCourse || aborted ? 2.0 : 1.10;
                if (Math.abs(yaw) > yawTolerance || Math.abs(pitch) > pitchTolerance) {
                    boolean horizontal = Math.abs(yaw) / 2.1 >= Math.abs(pitch) / 1.6;
                    double error = horizontal ? yaw : pitch;
                    press(
                            screen,
                            horizontal
                                    ? (error > 0 ? "left" : "right")
                                    : (error > 0 ? "up" : "down"),
                            f);
                    pulsing = true;
                    due =
                            tick
                                    + Math.max(
                                            2,
                                            Math.min(
                                                    40,
                                                    (int)
                                                            Math.round(
                                                                    Math.abs(error)
                                                                            / (horizontal
                                                                                    ? 2.1
                                                                                    : 1.6))));
                    return;
                }
                trace(firstAlignment ? "reticle-recentered" : "fine-approach", f);
                firstAlignment = false;
                press(screen, "forward", f);
                step = Step.BURN;
                due = tick + 6;
            }
            case BURN -> {
                if (speed >= 14
                        && !Files.exists(
                                Path.of("screenshots", "manual-" + target + "-underway.png")))
                    capture(game, "underway");
                if (BOUNDARY
                        && target.equals("moon")
                        && !changedCourse
                        && speed >= 16
                        && distance < 4500) {
                    trace("course-change-at-speed", f);
                    press(screen, "left", f);
                    press(screen, "up", f);
                    step = Step.FAST_TURN;
                    due = tick + 14;
                    return;
                }
                if (distance > speed * speed / (2 * .32) + 125 + 2 * speed) return;
                release(screen, f);
                if (click(screen, "brake", f)) {
                    step = Step.BRAKING;
                    due = tick + 10;
                }
            }
            case FAST_TURN -> {
                trace("diverted-under-thrust", f);
                capture(game, "turn");
                release(screen, f);
                if (click(screen, "brake", f)) {
                    changedCourse = true;
                    step = Step.RECOVER_COURSE;
                    due = tick + 12;
                }
            }
            case RECOVER_COURSE -> {
                if (speed > .05) return;
                trace("stopped-after-diversion", f);
                step = Step.ALIGN;
                due = tick + 2;
            }
            case BRAKING -> {
                if (speed > .05) return;
                if (distance > OrbitalFlight.ENTRY) {
                    step = Step.ALIGN;
                    due = tick + 2;
                    return;
                }
                if (f.cruise())
                    throw new IllegalStateException("Autopilot resumed during manual approach");
                if (BOUNDARY && target.equals("moon") && !aborted) {
                    if (click(screen, "landing", f)) {
                        step = Step.CHOOSE_GO_AROUND;
                        due = tick + 10;
                    }
                    return;
                }
                trace("stopped-in-entry", f);
                capture(game, "stopped");
                ready.add(target);
                step = Step.ARRIVED;
            }
            case CHOOSE_GO_AROUND -> {
                var button = (Button) field(screen, "landButton");
                if (button == null || !button.active) return;
                trace("safe-berth-before-abort", f);
                capture(game, "clear-before-go-around");
                if (click(screen, "go_around", f)) {
                    aborted = true;
                    step = Step.LEAVING_ATMOSPHERE;
                    due = tick + 15;
                }
            }
            case LEAVING_ATMOSPHERE -> {
                if (distance <= OrbitalFlight.ENTRY + 55 || speed > .05) return;
                trace("outside-atmosphere-after-abort", f);
                capture(game, "outside-after-go-around");
                step = Step.ALIGN;
                due = tick + 5;
            }
            default -> {}
        }
    }
}
