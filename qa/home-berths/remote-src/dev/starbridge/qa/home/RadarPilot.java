package dev.starbridge.qa.home;

import com.mojang.blaze3d.platform.NativeImage;
import dev.starbridge.orbit.OrbitWire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Clicks only ordinary helm controls. The reflection reads which incoming Frame was actually
 * painted.
 */
final class RadarPilot {
    private enum Step {
        OPEN,
        FIRST,
        SECOND,
        REORDERED,
        PICKED,
        CYCLED,
        LOST,
        RECOVERED,
        DONE
    }

    private static final String PREFIX = "orbit.starbridge.radar.";
    private static Step step = Step.OPEN;
    private static final Map<String, Object> frames = new LinkedHashMap<>();
    private static final List<Object> actions = new ArrayList<>();
    private static Map<UUID, List<Integer>> painted = Map.of();
    private static boolean captured;

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static List<UUID> ids(OrbitWire.Frame frame) {
        return frame.contacts().stream().map(OrbitWire.Contact::core).toList();
    }

    private static void button(Screen screen, String key, String expected) {
        var control = RemoteHomePilot.button(screen, PREFIX + key);
        RemoteHomeWorld.require(
                control != null
                        && control.active
                        && control.getMessage().getString().equals(expected),
                "Visible radar button: " + expected);
        RemoteHomePilot.at(
                screen,
                control.getX() + control.getWidth() / 2d,
                control.getY() + control.getHeight() / 2d);
        actions.add(Map.of("action", key, "label", expected));
    }

    private static void pick(Screen screen, UUID id) {
        var gui = painted.get(id);
        var fb = Minecraft.getInstance().getMainRenderTarget();
        int x = (int) Math.floor((gui.get(0) + .5) * fb.width / screen.width),
                y = (int) Math.floor((gui.get(1) + .5) * fb.height / screen.height);
        RemoteHomePilot.at(
                screen, (x + .5) * screen.width / fb.width, (y + .5) * screen.height / fb.height);
        actions.add(Map.of("action", "echo", "id", id, "pixel", List.of(x, y), "fromFrame", tag()));
    }

    private static String tag() {
        return "radar-" + step.name().toLowerCase(Locale.ROOT);
    }

    private static void next(Step target) {
        step = target;
        captured = false;
    }

    static boolean drive(Screen screen) throws Exception {
        var frame = (OrbitWire.Frame) field(screen, "frame");
        if (frame == null) return false;
        RemoteHomeWorld.require(
                frame.target().equals("home"), "Radar must preserve the return destination");
        if (step == Step.OPEN) {
            if (!ids(frame).equals(List.of(RadarTraffic.A, RadarTraffic.B))) return false;
            button(screen, "toggle", "舰船雷达 · 2");
            next(Step.FIRST);
            return false;
        }
        if (!captured) return step == Step.DONE;
        switch (step) {
            case FIRST -> {
                pick(screen, RadarTraffic.B);
                next(Step.SECOND);
            }
            case SECOND -> {
                RadarTraffic.requested = 1;
                next(Step.REORDERED);
            }
            case REORDERED -> {
                pick(screen, RadarTraffic.A);
                next(Step.PICKED);
            }
            case PICKED -> {
                button(screen, "next", "下一艘");
                next(Step.CYCLED);
            }
            case CYCLED -> {
                RadarTraffic.requested = 2;
                next(Step.LOST);
            }
            case LOST -> {
                button(screen, "next", "下一艘");
                next(Step.RECOVERED);
            }
            case RECOVERED -> {
                button(screen, "toggle", "舰船雷达 · 1");
                RadarTraffic.requested = 3;
                RemoteHomeWorld.write(
                        "radar-client.json",
                        Map.of("language", "zh_cn", "actions", actions, "frames", frames));
                next(Step.DONE);
                return true;
            }
            default -> {}
        }
        return false;
    }

