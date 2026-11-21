package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.survey.SurfaceSurveyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Record actual feet, walk to a second site and re-open the same terminal through its key shortcut. */
final class FieldCheck {
    private static int step, next;
    private static BlockPos ship, site;
    private FieldCheck() {}

    static boolean tick(Minecraft game, int ticks) throws Exception {
        if (step == 13) return true;
        if (ticks < next) return false;
        var page = game.screen;
        switch (step) {
            case 0 -> {
                if (!(page instanceof FirstFlightScreen) || !HabitatClient.click(page,"slate.starbridge.field.title")) return false;
            }
            case 1 -> {
                if (!(page instanceof SurfaceSurveyScreen) || !HabitatClient.click(page,"slate.starbridge.field.mark_ship")) return false;
            }
            case 2 -> {
                ship = mark(game,"SHIP");
                if (ship == null || !ship.equals(game.player.blockPosition())) return false;
                if (!HabitatClient.click(page,"gui.done")) return false;
            }
            case 3 -> {
                if (!(page instanceof FirstFlightScreen) || !HabitatClient.click(page,"gui.done")) return false;
                game.options.keyRight.setDown(true);
            }
            case 4 -> {
                if (page != null || game.player.blockPosition().distManhattan(ship) < 1) return false;
                game.options.keyRight.setDown(false);
                game.options.keyShift.setDown(true);
            }
            case 5 -> {
                if (!game.player.isShiftKeyDown()) return false;
                game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
            }
            case 6 -> {
                if (!(page instanceof SurfaceSurveyScreen)) return false;
                game.options.keyShift.setDown(false);
                if (!HabitatClient.click(page,"slate.starbridge.field.mark_site")) return false;
            }
            case 7 -> {
                site = mark(game,"SITE");
                if (site == null || !site.equals(game.player.blockPosition()) || site.equals(ship)) return false;
                if (!HabitatClient.click(page,"slate.starbridge.field.mark_ship")) return false;
            }
            case 8 -> {
                if (!ship.equals(mark(game,"SHIP")) || !HabitatClient.click(page,"slate.starbridge.field.ship")) return false;
                HabitatClient.snap(game,"00-field-bearing");
            }
            case 9 -> {
                if (!HabitatClient.click(page,"slate.starbridge.field.scale")) return false;
            }
            case 10 -> {
                HabitatClient.snap(game,"00-field-map");
                if (!HabitatClient.click(page,"gui.done")) return false;
                game.options.keyLeft.setDown(true);
            }
            case 11 -> {
                if (page != null || game.player.getBlockX() != ship.getX() || game.player.getBlockZ() != ship.getZ()) return false;
                game.options.keyLeft.setDown(false);
                game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
            }
            case 12 -> {
                if (!(page instanceof FirstFlightScreen) || !ship.equals(mark(game,"SHIP")) || !site.equals(mark(game,"SITE"))) return false;
                Files.writeString(Path.of("field-survey-result.json"), new Gson().toJson(Map.of("pass",true,
                        "dimension",game.level.dimension().location().toString(),"ship",ship.asLong(),"site",site.asLong(),
                        "returnedToShip",game.player.blockPosition().asLong(),"unconfirmedOverwritePreserved",true,
                        "normalUseReturnedToGuide",true,"shortcutOpenedSurvey",true)));
            }
            default -> throw new IllegalStateException();
        }
        step++; next = ticks + (step == 11 ? 1 : 12);
        return step == 13;
    }
    private static BlockPos mark(Minecraft game, String key) {
        var tag = game.player.getMainHandItem().getTagElement("StarbridgeFieldNotes");
        if (tag == null) return null;
        var marks = tag.getCompound(game.level.dimension().location().toString());
        return marks.contains(key) ? BlockPos.of(marks.getLong(key)) : null;
    }
}
