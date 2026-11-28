package dev.starbridge.client;

import dev.starbridge.Starbridge;
import dev.starbridge.content.ContentModule;
import dev.starbridge.voyage.CabinPressure;
import dev.starbridge.voyage.VentMenu;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Starbridge.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VentScreen extends AbstractContainerScreen<VentMenu> {
    private static final int FRAME = 0xff365566, PANEL = 0xff142837, INK = 0xff0c1723;
    private static final int TEXT = 0xffdeebeb, MUTED = 0xffa9bdc8, AQUA = 0xff74dec7, AMBER = 0xffffc683;
    private Button refill;

    public VentScreen(VentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 252;
        imageHeight = 176;
    }
    @SubscribeEvent public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(VoyageModule.VENT_MENU.get(), VentScreen::new));
    }
    private static Component text(String key, Object... args) { return Component.translatable("vent.starbridge." + key, args); }

    @Override protected void init() {
        super.init();
        refill = addRenderableWidget(Button.builder(text("fill"), b -> {
            if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
        }).bounds(leftPos + 12, topPos + 146, 151, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(leftPos + 169, topPos + 146, 71, 20).build());
        updateRefill();
    }
    @Override protected void containerTick() { super.containerTick(); updateRefill(); }
    private void updateRefill() { refill.active = menu.canRefill(); }
    @Override protected void renderBg(GuiGraphics g, float dt, int mouseX, int mouseY) {
        int x = leftPos, y = topPos, width = imageWidth;
        g.fill(x, y, x + width, y + imageHeight, PANEL);
        g.renderOutline(x, y, width, imageHeight, FRAME);
        g.hLine(x + 1, x + width - 2, y + 28, FRAME);
        int gaugeX = x + 13, gaugeY = y + 52, gaugeWidth = width - 26;
        g.fill(gaugeX, gaugeY, gaugeX + gaugeWidth, gaugeY + 10, INK);
        g.fill(gaugeX, gaugeY, gaugeX + gaugeWidth * menu.charge() / CabinPressure.CAPACITY, gaugeY + 10, AQUA);
        for (int i = 1; i < 16; i++) g.vLine(gaugeX + gaugeWidth * i / 16, gaugeY, gaugeY + 9, FRAME);
        g.renderItem(new ItemStack(ContentModule.OXYGEN_CANISTER.get()), x + 12, y + 121);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 12, 10, TEXT, false);
        g.drawString(font, text("reserve", menu.charge(), CabinPressure.CAPACITY), 13, 38, TEXT, false);
        int y = 75;
        Component status = text(menu.sealed() ? (menu.charge() == 0 ? "empty" : "sealed") : "open");
        for (var line : font.split(status, imageWidth - 26)) {
            g.drawString(font, line, 13, y, menu.sealed() && menu.charge() != 0 ? AQUA : AMBER, false);
            y += font.lineHeight + 2;
        }
        g.drawString(font, text("inventory", menu.canisters()), 34, 125, MUTED, false);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float tick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, tick);
        renderTooltip(g, mouseX, mouseY);
    }
}
