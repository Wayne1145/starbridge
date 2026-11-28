package dev.starbridge.client.survey;

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
    private static final int EXPOSED_MASONRY = 0xffdc99e4;
    private static final int WALKING_EDGE = 0xffe7b48d;
    private final Screen previous;
    private final SurfaceSketch terrain = new SurfaceSketch();
    private FieldNotes.View view;
    private Marker focus = Marker.SHIP, confirming;
    private boolean waiting;
    private int stride = 4, left, top, w, h, mapX, mapY, cell, sideX, centerX, centerZ;
    private Button markShip, markSite, zoomIn, zoomOut, profileButton;
    private int profileColumn, profileRow;
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
            if (!game.level.dimension().location().equals(next.dimension())) return;
            var parent = game.screen;
            if (parent == null && next.menuRequest() != 0) return;
            if (parent != null && !SurveyButton.requestedBy(parent, next)) return;
            game.setScreen(new SurfaceSurveyScreen(parent, next));
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
        profileButton = button(SurfaceProfileScreen.text("title"), left + 12, top + 3, 72,
                b -> openProfile(centerX + profileColumn, centerZ + profileRow));
        profileButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(SurfaceProfileScreen.text("keys")));
        button(label("clues.title"), left + w - 81, top + 3, 69, b -> minecraft.setScreen(new SurfaceCluesScreen(this)));
        markShip = button(label("mark_ship"), sideX, top + 43, sw, b -> mark(Marker.SHIP));
        markSite = button(label("mark_site"), sideX, top + 65, sw, b -> mark(Marker.SITE));
        boolean enabled = view.notice() != FieldNotes.Notice.MOVED && view.notice() != FieldNotes.Notice.CHANGED_WORLD && !waiting;
        markShip.active = enabled; markSite.active = enabled;
        int half = (sw - 4) / 2;
        button(label("ship"), sideX, top + 113, half, b -> { focus = Marker.SHIP; confirming = null; rebuildWidgets(); }).active = view.ship() != null && focus != Marker.SHIP;
        button(label("site"), sideX + half + 4, top + 113, sw - half - 4, b -> { focus = Marker.SITE; confirming = null; rebuildWidgets(); }).active = view.site() != null && focus != Marker.SITE;
        int bottom = top + h - 24;
        int mapWidth = cell * SurfaceSketch.SIDE;
        int zoomWidth = (mapWidth - 4) / 2;
        zoomOut = button(label("zoom_out"), mapX, bottom, zoomWidth, b -> changeScale(stride * 2));
        zoomIn = button(label("zoom_in"), mapX + zoomWidth + 4, bottom, mapWidth - zoomWidth - 4, b -> changeScale(stride / 2));
        updateZoomButtons();
        button(Component.translatable("gui.done"), sideX, bottom, sw, b -> onClose());
        updateTerrain();
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (getFocused() == profileButton && profileButton != null) {
            int dx = key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT ? -1 : key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT ? 1 : 0;
            int dz = key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP ? -1 : key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN ? 1 : 0;
            if (dx != 0 || dz != 0) {
                profileColumn = Mth.clamp(profileColumn + dx, -SurfaceSketch.MID, SurfaceSketch.MID);
                profileRow = Mth.clamp(profileRow + dz, -SurfaceSketch.MID, SurfaceSketch.MID);
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }
    private void openProfile(int x, int z) {
        if (minecraft.level == null) return;
        // Center the transects on the lowest sampled surface in the square the player chose.
        BlockPos point = terrain.exposedMasonry(x, z);
        if (point == null) point = terrain.lowPoint(x, z);
        int sx = point == null ? x * stride : point.getX(), sz = point == null ? z * stride : point.getZ();
        var world = minecraft.level;
        minecraft.setScreen(new SurfaceProfileScreen(this, new SurfaceProfile(sx, sz, (wx, wz) -> SurfaceSketch.read(world, wx, wz))));
    }
    @Override public boolean mouseClicked(double x, double y, int mouse) {
        int size = cell * SurfaceSketch.SIDE;
        if (mouse == 0 && cell > 0 && x >= mapX && x < mapX + size && y >= mapY && y < mapY + size) {
            profileColumn = (int)(x - mapX) / cell - SurfaceSketch.MID;
            profileRow = (int)(y - mapY) / cell - SurfaceSketch.MID;
            openProfile(centerX + profileColumn, centerZ + profileRow);
            return true;
        }
        return super.mouseClicked(x, y, mouse);
    }
    private void changeScale(int next) {
        stride = Mth.clamp(next, 1, 8);
        terrain.clear();
        updateTerrain();
        updateZoomButtons();
    }
    private void updateZoomButtons() {
        zoomIn.active = stride > 1;
        zoomOut.active = stride < 8;
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
            default -> confirming != null ? label("replace", label(key(confirming))) : label(stride == 1 ? "walking_legend" : "legend");
        };
        g.drawWordWrap(font, notice, mapX, top + h - 46, w - 24, confirming != null ? SITE : SOFT);
        super.render(g, mx, my, dt);
        int size = cell * SurfaceSketch.SIDE;
        if (mx >= mapX && my >= mapY && mx < mapX + size && my < mapY + size) {
            int x = centerX + (mx - mapX) / cell - SurfaceSketch.MID;
            int z = centerZ + (my - mapY) / cell - SurfaceSketch.MID;
            int surface = terrain.at(x, z);
            if (surface != SurfaceSketch.UNKNOWN) {
                var height = label("clues.height", surface);
                var exposed = terrain.exposedMasonry(x, z);
                if (exposed != null) {
                    var exposedHeight = label("clues.height", exposed.getY() + 1);
                    g.renderComponentTooltip(font, java.util.List.of(label("exposed_masonry"), exposedHeight), mx, my);
                } else if (terrain.depression(x, z)) {
                    var relief = label(terrain.steep(x, z) ? "clues.steep_reading" : "clues.pit_reading", terrain.rimDrop(x, z));
                    g.renderComponentTooltip(font, java.util.List.of(height, relief, SurfaceProfileScreen.text("click")), mx, my);
                } else g.renderComponentTooltip(font, java.util.List.of(height, SurfaceProfileScreen.text("click")), mx, my);
            }
        }
    }
    private static boolean tallStep(int surface, int neighbour) {
        return neighbour != SurfaceSketch.UNKNOWN && Math.abs(surface - neighbour) >= 2;
    }
    private void map(GuiGraphics g, BlockPos feet) {
        int side = SurfaceSketch.SIDE * cell;
        int cx = mapX + side / 2, cz = mapY + side / 2;
        g.drawString(font, label("north"), mapX, mapY - 12, SOFT, false);
        Component span = label("scale", (SurfaceSketch.SIDE - 1) * stride);
        g.drawString(font, span, mapX + side - font.width(span), mapY - 12, SOFT, false);
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
            if (terrain.steep(x, z)) g.renderOutline(px, py, cell, cell, SurfaceCluesScreen.SHAFT);
            if (stride == 1 && elevation != SurfaceSketch.UNKNOWN) {
                if (col < SurfaceSketch.SIDE - 1 && tallStep(elevation, terrain.at(x + 1, z)))
                    g.vLine(px + cell - 1, py, py + cell - 1, WALKING_EDGE);
                if (row < SurfaceSketch.SIDE - 1 && tallStep(elevation, terrain.at(x, z + 1)))
                    g.hLine(px, px + cell - 1, py + cell - 1, WALKING_EDGE);
            }
            if (terrain.exposedMasonry(x,z) != null) {
                g.fill(px, py, px + cell, py + cell, EXPOSED_MASONRY);
                if (cell > 2) g.fill(px + 1, py + 1, px + cell - 1, py + cell - 1, 0xff733463);
            }
        }
        if (getFocused() == profileButton && profileButton != null) {
            g.renderOutline(mapX + (SurfaceSketch.MID + profileColumn) * cell - 1,
                    mapY + (SurfaceSketch.MID + profileRow) * cell - 1, cell + 2, cell + 2, SurfaceCluesScreen.SHAFT);
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
