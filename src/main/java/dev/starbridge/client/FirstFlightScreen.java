package dev.starbridge.client;

import dev.starbridge.Starbridge;
import dev.starbridge.client.shipyard.LaunchPadPlan;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.ShapedRecipe;
import java.util.EnumMap;

/** The unpaired slate is the first in-game entry to both the native shipyard and the helm. */
public final class FirstFlightScreen extends Screen {
    private static final int PANEL = 0xff142837, EDGE = 0xff365566, TEXT = 0xffdbe9e9, AQUA = 0xff74dec7;
    private static final int AMBER = 0xffffc683;
    private enum Page { LINK, SHIPYARD, RECIPE }
    private Page page = Page.LINK;
    private final Screen previous;
    private final EnumMap<LaunchPadPlan, ItemStack> dockItems = new EnumMap<>(LaunchPadPlan.class);
    private boolean sideView;
    private int left, top, panelWidth, panelHeight;

    private FirstFlightScreen(Screen previous) {
        super(label("title"));
        this.previous = previous;
        for (var part : LaunchPadPlan.values()) dockItems.put(part, part.item());
    }

    public static void open() {
        open(null);
    }

    static void open(Screen previous) {
        Minecraft.getInstance().setScreen(new FirstFlightScreen(previous));
    }

    @Override public void onClose() {
        minecraft.setScreen(previous);
    }

    private static Component label(String name, Object... args) {
        return Component.translatable("slate.starbridge.guide." + name, args);
    }

    @Override protected void init() {
        panelWidth = Math.min(340, width - 10);
        panelHeight = Math.min(page == Page.SHIPYARD ? 212 : 192, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int tabWidth = (panelWidth - 28) / 3;
        tab(Page.SHIPYARD, "shipyard", left + 10, tabWidth);
        tab(Page.RECIPE, "recipe", left + 14 + tabWidth, tabWidth);
        tab(Page.LINK, "binding", left + 18 + 2 * tabWidth, tabWidth);
        if (page == Page.SHIPYARD) {
            int half = (panelWidth / 2 - 15) / 2;
            viewButton(false, "plan", left + 10, half);
            viewButton(true, "elevation", left + 12 + half, half);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + (panelWidth - 84) / 2, top + panelHeight - 26, 84, 18).build());
    }

    private void tab(Page next, String name, int x, int tabWidth) {
        addRenderableWidget(Button.builder(label(name), b -> {
            page = next;
            rebuildWidgets();
        }).bounds(x, top + 25, tabWidth, 19).build()).active = page != next;
    }

