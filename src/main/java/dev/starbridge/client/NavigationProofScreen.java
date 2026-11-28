package dev.starbridge.client;

import dev.starbridge.voyage.NavigationCalibration.Entry;
import dev.starbridge.voyage.VoyageNetwork;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;

/** Original schematics and ships for the destination the player selected. */
final class NavigationProofScreen extends Screen {
    private static final int TEXT = 0xffdbe9e9,
            DIM = 0xff9bb3b8,
            GREEN = 0xff74dec7,
            BORDER = 0xff365566;
    private final ChartScreen parent;
    private final VoyageNetwork.Row destination;
    private VoyageNetwork.Chart chart;
    private int left, top, panelWidth, panelHeight, page, rows;
    private List<Entry> choices;
    private Component itemHelp;

    NavigationProofScreen(
            ChartScreen parent, VoyageNetwork.Chart chart, VoyageNetwork.Row destination) {
        super(text("title"));
        this.parent = parent;
        this.chart = chart;
        this.destination = destination;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("nav.starbridge.proof." + key, args);
    }

    boolean update(VoyageNetwork.Chart update) {
        if (!chart.core().equals(update.core())) return false;
        chart = update;
        itemHelp = null;
        parent.updateChart(update);
        rebuildWidgets();
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 8, 346);
        panelHeight = Math.min(height - 6, 220);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rows = Math.max(1, Math.min(4, (panelHeight - 117) / 25));
        int lowest =
                chart.technology().stream()
                        .mapToInt(e -> e.sample().tier())
                        .filter(t -> t >= destination.tier())
                        .min()
                        .orElse(-1);
        choices =
                chart.technology().stream()
                        .filter(
                                e ->
                                        e.sample().tier() == lowest
                                                || e.available()
                                                        && e.sample().tier() >= destination.tier())
                        .sorted(
                                Comparator.comparing((Entry e) -> !e.available())
                                        .thenComparingInt(e -> e.sample().tier()))
                        .toList();
        int pages = Math.max(1, (choices.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < choices.size(); i++)
            addRenderableWidget(new ItemRow(choices.get(page * rows + i), top + 45 + i * 25));
        int y = top + panelHeight - 45;
        nav(
                                Component.literal("‹"),
                                left + 10,
                                y,
                                20,
                                () -> {
                                    page--;
                                    rebuildWidgets();
                                })
                        .active =
                page > 0;
        nav(
                                Component.literal("›"),
                                left + 67,
                                y,
                                20,
                                () -> {
                                    page++;
                                    rebuildWidgets();
                                })
                        .active =
                page + 1 < pages;
        nav(
                text("refresh"),
                left + 94,
                y,
                panelWidth - 104,
                () -> VoyageNetwork.action(chart.core(), VoyageNetwork.Action.REFRESH, "", 0));
        nav(text("back"), left + 10, top + panelHeight - 23, panelWidth - 20, this::onClose);
    }

    private Button nav(Component message, int x, int y, int w, Runnable action) {
        return addRenderableWidget(
                Button.builder(message, b -> action.run()).bounds(x, y, w, 18).build());
    }

    private static Component itemName(Entry entry) {
        return text(
                entry.sample().rocket() ? "rocket" : "schematic",
                entry.sample().tier(),
                entry.sample().stack().getHoverName());
    }

    private Component itemState(Entry entry) {
        if (!entry.available()) return text("absent");
        if (chart.tier() >= destination.tier()) return text("known_short");
        return text(entry.fromWorkbench() ? "apply_workbench" : "apply");
    }

    private final class ItemRow extends Button {
        private final Entry entry;
        private final ItemStack stack;
        private final Component name;

        ItemRow(Entry entry, int y) {
            super(
                    left + 10,
                    y,
                    panelWidth - 20,
                    23,
                    text("entry", itemName(entry), itemState(entry)),
                    b -> select(entry),
                    DEFAULT_NARRATION);
            this.entry = entry;
            this.stack = entry.sample().stack();
            this.name = itemName(entry);
            setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "nav.starbridge.proof.hint", getMessage(), source())));
        }

        private String provider() {
            return switch (entry.sample().item().getNamespace()) {
                case "galacticraftcore", "galacticraftmars" -> "Galacticraft";
                case "galaxyspace" -> "GalaxySpace";
                case "extraplanets" -> "ExtraPlanets";
                default -> entry.sample().item().getNamespace();
            };
        }

        private Component source() {
            return entry.fromWorkbench() ? text("source_workbench", provider()) : Component.literal(provider());
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float dt) {
            int x = getX(), y = getY(), right = x + getWidth();
            g.fill(x, y, right, y + getHeight(), 0xff0d1d28);
            boolean canCalibrate = entry.available() && chart.tier() < destination.tier();
            g.renderOutline(
                    x,
                    y,
                    getWidth(),
                    getHeight(),
                    isHoveredOrFocused() ? TEXT : canCalibrate ? GREEN : BORDER);
            g.renderItem(stack, x + 4, y + 3);
            var state = itemState(entry);
            int side = Math.min(font.width(state) + 12, getWidth() / 3);
            renderScrollingString(g, font, name, x + 24, y + 1, right - side - 3, y + 13, TEXT);
            renderScrollingString(g, font, source(), x + 24, y + 12, right - side - 3, y + 22, DIM);
            renderScrollingString(
                    g,
                    font,
                    state,
                    right - side,
                    y,
                    right - 4,
                    y + getHeight(),
                    canCalibrate ? GREEN : DIM);
        }
    }

    private void select(Entry entry) {
        if (entry.available() && chart.tier() < destination.tier()) {
            VoyageNetwork.calibrate(chart.core(), destination.id(), entry);
            return;
        }
        if (entry.sample().rocket()) itemHelp = text("find_rocket");
        else if (entry.sample().tier() == 4
                && entry.sample().item().getNamespace().equals("extraplanets")
                && chart.ports().stream().anyMatch(row -> row.id().equals("galaxyspace.ceres")))
            itemHelp = text("find_ceres");
        else itemHelp = text("find_schematic");
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xff142837);
        g.renderOutline(left, top, panelWidth, panelHeight, BORDER);
        g.drawCenteredString(font, title, width / 2, top + 8, TEXT);
        g.drawCenteredString(
                font,
                text("route", ChartScreen.display(destination), destination.tier(), chart.tier()),
                width / 2,
                top + 26,
                GREEN);
        Component detail =
                chart.notice().getString().isBlank()
                        ? text(choices.isEmpty() ? "none" : "instructions")
                        : chart.notice();
        if (itemHelp != null) detail = itemHelp;
        g.drawWordWrap(font, detail, left + 12, top + panelHeight - 69, panelWidth - 24, TEXT);
        g.drawCenteredString(
                font,
                (page + 1) + "/" + Math.max(1, (choices.size() + rows - 1) / rows),
                left + 49,
                top + panelHeight - 40,
                DIM);
        super.render(g, mx, my, delta);
    }
}
