package dev.starbridge.qa.encounter;

import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgelunarqa", value = Dist.CLIENT)
public final class BlueprintClient {
    static int clock, nextConnect, phase, lastAction;
    static String previous = "";

    static void log(String s) throws Exception {
        Files.writeString(
                Path.of("blueprint-actions.log"),
                s + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static Object field(Object obj, String name) throws Exception {
        Class<?> type = obj.getClass();
        while (type != null) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(obj);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void use(Minecraft mc, BlockPos pos) {
        var hit = Vec3.atCenterOf(pos).add(0, 0, .49);
        mc.gameMode.useItemOn(
                mc.player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.SOUTH, pos, false));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) throws Exception {
        if (e.phase != TickEvent.Phase.END
                || !Files.exists(Path.of("starbridge-blueprint.fixture"))) return;
        var mc = Minecraft.getInstance();
        mc.setWindowActive(true);
        clock++;
        var p = mc.player;
        if (p == null) {
            if (mc.getOverlay() == null
                    && (mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen)
                    && clock > nextConnect + 70) {
                nextConnect = clock + 100;
                String addr = Files.readString(Path.of("lunar-server.txt")).trim();
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(addr),
                        new ServerData("Native schematic test", addr, true),
                        false);
            }
            return;
        }
        if (mc.level == null || mc.gameMode == null || clock - lastAction < 14) return;
        if (mc.screen == null) {
            boolean atBench =
                    mc.level.dimension().location().getPath().equals("overworld")
                            && p.position().distanceToSqr(.5, 157, 2.5) < 20;
            if (!atBench
                    && !mc.level
                            .dimension()
                            .location()
                            .getPath()
                            .equals(EncounterSite.SELECTED.path())) return;
            use(mc, atBench ? BlueprintTrial.BENCH : EncounterSite.SELECTED.chest());
            lastAction = clock;
            return;
        }
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        Object menu = screen.getMenu();
        Object original = null;
        try {
            original = menu.getClass().getMethod("legacyScreen").invoke(menu);
        } catch (ReflectiveOperationException ignored) {
        }
        if (original == null) return;
        String cls = original.getClass().getName();
        if (!cls.equals(previous)) {
            log("Opened " + cls);
            previous = cls;
        }
        boolean onPlanet =
                mc.level.dimension().location().getPath().equals(EncounterSite.SELECTED.path());
        if (onPlanet) {
            for (var slot : screen.getMenu().slots) {
                if (slot.container != p.getInventory()
                        && slot.hasItem()
                        && BlueprintTrial.originalId()
                                .equals(ForgeRegistries.ITEMS.getKey(slot.getItem().getItem()))) {
                    mc.gameMode.handleInventoryMouseClick(
                            screen.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE, p);
                    log("Moved natural schematic from chest slot " + slot.index);
                    lastAction = clock;
                    phase = 1;
                    return;
                }
            }
            return;
        }
        if (cls.contains("SchematicInput") && phase < 3) {
            var slots = screen.getMenu().slots;
            var input = slots.get(0);
            if (!input.hasItem()) {
                if (!p.containerMenu.getCarried().isEmpty()) {
                    mc.gameMode.handleInventoryMouseClick(
                            screen.getMenu().containerId, 0, 0, ClickType.PICKUP, p);
                    lastAction = clock;
                    log("Placed blueprint in original input slot");
                    return;
                }
                for (var slot : slots)
                    if (slot.container == p.getInventory()
                            && slot.hasItem()
                            && BlueprintTrial.originalId()
                                    .equals(
                                            ForgeRegistries.ITEMS.getKey(
                                                    slot.getItem().getItem()))) {
                        mc.gameMode.handleInventoryMouseClick(
                                screen.getMenu().containerId, slot.index, 0, ClickType.PICKUP, p);
                        log("Picked up the original blueprint in NASA workbench");
                        lastAction = clock;
                        return;
                    }
                return;
            }
            phase = 2;
        }
        int wanted = cls.contains("SchematicInput") && phase >= 2 ? 2 : 1;
        var list = (List<?>) field(original, "field_146292_n");
        for (Object b : list) {
            if ((int) field(b, "field_146127_k") != wanted || !(boolean) field(b, "field_146124_l"))
                continue;
            int x = (int) field(b, "field_146128_h") + (int) field(b, "field_146120_f") / 2;
            int y = (int) field(b, "field_146129_i") + (int) field(b, "field_146121_g") / 2;
            log("Clicked native button " + wanted + " " + field(b, "field_146126_j"));
            screen.mouseClicked(x, y, 0);
            screen.mouseReleased(x, y, 0);
            lastAction = clock;
            if (wanted == 2) phase = 3;
            return;
        }
    }
}
