package dev.starbridge.client.survey;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Two measured profiles at the selected cell, on a common scale. */
public final class SurfaceProfileScreen extends Screen {
    private static final int PANEL = 0xff142837,
            EDGE = 0xff365566,
            TEXT = 0xffdeecec,
            MUTED = 0xff9bb3bd;
    private final SurfaceSurveyScreen map;
    private final SurfaceProfile profile;
    private int left, top, w, h;

    SurfaceProfileScreen(SurfaceSurveyScreen map, SurfaceProfile profile) {
        super(text("title"));
        this.map = map;
        this.profile = profile;
    }

    static Component text(String name, Object... args) {
        return Component.translatable("slate.starbridge.field.profile." + name, args);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(map);
    }

    @Override
    protected void init() {
        w = Math.min(354, width - 8);
        h = Math.min(224, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        int half = (w - 26) / 2;
        addRenderableWidget(
                Button.builder(
                                Component.translatable("slate.starbridge.field.clues.back"),
                                b -> onClose())
                        .bounds(left + 11, top + h - 25, half, 18)
                        .build());
        addRenderableWidget(
                Button.builder(
                                Component.translatable("slate.starbridge.field.sight.inspect"),
                                b -> {
                                    var sight = new SurfaceCluesScreen(map);
                                    minecraft.setScreen(sight);
                                    sight.look();
                                })
                        .bounds(left + 15 + half, top + h - 25, half, 18)
                        .build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + w, top + h, PANEL);
        g.renderOutline(left, top, w, h, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        g.drawCenteredString(
                font, text("position", profile.x, profile.z), width / 2, top + 24, MUTED);
        int plotW = (w - 32) / 2, plotH = Math.min(58, Math.max(22, (h - 110) / 2)), y = top + 55;
        drawAxis(g, left + 12, y, plotW, plotH, profile.eastWest, "east_west");
        drawAxis(g, left + 19 + plotW, y, plotW, plotH, profile.northSouth, "north_south");
        int next = y + plotH + 8;
        if (profile.shape != SurfaceProfile.Shape.UNSURVEYED) {
            g.drawString(
                    font,
                    text("reading", profile.depth, profile.eastWidth, profile.northWidth),
                    left + 12,
                    next,
                    TEXT,
                    false);
            next += 15;
        }
        g.drawWordWrap(
                font,
                text(profile.shape.name().toLowerCase(java.util.Locale.ROOT)),
                left + 12,
                next,
                w - 24,
                profile.shape == SurfaceProfile.Shape.NARROW ? SurfaceCluesScreen.SHAFT : MUTED);
        super.render(g, mx, my, partialTick);
    }

    private void drawAxis(
            GuiGraphics g, int x, int y, int width, int height, int[] line, String axis) {
        g.drawCenteredString(font, text(axis), x + width / 2, y - 13, TEXT);
        g.fill(x, y, x + width, y + height, 0xff0c1b24);
        int low = profile.bottom - 1, span = Math.max(6, profile.top + 1 - low);
        for (int i = 0; i < line.length; i++) {
            int start = x + 1 + (width - 2) * i / line.length;
            int end = x + 1 + (width - 2) * (i + 1) / line.length;
            if (line[i] == SurfaceProfile.UNKNOWN) continue;
            int surface = y + height - 2 - (height - 4) * (line[i] - low) / span;
            g.fill(start, surface, end, y + height - 1, 0xff465e68);
            g.fill(
                    start,
                    surface,
                    end,
                    surface + 2,
                    i == SurfaceProfile.RADIUS ? SurfaceCluesScreen.SHAFT : 0xff80beaf);
            if (i == SurfaceProfile.RADIUS)
                g.vLine((start + end) / 2, y + 1, surface - 1, 0xff83784c);
        }
        g.renderOutline(x, y, width, height, EDGE);
    }
}
