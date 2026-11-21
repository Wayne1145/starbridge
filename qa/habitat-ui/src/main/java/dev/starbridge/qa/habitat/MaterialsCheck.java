package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.client.shipyard.LaunchPadPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Use the visible blueprint, ingredient and back buttons in a real client with the original recipes. */
final class MaterialsCheck {
    private static int step, next;
    private static Screen guide;
    private static String rod;
    private MaterialsCheck() {}

    static boolean tick(Minecraft game, int ticks) throws Exception {
        if (step == 6) return true;
        if (ticks < next) return false;
        Screen page = game.screen;
        switch (step) {
            case 0 -> {
                if (!(page instanceof FirstFlightScreen) || !HabitatClient.click(page, "slate.starbridge.materials.title")) return false;
                guide = page;
            }
            case 1 -> {
                if (!(page instanceof FabricationScreen) || ingredients(page).size() != 3) return false;
                HabitatClient.snap(game,"00-materials-launch-pad");
                var name = LaunchPadPlan.TOWER.item().getHoverName().getString();
                var part = page.children().stream().filter(b -> b instanceof Button button && button.getMessage().getString().equals(name))
                        .map(b -> (Button) b).findFirst().orElseThrow();
                click(page, part);
            }
            case 2 -> {
                var slots = ingredients(page);
                if (slots.size() != 7 || slots.stream().map(b -> b.getMessage().getString()).distinct().count() != 1) return false;
                rod = slots.get(0).getMessage().getString();
                HabitatClient.snap(game,"00-materials-tower");
                click(page, slots.get(0));
            }
            case 3 -> {
                if (!(page instanceof FabricationScreen)) return false;
                HabitatClient.snap(game,"00-materials-rod");
                if (!HabitatClient.click(page,"slate.starbridge.materials.back_ingredient")) return false;
            }
            case 4 -> {
                if (ingredients(page).size() != 7 || !HabitatClient.click(page,"slate.starbridge.materials.back_plan")) return false;
            }
            case 5 -> {
                if (game.screen != guide) throw new IllegalStateException("Lost the original launch pad guide");
                var result = Map.of("pass",true,"role","ordinary survival host", "padIngredients",3,"towerIngredients",7,
                        "inspected",rod,"returnsToSamePlan",true,"mainHand",game.player.getMainHandItem().toString());
                Files.writeString(Path.of("materials-ui-result.json"), new Gson().toJson(result));
            }
            default -> throw new IllegalStateException();
        }
        step++; next = ticks + 15;
        return step == 6;
    }

    private static List<Button> ingredients(Screen page) {
        return page.children().stream().filter(b -> b instanceof Button button
                && button.getMessage().getContents() instanceof TranslatableContents label
                && label.getKey().equals("slate.starbridge.materials.inspect")).map(b -> (Button) b).toList();
    }
    private static void click(Screen page, Button button) {
        int x = button.getX() + button.getWidth()/2, y = button.getY() + button.getHeight()/2;
        page.mouseClicked(x, y, 0); page.mouseReleased(x, y, 0);
    }
}
