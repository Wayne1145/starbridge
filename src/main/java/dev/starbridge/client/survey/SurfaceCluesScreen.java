package dev.starbridge.client.survey;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Cross sections for interpreting only the relief visible from the surface. */
public final class SurfaceCluesScreen extends Screen {
    private static final int PANEL = 0xff142837, EDGE = 0xff365566, TEXT = 0xffdeecec, MUTED = 0xff9bb3bd;
    static final int SHAFT = 0xffedd78d;
    private static final int ROCK = 0xff557780, BASIN = 0xffd88963;
    private final SurfaceSurveyScreen map;
    private ObservedRock observed;
    private int left, top, panelWidth, panelHeight;

    SurfaceCluesScreen(SurfaceSurveyScreen map) { super(label("title")); this.map = map; }
    private static Component label(String key) { return Component.translatable("slate.starbridge.field.clues." + key); }
    void look() { observed = ObservedRock.atCrosshair(minecraft); rebuildWidgets(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(map); }
    @Override protected void init() {
        panelWidth = Math.min(348, width - 12); panelHeight = Math.min(214, height - 8);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        var compare = Component.translatable(observed == null ? "slate.starbridge.field.sight.inspect" : "slate.starbridge.field.clues.title");
        addRenderableWidget(Button.builder(compare, button -> {
            observed = observed == null ? ObservedRock.atCrosshair(minecraft) : null;
            rebuildWidgets();
        }).bounds(left + panelWidth - 93, top + 3, 82, 18).build());
        addRenderableWidget(Button.builder(label("back"), button -> onClose())
                .bounds(left + 12, top + panelHeight - 25, panelWidth - 24, 18).build());
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        g.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        if (observed != null) {
            sight(g, mouseX, mouseY);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        int slot = (panelWidth - 30) / 2;
        g.drawManaged(() -> {
            column(g, left + 12, slot, false);
            column(g, left + 18 + slot, slot, true);
        });
        int advice = top + panelHeight - 66;
        g.hLine(left + 12, left + panelWidth - 13, advice, EDGE);
        g.drawWordWrap(font, label("moon"), left + 13, advice + 7, panelWidth - 26, MUTED);
        super.render(g, mouseX, mouseY, partialTick);
    }
    private void sight(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 16, y = top + 39;
        g.drawCenteredString(font, Component.translatable("slate.starbridge.field.sight.inspect"), width / 2, top + 9, TEXT);
        g.renderOutline(x, y, 50, 50, observed.description().equals("brick") ? SHAFT : EDGE);
        if (!observed.sample().isEmpty()) {
            g.pose().pushPose(); g.pose().translate(x + 9, y + 9, 0); g.pose().scale(2, 2, 1);
            g.renderItem(observed.sample(), 0, 0); g.pose().popPose();
        } else {
            g.hLine(x + 18, x + 31, y + 25, MUTED); g.vLine(x + 25, y + 18, y + 31, MUTED);
        }
        int details = x + 62;
        int available = left + panelWidth - 12 - details;
        if (observed.sample().isEmpty()) {
            g.drawWordWrap(font, observed.title(), details, y + 5, available, TEXT);
        } else {
            var name = observed.sample().getHoverName();
            g.drawString(font, font.plainSubstrByWidth(name.getString(), available), details, y + 3, TEXT, false);
            g.drawWordWrap(font, observed.title(), details, y + 19, available, MUTED);
        }
        if (observed.surface() != null) {
            var range = observed.distance() == 0 ? Component.translatable("slate.starbridge.field.sight.close_range")
                    : Component.translatable("slate.starbridge.field.sight.range", observed.distance());
            g.drawString(font, range, details, y + 40, MUTED, false);
        }
        g.hLine(x, left + panelWidth - 16, y + 63, EDGE);
        var action = Component.translatable("slate.starbridge.field.sight." + (observed.description().equals("brick") ? "mark" : "aim"));
        g.drawWordWrap(font, action, x, y + 77, panelWidth - 32, observed.description().equals("brick") ? SHAFT : TEXT);
        if (!observed.sample().isEmpty() && mouseX >= x && mouseX < x + 50 && mouseY >= y && mouseY < y + 50)
            g.renderTooltip(font, observed.sample(), mouseX, mouseY);
    }
    private void column(GuiGraphics g, int x, int w, boolean opening) {
        int y = top + 29, h = Math.min(54, panelHeight / 4);
        int color = opening ? SHAFT : BASIN;
        g.drawCenteredString(font, label(opening ? "shaft" : "bowl"), x + w / 2, y, color);
        y += 15;
        g.fill(x, y, x + w, y + h, 0xff0c1b24);
        int half = w / 2;
        for (int i = 1; i < w - 1; ++i) {
            double distance = Math.abs(i - half) / (double)half;
            double shape = opening ? Math.max(0, 1 - 8 * distance) : Math.max(0, 1 - 1.55 * distance * distance);
            int surface = y + 7 + (int)((opening ? h - 17 : h * .36) * shape);
            g.fill(x + i, surface, x + i + 1, y + h, ROCK);
            g.fill(x + i, surface, x + i + 1, surface + 1, color);
        }
        g.renderOutline(x, y, w, h, EDGE);
        g.drawWordWrap(font, label(opening ? "shaft_help" : "bowl_help"), x + 2, y + h + 7, w - 4, TEXT);
    }
}
