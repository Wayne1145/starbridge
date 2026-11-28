package dev.starbridge.flightqa.equipment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridge_flightqa", value = Dist.CLIENT)
public final class EquipmentCrewClient {
    private static int clock, retry, next, part;
    private static boolean done, clickedEquipment;
    private static Object entry;
    private static final List<String> actions = new ArrayList<>();

    private static String translated(String key) {
        return Component.translatable(key).getString();
    }

    private static Button find(Screen screen, String key) {
        String text = translated(key);
        return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.visible && b.getMessage().getString().equals(text))
                .findFirst()
                .orElse(null);
    }

    private static void click(Screen screen, int x, int y) {
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    private static boolean click(Screen screen, String key) {
        var button = find(screen, key);
        if (button == null || !button.active) return false;
        click(
                screen,
                button.getX() + button.getWidth() / 2,
                button.getY() + button.getHeight() / 2);
        actions.add(button.getMessage().getString());
        return true;
    }

    private static void snap(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> {});
    }

    private static void finish(Minecraft mc, boolean captain, String detail) throws Exception {
        EquipmentCrew.write(
                "equipment-ui-result.json",
                Map.of(
                        "entry",
                        captain ? "LunarKitScreen" : "FirstFlightScreen",
                        "actions",
                        actions,
                        "language",
                        mc.getLanguageManager().getSelected(),
                        "observed",
                        detail));
        done = true;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (!EquipmentCrew.ACTIVE || done || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        clock++;
        if (mc.player == null) {
            if (mc.getOverlay() == null
                    && mc.screen instanceof DisconnectedScreen
                    && ++retry > 25) {
                retry = 0;
                String address = Files.readString(Path.of("qa-server.txt")).trim();
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(address),
                        new ServerData("Equipment QA", address, true),
                        false);
            }
            return;
        }
        if (mc.level == null || mc.gameMode == null || clock < next) return;
        String who = mc.getUser().getName();
        boolean captain = who.equals("OrbitCaptain");
        if (!Files.isRegularFile(Path.of("../server/equipment-" + who + "-baseline.json"))) return;
        if (!captain
                && !EquipmentCrew.expectsFailure()
                && !Files.isRegularFile(Path.of("../server/captain-checked.json"))) return;
        next = clock + 8;
        if (mc.screen == null && !clickedEquipment) {
            mc.player.getInventory().selected = 4;
            if (captain) {
                var at = new BlockPos(1, 120, 1);
                mc.gameMode.useItemOn(
                        mc.player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3.atCenterOf(at).add(0, 0, .5), Direction.SOUTH, at, false));
            } else mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return;
        }
        if (mc.screen == null) return;
        var screen = mc.screen;
        String type = screen.getClass().getSimpleName();
        if (captain && type.equals("ChartScreen")) {
            if (click(screen, "route.starbridge.moon")) click(screen, "nav.starbridge.kit.title");
            return;
        }
        if (type.equals(captain ? "LunarKitScreen" : "FirstFlightScreen")) {
            if (!clickedEquipment) {
                snap(mc, "open-from-" + (captain ? "kit" : "guide"));
                entry = screen;
                clickedEquipment = click(screen, "nav.starbridge.kit.open");
            } else if (EquipmentCrew.expectsFailure()) {
                var field = screen.getClass().getSuperclass().getDeclaredField("notice");
                field.setAccessible(true);
                Component notice = (Component) field.get(screen);
                if (notice != null
                        && notice.getString()
                                .equals(
                                        translated(
                                                EquipmentCrew.MISSING
                                                        ? "nav.starbridge.kit.open.missing"
                                                        : "nav.starbridge.kit.open.unavailable"))
                        && screen == entry
                        && find(screen, "nav.starbridge.kit.open").active) {
                    snap(mc, "missing-equipment");
                    finish(mc, captain, notice.getString());
                }
            }
            return;
        }
        if (!EquipmentCrew.expectsFailure()
                && clickedEquipment
                && type.equals("LegacyScreen")
                && screen instanceof AbstractContainerScreen<?> gui) {
            if (part == 0) snap(mc, "original-empty");
            if (part < 4) {
                var menu = gui.getMenu();
                var source =
                        menu.slots.stream()
                                .filter(
                                        s ->
                                                s.container == mc.player.getInventory()
                                                        && s.getSlotIndex() == part
                                                        && !s.getItem().isEmpty())
                                .findFirst();
                if (source.isEmpty()) return;
                var from = source.get();
                var target = menu.getSlot(40 + part);
                int left = gui.getGuiLeft(), top = gui.getGuiTop();
                click(gui, left + from.x + 8, top + from.y + 8);
                click(gui, left + target.x + 8, top + target.y + 8);
                actions.add("slot " + from.index + " to " + target.index);
                part++;
            } else if (gui.getMenu().slots.subList(40, 44).stream()
                    .allMatch(s -> !s.getItem().isEmpty())) {
                snap(mc, "original-equipped");
                finish(mc, captain, type);
            }
        }
    }
}
