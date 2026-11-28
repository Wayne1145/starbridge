package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.ChartScreen;
import dev.starbridge.client.survey.SurfaceSurveyScreen;
import dev.starbridge.client.survey.SurveyButton;
import dev.starbridge.survey.FieldNotes;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Mouse navigation from the paired helm, including a reply arriving after the helm was closed. */
final class ChartSurveyCheck {
    static final Path MARKER = Path.of("starbridge-chart-survey.fixture");
    private static final String SURVEY = "slate.starbridge.field.title";
    private static int step, next, terminalSlot;
    private static ChartScreen chart;
    private static BlockPos marked;
    private static final LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();

    static boolean tick(Minecraft game, int tick, BlockPos helm) throws Exception {
        if (step == 9) return true;
        if (tick < next) return false;
        var page = game.screen;
        var player = game.player;
        var inventory = player.getInventory();
        switch (step) {
            case 0 -> {
                if (!(page instanceof ChartScreen open) || !HabitatClient.click(open, "route.starbridge.moon")) return false;
                chart = open;
                var server = game.getSingleplayerServer();
                if (server == null || server.getWorldData().getAllowCommands() || player.hasPermissions(2) || player.isCreative())
                    throw new IllegalStateException("Ordinary no-cheat survival needed");
                terminalSlot = inventory.selected;
                evidence.put("allowCommands", false);
                evidence.put("language", game.getLanguageManager().getSelected());
            }
            case 1 -> {
                HabitatClient.snap(game, "helm-survey-entry");
                if (!HabitatClient.click(chart, SURVEY)) return false;
            }
            case 2 -> {
                if (!(page instanceof SurfaceSurveyScreen) || !HabitatClient.click(page, "slate.starbridge.field.mark_ship")) return false;
                marked = player.blockPosition();
            }
            case 3 -> {
                var tag = player.getMainHandItem().getTag();
                if (tag == null || !tag.hasUUID("StarbridgeSurveyId") || !marked.equals(FieldNotes.markedOn(player.getMainHandItem(),
                        tag.getUUID("StarbridgeSurveyId"), game.level.dimension().location(), FieldNotes.Marker.SHIP))) return false;
                HabitatClient.snap(game, "helm-survey-marked");
                if (!HabitatClient.click(page, "gui.done") || game.screen != chart) throw new IllegalStateException("Wrong return page");
                evidence.put("markedAtFeet", marked.toShortString());
                evidence.put("terminal", tag.getUUID("StarbridgeSurveyId").toString());
                evidence.put("returnedToSameChart", true);
            }
            case 4 -> {
                if (!HabitatClient.click(chart, "nav.starbridge.kit.title") || !(game.screen instanceof dev.starbridge.client.LunarKitScreen kit))
                    throw new IllegalStateException("Lunar route was not kept");
                if (!HabitatClient.click(kit, "nav.starbridge.kit.back") || game.screen != chart) throw new IllegalStateException("Wrong chart after EVA kit");
                if (!HabitatClient.click(chart, SURVEY)) return false;
                chart.onClose();
            }
            case 5 -> {
                if (page != null) throw new IllegalStateException("Late field-map reply reopened a dismissed screen");
                evidence.put("dismissedReplyStayedClosed", true);
                int empty = -1;
                for (int i = 0; i < 9; i++) if (inventory.getItem(i).isEmpty()) { empty = i; break; }
                if (empty < 0) throw new IllegalStateException("No empty hotbar slot");
                inventory.selected = empty;
                game.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(helm).add(0,.5,0), Direction.UP, helm, false));
            }
            case 6 -> {
                if (!(page instanceof ChartScreen emptyHanded)) return false;
                var button = emptyHanded.children().stream().filter(SurveyButton.class::isInstance).map(SurveyButton.class::cast).findFirst().orElseThrow();
                if (button.active || button.getTooltip() == null) throw new IllegalStateException("Empty hand must explain the missing terminal");
                var words = new StringBuilder();
                for (var line : button.getTooltip().toCharSequence(game)) line.accept((i, style, code) -> {words.appendCodePoint(code); return true;});
                if (!words.toString().replace(" ", "").equals(Component.translatable("slate.starbridge.field.hold").getString().replace(" ", "")))
                    throw new IllegalStateException("Wrong held-terminal hint: " + words);
                evidence.put("emptyHandHint", words.toString());
                emptyHanded.onClose();
                inventory.selected = terminalSlot;
            }
            case 7 -> {
                if (page != null) return false;
                game.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            case 8 -> {
                if (!(page instanceof ChartScreen)) return false;
                evidence.put("reopenedFromPairedTerminal", true);
                evidence.put("pass", true);
                Files.writeString(Path.of("chart-survey-result.json"), new Gson().toJson(evidence));
            }
        }
        step++;
        next = tick + 14;
        return step == 9;
    }
}
