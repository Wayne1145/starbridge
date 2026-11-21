package dev.starbridge.client;

import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.compat.rocketry.OriginalPropellant.Batches;
import dev.starbridge.voyage.VoyageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** One batch on each original machine, using the recipes received from the current game server. */
public final class PropellantScreen extends Screen {
    private static final int INK = 0xff142837, EDGE = 0xff365566, TEXT = 0xffdbe9e9, MUTED = 0xff99afbb, AMBER = 0xffffc683;
    private static final ResourceLocation SPLITTER = new ResourceLocation("advancedrocketry", "tile.electrolyser");
    private static final ResourceLocation REACTOR = new ResourceLocation("advancedrocketry", "tile.chemreactor");
    private static int sequence;
    private final Screen previous;
    private final int request = ++sequence;
    private Batches batches;
    private boolean mixing;
    private int left, top, panelWidth;

    private PropellantScreen(Screen previous) { super(tr("title")); this.previous = previous; }
    private static Component tr(String key, Object... args) { return Component.translatable("slate.starbridge.propellant." + key, args); }
    static void open(Screen previous) {
        var page = new PropellantScreen(previous);
        Minecraft.getInstance().setScreen(page);
        VoyageNetwork.requestFuelRecipes(page.request);
    }
    public static void receive(int request, Batches batches) {
        if (Minecraft.getInstance().screen instanceof PropellantScreen page && page.request == request) page.batches = batches;
    }
    @Override public void onClose() { minecraft.setScreen(previous); }
    @Override public boolean isPauseScreen() { return false; }
    private Button button(String key, int x, int y, int w, Button.OnPress action) {
        return addRenderableWidget(Button.builder(tr(key), action).bounds(x, y, w, 19).build());
    }
    @Override protected void init() {
        panelWidth = Math.min(348, width - 10); left = (width - panelWidth) / 2; top = (height - 205) / 2;
        int x = left + 10, half = (panelWidth - 24) / 2;
        button("electrolyser", x, top + 27, half, b -> { mixing = false; rebuildWidgets(); }).active = mixing;
        button("reactor", x + half + 4, top + 27, panelWidth - 24 - half, b -> { mixing = true; rebuildWidgets(); }).active = !mixing;
        var machine = mixing ? REACTOR : SPLITTER;
        button("controller", x, top + 177, half, b -> FabricationScreen.open(this, machine)).active = ForgeRegistries.ITEMS.containsKey(machine);
        button("back", x + half + 4, top + 177, panelWidth - 24 - half, b -> onClose());
    }

    private void fluid(GuiGraphics g, int x, int y, String key, int amount, int tint) {
        g.renderOutline(x, y, 9, 22, EDGE);
        g.fill(x + 2, y + 5, x + 7, y + 20, tint);
        g.drawString(font, tr(key), x + 13, y, TEXT, false);
        g.drawString(font, tr("amount", amount), x + 13, y + 12, tint, false);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + 205, INK); g.renderOutline(left, top, panelWidth, 205, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        int center = left + panelWidth / 2, in = left + 13, out = left + 2 * panelWidth / 3 + 8;
        g.drawString(font, tr("in"), in, top + 58, MUTED, false);
        g.drawString(font, tr("out"), out, top + 58, MUTED, false);
        var machine = ForgeRegistries.ITEMS.getValue(mixing ? REACTOR : SPLITTER);
        var item = machine == null ? ItemStack.EMPTY : new ItemStack(machine);
        g.renderOutline(center - 14, top + 84, 28, 28, EDGE);
        if (!item.isEmpty()) g.renderItem(item, center - 8, top + 90);
        boolean missing = batches != null && (mixing ? batches.fuel() : batches.water()) <= 0;
        if (batches != null && !missing) {
            if (mixing) {
                fluid(g, in, top + 74, "oxygen", batches.oxygenUsed(), 0xff8dcbdc);
                fluid(g, in, top + 106, "hydrogen", batches.hydrogenUsed(), 0xffa99ee3);
                fluid(g, out, top + 90, "fuel", batches.fuel(), AMBER);
            } else {
                fluid(g, in, top + 90, "water", batches.water(), 0xff739de8);
                fluid(g, out, top + 74, "oxygen", batches.oxygenMade(), 0xff8dcbdc);
                fluid(g, out, top + 106, "hydrogen", batches.hydrogenMade(), 0xffa99ee3);
            }
            g.drawCenteredString(font, "→", center - 25, top + 94, MUTED);
            g.drawCenteredString(font, "→", center + 25, top + 94, MUTED);
        }
        var help = tr(batches == null ? "waiting" : missing ? "missing" : mixing ? "collect" : "transfer");
        g.drawWordWrap(font, help, left + 13, top + 141, panelWidth - 26, missing ? AMBER : TEXT);
        super.render(g, mouseX, mouseY, partialTick);
        if (!item.isEmpty() && mouseX >= center - 14 && mouseX < center + 14 && mouseY >= top + 84 && mouseY < top + 112) {
            g.renderTooltip(font, item, mouseX, mouseY);
        }
    }
}
