package dev.starbridge.client.shipyard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Follow a part from the starter blueprint to the ingredients in this world's recipe book. */
public final class FabricationScreen extends Screen {
    private static final int PANEL = 0xff142837, INK = 0xff0c1924, EDGE = 0xff365566;
    private static final int TEXT = 0xffdbe9e9, MUTED = 0xffa2bbc1, READY = 0xff74dec7, MISSING = 0xffffc683;
    private record Selection(ItemStack item, int page) {}
    private final Screen previous;
    private final FabricationRecipes recipes;
    private final List<StarterHullPlan.Part> parts = StarterHullPlan.materials();
    private final Deque<Selection> trail = new ArrayDeque<>();
    private StarterHullPlan.Part root;
    private Selection selection;
    private List<FabricationRecipes.Page> pages;
    private int left, top, panelWidth, panelHeight, gridX, gridY, infoX;
    private ItemStack hovered = ItemStack.EMPTY;

    private FabricationScreen(Screen previous, ResourceLocation focus, FabricationRecipes recipes) {
        super(label("title"));
        this.previous = previous;
        this.recipes = recipes;
        root = parts.stream().filter(part -> part.id().equals(focus)).findFirst().orElseGet(() -> new StarterHullPlan.Part(focus, 1));
        select(new Selection(root.item(), 0));
    }

    public static void open(Screen previous, ResourceLocation focus) {
        var game = Minecraft.getInstance();
        if (game.level != null) game.setScreen(new FabricationScreen(previous, focus, new FabricationRecipes(game.level)));
    }

    private static Component label(String key, Object... args) { return Component.translatable("slate.starbridge.materials." + key, args); }

    private void select(Selection next) {
        selection = next;
        pages = recipes.forStack(next.item());
    }

    private void inspect(ItemStack item) {
        if (item.isEmpty() || ItemStack.isSameItemSameTags(selection.item(), item)) return;
        // A metal block and its ingots can point back to each other. Keep one path to each item.
        var ancestor = trail.stream().filter(s -> ItemStack.isSameItemSameTags(s.item(), item)).findFirst();
        if (ancestor.isPresent()) {
            while (trail.pop() != ancestor.get()) { /* unwind back to the earlier selection */ }
            select(ancestor.get());
            rebuildWidgets();
            return;
        }
        trail.push(selection);
        select(new Selection(item.copyWithCount(1), 0));
        rebuildWidgets();
    }