    private void viewButton(boolean side, String key, int x, int buttonWidth) {
        addRenderableWidget(Button.builder(label(key), b -> {
            sideView = side;
            rebuildWidgets();
        }).bounds(x, top + 49, buttonWidth, 17).build()).active = sideView != side;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        graphics.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        graphics.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        ItemStack hovered = switch (page) {
            case RECIPE -> drawRecipe(graphics, mouseX, mouseY);
            case SHIPYARD -> drawShipyard(graphics, mouseX, mouseY);
            case LINK -> drawBinding(graphics, mouseX, mouseY);
        };
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hovered.isEmpty()) graphics.renderTooltip(font, hovered, mouseX, mouseY);
    }

    private ItemStack drawBinding(GuiGraphics graphics, int mouseX, int mouseY) {
        ItemStack[] items = {new ItemStack(VoyageModule.SLATE.get()), new ItemStack(Starbridge.SHIP_CORE.get()), new ItemStack(Items.COMPASS)};
        String[] names = {"slate", "core", "chart"};
        int inner = panelWidth - 20, y = top + 51;
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < items.length; i++) {
            int cx = left + 10 + inner * (2 * i + 1) / 6;
            if (i > 0) graphics.drawCenteredString(font, "→", left + 10 + inner * i / 3, y + 6, AQUA);
            graphics.renderOutline(cx - 12, y - 2, 24, 24, items[i].isEmpty() ? AMBER : EDGE);
            if (items[i].isEmpty()) graphics.drawCenteredString(font, "?", cx, y + 5, AMBER);
            else graphics.renderItem(items[i], cx - 8, y + 2);
            graphics.drawCenteredString(font, label(names[i]), cx, y + 26, TEXT);
            if (i < 2 && mouseX >= cx - 12 && mouseX < cx + 12 && mouseY >= y - 2 && mouseY < y + 22) hovered = items[i];
        }
        graphics.drawWordWrap(font, label("bind_help", minecraft.options.keyUse.getTranslatedKeyMessage()),
                left + 13, y + 43, inner - 6, TEXT);
        return hovered;
    }

    private ItemStack drawShipyard(GuiGraphics graphics, int mouseX, int mouseY) {
        int rows = sideView ? LaunchPadPlan.HEIGHT : LaunchPadPlan.WIDTH;
        int helpY = top + panelHeight - 61;
        int cell = Math.max(1, Math.min(16, (helpY - top - 76) / rows));
        int startX = left + (panelWidth / 2 - LaunchPadPlan.WIDTH * cell) / 2;
        int startY = top + 70;
        ItemStack hovered = ItemStack.EMPTY;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < LaunchPadPlan.WIDTH; col++) {
                int x = startX + col * cell, y = startY + row * cell;
                var part = LaunchPadPlan.visibleCell(sideView, col, row);
                graphics.renderOutline(x, y, cell, cell, part == null ? EDGE : part.color);
                if (part == null) continue;
                ItemStack item = dockItems.get(part);
                if (item.isEmpty()) graphics.drawCenteredString(font, "?", x + cell / 2, y + 2, AMBER);
                else {
                    var pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(x + 1, y + 1, 0);
                    float scale = (cell - 2) / 16f;
                    pose.scale(scale, scale, 1);
                    graphics.renderItem(item, 0, 0);
                    pose.popPose();
                }
                if (mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell) hovered = item;
            }
        }
        int legendX = left + panelWidth / 2 + 5;
        graphics.drawString(font, label("yard_example"), legendX, top + 52, AQUA, false);
        int legendY = startY;
        for (var part : LaunchPadPlan.values()) {
            var item = dockItems.get(part);
            if (slot(graphics, item, legendX, legendY, mouseX, mouseY, part.color)) hovered = item;
            graphics.drawString(font, label("material", label(part.label), part.count), legendX + 25, legendY + 6, TEXT, false);
            legendY += 23;
        }
        boolean missing = dockItems.values().stream().anyMatch(ItemStack::isEmpty);
        Component help = missing ? label("missing") : label(sideView ? "elevation_help" : "plan_help",
                minecraft.options.keyUse.getTranslatedKeyMessage());
        graphics.drawWordWrap(font, help, left + 13, helpY + 2, panelWidth - 26, missing ? AMBER : TEXT);
        return hovered;
    }

    /** Show the actual recipe synchronized by the server, including modpack ingredient changes. */
    private ItemStack drawRecipe(GuiGraphics graphics, int mouseX, int mouseY) {
        var level = minecraft.level;
        var recipe = level == null ? null : level.getRecipeManager()
                .byKey(new ResourceLocation(Starbridge.ID, "ship_core")).orElse(null);
        if (!(recipe instanceof ShapedRecipe shaped) || shaped.getWidth() > 3 || shaped.getHeight() > 3) {
            graphics.drawWordWrap(font, label("recipe_unavailable"), left + 14, top + 59, panelWidth - 28, AMBER);
            return ItemStack.EMPTY;
        }
        int startX = left + (panelWidth - 124) / 2, startY = top + 54;
        ItemStack hovered = ItemStack.EMPTY;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                ItemStack item = ItemStack.EMPTY;
                if (col < shaped.getWidth() && row < shaped.getHeight()) {
                    var alternatives = shaped.getIngredients().get(row * shaped.getWidth() + col).getItems();
                    if (alternatives.length > 0) item = alternatives[(int) Math.floorMod(level.getGameTime() / 30, (long) alternatives.length)];
                }
                if (slot(graphics, item, startX + col * 21, startY + row * 21, mouseX, mouseY, EDGE)) hovered = item;
            }
        }
        graphics.drawCenteredString(font, "→", startX + 79, startY + 25, AQUA);
        ItemStack output = shaped.getResultItem(level.registryAccess());
        if (slot(graphics, output, startX + 100, startY + 21, mouseX, mouseY, AQUA)) hovered = output;
        graphics.drawWordWrap(font, label("recipe_help"), left + 13, startY + 69, panelWidth - 26, TEXT);
        return hovered;
    }

    private boolean slot(GuiGraphics graphics, ItemStack stack, int x, int y, int mouseX, int mouseY, int border) {
        graphics.renderOutline(x, y, 20, 20, border);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x + 2, y + 2);
            graphics.renderItemDecorations(font, stack, x + 2, y + 2);
        }
        return mouseX >= x && mouseY >= y && mouseX < x + 20 && mouseY < y + 20;
    }

    @Override public boolean isPauseScreen() { return false; }
}
