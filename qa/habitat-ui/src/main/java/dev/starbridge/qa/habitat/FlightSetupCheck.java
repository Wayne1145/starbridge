package dev.starbridge.qa.habitat;

import com.google.gson.JsonObject;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.setup.FlightInstallScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.client.gui.widget.ModListWidget;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercise the public Forge mod menu with the pack installed, and with the modern main JAR alone. */
@Mod.EventBusSubscriber(modid = "starbridgehabitatqa", value = Dist.CLIENT)
public final class FlightSetupCheck {
    private static final Path MODE = Path.of("starbridge-setup.fixture");
    private static final Path RESULT = Path.of("flight-setup-result.json");
    private static final JsonObject report = new JsonObject();
    private static int frames, step, runtimeStep;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || !Files.isRegularFile(MODE) || Files.exists(RESULT)) return;
        Minecraft game = Minecraft.getInstance();
        if (game.getOverlay() != null || ++frames % 10 != 0) return;
        try {
            advance(game, Files.readString(MODE).trim());
        } catch (Exception | AssertionError failure) {
            report.addProperty("pass", false);
            report.addProperty("error", "Step " + step + ": " + failure);
            Files.writeString(RESULT, report.toString());
        }
    }

    private static void advance(Minecraft game, String mode) throws Exception {
        Screen page = game.screen;
        switch (step) {
            case 0 -> {
                if (!(page instanceof TitleScreen)) return;
                click(page, "fml.menu.mods");
            }
            case 1 -> {
                require(page instanceof ModListScreen, "Open mod list");
                EditBox search = page.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
                page.mouseClicked(search.getX() + 4, search.getY() + 4, 0);
                for (char c : "Starbridge /".toCharArray()) search.charTyped(c, 0);
            }
            case 2 -> {
                var list = page.children().stream().filter(ModListWidget.class::isInstance).map(ModListWidget.class::cast).findFirst().orElseThrow();
                require(list.children().size() == 1 && list.children().get(0).getInfo().getModId().equals("starbridge"), "Find Starbridge");
                int x = list.getRowLeft() + 12, y = list.getTop() + 12;
                page.mouseClicked(x, y, 0);
                page.mouseReleased(x, y, 0);
            }
            case 3 -> click(page, "fml.menu.mods.config");
            case 4 -> {
                require(page instanceof FlightInstallScreen, "Enter setup from the vanilla title screen via Forge");
                report.addProperty("scenario", mode);
                report.addProperty("inWorld", game.level != null);
                JsonObject required = rows(page);
                report.add("required", required);
                int expectedLoaded = switch (mode) { case "full" -> 8; case "no-dock" -> 7; case "main-only" -> 1; case "no-runtime" -> 4; default -> throw new IllegalStateException(mode); };
                long loaded = required.entrySet().stream().filter(e -> e.getValue().getAsString().equals("loaded")).count();
                require(required.size() == 8 && loaded == expectedLoaded, "Required components: " + required);
                require(required.get("starbridge").getAsString().equals("loaded"), "Main JAR");
                if (mode.equals("no-dock")) require(required.get("dock").getAsString().equals("missing"), "Missing companion");
                if (mode.equals("no-runtime")) {
                    require(required.get("retrofit").getAsString().equals("loaded"), "Modern bridge is present");
                    for (String legacy : new String[]{"dock", "advancedrocketry", "libvulpes", "galacticraft"}) {
                        require(required.get(legacy).getAsString().equals("waiting"), "Legacy runtime stopped: " + legacy);
                    }
                }
                HabitatClient.snap(game, "setup-required");
                String chosen = mode.equals("no-dock") ? "dock" : "galacticraft";
                clickRow(page, chosen);
                report.addProperty("detailOpened", chosen);
            }
            case 5 -> {
                if (mode.equals("no-runtime") || mode.equals("main-only")) {
                    if (runtimeStep == 0) {
                        HabitatClient.snap(game, "setup-original-waiting");
                        click(page, "gui.starbridge.install.runtime");
                        runtimeStep++;
                        return;
                    }
                    if (runtimeStep == 1) {
                        HabitatClient.snap(game, "setup-runtime");
                        click(page, "gui.starbridge.install.back_to");
                        runtimeStep++;
                        return;
                    }
                    require(page.children().stream().anyMatch(w -> w instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents title
                            && title.getKey().equals("gui.starbridge.install.runtime")), "Return to the waiting component");
                    report.addProperty("followedRuntimeAndReturned", true);
                }
                HabitatClient.snap(game, "setup-file");
                click(page, "gui.starbridge.install.source");
            }
            case 6 -> {
                require(page instanceof ConfirmLinkScreen, "Original author link has a confirmation");
                page.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            }
            case 7 -> click(page, "gui.starbridge.install.back");
            case 8 -> click(page, "gui.starbridge.install.optional");
            case 9 -> {
                JsonObject optional = rows(page);
                report.add("optional", optional);
                require(optional.size() == 3, "Optional component group");
                long loaded = optional.entrySet().stream().filter(e -> e.getValue().getAsString().equals("loaded")).count();
                require(loaded == (mode.equals("main-only") ? 0 : mode.equals("no-runtime") ? 1 : 3), "Optional components: " + optional);
                HabitatClient.snap(game, "setup-optional");
                click(page, "gui.done");
            }
            case 10 -> {
                require(page instanceof ModListScreen, "Back to the parent Mods page");
                report.addProperty("returnedToMods", true);
                report.addProperty("pass", true);
                Files.writeString(RESULT, report.toString());
            }
            default -> throw new AssertionError(step);
        }
        step++;
    }

    static final Path SLATE = Path.of("starbridge-setup-slate.fixture");
    private static int slateStep, nextTick;
    static boolean fromSlate(Minecraft game, int tick) throws Exception {
        if (tick < nextTick) return false;
        Screen screen = game.screen;
        switch (slateStep) {
            case 0 -> {
                if (!(screen instanceof FirstFlightScreen)) return false;
                HabitatClient.snap(game, "setup-slate-entry");
                click(screen, "gui.starbridge.install.title");
            }
            case 1 -> {
                require(screen instanceof FlightInstallScreen && rows(screen).get("retrofit").getAsString().equals("missing"), "Open with just the main JAR");
                click(screen, "gui.done");
            }
            case 2 -> click(screen, "slate.starbridge.guide.shipyard");
            case 3 -> {
                require(screen instanceof FirstFlightScreen, "Back in shipyard guide");
                HabitatClient.snap(game, "setup-yard-entry");
                click(screen, "gui.starbridge.install.title");
            }
            case 4 -> {
                require(screen instanceof FlightInstallScreen, "Missing yard can open setup");
                click(screen, "gui.done");
            }
            case 5 -> {
                require(screen instanceof FirstFlightScreen, "Back at shipyard");
                click(screen, "slate.starbridge.guide.elevation");
            }
            case 6 -> {
                try {
                    if (!MissingYardInputs.tick(game, tick)) return false;
                } catch (IllegalStateException | AssertionError failure) {
                    JsonObject result = new JsonObject();
                    result.addProperty("pass", false);
                    result.addProperty("error", failure.toString());
                    result.add("blueprintInteractions", MissingYardInputs.visits());
                    Files.writeString(Path.of("flight-slate-result.json"), result.toString());
                    slateStep++;
                    return false;
                }
                JsonObject result = new JsonObject();
                result.add("blueprintInteractions", MissingYardInputs.visits());
                result.addProperty("gameType", game.gameMode.getPlayerMode().getName());
                result.addProperty("commands", game.getSingleplayerServer().getWorldData().getAllowCommands());
                result.addProperty("heldCraftedSlate", game.player.getMainHandItem().is(dev.starbridge.voyage.VoyageModule.SLATE.get()));
                result.addProperty("openedFromUnlinkedSlateAndMissingYard", true);
                result.addProperty("returnedToYard", true);
                result.addProperty("pass", result.get("heldCraftedSlate").getAsBoolean() && !result.get("commands").getAsBoolean() && result.get("gameType").getAsString().equals("survival"));
                Files.writeString(Path.of("flight-slate-result.json"), result.toString());
            }
            default -> { return false; }
        }
        slateStep++;
        nextTick = tick + 10;
        return false;
    }

    static JsonObject rows(Screen page) {
        var values = new JsonObject();
        for (var widget : page.children()) {
            if (!(widget instanceof Button b) || !(b.getMessage().getContents() instanceof TranslatableContents t)
                    || !t.getKey().equals("gui.starbridge.install.row")) continue;
            String id = key(t.getArgs()[0]), state = key(t.getArgs()[1]);
            values.addProperty(id.substring(id.lastIndexOf('.') + 1), state.substring(state.lastIndexOf('.') + 1));
        }
        return values;
    }

    static void clickRow(Screen page, String id) {
        for (var widget : page.children()) {
            if (widget instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals("gui.starbridge.install.row") && key(t.getArgs()[0]).equals("gui.starbridge.install.name." + id)) {
                int x = b.getX() + b.getWidth() / 2, y = b.getY() + b.getHeight() / 2;
                require(page.mouseClicked(x, y, 0), "Click component " + id);
                page.mouseReleased(x, y, 0);
                return;
            }
        }
        throw new IllegalStateException("No visible component " + id);
    }

    private static String key(Object component) { return ((TranslatableContents)((Component) component).getContents()).getKey(); }
    static void click(Screen page, String key) throws Exception { require(HabitatClient.click(page, key), "Click " + key); }
    static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
