package dev.starbridge.client.shipyard;

import dev.starbridge.compat.rocketry.OriginalFabrication;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** See the input and output of the original hand press before and after applying redstone. */
final class PressSetupScreen extends Screen {
    private static final ResourceLocation PRESS = new ResourceLocation("advancedrocketry", "tile.blockhandpress");
    private static final ResourceLocation TIN_BLOCK = new ResourceLocation("libvulpes", "libvulpesmetal0");
    private static final ResourceLocation TIN_PLATES = new ResourceLocation("libvulpes", "libvulpesproductplate");
    private static final int TIN_META = 5;
    private static final int PANEL = 0xff142837, WELL = 0xff0c1924, EDGE = 0xff526e7d;
    private static final int TEXT = 0xffdbe9e9, MUTED = 0xffa2bbc1, POWER = 0xff74dec7;
    private final Screen previous;
    private final ItemStack press = original(PRESS, 0, 1), input, output;
    private final boolean tinExample;
    private final ItemStack obsidian = new ItemStack(Items.OBSIDIAN), lever = new ItemStack(Items.LEVER);
    private ItemStack hovered = ItemStack.EMPTY;
    private boolean powered;
    private int left, top, panelWidth, panelHeight, assemblyX, assemblyY;

    PressSetupScreen(Screen previous, OriginalFabrication.Batch batch) {
        super(label("press_title"));
        this.previous = previous;
        tinExample = !supports(batch);
        input = tinExample ? original(TIN_BLOCK, TIN_META, 1) : feed(batch);
        output = tinExample ? original(TIN_PLATES, TIN_META, 4) : batch.output();
    }

    static boolean supports(OriginalFabrication.Batch batch) {
        return batch != null && PRESS.equals(ForgeRegistries.ITEMS.getKey(batch.station().getItem()))
                && batch.inputs().size() == 1 && !feed(batch).isEmpty();
    }

    private static ItemStack feed(OriginalFabrication.Batch batch) {
        return batch.inputs().get(0).stream().filter(stack -> stack.getItem() instanceof BlockItem)
                .findFirst().orElse(ItemStack.EMPTY);
    }

    static boolean appliesTo(ItemStack item) {
        if (item.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item.getItem());
        return PRESS.equals(id) || TIN_PLATES.equals(id) && item.hasTag() && item.getTag().getInt("LegacyMeta") == TIN_META;
    }

    private static ItemStack original(ResourceLocation id, int meta, int count) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return ItemStack.EMPTY;
        var stack = new ItemStack(item, count);
        if (meta != 0) stack.getOrCreateTag().putInt("LegacyMeta", meta);
        return stack;
    }

    private static Component label(String key) {
        return Component.translatable("slate.starbridge.materials." + key);
    }

    @Override protected void init() {
        panelWidth = Math.min(326, width - 8);
        panelHeight = Math.min(200, height - 6);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        assemblyX = left + panelWidth * 2 / 5;
        assemblyY = top + 43;
        if (!powered && !input.isEmpty()) {
            var inputSlot = new Button(assemblyX - 11, assemblyY + 27, 23, 23, label(tinExample ? "press_inspect_tin" : "press_input_recipe"),
                    button -> FabricationScreen.open(this, input), narration -> narration.get()) {
                @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
                    int x = getX(), y = getY();
                    g.renderOutline(x, y, width, height, isHoveredOrFocused() ? TEXT : POWER);
                    g.renderItem(input, x + 3, y + 3);
                    // A corner arrow marks the material as a link even without colour or a mouse.
                    g.hLine(x + 16, x + 20, y + 2, TEXT);
                    g.vLine(x + 20, y + 2, y + 6, TEXT);
                    g.fill(x + 17, y + 4, x + 19, y + 6, TEXT);
                }
            };
            inputSlot.setTooltip(Tooltip.create(inputSlot.getMessage()));
            addRenderableWidget(inputSlot);
        }
        int buttonWidth = (panelWidth - 28) / 3;
        int controlsY = top + panelHeight - 27;
        addRenderableWidget(Button.builder(label(powered ? "press_reset" : "press_power"), b -> {
            powered = !powered;
            rebuildWidgets();
        }).bounds(left + 10, controlsY, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(label(tinExample ? "press_tin_recipe" : "press_input_recipe"), b -> FabricationScreen.open(this, input))
                .bounds(left + 14 + buttonWidth, controlsY, buttonWidth, 18).build()).active = !input.isEmpty();
        addRenderableWidget(Button.builder(label("press_back"), b -> onClose())
                .bounds(left + panelWidth - buttonWidth - 10, controlsY, buttonWidth, 18).build());
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        hovered = ItemStack.EMPTY;
        g.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        g.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 8, TEXT);
        g.drawCenteredString(font, label(tinExample ? "press_example" : "press_ore_example"), width / 2, top + 22, MUTED);
        int leverX = left + panelWidth / 7;
        int resultX = left + panelWidth * 5 / 6, y = assemblyY, wire = powered ? POWER : EDGE;
        g.hLine(leverX + 11, assemblyX - 11, y + 10, wire);
        slot(g, lever, leverX, y, wire, mouseX, mouseY);
        g.drawCenteredString(font, label("press_lever"), leverX, y + 30, TEXT);
        g.drawCenteredString(font, label(powered ? "press_on" : "press_off"), leverX, y + 43, powered ? POWER : MUTED);
        slot(g, press, assemblyX, y, EDGE, mouseX, mouseY);
        slot(g, ItemStack.EMPTY, assemblyX, y + 27, EDGE, mouseX, mouseY);
        slot(g, obsidian, assemblyX, y + 54, EDGE, mouseX, mouseY);
        g.drawString(font, label("press_top"), assemblyX + 16, y + 7, TEXT, false);
        if (!powered) {
            var middle = tinExample ? label("press_middle") : input.getHoverName();
            g.drawString(font, font.plainSubstrByWidth(middle.getString(), left + panelWidth - assemblyX - 28),
                    assemblyX + 16, y + 34, TEXT, false);
        }
        g.drawString(font, label("press_bottom"), assemblyX + 16, y + 61, TEXT, false);
        if (powered) {
            g.hLine(assemblyX + 12, resultX - 17, y + 37, POWER);
            g.drawString(font, "›", resultX - 19, y + 33, POWER, false);
        }
        slot(g, powered ? output : ItemStack.EMPTY, resultX, y + 27, wire, mouseX, mouseY);
        g.drawCenteredString(font, label(tinExample ? "press_output" : "press_product"), resultX, y + 58, powered ? TEXT : MUTED);
        String help = tinExample ? (powered ? "press_help_on" : "press_help_off")
                : powered ? "press_ore_help_on"
                : input.is(Items.GOLD_ORE) ? "press_gold_help_off" : "press_ore_help_off";
        g.drawWordWrap(font, label(help), left + 13, top + 127, panelWidth - 26, TEXT);
        super.render(g, mouseX, mouseY, delta);
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    private void slot(GuiGraphics g, ItemStack item, int centerX, int y, int edge, int mouseX, int mouseY) {
        int x = centerX - 11;
        g.fill(x, y, x + 23, y + 23, WELL);
        g.renderOutline(x, y, 23, 23, edge);
        if (item.isEmpty()) return;
        g.renderItem(item, x + 3, y + 3);
        g.renderItemDecorations(font, item, x + 3, y + 3);
        if (mouseX >= x && mouseX < x + 23 && mouseY >= y && mouseY < y + 23) hovered = item;
    }
    @Override public void onClose() { minecraft.setScreen(previous); }
    @Override public boolean isPauseScreen() { return false; }
}
