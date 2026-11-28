package dev.starbridge.client;

import dev.starbridge.voyage.VoyageNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Original equipment for the selected lunar route. The icons come from the installed pack. */
public final class LunarKitScreen extends EquipmentAccessScreen {
    private static final int PANEL = 0xff142837, EDGE = 0xff365566, TEXT = 0xffdbe9e9;
    private static final int OXYGEN = 0xff78daca, EXPOSURE = 0xffffc683;
    private static final ResourceLocation SEAL = ResourceLocation.parse("retrofit:spacebreathing");
    private static final List<EquipmentSlot> SUIT_SLOTS =
            List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private enum SealCheck {
        SEALED("+", OXYGEN), UNSEALED("!", EXPOSURE), UNEQUIPPED("-", 0xffadc3ca), UNKNOWN("?", 0xffadc3ca);

        final String icon;
        final int color;

        SealCheck(String icon, int color) {
            this.icon = icon;
            this.color = color;
        }

        Component tooltip() {
            return label("seal." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private record Hover(ItemStack item, SealCheck check) {}

    private final ChartScreen chart;
    private final List<ItemStack> oxygen, suit;
    private final boolean radiation;
    private int left, top, panelWidth, panelHeight;

    LunarKitScreen(ChartScreen chart) {
        super(label("title"));
        this.chart = chart;
        oxygen = items("galacticraftcore", "item.oxygenmask", "item.oxygengear",
                "item.oxygentankheavyfull", "item.oxygentankheavyfull");
        radiation = ForgeRegistries.ITEMS.containsKey(new ResourceLocation("galaxyspace", "item.spacesuit_helmet"));
        suit = radiation ? items("galaxyspace", "item.spacesuit_helmet", "item.spacesuit_plate", "item.spacesuit_leg", "item.spacesuit_boots")
                : items("advancedrocketry", "item.spacehelmet", "item.spacechest", "item.spaceleggings", "item.spaceboots");
    }

    private static List<ItemStack> items(String mod, String... names) {
        return java.util.Arrays.stream(names).map(name -> {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(mod, name));
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }).toList();
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("nav.starbridge.kit." + key, args);
    }

    private List<SealCheck> radiationChecks() {
        var seal = ForgeRegistries.ENCHANTMENTS.getValue(SEAL);
        return java.util.stream.IntStream.range(0, SUIT_SLOTS.size()).mapToObj(index -> {
            var expected = suit.get(index);
            if (minecraft.player == null || expected.isEmpty()) return SealCheck.UNKNOWN;
            var worn = minecraft.player.getItemBySlot(SUIT_SLOTS.get(index));
            if (!worn.is(expected.getItem())) return SealCheck.UNEQUIPPED;
            if (seal == null) return SealCheck.UNKNOWN;
            return EnchantmentHelper.getItemEnchantmentLevel(seal, worn) > 0 ? SealCheck.SEALED : SealCheck.UNSEALED;
        }).toList();
    }

    @Override protected void init() {
        panelWidth = Math.min(width - 8, 364);
        panelHeight = Math.min(height - 8, 210);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(label("rations.title"),
                b -> minecraft.setScreen(new LunarRationsScreen(this, radiation))).bounds(left + panelWidth - 95, top + 3, 87, 18).build());
        equipmentActions(left + 12, top + panelHeight - 24, panelWidth - 24, label("back"));
    }

    boolean updateChart(VoyageNetwork.Chart update) {
        if (!chart.hasCore(update)) return false;
        chart.updateChart(update);
        return true;
    }

    @Override public void onClose() { minecraft.setScreen(chart); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        graphics.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        graphics.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        int secondY = top + 26 + (panelHeight - 52) / 2;
        var checks = radiation ? radiationChecks() : List.<SealCheck>of();
        var oxygenHover = row(graphics, top + 26, oxygen, List.of(), "oxygen", "oxygen_help", OXYGEN, mouseX, mouseY);
        var suitHover = row(graphics, secondY, suit, checks, radiation ? "radiation" : "pressure",
                radiation ? "radiation_help" : "pressure_help", EXPOSURE, mouseX, mouseY);
        if (radiation) {
            long sealed = checks.stream().filter(check -> check == SealCheck.SEALED).count();
            graphics.drawString(font, label("seal.count", sealed, SUIT_SLOTS.size()), left + 12, secondY + 49,
                    sealed == SUIT_SLOTS.size() ? OXYGEN : EXPOSURE, false);
        }
        drawEquipmentNotice(graphics, left + 12, top + panelHeight - 26, panelWidth - 24);
        super.render(graphics, mouseX, mouseY, partialTick);
        var hover = oxygenHover == null ? suitHover : oxygenHover;
        if (hover == null) return;
        if (hover.check() == null) {
            if (!hover.item().isEmpty()) graphics.renderTooltip(font, hover.item(), mouseX, mouseY);
        } else {
            var tooltip = new ArrayList<Component>();
            if (!hover.item().isEmpty()) tooltip.addAll(Screen.getTooltipFromItem(minecraft, hover.item()));
            tooltip.add(hover.check().tooltip());
            graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
        }
    }

    private Hover row(GuiGraphics graphics, int y, List<ItemStack> pieces, List<SealCheck> checks,
                      String heading, String hint, int accent, int mouseX, int mouseY) {
        int x = left + 12;
        graphics.hLine(x, left + panelWidth - 13, y, EDGE);
        graphics.drawString(font, label(heading), x, y + 7, accent, false);
        Hover hovered = null;
        for (int index = 0; index < pieces.size(); index++) {
            var piece = pieces.get(index);
            SealCheck check = checks.isEmpty() ? null : checks.get(index);
            int slotY = y + 23;
            graphics.renderOutline(x, slotY, 21, 21, check == null ? accent : check.color);
            if (piece.isEmpty()) graphics.drawCenteredString(font, "?", x + 10, slotY + 6, accent);
            else graphics.renderItem(piece, x + 3, slotY + 3);
            if (check != null) {
                graphics.fill(x + 13, slotY + 13, x + 20, slotY + 20, PANEL);
                graphics.drawString(font, check.icon, x + 14, slotY + 13, check.color, false);
            }
            if (mouseX >= x && mouseX < x + 21 && mouseY >= slotY && mouseY < slotY + 21) {
                hovered = new Hover(piece, check);
            }
            x += 24;
        }
        graphics.drawWordWrap(font, label(hint), x + 4, y + 21, left + panelWidth - x - 16, TEXT);
        return hovered;
    }
}