    @Override public void onClose() {
        if (trail.isEmpty()) minecraft.setScreen(previous);
        else { select(trail.pop()); rebuildWidgets(); }
    }
    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        panelWidth = Math.min(348, width - 8);
        panelHeight = Math.min(208, height - 6);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int step = Math.min(26, (panelWidth - 16) / parts.size());
        int x = left + (panelWidth - step * parts.size()) / 2;
        for (var part : parts) {
            var icon = part.item();
            var button = new IconButton(x, top + 25, step - 2, () -> icon,
                    icon.isEmpty() ? Component.literal(part.id().toString()) : icon.getHoverName(), () -> {
                root = part;
                trail.clear();
                select(new Selection(icon, 0));
                rebuildWidgets();
            }, part.equals(root), part.required());
            addRenderableWidget(button);
            x += step;
        }
        gridX = left + 16;
        gridY = top + 81;
        infoX = gridX + 107;
        if (!pages.isEmpty()) {
            var page = pages.get(selection.page());
            for (int i = 0; i < page.ingredients().size(); i++) {
                var ingredient = page.ingredients().get(i);
                if (ingredient.isEmpty() || ingredient.getItems().length == 0) continue;
                Supplier<ItemStack> sample = () -> sample(ingredient);
                var button = new IconButton(gridX + i % page.columns() * 22, gridY + i / page.columns() * 22,
                        22, sample, label("inspect", sample.get().getHoverName()), () -> inspect(sample.get()), false, 0);
                addRenderableWidget(button);
            }
            if (pages.size() > 1) {
                int right = left + panelWidth - 12;
                addRenderableWidget(Button.builder(Component.literal("‹"), b -> turn(-1)).bounds(right - 65, gridY + 42, 18, 17).build());
                addRenderableWidget(Button.builder(Component.literal("›"), b -> turn(1)).bounds(right - 18, gridY + 42, 18, 17).build());
            }
        }
        addRenderableWidget(Button.builder(label(trail.isEmpty() ? "back_plan" : "back_ingredient"), b -> onClose())
                .bounds(left + 12, top + panelHeight - 25, panelWidth - 24, 18).build());
    }

    private void turn(int delta) {
        selection = new Selection(selection.item(), Math.floorMod(selection.page() + delta, pages.size()));
        rebuildWidgets();
    }

    private ItemStack sample(Ingredient ingredient) {
        var alternatives = ingredient.getItems();
        return alternatives[(int) Math.floorMod(minecraft.level.getGameTime() / 40L, alternatives.length)];
    }

    private int owned(ItemStack wanted) {
        if (wanted.isEmpty() || minecraft.player == null) return 0;
        int count = 0;
        for (var stack : minecraft.player.getInventory().items) {
            if (ItemStack.isSameItemSameTags(stack, wanted)) count += stack.getCount();
        }
        for (var stack : minecraft.player.getInventory().offhand) {
            if (ItemStack.isSameItemSameTags(stack, wanted)) count += stack.getCount();
        }
        return count;
    }

    private void text(GuiGraphics g, Component text, int x, int y, int space, int color) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), Math.max(0, space)), x, y, color, false);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float dt) {
        renderBackground(g);
        hovered = ItemStack.EMPTY;
        g.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        g.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 8, TEXT);
        g.hLine(left + 10, left + panelWidth - 11, top + 53, EDGE);
        boolean absent = selection.item().isEmpty();
        int count = owned(selection.item());
        text(g, absent ? Component.literal(root.id().toString()) : selection.item().getHoverName(),
                left + 15, top + 62, panelWidth * 3 / 5 - 14, TEXT);
        var status = trail.isEmpty() && parts.contains(root) ? label("stock_goal", count, root.required()) : label("stock", count);
        text(g, status, left + panelWidth * 3 / 5, top + 62, panelWidth * 2 / 5 - 8,
                count >= (trail.isEmpty() ? root.required() : 1) ? READY : MISSING);
        if (pages.isEmpty()) {
            g.drawWordWrap(font, label(absent ? "missing" : "outside_table"), left + 16, gridY + 7, panelWidth - 32, MUTED);
        } else {
            var page = pages.get(selection.page());
            for (int i = 0; i < 9; i++) {
                int x = gridX + i % 3 * 22, y = gridY + i / 3 * 22;
                g.fill(x, y, x + 21, y + 21, INK);
                g.renderOutline(x, y, 21, 21, EDGE);
            }
            g.drawCenteredString(font, "→", gridX + 84, gridY + 27, READY);
            g.renderItem(new ItemStack(page.station()), infoX, gridY);
            text(g, new ItemStack(page.station()).getHoverName(), infoX + 20, gridY + 4, left + panelWidth - infoX - 26, MUTED);
            var result = page.result();
            g.renderOutline(infoX, gridY + 22, 22, 22, READY);
            g.renderItem(result, infoX + 3, gridY + 25);
            g.renderItemDecorations(font, result, infoX + 3, gridY + 25);
            text(g, label(page.kind()), infoX + 27, gridY + 29, left + panelWidth - infoX - 36, TEXT);
            if (pages.size() > 1) g.drawCenteredString(font, (selection.page() + 1) + "/" + pages.size(),
                    left + panelWidth - 45, gridY + 47, MUTED);
            text(g, label("ingredients"), left + 15, top + panelHeight - 44, panelWidth - 30, MUTED);
            if (mx >= infoX && mx < infoX + 22 && my >= gridY + 22 && my < gridY + 44) hovered = result;
        }
        super.render(g, mx, my, dt);
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mx, my);
    }

    private final class IconButton extends Button {
        private final Supplier<ItemStack> icon;
        private final boolean selected;
        private final int count;
        IconButton(int x, int y, int size, Supplier<ItemStack> icon, Component name, Runnable onPress, boolean selected, int count) {
            super(x, y, size, size, name, button -> onPress.run(), DEFAULT_NARRATION);
            this.icon = icon;
            this.selected = selected;
            this.count = count;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float dt) {
            var stack = icon.get();
            int x = getX(), y = getY();
            g.fill(x, y, x + width - 1, y + height - 1, INK);
            g.renderOutline(x, y, width - 1, height - 1, isHoveredOrFocused() || selected ? READY : EDGE);
            if (stack.isEmpty()) { g.drawCenteredString(font, "?", x + width / 2, y + 5, MISSING); return; }
            int cx = x + (width - 16) / 2, cy = y + (height - 16) / 2;
            g.renderItem(stack, cx, cy);
            if (count > 1) g.renderItemDecorations(font, stack, cx, cy, Integer.toString(count));
            if (isMouseOver(mx, my)) hovered = stack;
        }
    }
}
