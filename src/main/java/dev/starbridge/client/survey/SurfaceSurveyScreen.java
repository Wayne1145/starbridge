package dev.starbridge.client.survey;

import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.survey.FieldNotes;
import dev.starbridge.survey.FieldNotes.Marker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** A north-up map and two player-placed bearings, available to every crew member. */
public final class SurfaceSurveyScreen extends Screen {
    private static final int PANEL = 0xff142837, EDGE = 0xff365566, TEXT = 0xffdeecec, SOFT = 0xff9bb3bd;
    private static final int SHIP = 0xff76e0c7, SITE = 0xffffbd78, PIT = 0xffd88963, UNKNOWN = 0xff1c2b38;
    private final Screen previous;
    private final SurfaceSketch terrain = new SurfaceSketch();
    private FieldNotes.View view;
    private Marker focus = Marker.SHIP, confirming;
    private boolean waiting;
    private int stride = 4, left, top, w, h, mapX, mapY, cell, sideX, centerX, centerZ;
    private Button markShip, markSite;
    private SurfaceSurveyScreen(Screen previous, FieldNotes.View data) {
        super(label("title")); this.previous = previous; this.view = data;
        this.focus = WalkingBearing.focusFor(data);
    }
    private static Component label(String key, Object... args) { return Component.translatable("slate.starbridge.field." + key, args); }
    private static String key(Marker mark) { return mark == Marker.SHIP ? "ship" : "site"; }

