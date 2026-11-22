package dev.starbridge.client.shipyard;

import dev.starbridge.compat.rocketry.ShipyardWire;
import dev.starbridge.compat.rocketry.ShipyardWire.Action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * Each tile represents a real cell of the selected deck. A separate construction diagram is always
 * available.
 */
public final class ShipyardScreen extends Screen {
    private static final int INK = 0xffddebed,
            SOFT = 0xff9bb3b8,
            TEAL = 0xff76daca,
            AMBER = 0xffffc465,
            LINE = 0xff3c5960;
    private ShipyardWire.View view;
    private int left, top, w, h, layer, turn, ticks;
    private boolean guide;
    private Button scan, build, fuel, board, launch, up, down, diagram, rotate;
    private ItemStack hover = ItemStack.EMPTY;
    private boolean hoverGap;

    private enum Repair {
        GUIDANCE("advancedrocketry:tile.guidancecomputer", 2),
        ENGINE("advancedrocketry:rocketmotor", 1),
        TANK("advancedrocketry:fueltank", 3),
        SEAT("advancedrocketry:seat", 3),
        CORE("starbridge:ship_core", 2);

        final ResourceLocation block;
        final int layer;

        Repair(String name, int layer) {
            this.block = new ResourceLocation(name);
            this.layer = layer;
        }
    }

    private Repair suggestedPart() {
        if (view.notice().equals("core")) return Repair.CORE;
        return switch (view.status()) {
            case "NOGUIDANCE" -> Repair.GUIDANCE;
            case "NOENGINES" -> Repair.ENGINE;
            case "NOFUEL" -> Repair.TANK;
            case "NOSEAT" -> Repair.SEAT;
            default -> null;
        };
    }

    private ShipyardScreen(ShipyardWire.View v) {
        super(t("title"));
        view = v;
        guide = !packed() && v.min().equals(v.console());
        if (v.padGap() != null) layer = -1;
    }

    private static Component t(String key, Object... values) {
        return Component.translatable("shipyard.starbridge." + key, values);
    }

