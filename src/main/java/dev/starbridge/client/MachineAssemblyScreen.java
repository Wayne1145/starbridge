package dev.starbridge.client;

import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Cell;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Role;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Station;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/** Select real parts in the original controller's layers and open the active world's matching recipes. */
public final class MachineAssemblyScreen extends Screen {
    private static final int PANEL = 0xff142837, EDGE = 0xff496775, INK = 0xffdbe9e9, TEAL = 0xff74dec7, AMBER = 0xffffc683;
    private final Screen previous;
    private final OriginalMachineLayout plan;
    private int layer, left, top, panelWidth, split, tileSize, originX, originY;
    private Cell selected;

    public MachineAssemblyScreen(Screen previous, Station station) {
        super(tr(station.title()));
        this.previous = previous;
        this.plan = OriginalMachineLayout.read(station).orElse(null);
        selectLayer(0);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("slate.starbridge.machine." + key, args);
    }

    private static Component role(Cell cell) {
        return tr("role." + cell.role().name().toLowerCase(Locale.ROOT));
    }

    private void selectLayer(int choice) {
        layer = choice;
        selected = null;
        if (plan == null) return;
        for (Cell cell : plan.cells()) {
            if (cell.layer() == layer && (selected == null || cell.role() == Role.CONTROLLER)) selected = cell;
        }
    }

    private Button button(Component text, int x, int y, int width, Button.OnPress action) {
        return addRenderableWidget(Button.builder(text, action).bounds(x, y, width, 18).build());
    }

    @Override protected void init() {
        panelWidth = Math.min(348, width - 8);
        left = (width - panelWidth) / 2;
        top = (height - 204) / 2;
        split = left + panelWidth / 2;
        int x = left + 9, col = (panelWidth - 24) / 3;
        button(tr("part_recipe"), x, top + 178, col, b -> FabricationScreen.open(this, selected.stack())).active = selected != null && selected.role() != Role.CLEAR;
        button(tr("projector_recipe"), x + col + 3, top + 178, col, b -> FabricationScreen.open(this, OriginalMachineLayout.PROJECTOR))
                .active = ForgeRegistries.ITEMS.containsKey(OriginalMachineLayout.PROJECTOR);
        button(tr(previous instanceof PropellantScreen ? "back" : "back_recipe"), x + (col + 3) * 2,
                top + 178, panelWidth - 24 - col * 2, b -> onClose());
        if (plan == null) return;
        tileSize = Math.min(30, Math.min((panelWidth / 2 - 22) / plan.width(), 80 / plan.depth()));
        originX = x + (panelWidth / 2 - 16 - tileSize * plan.width()) / 2;
        originY = top + 69 + (78 - tileSize * plan.depth()) / 2;
        button(tr("lower"), x, top + 31, 25, b -> { selectLayer(layer - 1); rebuildWidgets(); }).active = layer > 0;
        button(tr("higher"), split - 30, top + 31, 25, b -> { selectLayer(layer + 1); rebuildWidgets(); }).active = layer + 1 < plan.height();
        for (Cell cell : plan.cells()) {
            if (cell.layer() != layer) continue;
            ItemStack icon = cell.stack();
            Component name = cell.role() == Role.CLEAR ? role(cell) : icon.getHoverName();
            var widget = new Button(originX + cell.x() * tileSize, originY + (plan.depth() - 1 - cell.z()) * tileSize,
                    tileSize, tileSize, name, b -> { selected = cell; rebuildWidgets(); }, narration -> narration.get()) {
                @Override protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
                    int tx = getX(), ty = getY();
                    g.fill(tx + 1, ty + 1, tx + tileSize - 1, ty + tileSize - 1, 0xff294350);
                    g.renderOutline(tx, ty, tileSize - 1, tileSize - 1, selected == cell || isHoveredOrFocused() ? TEAL : EDGE);
                    if (cell.role() == Role.CLEAR) {
                        g.drawCenteredString(font, "×", tx + tileSize / 2, ty + (tileSize - 8) / 2, AMBER);
                    } else g.renderItem(icon, tx + (tileSize - 16) / 2, ty + (tileSize - 16) / 2);
                }
            };
            widget.setTooltip(Tooltip.create(cell.role() == Role.CLEAR ? name : tr("part", name, role(cell))));
            addRenderableWidget(widget);
        }
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + 204, PANEL);
        g.renderOutline(left, top, panelWidth, 204, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 9, INK);
        if (plan == null) {
            g.drawWordWrap(font, tr("missing"), left + 15, top + 62, panelWidth - 30, AMBER);
        } else {
            g.drawCenteredString(font, tr("layer", layer + 1, plan.height()), (left + split) / 2, top + 36, INK);
            g.drawCenteredString(font, tr("front"), (left + split) / 2, top + 149, TEAL);
            for (int x = 0; x < plan.width(); x++) for (int z = 0; z < plan.depth(); z++)
                g.renderOutline(originX + x * tileSize, originY + z * tileSize, tileSize - 1, tileSize - 1, EDGE);
            if (selected != null) {
                var stack = selected.stack();
                int tx = split + 7, span = left + panelWidth - tx - 12;
                if (selected.role() == Role.CLEAR) {
                    g.drawWordWrap(font, role(selected), tx, top + 53, span, AMBER);
                } else {
                    g.renderItem(stack, tx, top + 52);
                    g.drawWordWrap(font, stack.getHoverName(), tx + 21, top + 53, span - 21, INK);
                    g.drawWordWrap(font, role(selected), tx, top + 78, span, TEAL);
                }
                g.drawWordWrap(font, tr("help"), tx, top + 104, span, INK);
            }
        }
        super.render(g, mx, my, partial);
    }

    @Override public void onClose() { minecraft.setScreen(previous); }
    @Override public boolean isPauseScreen() { return false; }
}
