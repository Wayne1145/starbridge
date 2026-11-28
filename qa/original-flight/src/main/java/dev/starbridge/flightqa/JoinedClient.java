package dev.starbridge.flightqa;

import static dev.starbridge.flightqa.MissionSignals.*;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;

@Mod.EventBusSubscriber(modid = "starbridge_flightqa", value = Dist.CLIENT)
public final class JoinedClient {
    private static int tick, last, rejoin;
    private static boolean initChart, chartSent, displayOpen, landSent;
    private static String mode = "", lastScreen = "";

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (!JOINED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (mc.player == null) {
            if (Files.isRegularFile(Path.of("mission.json"))
                    && mc.getOverlay() == null
                    && mc.screen instanceof DisconnectedScreen
                    && tick > rejoin) {
                rejoin = tick + 150;
                String address = Files.readString(Path.of("qa-server.txt")).trim();
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(address),
                        new ServerData("Return to crew", address, true),
                        false);
                FlightClient.log("Reconnected to the dedicated server after staged network loss");
            }
            return;
        }
        if (mc.level == null || tick % 3 != 0) return;
        JsonObject signal = read();
        if (signal == null) return;
        String action = signal.get("action").getAsString();
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        if (!screen.equals(lastScreen)) {
            lastScreen = screen;
            FlightClient.log("screen=" + screen);
        }
        if (!action.equals(mode)) {
            mode = action;
            FlightClient.log("phase=" + action);
            if (!action.equals("finish")) {
                initChart = chartSent = displayOpen = landSent = false;
            }
            mc.options.keyUp.setDown(false);
            last = tick - 12;
        }
        mc.setWindowActive(true);
        if (action.equals("wait")) {
            if (mc.screen != null && !(mc.screen instanceof ReceivingLevelScreen))
                mc.screen.onClose();
            return;
        }
        if (mc.getUser().getName().equals(PILOT)) {
            if (tick - last >= 8) pilot(mc, action, screen);
            return;
        }
        engineer(mc, action, signal);
    }

    private static void pilot(Minecraft mc, String action, String screen) throws Exception {
        if (!action.equals("outbound") && !action.equals("home") && !action.equals("finish"))
            return;
        boolean returning = !action.equals("outbound");
        String world = mc.level.dimension().location().toString();
        if (returning && !world.equals(MOON) || !returning && world.equals(MOON)) return;
        if (!initChart && mc.screen != null && screen.equals("ChartScreen")) {
            mc.screen.onClose();
            initChart = true;
            last = tick;
            return;
        }
        if (mc.screen == null && !chartSent) {
            initChart = true;
            mc.player.getInventory().selected = 0;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            last = tick;
            return;
        }
        if (screen.equals("ChartScreen") && !chartSent) {
            String route = returning ? "home" : "moon";
            if (FlightClient.click(mc.screen, "route.starbridge." + route)
                    && FlightClient.click(mc.screen, "nav.starbridge.cruise")) {
                chartSent = true;
                last = tick;
                FlightClient.snap(mc, route + "-shipyard-flight");
            }
            return;
        }
        if (!screen.equals("OrbitScreen")) return;
        if (!displayOpen) {
            if (FlightClient.click(mc.screen, "orbit.starbridge.landing")) {
                displayOpen = true;
                last = tick;
                if (!returning) {
                    int w = mc.screen.width,
                            h = mc.screen.height,
                            size = Math.max(52, Math.min(180, Math.min(w / 2 - 28, h - 93)));
                    int l = Math.max(12, (w / 2 - size) / 2), top = (h - 35 - size) / 2;
                    double x = l + (396 + 512) * (size - 1) / 1024.,
                            y = top + (193 + 512) * (size - 1) / 1024.;
                    mc.screen.mouseClicked(x, y, 0);
                    mc.screen.mouseReleased(x, y, 0);
                    FlightClient.log(
                            "Selected the approximate lunar dungeon area on the landing map");
                }
            }
            return;
        }
        if (!returning || action.equals("finish")) {
            var button = FlightClient.find(mc.screen, "orbit.starbridge.touchdown");
            if (button != null && button.active && (!landSent || tick - last > 90)) {
                FlightClient.snap(mc, returning ? "home-approach" : "moon-approach");
                if (FlightClient.click(mc.screen, "orbit.starbridge.touchdown")) {
                    landSent = true;
                    last = tick;
                }
            }
        }
    }

    private static void engineer(Minecraft mc, String action, JsonObject s) throws Exception {
        var p = mc.player;
        if (action.equals("battle")) {
            if (mc.screen != null || !MOON.equals(mc.level.dimension().location().toString()))
                return;
            p.getInventory().selected = 2;
            ItemEntity key = null;
            LivingEntity boss = null;
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof ItemEntity drop
                        && id(drop.getItem()).startsWith("galacticraftcore:")
                        && id(drop.getItem()).contains("key")) key = drop;
                if (e instanceof LivingEntity living
                        && living.isAlive()
                        && "galacticraftcore:evolvedskeletonboss"
                                .equals(
                                        String.valueOf(
                                                ForgeRegistries.ENTITY_TYPES.getKey(e.getType()))))
                    boss = living;
            }
            if (key != null) {
                p.lookAt(Anchor.EYES, key.position());
                mc.options.keyUp.setDown(key.distanceToSqr(p) > .15);
                return;
            }
            if (boss == null) {
                mc.options.keyUp.setDown(false);
                return;
            }
            p.lookAt(Anchor.EYES, boss.getBoundingBox().getCenter());
            double distance = boss.getBoundingBox().distanceToSqr(p.getEyePosition());
            mc.options.keyUp.setDown(distance > 2.6);
            if (distance < 9 && p.getAttackStrengthScale(0) > .94f) {
                mc.gameMode.attack(p, boss);
                p.swing(InteractionHand.MAIN_HAND);
                FlightClient.log(
                        "Attacked original skeleton boss in reach, hp=" + boss.getHealth());
            }
            return;
        }
        if (tick - last < 9) return;
        if (action.equals("unlock") && mc.screen == null) {
            for (int i = 0; i < 9; i++)
                if (id(p.getInventory().getItem(i)).contains("key")) {
                    p.getInventory().selected = i;
                    break;
                }
            var point = Vec3.atCenterOf(REWARD).add(0, 0, .49);
            p.lookAt(Anchor.EYES, point);
            mc.gameMode.useItemOn(
                    p,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(point, Direction.SOUTH, REWARD, false));
            last = tick;
            return;
        }
        if (action.equals("take") && mc.screen instanceof AbstractContainerScreen<?> gui) {
            int slot = s.get("slot").getAsInt();
            if (gui.getMenu().getSlot(slot).hasItem()) {
                FlightClient.snap(mc, "original-reward");
                FlightClient.log(
                        "Shift-clicked original chest offering "
                                + gui.getMenu().getSlot(slot).getItem());
                mc.gameMode.handleInventoryMouseClick(
                        gui.getMenu().containerId, slot, 0, ClickType.QUICK_MOVE, p);
                last = tick;
            }
            return;
        }
        if (action.equals("deposit")) {
            if (mc.screen == null) {
                var pos =
                        new BlockPos(
                                s.get("x").getAsInt(),
                                s.get("y").getAsInt(),
                                s.get("z").getAsInt());
                p.lookAt(
                        Anchor.EYES,
                        new Vec3(
                                s.get("worldX").getAsDouble(),
                                s.get("worldY").getAsDouble(),
                                s.get("worldZ").getAsDouble()));
                mc.gameMode.useItemOn(
                        p,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3.atCenterOf(pos).add(0, .49, 0), Direction.UP, pos, false));
                last = tick;
                return;
            }
            if (mc.screen instanceof AbstractContainerScreen<?> gui) {
                for (var slot : gui.getMenu().slots) {
                    if (slot.container == p.getInventory()
                            && slot.hasItem()
                            && id(slot.getItem()).equals(s.get("item").getAsString())) {
                        FlightClient.snap(mc, "reward-to-cargo");
                        FlightClient.log(
                                "Deposited lunar reward into the physical ship chest "
                                        + slot.getItem());
                        mc.gameMode.handleInventoryMouseClick(
                                gui.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE, p);
                        last = tick;
                        return;
                    }
                }
            }
        }
    }
}