    public static void receive(FieldNotes.View next) {
        var game = Minecraft.getInstance();
        if (game.level == null) return;
        if (next.notice() == FieldNotes.Notice.OPEN) {
            if (game.level.dimension().location().equals(next.dimension()))
                game.setScreen(new SurfaceSurveyScreen(game.screen instanceof FirstFlightScreen ? game.screen : null, next));
        } else if (game.screen instanceof SurfaceSurveyScreen page && page.view.id().equals(next.id()) && page.view.dimension().equals(next.dimension())) {
            page.view = next; page.confirming = null; page.waiting = false; page.rebuildWidgets();
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        WalkingBearing.follow(view, focus);
        minecraft.setScreen(previous);
    }
    @Override protected void init() {
        w = Math.min(352, width - 8); h = Math.min(219, height - 6);
        left = (width - w) / 2; top = (height - h) / 2;
        cell = Math.max(2, Math.min(4, Math.min((h - 75) / SurfaceSketch.SIDE, (w / 2 - 10) / SurfaceSketch.SIDE)));
        mapX = left + 12; mapY = top + 32; sideX = mapX + cell * SurfaceSketch.SIDE + 13;
        int sw = left + w - sideX - 12;
        markShip = button(label("mark_ship"), sideX, top + 43, sw, b -> mark(Marker.SHIP));
        markSite = button(label("mark_site"), sideX, top + 65, sw, b -> mark(Marker.SITE));
        boolean enabled = view.notice() != FieldNotes.Notice.MOVED && view.notice() != FieldNotes.Notice.CHANGED_WORLD && !waiting;
        markShip.active = enabled; markSite.active = enabled;
        int half = (sw - 4) / 2;
        button(label("ship"), sideX, top + 113, half, b -> { focus = Marker.SHIP; confirming = null; rebuildWidgets(); }).active = view.ship() != null && focus != Marker.SHIP;
        button(label("site"), sideX + half + 4, top + 113, sw - half - 4, b -> { focus = Marker.SITE; confirming = null; rebuildWidgets(); }).active = view.site() != null && focus != Marker.SITE;
        int bottom = top + h - 24;
        button(label("scale", (SurfaceSketch.SIDE - 1) * stride), mapX, bottom, Math.min(141, w / 2 - 12), b -> {
            stride = stride == 4 ? 8 : stride == 8 ? 2 : 4; terrain.clear(); updateTerrain(); rebuildWidgets();
        });
        button(Component.translatable("gui.done"), sideX, bottom, sw, b -> onClose());
        updateTerrain();
    }
    private Button button(Component title, int x, int y, int width, Button.OnPress action) {
        return addRenderableWidget(Button.builder(title, action).bounds(x, y, width, 18).build());
    }
    private void mark(Marker marker) {
        if (waiting || minecraft.player == null) return;
        var old = view.at(marker);
        if (old != null && !old.equals(minecraft.player.blockPosition()) && confirming != marker) {
            confirming = marker;
            markShip.setMessage(label(marker == Marker.SHIP ? "confirm" : "mark_ship"));
            markSite.setMessage(label(marker == Marker.SITE ? "confirm" : "mark_site"));
            return;
        }
        focus = marker; waiting = true; markShip.active = false; markSite.active = false;
        FieldNotes.record(view, marker);
    }
    private void updateTerrain() {
        if (minecraft.player == null || minecraft.level == null) return;
        centerX = Math.floorDiv(minecraft.player.getBlockX(), stride);
        centerZ = Math.floorDiv(minecraft.player.getBlockZ(), stride);
        terrain.tick(minecraft.level, centerX, centerZ, stride);
    }
    @Override public void tick() {
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(view.dimension())) { minecraft.setScreen(null); return; }
        updateTerrain();
    }
    @Override public void render(GuiGraphics g, int mx, int my, float dt) {
        renderBackground(g);
        g.fill(left, top, left + w, top + h, PANEL); g.renderOutline(left, top, w, h, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        if (minecraft.player == null) return;
        var feet = minecraft.player.blockPosition();
        // Submit the contour cells together; an unbatched fill flushes once per cell.
        g.drawManaged(() -> map(g, feet));
        g.drawString(font, label("mark_here"), sideX, top + 30, SOFT, false);
        g.drawString(font, label("bearing"), sideX, top + 98, SOFT, false);
        var target = view.at(focus);
        Component reading = label("unmarked");
        if (target != null) {
            int dx = target.getX() - feet.getX(), dz = target.getZ() - feet.getZ();
            int distance = (int) Math.round(Math.hypot(dx, dz));
            int direction = Math.floorMod((int)Math.round(Math.atan2(dx, -dz) / (Math.PI / 4)), 8);
            reading = distance == 0 ? label("here") : label("course", label("dir." + direction), distance);
        }
        int color = focus == Marker.SHIP ? SHIP : SITE;
        g.drawString(font, reading, sideX, top + 139, color, false);
        if (target != null) g.drawString(font, label("altitude", target.getY() - feet.getY()), sideX, top + 153, SOFT, false);
        Component notice = switch (view.notice()) {
            case MOVED -> label("moved"); case CHANGED_WORLD -> label("world");
            default -> confirming != null ? label("replace", label(key(confirming))) : label("legend");
        };
        g.drawWordWrap(font, notice, mapX, top + h - 46, w - 24, confirming != null ? SITE : SOFT);
        super.render(g, mx, my, dt);
    }
    private void map(GuiGraphics g, BlockPos feet) {
        int side = SurfaceSketch.SIDE * cell;
        int cx = mapX + side / 2, cz = mapY + side / 2;
        g.drawString(font, label("north"), mapX, mapY - 12, SOFT, false);
        for (int row = 0; row < SurfaceSketch.SIDE; row++) for (int col = 0; col < SurfaceSketch.SIDE; col++) {
            int x = centerX + col - SurfaceSketch.MID, z = centerZ + row - SurfaceSketch.MID;
            int elevation = terrain.at(x, z), shade = UNKNOWN;
            if (elevation != SurfaceSketch.UNKNOWN) {
                int band = Mth.clamp((elevation - 40) / 3, -14, 32);
                int north = terrain.at(x, z - 1);
                int light = north == SurfaceSketch.UNKNOWN ? 0 : Mth.clamp(elevation - north, -4, 4) * 3;
                shade = 0xff000000 | ((61 + band + light) << 16) | ((103 + band + light) << 8) | (103 + band + light);
                if (terrain.depression(x, z)) shade = PIT;
            }
            int px=mapX+col*cell, py=mapY+row*cell;
            g.fill(px, py, px+cell, py+cell, shade);
        }
        g.renderOutline(mapX - 1, mapY - 1, side + 2, side + 2, EDGE);
        for (var mark : Marker.values()) {
            var point = view.at(mark);
            if (point == null) continue;
            // Clamp a remote mark along its bearing to the edge, where it still points the way home.
            double dx = (point.getX() - feet.getX()) * (double)cell / stride, dz = (point.getZ() - feet.getZ()) * (double)cell / stride;
            double scale = Math.min(1, (side / 2.0 - 4) / Math.max(1, Math.max(Math.abs(dx), Math.abs(dz))));
            int px = cx + (int)Math.round(dx * scale), pz = cz + (int)Math.round(dz * scale);
            int radius = mark == focus ? 4 : 3;
            g.renderOutline(px-radius, pz-radius, radius*2+1, radius*2+1, mark == Marker.SHIP ? SHIP : SITE);
        }
        g.fill(cx - 2, cz, cx + 3, cz + 1, TEXT); g.fill(cx, cz - 2, cx + 1, cz + 3, TEXT);
        double yaw = Math.toRadians(minecraft.player.getYRot());
        for (int d=3; d<=6; d++) {
            int x=cx+(int)Math.round(-Math.sin(yaw)*d), z=cz+(int)Math.round(Math.cos(yaw)*d);
            g.fill(x,z,x+1,z+1,TEXT);
        }
    }
}
