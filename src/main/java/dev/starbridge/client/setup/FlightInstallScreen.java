package dev.starbridge.client.setup;

import dev.starbridge.Starbridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;
import java.util.Locale;

/** Players can check the installed flight components before creating a world or from the navigation slate. */
@Mod.EventBusSubscriber(modid = Starbridge.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FlightInstallScreen extends Screen {
    private static final int PANEL = 0xff142837, TILE = 0xff1d3644, EDGE = 0xff436170;
    private static final int WHITE = 0xffecf3ee, AQUA = 0xff78debe, AMBER = 0xffffc682, MUTED = 0xffacc2cc;
    private final Screen previous;
    private final List<FlightInstall.Entry> entries;
    private final boolean fromYardPart;
    private boolean optional;
    private boolean downloadsRestored;
    private boolean downloadsDenied;
    private boolean downloadSettingFailed;
    private FlightInstall.Entry selected;
    private FlightInstall.Entry previousEntry;
    private int left, top, panelWidth, panelHeight;

    public FlightInstallScreen(Screen previous) {
        this(previous, null);
    }

    public static FlightInstallScreen forYardPart(Screen guide, String provider) {
        return new FlightInstallScreen(guide, provider);
    }

    private FlightInstallScreen(Screen previous, String provider) {
        super(text("title"));
        this.previous = previous;
        entries = FlightInstall.snapshot();
        selected = entries.stream().filter(entry -> entry.id().equals(provider)).findFirst().orElse(null);
        fromYardPart = selected != null;
    }

    @SubscribeEvent public static void configure(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(Starbridge.ID).orElseThrow().registerExtensionPoint(
                ConfigScreenFactory.class, () -> new ConfigScreenFactory(previous -> new FlightInstallScreen(previous)));
    }

    public static Component text(String key, Object... args) {
        return Component.translatable("gui.starbridge.install." + key, args);
    }

    private static Component name(FlightInstall.Entry entry) { return text("name." + entry.id()); }
    private static Component state(FlightInstall.Entry entry) {
        return text("state." + entry.state().name().toLowerCase(Locale.ROOT));
    }
    private static int color(FlightInstall.Entry entry) {
        return entry.state() == FlightInstall.State.LOADED ? AQUA : entry.optional() ? MUTED : AMBER;
    }

    @Override protected void init() {
        panelWidth = Math.min(370, width - 10);
        panelHeight = Math.min(216, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        if (selected == null) overview();
        else details();
        Component back;
        if (previousEntry != null) back = text("back_to", name(previousEntry));
        else if (fromYardPart) back = text("back_to_yard");
        else back = selected == null ? Component.translatable("gui.done") : text("back");
        addRenderableWidget(Button.builder(back, button -> onClose())
                .bounds(left + 10, top + panelHeight - 24, panelWidth - 20, 18).build());
    }

    private void overview() {
        int tileWidth = (panelWidth - 24) / 2;
        for (int i = 0; i < 2; i++) {
            boolean extra = i == 1;
            var group = entries.stream().filter(e -> e.optional() == extra).toList();
            long loaded = group.stream().filter(e -> e.state() == FlightInstall.State.LOADED).count();
            addRenderableWidget(Button.builder(text(extra ? "optional" : "required", loaded, group.size()), b -> {
                optional = extra;
                rebuildWidgets();
            }).bounds(left + 10 + i * (tileWidth + 4), top + 41, tileWidth, 18).build()).active = optional != extra;
        }
        var group = entries.stream().filter(e -> e.optional() == optional).toList();
        int tileHeight = Math.min(28, (panelHeight - 98) / Math.max(1, (group.size() + 1) / 2));
        for (int i = 0; i < group.size(); i++) {
            var entry = group.get(i);
            addRenderableWidget(new ComponentButton(left + 10 + (i % 2) * (tileWidth + 4),
                    top + 65 + (i / 2) * (tileHeight + 2), tileWidth, tileHeight, entry));
        }
    }

    private final class ComponentButton extends Button {
        private final FlightInstall.Entry entry;
        private final ItemStack icon;
        ComponentButton(int x, int y, int w, int h, FlightInstall.Entry entry) {
            super(x, y, w, h, text("row", name(entry), state(entry)), b -> {
                selected = entry;
                rebuildWidgets();
            }, DEFAULT_NARRATION);
            this.entry = entry;
            icon = new ItemStack(item(entry));
            setTooltip(Tooltip.create(text("use." + entry.id())));
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), rowWidth = getWidth(), rowHeight = getHeight();
            g.fill(x, y, x + rowWidth, y + rowHeight, TILE);
            g.renderOutline(x, y, rowWidth, rowHeight, isHoveredOrFocused() ? WHITE : EDGE);
            g.fill(x + 1, y + 1, x + 3, y + rowHeight - 1, color(entry));
            g.renderItem(icon, x + 6, y + (rowHeight - 16) / 2);
            String clipped = font.plainSubstrByWidth(name(entry).getString(), rowWidth - 32);
            g.drawString(font, clipped, x + 26, y + (rowHeight - 19) / 2, WHITE, false);
            g.drawString(font, state(entry), x + 26, y + (rowHeight - 19) / 2 + 10, color(entry), false);
        }
    }

    private void details() {
        if (selected.state() == FlightInstall.State.WAITING) {
            entries.stream().filter(entry -> entry.id().equals("retrofit")).findFirst().ifPresent(bridge ->
                    addRenderableWidget(Button.builder(text("runtime"), button -> {
                        previousEntry = selected;
                        selected = bridge;
                        rebuildWidgets();
                    }).bounds(left + 10, top + panelHeight - 69, panelWidth - 20, 17).build()));
        }
        downloadsDenied = waitingForRetrofit() && FlightInstall.downloadsDenied();
        if (waitingForRetrofit() && (downloadsRestored || downloadsDenied)) {
            var permission = Button.builder(text(downloadsRestored ? "download_enabled" : "download_enable"), button -> {
                downloadsRestored = FlightInstall.askForDownloadsOnRestart();
                downloadSettingFailed = !downloadsRestored;
                rebuildWidgets();
            }).bounds(left + 10, top + panelHeight - 69, panelWidth - 20, 17).build();
            permission.active = !downloadsRestored;
            addRenderableWidget(permission);
        }
        int buttonWidth = (panelWidth - 24) / 2;
        addRenderableWidget(Button.builder(text("copy"), b -> {
            minecraft.keyboardHandler.setClipboard(selected.directory() + "/" + selected.file());
            b.setMessage(text("copied"));
        }).bounds(left + 10, top + panelHeight - 47, buttonWidth, 18).build());
        String url = selected.source();
        var source = Button.builder(text("source"), b -> ConfirmLinkScreen.confirmLinkNow(url, this, false))
                .bounds(left + 14 + buttonWidth, top + panelHeight - 47, buttonWidth, 18).build();
        source.active = url != null && url.startsWith("https://");
        addRenderableWidget(source);
    }

    private boolean waitingForRetrofit() {
        return selected.id().equals("retrofit") && selected.state() == FlightInstall.State.LOADED
                && entries.stream().anyMatch(entry -> entry.state() == FlightInstall.State.WAITING);
    }

    private static Item item(FlightInstall.Entry entry) {
        return switch (entry.id()) {
            case "starbridge" -> Items.COMPASS;
            case "retrofit" -> Items.REPEATER;
            case "dock" -> Items.PISTON;
            case "advancedrocketry" -> Items.FIREWORK_ROCKET;
            case "libvulpes" -> Items.IRON_INGOT;
            case "galacticraft" -> Items.END_STONE;
            case "valkyrienskies" -> Items.FEATHER;
            case "kotlinforforge" -> Items.CHAIN;
            case "extraplanets" -> Items.SPYGLASS;
            case "galaxyspace" -> Items.AMETHYST_SHARD;
            case "computercraft" -> Items.COMPARATOR;
            default -> Items.BOOK;
        };
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        g.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        g.drawCenteredString(font, title, width / 2, top + 8, WHITE);
        if (selected == null) {
            g.drawWordWrap(font, text("local"), left + 12, top + 22, panelWidth - 24, MUTED);
        } else {
            g.renderItem(new ItemStack(item(selected)), left + 14, top + 28);
            g.drawString(font, name(selected), left + 38, top + 24, WHITE, false);
            g.drawString(font, state(selected), left + 38, top + 36, color(selected), false);
            g.drawWordWrap(font, text("use." + selected.id()), left + 14, top + 53, panelWidth - 28, WHITE);
            g.fill(left + 10, top + 80, left + panelWidth - 10, top + 126, TILE);
            g.drawString(font, text("folder", selected.directory() + "/"), left + 15, top + 85, AQUA, false);
            g.drawWordWrap(font, Component.literal(selected.file()), left + 15, top + 101, panelWidth - 30, WHITE);
            String hint = "help." + selected.state().name().toLowerCase(Locale.ROOT);
            if (waitingForRetrofit()) {
                if (downloadsRestored) hint = "download_restart";
                else if (downloadSettingFailed) hint = "download_failed";
                else if (downloadsDenied) hint = "download_denied";
                else hint = "runtime_inactive";
            }
            g.drawWordWrap(font, text(hint), left + 13, top + 130, panelWidth - 26, MUTED);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() {
        if (previousEntry != null) {
            selected = previousEntry;
            previousEntry = null;
        } else if (selected != null && !fromYardPart) {
            selected = null;
        } else {
            minecraft.setScreen(previous);
            return;
        }
        rebuildWidgets();
    }
}
