package dev.starbridge.qa.habitat;

import com.google.gson.Gson;

import dev.starbridge.client.ChartScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Marker for an independent rendering worker with the locked originals installed. */
final class NavigationChartInput {
    static final Path MARKER = Path.of("starbridge-navigation-proof.fixture");
    private static final String PROOF = "nav.starbridge.proof.";
    private static int step, waitUntil;
    private static Screen chart;

    private NavigationChartInput() {}

    static boolean tick(Minecraft game, int tick) throws Exception {
        if (step == 6) return true;
        if (tick < waitUntil) return false;
        Screen screen = game.screen;
        switch (step) {
            case 0 -> {
                if (!(screen instanceof ChartScreen)
                        || !HabitatClient.click(screen, "route.starbridge.mars")) return false;
                chart = screen;
            }
            case 1 -> {
                if (!HabitatClient.click(screen, PROOF + "title")) return false;
            }
            case 2 -> {
                if (!key(screen.getTitle()).equals(PROOF + "title")) return false;
                if (!clickEntry(screen, "rocket", "absent"))
                    throw new IllegalStateException("No inspectable original T2 rocket");
            }
            case 3 -> {
                HabitatClient.snap(game, "navigation-proof-options");
                if (!clickEntry(screen, "schematic", "apply"))
                    throw new IllegalStateException("Missing carried T2 schematic");
            }
            case 4 -> {
                if (entry(screen, "schematic", "known_short") == null) return false;
                HabitatClient.snap(game, "navigation-proof-learned");
                if (!HabitatClient.click(screen, PROOF + "back")) return false;
            }
            case 5 -> {
                if (game.screen != chart
                        || chart.children().stream()
                                .anyMatch(
                                        w ->
                                                w instanceof Button b
                                                        && b.visible
                                                        && key(b.getMessage())
                                                                .equals(PROOF + "title")))
                    throw new IllegalStateException("Did not return to the unlocked Mars route");
                var original = game.player.getInventory().getItem(33);
                if (original.getCount() != 1
                        || !original.hasTag()
                        || original.getTag().getInt("LegacyMeta") != 1)
                    throw new IllegalStateException("Schematic was not preserved");
                Files.writeString(
                        Path.of("navigation-proof-ui.json"),
                        new Gson()
                                .toJson(
                                        Map.of(
                                                "pass",
                                                true,
                                                "clicked",
                                                java.util.List.of(
                                                        "Mars",
                                                        "calibration",
                                                        "absent rocket",
                                                        "carried tier 2 schematic",
                                                        "back"),
                                                "keptSchematic",
                                                true,
                                                "returnedToSameChart",
                                                true)));
            }
        }
        step++;
        waitUntil = tick + 12;
        return step == 6;
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translation
                ? translation.getKey()
                : "";
    }

    private static Button entry(Screen screen, String type, String state) {
        for (var child : screen.children()) {
            if (!(child instanceof Button b)
                    || !b.visible
                    || !(b.getMessage().getContents() instanceof TranslatableContents label)
                    || !label.getKey().equals(PROOF + "entry")) continue;
            var args = label.getArgs();
            if (args[0] instanceof Component name
                    && key(name).equals(PROOF + type)
                    && args[1] instanceof Component offered
                    && key(offered).equals(PROOF + state)) return b;
        }
        return null;
    }

    private static boolean clickEntry(Screen screen, String type, String state) throws Exception {
        Button button = entry(screen, type, state);
        if (button == null) return false;
        int x = button.getX() + button.getWidth() / 2, y = button.getY() + button.getHeight() / 2;
        if (!screen.mouseClicked(x, y, 0)) return false;
        screen.mouseReleased(x, y, 0);
        HabitatClient.log("Clicked navigation " + type + " (" + state + ")");
        return true;
    }
}
