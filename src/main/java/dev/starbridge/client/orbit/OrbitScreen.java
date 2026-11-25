package dev.starbridge.client.orbit;

import static dev.starbridge.client.orbit.OrbitClient.Binding;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.orbit.LandingMap;
import dev.starbridge.orbit.OrbitalFlight;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.opengl.GL11;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Chase camera and approach instrument. Closing this screen immediately returns the captain to
 * their own cabin.
 */
final class OrbitScreen extends Screen {
    private static final int INK = 0xff09141e,
            EDGE = 0xff345368,
            WHITE = 0xffe6f1ef,
            MUTED = 0xff9bb1bf,
            MINT = 0xff83e0cb,
            WARN = 0xffffc882;
    private final UUID ship;
    private final EnumSet<Binding> held = EnumSet.noneOf(Binding.class);
    private final Map<UUID, OrbitWire.Hull> hulls = new HashMap<>();
    private final Map<UUID, HullMesh> meshes = new HashMap<>();
    private OrbitWire.Frame previous, frame;
    private long received;
    private int tick, siteX, siteZ, nextSurvey, lastSurveySent = -100;
    private OrbitWire.Site landingSite;
    private boolean approach, ended, sentMovement, stale, ignoreUntilNeutral;
    private Button modeButton, brakeButton, landButton, radarButton, nextContact;
    private boolean radarOpen;
    private final TrafficScope radar = new TrafficScope();
    private double viewYaw, viewPitch;
    private long lastDraw;

    OrbitScreen(OrbitWire.Frame initial) {
        super(tr("title"));
        ship = initial.core();
        previous = frame = initial;
        viewYaw = initial.yaw();
        viewPitch = initial.pitch();
        received = lastDraw = Util.getMillis();
    }

    UUID ship() {
        return ship;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("orbit.starbridge." + key, args);
    }

    void update(OrbitWire.Frame state) {
        previous = frame;
        frame = state;
        if (radarOpen) radar.acquire(state.contacts());
        if (radarButton != null) radarButton.setMessage(TrafficScope.label("toggle", state.contacts().size()));
        if (nextContact != null) nextContact.active = !state.contacts().isEmpty();
        received = Util.getMillis();
    }

    void site(OrbitWire.Site data) {
        if (approach && ship.equals(data.ship()) && data.x() == siteX && data.z() == siteZ)
            landingSite = data;
    }

    private boolean clearSite() {
        return landingSite != null && landingSite.clearance() == OrbitWire.Clearance.CLEAR;
    }

    void hull(OrbitWire.Hull data) {
        if (!data.ship().equals(ship)
                && !frame.contacts().stream().anyMatch(c -> c.core().equals(data.ship()))) return;
        if (!data.ship().equals(ship) && hulls.size() >= 32) {
            var oldest =
                    hulls.keySet().stream()
                            .filter(
                                    id ->
                                            !id.equals(ship)
                                                    && frame.contacts().stream()
                                                            .noneMatch(c -> c.core().equals(id)))
                            .findFirst();
            if (oldest.isEmpty()) return;
            var old = oldest.get();
            hulls.remove(old);
            var mesh = meshes.remove(old);
            if (mesh != null) mesh.close();
        }
        hulls.put(data.ship(), data);
        var mesh = meshes.remove(data.ship());
        if (mesh != null) mesh.close();
    }

    void reloadModels() {
        meshes.values().forEach(HullMesh::close);
        meshes.clear();
    }

    private HullMesh mesh(UUID id) {
        var data = hulls.get(id);
        return data == null ? null : meshes.computeIfAbsent(id, k -> new HullMesh(data));
    }

