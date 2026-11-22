package dev.starbridge.client.survey;

import dev.starbridge.survey.FieldNotes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Opens the held terminal's field map from the current page. */
public final class SurveyButton extends Button {
    private static final Component TITLE = Component.translatable("slate.starbridge.field.title");
    private static final Component SHORT_TITLE = Component.translatable("slate.starbridge.field.tab");
    private static int sequence;
    private final ItemStack icon = new ItemStack(Items.MAP);
    private final Tooltip shortcut = Tooltip.create(Component.translatable("slate.starbridge.field.shortcut",
            Component.keybind("key.sneak"), Component.keybind("key.use")));
    private final Tooltip needsTerminal = Tooltip.create(Component.translatable("slate.starbridge.field.hold"));
    private int request;
    private InteractionHand hand;

    public SurveyButton(int x, int y, int width, int height) {
        super(x, y, width, height, TITLE, button -> ((SurveyButton) button).open(), DEFAULT_NARRATION);
        updateHand();
    }

    private void updateHand() {
        var player = Minecraft.getInstance().player;
        var held = player == null ? null : FieldNotes.held(player);
        if (hand != held) request = 0;
        hand = held;
        active = hand != null;
        setTooltip(active ? shortcut : needsTerminal);
    }

    private void open() {
        updateHand();
        if (!active) return;
        request = ++sequence;
        FieldNotes.askOpen(hand, request);
    }

    static boolean requestedBy(Screen parent, FieldNotes.View view) {
        for (var child : parent.children()) {
            if (!(child instanceof SurveyButton button)) continue;
            button.updateHand();
            if (button.hand == view.hand() && button.request != 0
                    && (view.menuRequest() == button.request || view.menuRequest() == 0)) {
                button.request = 0;
                return true;
            }
        }
        return false;
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateHand();
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderString(GuiGraphics graphics, Font font, int color) {
        var label = font.width(TITLE) + 24 <= width ? TITLE : SHORT_TITLE;
        int margin = Math.max(3, (width - font.width(label) - 18) / 2);
        graphics.renderItem(icon, getX() + margin, getY() + (height - 16) / 2);
        renderScrollingString(graphics, font, label, getX() + margin + 18, getY(), getX() + width - margin, getY() + height, color);
    }
}