    /**
     * Use the incoming positions and the ship heading, independently of TrafficScope's hit test.
     */
    private static List<Integer> plot(
            OrbitWire.Frame f, OrbitWire.Contact c, int cx, int cy, int radius) {
        var d = c.position().subtract(f.position());
        double yaw = Math.toRadians(f.yaw());
        double side = Math.cos(yaw) * d.x() + Math.sin(yaw) * d.z(),
                depth = -Math.sin(yaw) * d.x() + Math.cos(yaw) * d.z();
        double h = Math.hypot(side, depth), scale = radius * Math.sqrt(Math.min(1, h / 5000d)) / h;
        return List.of(cx + (int) Math.round(scale * side), cy + (int) Math.round(-scale * depth));
    }

    private static int rgb(NativeImage img, Screen screen, int x, int y) {
        int packed =
                img.getPixelRGBA(
                        (int) ((x + .5) * img.getWidth() / screen.width),
                        (int) ((y + .5) * img.getHeight() / screen.height));
        return (packed & 255) << 16 | (packed & 0xff00) | (packed >>> 16) & 255;
    }

    static void rendered(Screen screen, GuiGraphics graphics) throws Exception {
        if (captured
                || step == Step.OPEN
                || step == Step.DONE
                || !(boolean) field(screen, "radarOpen")) return;
        var frame = (OrbitWire.Frame) field(screen, "frame");
        var expected =
                switch (step) {
                    case FIRST, SECOND -> List.of(RadarTraffic.A, RadarTraffic.B);
                    case REORDERED, PICKED, CYCLED -> List.of(RadarTraffic.B, RadarTraffic.A);
                    default -> List.of(RadarTraffic.A);
                };
        if (!ids(frame).equals(expected)) return;
        Object scope = field(screen, "radar");
        UUID selected = (UUID) field(scope, "selected");
        UUID wanted =
                switch (step) {
                    case FIRST, PICKED, RECOVERED -> RadarTraffic.A;
                    default -> RadarTraffic.B;
                };
        RemoteHomeWorld.require(
                wanted.equals(selected) && frame.target().equals("home"),
                "Lock and navigation target in " + step);
        var caption = (Component) field(scope, "lastLabel");
        String name = "飞船 " + wanted.toString().substring(0, 6);
        RemoteHomeWorld.require(
                caption != null && caption.getString().equals(name),
                "Last visible callsign in " + step);
        boolean lost = step == Step.LOST;
        int cx = (int) field(scope, "cx"),
                cy = (int) field(scope, "cy"),
                radius = (int) field(scope, "radius");
        var dots = new LinkedHashMap<UUID, List<Integer>>();
        var signals = new ArrayList<Object>();
        graphics.flush();
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            for (var c : frame.contacts()) {
                var dot = plot(frame, c, cx, cy, radius);
                dots.put(c.core(), dot);
                int x = dot.get(0), y = dot.get(1);
                RemoteHomeWorld.require(
                        rgb(image, screen, x, y) == 0x9bbef7,
                        "Unobscured painted radar signal at " + dot);
                boolean tracked = c.core().equals(selected);
                RemoteHomeWorld.require(
                        (rgb(image, screen, x - 4, y - 4) == 0xffc882) == tracked,
                        "Amber outline on actual tracked echo");
                signals.add(
                        Map.of(
                                "id",
                                c.core(),
                                "position",
                                c.position(),
                                "velocity",
                                c.velocity(),
                                "captain",
                                c.captain(),
                                "dot",
                                dot));
            }
            var dir = Path.of("screenshots");
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(tag() + ".png"));
            var row = new LinkedHashMap<String, Object>();
            row.put("target", frame.target());
            row.put("core", frame.core());
            row.put("ship", frame.position());
            row.put("velocity", frame.velocity());
            row.put("yaw", frame.yaw());
            row.put("contacts", signals);
            row.put("selected", selected);
            row.put("callsign", caption.getString());
            row.put(
                    "label",
                    lost
                            ? Component.translatable(PREFIX + "lost").getString()
                            : caption.getString());
            row.put("scope", List.of(cx, cy, radius));
            row.put("gui", List.of(screen.width, screen.height));
            row.put("framebuffer", List.of(image.getWidth(), image.getHeight()));
            frames.put(tag(), row);
        }
        RemoteHomeWorld.write(
                "radar-client.json",
                Map.of(
                        "language",
                        "zh_cn",
                        "lastRendered",
                        tag(),
                        "actions",
                        actions,
                        "frames",
                        frames));
        painted = dots;
        captured = true;
    }
}