    void closedByServer() {
        ended = true;
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        if (!ended) transmit(OrbitWire.Action.LEAVE, 0, 0, 0);
        ended = true;
        held.clear();
        reloadModels();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int gap = 3, w = (width - 12 - 3 * gap) / 4, x = 6, y = height - 24;
        modeButton =
                button(
                        x,
                        y,
                        w,
                        tr(frame.cruise() ? "take" : "cruise"),
                        b ->
                                request(
                                        frame.cruise()
                                                ? OrbitWire.Action.MANUAL
                                                : OrbitWire.Action.CRUISE));
        x += w + gap;
        brakeButton = button(x, y, w, tr("brake"), b -> request(OrbitWire.Action.BRAKE));
        x += w + gap;
        button(x, y, w, tr(approach ? "cockpit" : "landing"), b -> switchView());
        x += w + gap;
        button(x, y, width - x - 6, tr("leave"), b -> onClose())
                .setTooltip(Tooltip.create(tr("leave_tip")));
        if (approach) {
            int panelX = mapLeft() + mapSize() + 10;
            landButton =
                    button(
                            panelX,
                            height - 52,
                            Math.min(105, (width - panelX - 10) * 3 / 5),
                            tr("touchdown"),
                            b -> request(OrbitWire.Action.LAND));
            int abortX = panelX + landButton.getWidth() + 3;
            button(
                            abortX,
                            height - 52,
                            width - abortX - 6,
                            tr("go_around"),
                            b -> {
                                request(OrbitWire.Action.GO_AROUND);
                                approach = false;
                                landingSite = null;
                                rebuildWidgets();
                            })
                    .setTooltip(Tooltip.create(tr("go_around_tip")));
            landButton.active = eligible() && clearSite();
        } else landButton = null;
        radarButton = nextContact = null;
        if (!approach) {
            radar.layout(width, height);
            int radarWidth = Math.min(97, width / 3 - 6);
            radarButton = button((width - radarWidth) / 2, 3, radarWidth,
                    TrafficScope.label("toggle", frame.contacts().size()), b -> {
                        radarOpen = !radarOpen;
                        if (radarOpen) radar.acquire(frame.contacts());
                        rebuildWidgets();
                        setFocused(radarButton);
                    });
            radarButton.setTooltip(Tooltip.create(TrafficScope.label("help")));
            if (radarOpen) {
                nextContact = button(radar.detailsX(), radar.buttonY(), radar.detailsWidth(),
                        TrafficScope.label("next"), b -> radar.next(frame.contacts()));
                nextContact.active = !frame.contacts().isEmpty();
            }
        }
    }

    private Button button(int x, int y, int w, Component label, Button.OnPress action) {
        return addRenderableWidget(Button.builder(label, action).bounds(x, y, w, 18).build());
    }

    private void switchView() {
        approach = !approach;
        landingSite = null;
        if (approach) nextSurvey = tick;
        else transmit(OrbitWire.Action.DISMISS_SURVEY, 0, 0, 0);
        if (sentMovement) transmit(OrbitWire.Action.INPUT, 0, 0, 0);
        sentMovement = false;
        held.removeIf(binding -> binding != Binding.APPROACH);
        rebuildWidgets();
    }

    private void request(OrbitWire.Action action) {
        if (stale || ended) return;
        ignoreUntilNeutral = true;
        sentMovement = false;
        transmit(action, 0, 0, 0);
    }

    private void transmit(OrbitWire.Action action, int thrust, int yaw, int pitch) {
        if (!ended && minecraft != null && minecraft.getConnection() != null)
            OrbitWire.send(new OrbitWire.Control(ship, action, thrust, yaw, pitch, siteX, siteZ));
    }