    public static void close(java.util.UUID token) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof ShipyardScreen screen && screen.view.token().equals(token))
            mc.setScreen(null);
    }

    int crewAboard() {
        return view.crewCount();
    }

    public static void receive(ShipyardWire.View v) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof ShipyardScreen s && s.view.token().equals(v.token())) {
            if (s.view.padGap() == null && v.padGap() != null && !s.guide) s.layer = -1;
            s.view = v;
            s.sync();
        } else if (v.open()) {
            mc.setScreen(new ShipyardScreen(v));
        }
    }

    private Button button(String label, int x, int y, int wide, Runnable action) {
        return addRenderableWidget(
                Button.builder(t(label), b -> action.run()).bounds(x, y, wide, 17).build());
    }

    @Override
    protected void init() {
        w = Math.min(width - 6, 409);
        h = Math.min(height - 6, 208);
        left = (width - w) / 2;
        top = (height - h) / 2;
        int gutter = 5;
        int col = (w - 2 * gutter - 12) / 5;
        int y = top + h - 21;
        scan =
                button(
                        "scan",
                        left + gutter,
                        y,
                        col,
                        () -> act(view.job().equals("INSPECT") ? Action.RECHECK : Action.SCAN));
        build = button("assemble", left + gutter + col + 3, y, col, () -> act(Action.ASSEMBLE));
        fuel = button("refuel", left + gutter + 2 * (col + 3), y, col, () -> act(Action.FUEL));
        board = button("board", left + gutter + 3 * (col + 3), y, col, () -> act(Action.BOARD));
        launch = button("launch", left + gutter + 4 * (col + 3), y, col, () -> act(Action.LAUNCH));
        int nav = top + h - 43;
        down =
                button(
                        "down",
                        left + 6,
                        nav,
                        24,
                        () -> {
                            layer--;
                            sync();
                        });
        up =
                button(
                        "up",
                        left + 32,
                        nav,
                        24,
                        () -> {
                            layer++;
                            sync();
                        });
        diagram =
                button(
                        "guide",
                        left + 58,
                        nav,
                        Math.min(70, w / 5),
                        () -> {
                            guide = !guide;
                            Repair repair = suggestedPart();
                            if (guide) layer = repair == null ? 0 : repair.layer;
                            else layer = view.padGap() == null ? 0 : -1;
                            sync();
                        });
        rotate = button("rotate", left + w - 61, nav, 55, () -> turn = (turn + 1) % 4);
        sync();
    }

    private boolean packed() {
        return view.job().equals("PACKED");
    }

    private void sync() {
        if (scan == null) return;
        int firstLayer = guide || packed() || view.min().equals(view.console()) ? 0 : -1;
        layer = Math.max(firstLayer, Math.min(layer, guide ? 5 : view.max().getY() - view.min().getY()));
        scan.active = view.canScan() || view.job().equals("INSPECT");
        scan.setMessage(t(view.job().equals("INSPECT") ? "recheck" : "scan"));
        build.active = view.canBuild();
        fuel.active = packed() && view.tank() >= 0 && view.tank() < view.capacity();
        board.active = view.canBoard();
        launch.active = view.canLaunch();
        up.active = layer < (guide ? 5 : view.max().getY() - view.min().getY());
        down.active = layer > firstLayer;
        Repair repair = suggestedPart();
        up.visible = down.visible = rotate.visible = guide || !packed();
        diagram.setMessage(t(guide && packed() ? "preflight" : guide ? "ship" : repair == null ? "guide" : "locate"));
        diagram.setTooltip(
                !guide && repair != null
                        ? net.minecraft.client.gui.components.Tooltip.create(t("locate_hint"))
                        : null);
    }

    private void act(Action a) {
        ShipyardWire.press(view.token(), a);
    }

    @Override
    public void tick() {
        if (++ticks % 10 == 0) act(Action.REFRESH);
    }

    @Override
    public void onClose() {
        act(Action.CLOSE);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void line(GuiGraphics g, Component msg, int x, int y, int max, int color) {
        g.drawString(
                font,
                font.plainSubstrByWidth(msg.getString(), Math.max(1, max)),
                x,
                y,
                color,
                false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hover = ItemStack.EMPTY;
        hoverGap = false;
        g.fill(left, top, left + w, top + h, 0xf4142125);
        g.renderOutline(left, top, w, h, LINE);
        g.drawString(font, title, left + 7, top + 6, INK, false);
        int pane = Math.max(91, w * 49 / 100), x = left + pane + 7, r = left + w - 7, span = r - x;
        g.fill(x - 4, top + 20, x - 3, top + h - 47, LINE);
        g.drawString(
                font,
                !guide && packed() ? t("preflight") : !guide && layer == -1 ? t("pad_floor")
                        : t(guide ? "example_layer" : "live_layer", layer + 1),
                left + 7,
                top + 20,
                TEAL,
                false);
        if (!guide && packed()) {
            preflight(g, left + 9, top + 40, pane - 17);
        } else {
            grid(g, left + 6, top + 33, pane - 10, h - 82, mouseX, mouseY);
        }
        int y = top + 22;
        String status =
                !view.notice().isEmpty()
                        ? "notice." + view.notice()
                        : !view.job().isEmpty()
                                ? "job." + view.job().toLowerCase(Locale.ROOT)
                                : "status." + view.status().toLowerCase(Locale.ROOT);
        Component description = view.padGap() == null ? t(status) : gapDescription();
        for (var part : font.split(description, span).stream().limit(3).toList()) {
            g.drawString(font, part, x, y, AMBER, false);
            y += 10;
        }
        y = Math.max(y + 3, top + 50);
        if (view.progress() >= 0) {
            g.fill(x, y, r, y + 3, LINE);
            g.fill(x, y, x + span * view.progress() / 100, y + 3, TEAL);
            y += 8;
        }
        if (view.energy() >= 0) line(g, t("power", view.energy()), x, y, span, SOFT);
        y += 12;
        if (packed() && (view.tank() < 0 || view.capacity() < 0)) {
            line(g, t("await_carrier"), x, y, span, AMBER);
        } else {
            line(g, t("thrust", view.thrust(), view.weight()), x, y, span, INK);
            y += 10;
            gauge(g, x, y, span, view.thrust(), view.weight());
            y += 8;
            if (packed()) {
                line(g, t("filled_tank", view.tank(), view.capacity()), x, y, span, INK);
                y += 10;
                gauge(g, x, y, span, view.tank(), view.capacity());
                y += 8;
                line(g, t("seated", view.crewCount()), x, y, span, SOFT);
            } else {
                line(g, t("tank", view.capacity(), view.need()), x, y, span, INK);
                y += 10;
                gauge(g, x, y, span, view.capacity(), view.need());
                y += 8;
                if (y < top + h - 57) {
                    line(g, t(view.job().isEmpty() ? "machines" : "fuel_crew",
                            view.job().isEmpty() ? view.machineCount() : view.tank(), view.crewCount()), x, y, span, SOFT);
                }
            }
        }
        super.render(g, mouseX, mouseY, partial);
        if (hoverGap) g.renderTooltip(font, gapDescription(), mouseX, mouseY);
        else if (!hover.isEmpty()) g.renderTooltip(font, hover, mouseX, mouseY);
        else if (mouseX >= x && mouseX < r && mouseY >= top + 22 && mouseY < top + 52)
            g.renderTooltip(font, description, mouseX, mouseY);
    }

    private Component gapDescription() {
        var gap = view.padGap();
        return t("pad_gap_at", gap.getX(), gap.getY(), gap.getZ());
    }

    private void preflight(GuiGraphics graphics, int x, int y, int width) {
        boolean hasFuel = view.tank() > 0;
        boolean aboard = !view.canBoard();
        preflightRow(graphics, x, y, width, "advancedrocketry:item.bucketrocketfuel",
                t(hasFuel ? "fuel_loaded" : "carry_fuel"), hasFuel);
        preflightRow(graphics, x, y + 27, width, "advancedrocketry:seat",
                t(aboard ? "aboard" : "board_empty"), aboard);
        preflightRow(graphics, x, y + 54, width, "advancedrocketry:rocketmotor",
                t("depart"), hasFuel && view.canLaunch());
    }

    private void preflightRow(GuiGraphics g, int x, int y, int width, String id, Component label, boolean ready) {
        int color = ready ? TEAL : SOFT;
        g.renderOutline(x, y, 21, 21, color);
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item != null) g.renderItem(new ItemStack(item), x + 3, y + 2);
        if (ready) g.drawString(font, "\u2713", x + 15, y + 12, TEAL, true);
        g.drawWordWrap(font, label, x + 26, y + 4, Math.max(1, width - 28), color);
    }

    private void gauge(GuiGraphics g, int x, int y, int width, int val, int target) {
        g.fill(x, y, x + width, y + 3, LINE);
        if (target > 0)
            g.fill(
                    x,
                    y,
                    x + (int) (width * Math.min(1, (double) val / target)),
                    y + 3,
                    val < target ? AMBER : TEAL);
    }

    private void grid(GuiGraphics g, int x, int y, int width, int height, int mouseX, int mouseY) {
        int sx = guide ? 5 : view.max().getX() - view.min().getX() + 1,
                sz = guide ? 5 : view.max().getZ() - view.min().getZ() + 1;
        if (sx < 1
                || sx > 300
                || sz < 1
                || sz > 300
                || minecraft == null
                || minecraft.level == null) return;
        Repair repair = guide ? suggestedPart() : null;
        boolean sideways = (turn & 1) != 0;
        int stride =
                Math.max(
                        1,
                        Math.max(
                                (int) Math.ceil((sideways ? sz : sx) * 3.0 / width),
                                (int) Math.ceil((sideways ? sx : sz) * 3.0 / height)));
        int cols = (sx + stride - 1) / stride, rows = (sz + stride - 1) / stride;
        int nx = sideways ? rows : cols, nz = sideways ? cols : rows;
        int cell = Math.max(1, Math.min(21, Math.min(width / nx, height / nz)));
        int ox = x + (width - cell * nx) / 2, oz = y + (height - cell * nz) / 2;
        for (int col = 0; col < cols; col++)
            for (int row = 0; row < rows; row++) {
                int dx = col * stride, dz = row * stride;
                int
                        bx =
                                turn == 0
                                        ? col
                                        : turn == 1
                                                ? rows - 1 - row
                                                : turn == 2 ? cols - 1 - col : row,
                        bz =
                                turn == 0
                                        ? row
                                        : turn == 1
                                                ? col
                                                : turn == 2 ? rows - 1 - row : cols - 1 - col;
                int px = ox + bx * cell, pz = oz + bz * cell;
                var pos = view.min().offset(dx, layer, dz);
                Block block =
                        guide
                                ? example(dx, layer, dz)
                                : minecraft.level.getBlockState(pos).getBlock();
                ItemStack item = block == null ? ItemStack.EMPTY : new ItemStack(block);
                boolean hit =
                        mouseX >= px && mouseX < px + cell && mouseY >= pz && mouseY < pz + cell;
                g.fill(
                        px,
                        pz,
                        px + cell - 1,
                        pz + cell - 1,
                        item.isEmpty() ? 0xff1d3035 : 0xff344c51);
                if (!item.isEmpty()) {
                    float scale = Math.min(1f, (cell - 1) / 16f);
                    g.pose().pushPose();
                    g.pose()
                            .translate(
                                    px + (cell - 16 * scale) / 2, pz + (cell - 16 * scale) / 2, 0);
                    g.pose().scale(scale, scale, 1);
                    g.renderItem(item, 0, 0);
                    g.pose().popPose();
                    if (hit) hover = item;
                }
                if (repair != null
                        && block != null
                        && repair.block.equals(ForgeRegistries.BLOCKS.getKey(block)))
                    g.renderOutline(px, pz, cell, cell, AMBER);
                if (!guide && view.machineCount() > 0 && pos.equals(view.firstMachine()))
                    g.renderOutline(px, pz, cell, cell, TEAL);
                // A long dock uses one screen tile for several blocks. Mark the tile containing
                // the gap even when its sampled corner is a complete pad block.
                var gap = view.padGap();
                if (!guide && layer == -1 && gap != null && gap.getX() - view.min().getX() >= dx
                        && gap.getX() - view.min().getX() < dx + stride
                        && gap.getZ() - view.min().getZ() >= dz
                        && gap.getZ() - view.min().getZ() < dz + stride) {
                    g.fill(px, pz, px + cell, pz + cell, 0xff543b2f);
                    for (int i = 0; i < cell; i++) {
                        g.fill(px + i, pz + i, px + i + 1, pz + i + 1, AMBER);
                        g.fill(px + cell - 1 - i, pz + i, px + cell - i, pz + i + 1, AMBER);
                    }
                    if (hit) hoverGap = true;
                }
            }
    }

    private Block example(int x, int y, int z) { return StarterHullPlan.blockAt(x, y, z); }
}
