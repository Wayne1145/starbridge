package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.PropellantScreen;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.compat.rocketry.OriginalPropellant.Batches;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Follow the two original machines from the refuelling tab, including both real workbench recipe pages. */
final class FuelGuideInputs {
    private static int step, next;
    private static Batches shown;
    private FuelGuideInputs() {}

    static boolean finished(Minecraft game, int ticks) throws Exception {
        if (!Files.isRegularFile(Path.of("starbridge-fuel-guide.fixture")) || step == 8) return true;
        if (ticks < next) return false;
        var screen = game.screen;
        switch (step) {
            case 0 -> { if (!(screen instanceof FirstFlightScreen) || !HabitatClient.click(screen, "slate.starbridge.propellant.title")) return false; }
            case 1 -> {
                if (!(screen instanceof PropellantScreen)) return false;
                var field = PropellantScreen.class.getDeclaredField("batches"); field.setAccessible(true);
                shown = (Batches) field.get(screen);
                if (shown == null) return false;
                if (!shown.equals(new Batches(10,100,100,10,10,20))) throw new IllegalStateException("Unexpected installed original recipes: " + shown);
                HabitatClient.snap(game, "propellant-electrolyser");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.controller")) throw new IllegalStateException("Missing original electrolyser controller item");
            }
            case 2, 5 -> {
                if (!(screen instanceof FabricationScreen)) return false;
                var pages = FabricationScreen.class.getDeclaredField("pages"); pages.setAccessible(true);
                if (((java.util.List<?>) pages.get(screen)).isEmpty()) throw new IllegalStateException("No controller recipe visible");
                HabitatClient.snap(game, step == 2 ? "propellant-electrolyser-controller" : "propellant-reactor-controller");
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_plan")) return false;
            }
            case 3 -> { if (!(screen instanceof PropellantScreen) || !HabitatClient.click(screen, "slate.starbridge.propellant.reactor")) return false; }
            case 4 -> {
                HabitatClient.snap(game, "propellant-reactor");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.controller")) throw new IllegalStateException("Missing original reactor controller item");
            }
            case 6 -> { if (!(screen instanceof PropellantScreen) || !HabitatClient.click(screen, "slate.starbridge.propellant.back")) return false; }
            case 7 -> {
                if (!(screen instanceof FirstFlightScreen)) return false;
                Files.writeString(Path.of("propellant-guide-result.json"), new Gson().toJson(Map.of("pass", true, "batch", shown,
                        "bothOriginalControllerRecipes", true, "returnedToRefuelling", true)));
            }
        }
        step++; next = ticks + 14;
        return step == 8;
    }
}