    private boolean press(InputConstants.Key key) {
        for (var b : Binding.values()) {
            if (!b.mapping.isActiveAndMatches(key)) continue;
            if (held.add(b)) {
                switch (b) {
                    case BRAKE -> request(OrbitWire.Action.BRAKE);
                    case MANUAL -> request(OrbitWire.Action.MANUAL);
                    case CRUISE -> request(OrbitWire.Action.CRUISE);
                    case APPROACH -> switchView();
                    default -> {}
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return press(InputConstants.getKey(key, scan)) || super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        return held.removeIf(b -> b.mapping.matches(key, scan))
                || super.keyReleased(key, scan, modifiers);
    }

    private void selectBerth(int x, int z) {
        if (x == siteX && z == siteZ) return;
        transmit(OrbitWire.Action.DISMISS_SURVEY, 0, 0, 0);
        siteX = x;
        siteZ = z;
        landingSite = null;
        nextSurvey = Math.max(tick + 1, lastSurveySent + 11);
        if (landButton != null) landButton.active = false;
    }

    @Override
    public boolean mouseClicked(double x, double y, int mouse) {
        if (super.mouseClicked(x, y, mouse)) return true;
        if (!approach && radarOpen && mouse == 0 && radar.click(frame, x, y)) return true;
        if (approach && mouse == 0) {
            int left = mapLeft(), top = mapTop(), size = mapSize();
            if (x >= left && x < left + size && y >= top && y < top + size) {
                int center = LandingMap.pixel(0, size);
                if (frame.target().equals("home") && Math.abs(x - left - center) <= 3 && Math.abs(y - top - center) <= 3)
                    selectBerth(0, 0);
                else selectBerth(LandingMap.coordinate(x - left, size), LandingMap.coordinate(y - top, size));
                return true;
            }
        }
        return press(InputConstants.Type.MOUSE.getOrCreate(mouse));
    }

    @Override
    public boolean mouseReleased(double x, double y, int mouse) {
        boolean release = held.removeIf(b -> b.mapping.matchesMouse(mouse));
        return super.mouseReleased(x, y, mouse) || release;
    }

    private int axis(Binding positive, Binding negative) {
        return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0);
    }

    @Override
    public void tick() {
        tick++;
        boolean fresh = Util.getMillis() - received < 1800;
        if (!fresh || minecraft == null || !minecraft.isWindowActive()) {
            if (!stale || sentMovement) {
                transmit(OrbitWire.Action.INPUT, 0, 0, 0);
                sentMovement = false;
            }
            held.clear();
            ignoreUntilNeutral = true;
        }
        stale = !fresh;
        modeButton.active = brakeButton.active = !stale;
        modeButton.setMessage(tr(frame.cruise() ? "take" : "cruise"));
        if (approach && !stale && tick >= nextSurvey) {
            transmit(OrbitWire.Action.SURVEY, 0, 0, 0);
            lastSurveySent = tick;
            nextSurvey = tick + (landingSite == null ? 20 : 90);
        }
        if (landButton != null) landButton.active = !stale && eligible() && clearSite();
        int thrust = axis(Binding.FORWARD, Binding.REVERSE),
                yaw = axis(Binding.RIGHT, Binding.LEFT),
                pitch = axis(Binding.UP, Binding.DOWN);
        // In orbital coordinates increasing yaw points left when looking toward +Z.
        boolean moving = (thrust | yaw | pitch) != 0 && !approach;
        if (!moving) ignoreUntilNeutral = false;
        if (stale || ignoreUntilNeutral || (tick & 1) != 0) return;
        if (moving) transmit(OrbitWire.Action.INPUT, thrust, -yaw, pitch);
        else if (sentMovement) transmit(OrbitWire.Action.INPUT, 0, 0, 0);
        sentMovement = moving;
    }

    private int mapSize() {
        return Math.max(52, Math.min(180, Math.min(width / 2 - 28, height - 93)));
    }

    private int mapLeft() {
        return Math.max(12, (width / 2 - mapSize()) / 2);
    }

    private int mapTop() {
        return (height - 35 - mapSize()) / 2;
    }

    private double distance() {
        return frame.body().subtract(frame.position()).length();
    }

    private boolean eligible() {
        return distance() <= OrbitalFlight.ENTRY
                && frame.velocity().length() <= OrbitalFlight.ENTRY_SPEED;
    }

    private Component targetName() {
        String key = "route.starbridge." + frame.target();
        if (I18n.exists(key)) return Component.translatable(key);
        String id = frame.target().substring(frame.target().lastIndexOf('.') + 1).replace('_', ' ');
        return Component.literal(
                id.isEmpty()
                        ? frame.target()
                        : Character.toUpperCase(id.charAt(0)) + id.substring(1));
    }

    private void text(GuiGraphics g, Component message, int x, int y, int room, int color) {
        g.drawString(
                font,
                font.plainSubstrByWidth(message.getString(), Math.max(0, room)),
                x,
                y,
                color,
                false);
    }

    private int paragraph(
            GuiGraphics g, Component message, int x, int y, int room, int bottom, int color) {
        for (var line : font.split(message, room)) {
            if (y + 9 > bottom) break;
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private void bar(GuiGraphics g, int x, int y, int w, double fraction, int color) {
        g.fill(x, y, x + w, y + 3, EDGE);
        g.fill(x, y, x + (int) Math.round(Mth.clamp(fraction, 0, 1) * w), y + 3, color);
    }

    private void drawSpace(GuiGraphics g) {
        long now = Util.getMillis();
        double part = Mth.clamp((now - received) / 150.0, 0, 1);
        double yaw = previous.yaw() + OrbitalFlight.wrap(frame.yaw() - previous.yaw()) * part;
        double pitch = Mth.lerp(part, previous.pitch(), frame.pitch());
        Vec3d p =
                previous.position()
                        .add(frame.position().subtract(previous.position()).multiply(part));
        double lag = 1 - Math.exp(-Math.min(200, Math.max(0, now - lastDraw)) / 125.0);
        viewYaw += OrbitalFlight.wrap(yaw - viewYaw) * lag;
        viewPitch = Mth.lerp(lag, viewPitch, pitch);
        lastDraw = now;
        var camera = FlightCamera.at(viewYaw, viewPitch);
        int top = 26, bottom = height - 58, cx = width / 2, cy = top + (bottom - top) * 4 / 10;
        double focal = width * .60;
        g.enableScissor(0, top, width, bottom);
        OrbitSky.draw(g, camera, p, width, top, bottom, cx, cy, focal);
        var delta = frame.body().subtract(p);
        var aim = camera.transform(delta);
        int tx = cx, ty = cy;
        boolean visible = false;
        double range = delta.length();
        if (aim.depth() > 1) {
            tx = aim.x(cx, focal);
            ty = aim.y(cy, focal);
            visible = tx >= 14 && tx <= width - 14 && ty >= top + 12 && ty <= bottom - 12;
            int radius =
                    Mth.clamp(
                            (int) (focal * 38 / aim.depth()), 3, Math.max(12, (bottom - top) / 3));
            if (tx > -radius && tx < width + radius && ty > top - radius && ty < bottom + radius)
                OrbitSky.body(g, tx, ty, radius, frame.target());
            if (visible) OrbitSky.marker(g, tx, ty, radius + 3, MINT);
        }
        if (!visible) {
            double x = aim.side(), y = -aim.vertical();
            if (Math.abs(x) + Math.abs(y) < .001) x = 1;
            double factor =
                    Math.min(
                            (width * .46) / Math.max(Math.abs(x), .001),
                            (bottom - top) * .30 / Math.max(Math.abs(y), .001));
            tx = Mth.clamp((int) (cx + x * factor), 12, width - 12);
            ty = Mth.clamp((int) (cy + y * factor), top + 9, bottom - 10);
            OrbitSky.marker(g, tx, ty, 6, WARN);
            text(
                    g,
                    tr(aim.depth() < 0 ? "behind" : "target"),
                    Math.min(width - 58, tx + 8),
                    ty - 3,
                    54,
                    WARN);
        }
        // Occlusion between ships is approximated by drawing far contacts first; every player
        // shares these server-space poses.
        for (int i = frame.contacts().size() - 1; i >= 0; i--) {
            var other = frame.contacts().get(i);
            boolean tracked = radarOpen && other.core().equals(radar.tracked());
            if (i >= 8 && !tracked) continue;
            var relative = other.position().subtract(p);
            var bearing = camera.transform(relative);
            if (bearing.depth() < 4) continue;
            int x = bearing.x(cx, focal), y = bearing.y(cy, focal);
            if (x < 4 || x > width - 4 || y < top || y > bottom) continue;
            float size = (float) Mth.clamp(focal * 12 / bearing.depth(), 3, 48);
            var otherMesh = mesh(other.core());
            if (otherMesh != null && size > 5)
                otherMesh.draw(
                        g,
                        x,
                        y,
                        size,
                        (float) (viewYaw - other.yaw()),
                        (float) (viewPitch - other.pitch()),
                        0);
            OrbitSky.marker(g, x, y, (int) (size / 2 + 2),
                    tracked ? WARN : 0xff9bbef7);
        }
        var own = mesh(ship);
        int shipY = top + (bottom - top) * 65 / 100;
        if (own != null)
            own.draw(
                    g,
                    cx,
                    shipY,
                    Math.min(width * .34f, (bottom - top) * .53f),
                    (float) (viewYaw - yaw + 8),
                    -24 + (float) (viewPitch - pitch),
                    (float) OrbitalFlight.wrap(viewYaw - yaw) * 1.3f);
        else g.drawCenteredString(font, tr("survey"), cx, shipY, MUTED);
        g.disableScissor();
        g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        int navWidth = Math.max(62, width / 3 - 14), y = height - 54;
        text(
                g,
                tr("speed", String.format(Locale.ROOT, "%.1f", frame.velocity().length() * 20)),
                8,
                y,
                navWidth,
                WHITE);
        bar(g, 8, y + 12, navWidth, frame.velocity().length() / 17.0, MINT);
        text(g, tr("distance", Math.round(range)), width / 3 + 4, y, navWidth, WHITE);
        bar(
                g,
                width / 3 + 4,
                y + 12,
                navWidth,
                Math.min(1, OrbitalFlight.ENTRY / Math.max(1, range)),
                eligible() ? MINT : WARN);
        int usable = Math.max(0, frame.fuel() - frame.reserve());
        text(g, tr("engine", usable), 2 * width / 3 + 4, y, navWidth, usable == 0 ? WARN : WHITE);
        bar(
                g,
                2 * width / 3 + 4,
                y + 12,
                navWidth,
                usable / (double) Math.max(1, frame.fuel()),
                MINT);
    }

    private void drawApproach(GuiGraphics g) {
        int x = mapLeft(),
                y = mapTop(),
                size = mapSize(),
                panel = x + size + 10,
                room = width - panel - 8;
        g.fill(x, y, x + size, y + size, 0xff102738);
        for (int i = 1; i < 8; i++) {
            int a = i * (size - 1) / 8;
            g.vLine(x + a, y, y + size - 1, 0xff284451);
            g.hLine(x, x + size - 1, y + a, 0xff284451);
        }
        g.renderOutline(x, y, size, size, EDGE);
        int sx = x + LandingMap.pixel(siteX, size), sz = y + LandingMap.pixel(siteZ, size);
        boolean home = frame.target().equals("home");
        boolean recorded = siteX == 0 && siteZ == 0;
        if (home) {
            int center = LandingMap.pixel(0, size);
            g.renderOutline(x + center - 3, y + center - 3, 7, 7, WHITE);
        }
        var state = landingSite == null ? OrbitWire.Clearance.PENDING : landingSite.clearance();
        int tone = switch (state) {
            case CLEAR -> MINT;
            case PENDING, BUSY -> MUTED;
            default -> WARN;
        };
        g.hLine(x, x + size - 1, sz, EDGE);
        g.vLine(sx, y, y + size - 1, EDGE);
        // Keep the complete symbol visible even when the selected coordinate is on the map edge.
        int mx = Mth.clamp(sx, x + 5, x + size - 6);
        int mz = Mth.clamp(sz, y + 5, y + size - 6);
        switch (state) {
            case CLEAR -> OrbitSky.marker(g, mx, mz, 5, tone);
            case PENDING, BUSY -> {
                for (int offset = -3; offset <= 3; offset += 3)
                    g.fill(mx + offset, mz, mx + offset + 2, mz + 2, tone);
            }
            default -> {
                for (int offset = -4; offset <= 4; offset++) {
                    g.fill(mx + offset, mz + offset, mx + offset + 2, mz + offset + 2, tone);
                    g.fill(mx + offset, mz - offset, mx + offset + 2, mz - offset + 2, tone);
                }
            }
        }
        text(g, tr("north"), x + 3, y + 3, size - 8, MUTED);
        Component selected = home && recorded ? tr("home_port") : tr(home ? "home_offset" : "coords", siteX, siteZ);
        text(g, selected, x, y + size + 3, size, tone);
        int bottom = height - 55;
        int detail = Math.max(24, Math.min(64, Math.min(room / 2 - 1, (bottom - 36) / 2)));
        drawTerrain(g, panel, 32, detail);
        int right = panel + detail + 5;
        int textRoom = width - right - 6;
        int row = 32;
        row =
                paragraph(
                                g,
                                tr("site_" + state.name().toLowerCase(Locale.ROOT)),
                                right,
                                row,
                                textRoom,
                                bottom,
                                tone)
                        + 3;
        if (landingSite != null) {
            row =
                    paragraph(
                                    g,
                                    tr(
                                            "hull_size",
                                            landingSite.width(),
                                            landingSite.height(),
                                            landingSite.depth()),
                                    right,
                                    row,
                                    textRoom,
                                    bottom,
                                    WHITE)
                            + 3;
            if (state == OrbitWire.Clearance.CLEAR)
                paragraph(
                        g,
                        tr("surface_level", landingSite.floor(), landingSite.relief()),
                        right,
                        row,
                        textRoom,
                        bottom,
                        MUTED);
            else if (state == OrbitWire.Clearance.OCCUPIED
                    || state == OrbitWire.Clearance.LIQUID
                    || state == OrbitWire.Clearance.BORDER)
                paragraph(
                        g,
                        tr("coords", landingSite.markX(), landingSite.markZ()),
                        right,
                        row,
                        textRoom,
                        bottom,
                        WARN);
        }
        String hint = home ? "home_tip" : "site_tip";
        if (state == OrbitWire.Clearance.OCCUPIED)
            hint = home ? "home_occupied_tip" : "occupied_tip";
        row = 38 + detail;
        row =
                paragraph(
                                g,
                                tr(hint),
                                panel,
                                row,
                                room,
                                bottom,
                                WHITE)
                        + 3;
        row =
                paragraph(
                                g,
                                tr(
                                        "entry_range",
                                        (int) Math.ceil(distance()),
                                        (int) OrbitalFlight.ENTRY),
                                panel,
                                row,
                                room,
                                bottom,
                                distance() <= OrbitalFlight.ENTRY ? MINT : WARN)
                        + 2;
        row = paragraph(
                g,
                tr(
                        "entry_speed",
                        (int) Math.ceil(frame.velocity().length() * 20),
                        Math.round(OrbitalFlight.ENTRY_SPEED * 20)),
                panel,
                row,
                room,
                bottom,
                frame.velocity().length() <= OrbitalFlight.ENTRY_SPEED ? MINT : WARN);
        if (frame.reserve() > 0 && row + font.lineHeight + 7 < bottom) {
            int color = frame.fuel() >= frame.reserve() ? MINT : WARN;
            row = paragraph(g, tr("landing_reserve", frame.fuel(), frame.reserve()),
                    panel, row + 4, room, bottom - 5, color);
            if (row + 4 < bottom) {
                bar(g, panel, row + 1, Math.min(room, 120), frame.fuel() / (double) frame.reserve(), color);
            }
        }
    }

    private void drawTerrain(GuiGraphics g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, 0xff15242e);
        var site = landingSite;
        if (site == null) {
            g.renderOutline(x, y, size, size, EDGE);
            return;
        }
        int grid = OrbitWire.Site.GRID;
        for (int b = 0; b < grid; ++b)
            for (int a = 0; a < grid; ++a) {
                int cell = site.cells()[b * grid + a];
                int color =
                        switch (cell) {
                            case 0 -> 0xff253444;
                            case 1 -> 0xff121925;
                            case 2 -> 0xff2e75ab;
                            default -> (0xff000000
                                    | (43 + cell * 7) << 16
                                    | (58 + cell * 7) << 8
                                    | (57 + cell * 6));
                        };
                g.fill(
                        x + a * size / grid,
                        y + b * size / grid,
                        x + (a + 1) * size / grid,
                        y + (b + 1) * size / grid,
                        color);
            }
        int minX = site.centerX() - site.width() / 2, minZ = site.centerZ() - site.depth() / 2;
        int fx = x + (minX - site.mapMinX()) * size / site.spanX(),
                fz = y + (minZ - site.mapMinZ()) * size / site.spanZ();
        int w = Math.max(2, site.width() * size / site.spanX()),
                d = Math.max(2, site.depth() * size / site.spanZ());
        g.renderOutline(fx, fz, w, d, site.clearance() == OrbitWire.Clearance.CLEAR ? MINT : WHITE);
        if (site.clearance() != OrbitWire.Clearance.CLEAR
                && site.clearance() != OrbitWire.Clearance.PENDING) {
            int hx =
                    Mth.clamp(
                            x + (site.markX() - site.mapMinX()) * size / site.spanX(),
                            x + 2,
                            x + size - 3);
            int hz =
                    Mth.clamp(
                            y + (site.markZ() - site.mapMinZ()) * size / site.spanZ(),
                            y + 2,
                            y + size - 3);
            OrbitSky.marker(g, hx, hz, 3, WARN);
        }
        g.renderOutline(x, y, size, size, EDGE);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, INK);
        if (approach) drawApproach(g);
        else drawSpace(g);
        if (!approach && radarOpen) radar.draw(g, font, frame);
        text(g, targetName(), 8, 8, radarButton == null ? width / 2 - 10 : radarButton.getX() - 12, WHITE);
        var status = tr(stale ? "stale" : frame.cruise() ? "auto_indicator" : "manual_indicator");
        g.drawString(font, status, width - font.width(status) - 8, 8, stale ? WARN : MINT, false);
        g.hLine(0, width - 1, 23, EDGE);
        if (!approach) {
            Component hint =
                    tr(
                            "controls",
                            Binding.FORWARD.mapping.getTranslatedKeyMessage(),
                            Binding.REVERSE.mapping.getTranslatedKeyMessage(),
                            Binding.LEFT.mapping.getTranslatedKeyMessage(),
                            Binding.RIGHT.mapping.getTranslatedKeyMessage(),
                            Binding.UP.mapping.getTranslatedKeyMessage(),
                            Binding.DOWN.mapping.getTranslatedKeyMessage());
            text(
                    g,
                    frame.notice().isBlank() ? hint : Component.translatable(frame.notice()),
                    8,
                    height - 35,
                    width - 16,
                    frame.notice().isBlank() ? MUTED : WARN);
        } else if (!frame.notice().isBlank())
            text(g, Component.translatable(frame.notice()), 8, height - 35, width - 16, WARN);
        super.render(g, mouseX, mouseY, partial);
        if (!approach && radarOpen) radar.tooltip(g, font, frame, mouseX, mouseY);
    }
}
