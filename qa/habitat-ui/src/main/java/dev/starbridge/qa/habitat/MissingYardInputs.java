package dev.starbridge.qa.habitat;

import static dev.starbridge.qa.habitat.FlightSetupCheck.click;
import static dev.starbridge.qa.habitat.FlightSetupCheck.require;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.setup.FlightInstallScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import org.lwjgl.glfw.GLFW;

/** Follows actual missing-part hit targets from both drawings and the keyboard legend. */
final class MissingYardInputs {
    private static final String GUIDE = "slate.starbridge.guide.";
    private static final String INSTALL = "gui.starbridge.install.";
    private static final String PROVIDER = "advancedrocketry";

    private record Visit(String view, String part, boolean keyboard, int column, int row) {
        String label() {
            return view + " " + (keyboard ? "keyboard" : "grid") + " " + part;
        }
    }

    private static final Visit[] ROUTES = {
        new Visit("plan", "pad", false, 2, 2),
        new Visit("elevation", "tower", false, 0, 0),
        new Visit("elevation", "assembler", true, 0, 0)
    };

    private enum Phase {
        SELECT_VIEW,
        FOCUS,
        OPEN,
        DETAILS,
        RUNTIME,
        BACK_TO_PLAN,
        VERIFY
    }

    private static Phase phase = Phase.SELECT_VIEW;
    private static final JsonArray completed = new JsonArray();
    private static FirstFlightScreen guide;
    private static String component;
    private static int visit, tabs, next;

    private MissingYardInputs() {}

    static JsonArray visits() {
        return completed;
    }

    static boolean tick(Minecraft game, int tick) throws Exception {
        if (visit == ROUTES.length) return true;
        if (tick < next) return false;
        next = tick + 8;
        Visit route = ROUTES[visit];
        Screen screen = game.screen;
        switch (phase) {
            case SELECT_VIEW -> {
                require(screen instanceof FirstFlightScreen, "Shipyard guide open");
                if (guide == null) guide = (FirstFlightScreen) screen;
                require(screen == guide, "Original guide before selecting view");
                Button button = labelled(screen, GUIDE + route.view());
                if (button.active) click(screen, GUIDE + route.view());
                phase = route.keyboard() ? Phase.FOCUS : Phase.OPEN;
            }
            case FOCUS -> {
                verifyView(screen, route.view());
                if (tabs == 0 || !isPart(guide.getFocused(), route.part())) {
                    require(
                            tabs++ < guide.children().size() * 2,
                            "Tab did not focus the assembler material row");
                    key(game, GLFW.GLFW_KEY_TAB);
                    next = tick + 2;
                    return false;
                }
                require(game.getLastInputType().isKeyboard(), "Keyboard focus is visible");
                HabitatClient.log("Tabbed to assembler material");
                phase = Phase.OPEN;
            }
            case OPEN -> {
                verifyView(screen, route.view());
                HabitatClient.snap(game, "yard-" + route.view() + "-" + route.part());
                if (route.keyboard()) {
                    require(
                            isPart(guide.getFocused(), route.part()),
                            "Enter activates the focused material");
                    key(game, GLFW.GLFW_KEY_ENTER);
                } else {
                    clickCell(route);
                }
                require(
                        game.screen instanceof FlightInstallScreen,
                        "The selected material opened installation information");
                HabitatClient.log("Activated " + route.label());
                phase = Phase.DETAILS;
            }
            case DETAILS -> {
                // The generic overview has neither a named return to the plan nor a runtime link.
                labelled(screen, INSTALL + "back_to_yard");
                HabitatClient.log("Opened component directly for " + route.label());
                if (visit == 0) HabitatClient.snap(game, "yard-advanced-rocketry-file");
                click(screen, INSTALL + "runtime");
                phase = Phase.RUNTIME;
            }
            case RUNTIME -> {
                // The back button's visible label names the component whose file details we opened.
                Button back = labelled(screen, INSTALL + "back_to");
                var text = (TranslatableContents) back.getMessage().getContents();
                require(
                        text.getArgs().length == 1 && text.getArgs()[0] instanceof Component,
                        "Named return from Retrofit");
                component = key((Component) text.getArgs()[0]);
                require(
                        component.equals(INSTALL + "name." + PROVIDER),
                        "The displayed file belongs to Advanced Rocketry");
                click(screen, INSTALL + "back_to");
                phase = Phase.BACK_TO_PLAN;
            }
            case BACK_TO_PLAN -> {
                click(screen, INSTALL + "back_to_yard");
                phase = Phase.VERIFY;
            }
            case VERIFY -> {
                verifyView(screen, route.view());
                JsonObject result = new JsonObject();
                result.addProperty("input", route.keyboard() ? "Tab+Enter" : "grid-click");
                result.addProperty("part", route.part());
                result.addProperty("view", route.view());
                result.addProperty("component", component.substring((INSTALL + "name.").length()));
                result.addProperty("directFromPart", true);
                result.addProperty("returnedToSameGuide", screen == guide);
                if (route.keyboard()) result.addProperty("tabPresses", tabs);
                completed.add(result);
                HabitatClient.log("Returned to " + route.view() + " after " + route.label());
                visit++;
                phase = Phase.SELECT_VIEW;
            }
        }
        return visit == ROUTES.length;
    }

    private static void verifyView(Screen current, String view) {
        require(
                current == guide
                        && !labelled(guide, GUIDE + view).active
                        && labelled(guide, GUIDE + (view.equals("plan") ? "elevation" : "plan"))
                                .active,
                "Preserved shipyard view " + view);
    }

    private static void clickCell(Visit route) {
        // Locate the grid from visible buttons, at the centre pad in plan or the top tower block in
        // elevation.
        Button plan = labelled(guide, GUIDE + "plan");
        Button legend = labelled(guide, "slate.starbridge.materials.title");
        int left = plan.getX() - 10, halfWidth = legend.getX() - left - 5;
        int gridRows = route.view().equals("plan") ? 5 : 6;
        int cell = Math.max(1, Math.min(16, (Math.min(212, guide.height - 8) - 137) / gridRows));
        int x = left + (halfWidth - 5 * cell) / 2 + route.column() * cell + cell / 2;
        int y = plan.getY() + 21 + route.row() * cell + cell / 2;
        require(
                x < legend.getX() && isPart(guide.getChildAt(x, y).orElse(null), route.part()),
                "Visible diagram cell belongs to " + route.part());
        require(guide.mouseClicked(x, y, 0), "Click directly on shipyard drawing");
        guide.mouseReleased(x, y, 0);
    }

    private static void key(Minecraft game, int key) {
        long window = game.getWindow().getWindow();
        game.keyboardHandler.keyPress(window, key, 0, GLFW.GLFW_PRESS, 0);
        game.keyboardHandler.keyPress(window, key, 0, GLFW.GLFW_RELEASE, 0);
    }

    private static String key(Component message) {
        return message.getContents() instanceof TranslatableContents label ? label.getKey() : "";
    }

    private static boolean isPart(GuiEventListener element, String part) {
        if (!(element instanceof Button b)
                || !b.active
                || !b.visible
                || !key(b.getMessage()).equals(INSTALL + "row")) return false;
        var args = ((TranslatableContents) b.getMessage().getContents()).getArgs();
        return args.length == 2
                && args[0] instanceof Component first
                && key(first).equals(GUIDE + part);
    }

    private static Button labelled(Screen screen, String key) {
        return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.visible && key(button.getMessage()).equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing " + key));
    }
}
