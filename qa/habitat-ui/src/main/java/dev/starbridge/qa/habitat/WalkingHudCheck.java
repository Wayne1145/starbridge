package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Look for the actual compass-colored pixels in the client's rendered framebuffer. */
final class WalkingHudCheck {
    private static final Map<String, Object> readings = new LinkedHashMap<>();
    private static int stage, next, slateSlot;
    private static float originalYaw, course;

    static boolean tick(Minecraft game, int tick, BlockPos ship) throws Exception {
        if (stage == 5) return true;
        if (tick < next || game.screen != null) return false;
        var player = game.player;
        switch (stage) {
            case 0 -> {
                originalYaw = player.getYRot();
                slateSlot = player.getInventory().selected;
                course = (float) Math.toDegrees(Math.atan2(player.getX() - ship.getX() - .5, ship.getZ() + .5 - player.getZ()));
                player.setYRot(course - 90);
            }
            case 1 -> {
                var right = capture(game, "right");
                check(right.count() > 12 && right.balance() > 0, "Arrow must be visible and point right");
                player.setYRot(course + 90);
            }
            case 2 -> {
                var left = capture(game, "left");
                check(left.count() > 12 && left.balance() < 0, "Arrow must turn left after a half-turn");
                player.getInventory().selected = (slateSlot + 1) % 9;
            }
            case 3 -> {
                check(capture(game, "holstered").count() == 0, "The compass must hide when the terminal is stowed");
                player.setYRot(originalYaw);
                player.getInventory().selected = slateSlot;
            }
            case 4 -> {
                check(capture(game, "held-again").count() > 12, "Holding the same terminal must restore the compass");
                readings.put("pass", true);
                write();
            }
        }
        stage++;
        next = tick + 14;
        return stage == 5;
    }

    private record Mask(int count, int balance) {}

    private static Mask capture(Minecraft game, String label) throws Exception {
        int count = 0, balance = 0;
        double scale = game.getWindow().getGuiScale();
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            var directory = Path.of("screenshots");
            Files.createDirectories(directory);
            image.writeToFile(directory.resolve("walking-bearing-" + label + ".png"));
            for (int y = 9; y <= 23; y++) for (int x = 9; x <= 23; x++) {
                int pixel = image.getPixelRGBA((int)((x + .5) * scale), (int)((y + .5) * scale));
                if ((pixel & 0xffffff) == 0xc7e076) {
                    count++;
                    balance += x - 16;
                }
            }
        }
        var reading = new Mask(count, balance);
        readings.put(label, Map.of("coloredPixels", count, "horizontalMoment", balance));
        write();
        return reading;
    }

    private static void check(boolean ok, String message) throws Exception {
        if (ok) return;
        readings.put("pass", false);
        readings.put("error", message);
        write();
        throw new IllegalStateException(message);
    }

    private static void write() throws Exception {
        Files.writeString(Path.of("walking-bearing-result.json"), new Gson().toJson(readings));
    }
}
