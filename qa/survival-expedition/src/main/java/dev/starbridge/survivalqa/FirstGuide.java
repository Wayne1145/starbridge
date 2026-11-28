package dev.starbridge.survivalqa;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.HitResult;

import java.util.Map;

/** Use the survivor's own terminal and click both views of the first shipyard. */
final class FirstGuide {
    private enum Step { OPEN, INSTALL, BACK_TO_GUIDE, SHIPYARD, ELEVATION, PLAN, CLOSE, FINISHED }

    private static final String SLATE = "starbridge:navigation_slate";
    private static final String GUIDE = "slate.starbridge.guide.";
    private static Step step = Step.OPEN;
    private static int next;
    private static Screen guide;
    private static int required, optional;

    private FirstGuide() {}

    static boolean read(Minecraft game, int tick, boolean checkInstall) {
        if (tick < next) return false;
        Screen screen = game.screen;
        if (screen == null) {
            if (step == Step.FINISHED) {
                Expedition.save("survival-first-guide.json", Map.of("pass", true,
                        "player", game.player.getStringUUID(), "useKey", game.options.keyUse.getTranslatedKeyMessage().getString(),
                        "heldItem", Expedition.item(game.player.getMainHandItem()), "worldTime", game.level.getGameTime(),
                        "checkedInstallation", checkInstall, "requiredLoaded", required, "optionalLoaded", optional,
                        "clicked", checkInstall ? java.util.List.of("install", "gui.done", "shipyard", "elevation", "plan", "gui.done")
                                : java.util.List.of("shipyard", "elevation", "plan", "gui.done")));
                step = Step.OPEN;
                return true;
            }
            step = Step.OPEN;
            if (!ExpeditionClient.select(game, SLATE) || game.player.isShiftKeyDown()) return false;
            game.player.setXRot(-89F);
            if (game.hitResult == null || game.hitResult.getType() != HitResult.Type.MISS) return false;
            KeyMapping.click(game.options.keyUse.getKey());
            Expedition.log("Used owned navigation slate with " + game.options.keyUse.getTranslatedKeyMessage().getString());
            step = checkInstall ? Step.INSTALL : Step.SHIPYARD;
            required = optional = 0;
            next = tick + 15;
            return false;
        }
        if (step == Step.BACK_TO_GUIDE) {
            if (!(screen.getTitle().getContents() instanceof TranslatableContents title)
                    || !title.getKey().equals("gui.starbridge.install.title")) return false;
            required = loaded(screen, "required", 8);
            optional = loaded(screen, "optional", 3);
            snap(game, "survival-guide-install");
            if (!click(screen, "gui.done") || game.screen != guide)
                throw new IllegalStateException("The installation check did not return to the same first-flight guide");
            step = Step.SHIPYARD;
            next = tick + 10;
            return false;
        }
        if (button(screen, GUIDE + "shipyard") == null) {
            screen.onClose();
            return false;
        }
        switch (step) {
            case INSTALL -> {
                guide = screen;
                if (!click(guide, "gui.starbridge.install.title")) return false;
                step = Step.BACK_TO_GUIDE;
            }
            case SHIPYARD -> { if (!click(screen, GUIDE + "shipyard")) return false; step = Step.ELEVATION; }
            case ELEVATION -> {
                if (!selected(screen, "plan")) return false;
                snap(game, "survival-guide-plan");
                if (!click(screen, GUIDE + "elevation")) return false;
                step = Step.PLAN;
            }
            case PLAN -> {
                if (!selected(screen, "elevation")) return false;
                snap(game, "survival-guide-elevation");
                if (!click(screen, GUIDE + "plan")) return false;
                step = Step.CLOSE;
            }
            case CLOSE -> {
                if (!selected(screen, "plan") || !click(screen, "gui.done")) return false;
                step = Step.FINISHED;
            }
            default -> { screen.onClose(); return false; }
        }
        next = tick + 10;
        return false;
    }

    private static int loaded(Screen screen, String group, int expected) {
        Button count = button(screen, "gui.starbridge.install." + group);
        if (count != null && count.getMessage().getContents() instanceof TranslatableContents text) {
            Object[] args = text.getArgs();
            if (args.length == 2 && args[0] instanceof Number present && args[1] instanceof Number total
                    && present.intValue() == expected && total.intValue() == expected) return expected;
        }
        throw new IllegalStateException("Unexpected installed components in the rendered " + group + " tab");
    }

    private static boolean selected(Screen screen, String key) {
        Button choice = button(screen, GUIDE + key);
        return choice != null && !choice.active;
    }

    private static Button button(Screen screen, String name) {
        for (var child : screen.children()) {
            if (child instanceof Button button && button.visible
                    && button.getMessage().getContents() instanceof TranslatableContents label && label.getKey().equals(name)) return button;
        }
        return null;
    }

    private static boolean click(Screen screen, String name) {
        Button button = button(screen, name);
        if (button == null || !button.active) return false;
        int x = button.getX() + button.getWidth() / 2;
        int y = button.getY() + button.getHeight() / 2;
        if (!screen.mouseClicked(x, y, 0)) return false;
        screen.mouseReleased(x, y, 0);
        Expedition.log("Clicked " + name + " in the survival shipyard guide");
        return true;
    }

    private static void snap(Minecraft game, String name) {
        Screenshot.grab(game.gameDirectory, name + ".png", game.getMainRenderTarget(), message -> Expedition.log(message.getString()));
    }
}
