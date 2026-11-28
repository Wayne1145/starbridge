package dev.starbridge.client.shipyard;

import dev.starbridge.Starbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opt-in, local graphical QA. Click actual visible widgets and send the ordinary use-item-on-block
 * packet.
 */
@Mod.EventBusSubscriber(modid = Starbridge.ID, value = Dist.CLIENT)
public final class ShipyardClientFixture {
    private static final BlockPos POS = new BlockPos(19, 191, 17);
    private static int step, ticks, next;
    private static boolean saved, leftDuringAscent;
    private static Class<?> lastScreen;
    private static int connectTicks, attempts, repairStep;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (!Boolean.getBoolean("starbridge.shipyardGuiFixture")
                || e.phase != TickEvent.Phase.END
                || saved) return;
        var mc = Minecraft.getInstance();
        if (!Files.exists(Path.of("starbridge-disposable-shipyard.fixture"))) return;
        if (mc.player == null) {
            Class<?> current = mc.screen == null ? null : mc.screen.getClass();
            if (current != lastScreen) {
                lastScreen = current;
                trace(
                        "screen="
                                + current
                                + " "
                                + (mc.screen == null
                                        ? ""
                                        : mc.screen.getNarrationMessage().getString()));
            }
            connectTicks++;
            if (mc.getOverlay() == null
                    && attempts < 3
                    && connectTicks > (leftDuringAscent ? 340 : 120) + attempts * 660
                    && (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                            || mc.screen
                                    instanceof
                                    net.minecraft.client.gui.screens.DisconnectedScreen)) {
                attempts++;
                int port = Integer.getInteger("starbridge.shipyardGuiPort", 25594);
                if (port < 1 || port > 65535)
                    throw new IllegalArgumentException("Local shipyard fixture port");
                String target = "127.0.0.1:" + port;
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(target),
                        new net.minecraft.client.multiplayer.ServerData(
                                "Dock fixture", target, true),
                        false);
            }
            return;
        }
        if (mc.level == null || mc.gameMode == null) return;
        if (++ticks < next) return;
        next = ticks + 10;
        try {
            if (Boolean.getBoolean("starbridge.shipyardPair")
                    && mc.getUser()
                            .getName()
                            .equals(
                                    System.getProperty(
                                            "starbridge.shipyardEngineer", "ShipyardEngineer"))) {
                engineer(mc);
                if (ticks > 3400 && !saved) {
                    saved = true;
                    Files.writeString(
                            Path.of("shipyard-gui-client.txt"),
                            "FAIL engineer step " + step + " screen=" + mc.screen + "\n");
                }
                return;
            }
            if (Boolean.getBoolean("starbridge.shipyardRepair") && step == 5 && repairStep < 5) {
                repairGuidance(mc);
                if (ticks > 3400 && !saved) {
                    saved = true;
                    Files.writeString(
                            Path.of("shipyard-gui-client.txt"), "FAIL repair step " + repairStep);
                }
                return;
            }
            if (step == 0 && !(mc.screen instanceof ShipyardScreen)) {
                if (mc.player.distanceToSqr(Vec3.atCenterOf(POS)) > 25) return;
                mc.gameMode.useItemOn(
                        mc.player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3.atCenterOf(POS).add(0, 0, -.5), Direction.NORTH, POS, false));
                return;
            }
            if (mc.screen instanceof ShipyardScreen screen) {
                switch (step) {
                    case 0 -> {
                        if (click(screen, "guide")) {
                            step++;
                            trace("right-click opened dock; clicked blueprint");
                        }
                    }
                    case 1 -> {
                        if (click(screen, "up")) {
                            step++;
                            trace("clicked next layer");
                        }
                    }
                    case 2 -> {
                        if (click(screen, "rotate")) {
                            step++;
                            trace("rotated construction diagram");
                        }
                    }
                    case 3 -> {
                        picture(mc, "01-shipyard-blueprint.png");
                        if (click(screen, "ship")) {
                            step++;
                        }
                    }
                    case 4 -> {
                        if (click(screen, "scan")) {
                            step++;
                            trace("clicked native scan");
                        }
                    }
                    case 5 -> {
                        if (has(screen, "assemble")) {
                            picture(mc, "02-native-scan.png");
                            if (click(screen, "assemble")) {
                                step++;
                                trace("clicked assemble");
                            }
                        }
                    }
                    case 6 -> {
                        if (has(screen, "board") && click(screen, "refuel")) {
                            step++;
                            trace("clicked refuel with original propellant bucket");
                            next = ticks + 25;
                        }
                    }
                    case 7 -> {
                        mc.player.getInventory().selected = 2;
                        step++;
                        trace("selected an empty hotbar slot");
                    }
                    case 8 -> {
                        if (click(screen, "board")) {
                            step++;
                            trace("clicked board");
                        }
                    }
                    case 9 -> {
                        if (has(screen, "launch")
                                && (!Boolean.getBoolean("starbridge.shipyardPair")
                                        || screen.crewAboard() >= 2)) {
                            picture(mc, "03-native-launch.png");
                            if (click(screen, "launch")) {
                                step++;
                                trace("clicked launch");
                            }
                        }
                    }
                }
            } else if (step == 10 && mc.screen instanceof dev.starbridge.client.ChartScreen) {
                picture(mc, "04-cabin-chart.png");
                Files.writeString(
                        Path.of("shipyard-gui-client.txt"),
                        "PASS actual dock and star chart rendered; clicked layers, rotate, scan,"
                                + " assembly, original fuel, board, launch.\n");
                saved = true;
                trace("cabin chart received");
            }
            if (ticks > 3400) {
                saved = true;
                Files.writeString(
                        Path.of("shipyard-gui-client.txt"),
                        "FAIL timed out at graphical step " + step + " screen=" + mc.screen + "\n");
            }
        } catch (Exception ex) {
            saved = true;
            Starbridge.LOGGER.error("Graphical dock fixture failed", ex);
        }
    }

    private static void repairGuidance(Minecraft mc) {
        BlockPos support = new BlockPos(19, 191, 19);
        switch (repairStep) {
            case 0 -> {
                if (mc.screen instanceof ShipyardScreen screen
                        && !has(screen, "assemble")
                        && click(screen, "locate")) {
                    repairStep++;
                    trace("clicked Show part after the original scan rejected the ship");
                }
            }
            case 1 -> {
                if (mc.screen instanceof ShipyardScreen screen && has(screen, "ship")) {
                    picture(mc, "07-missing-guidance.png");
                    screen.onClose();
                    repairStep++;
                }
            }
            case 2 -> {
                if (mc.screen == null) {
                    mc.player.getInventory().selected = 1;
                    mc.gameMode.useItemOn(
                            mc.player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    Vec3.atCenterOf(support).add(0, .5, 0),
                                    Direction.UP,
                                    support,
                                    false));
                    repairStep++;
                    trace("used the guidance computer item on the empty deck position");
                }
            }
            case 3 -> {
                if (!mc.level.getBlockState(support.above()).isAir()) {
                    mc.player.getInventory().selected = 0;
                    mc.gameMode.useItemOn(
                            mc.player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    Vec3.atCenterOf(POS).add(0, 0, -.5),
                                    Direction.NORTH,
                                    POS,
                                    false));
                    repairStep++;
                }
            }
            case 4 -> {
                if (mc.screen instanceof ShipyardScreen screen && click(screen, "scan")) {
                    repairStep++;
                    trace("reopened the original assembler and clicked scan after the repair");
                }
            }
        }
    }

    private static Vec3 cabinEntry;

    private static void engineer(Minecraft mc) throws java.io.IOException {
        switch (step) {
            case 0 -> {
                if (mc.screen instanceof ShipyardScreen shipyard) {
                    if (click(shipyard, "board")) {
                        step++;
                        trace("engineer clicked the separate passenger seat");
                    }
                    return;
                }
                if (mc.player.distanceToSqr(Vec3.atCenterOf(POS)) <= 25)
                    mc.gameMode.useItemOn(
                            mc.player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    Vec3.atCenterOf(POS).add(0, 0, -.5),
                                    Direction.NORTH,
                                    POS,
                                    false));
            }
            case 1 -> {
                if (Boolean.getBoolean("starbridge.shipyardReconnect")
                        && !leftDuringAscent
                        && mc.player.isPassenger()
                        && mc.player.getY() > 196.5) {
                    leftDuringAscent = true;
                    attempts = connectTicks = 0;
                    trace("engineer disconnecting from the moving rocket at y=" + mc.player.getY());
                    mc.getConnection()
                            .getConnection()
                            .disconnect(Component.literal("Shipyard crew reconnect test"));
                    return;
                }
                if (mc.player.getX() < -20000 && mc.screen == null && !mc.player.isPassenger()) {
                    cabinEntry = mc.player.position();
                    mc.options.keyDown.setDown(true);
                    next = ticks + 7;
                    step++;
                    trace("engineer in fixed cabin, walking toward cargo");
                }
            }
            case 2 -> {
                mc.options.keyDown.setDown(false);
                next = ticks + 7;
                step++;
            }
            case 3 -> {
                double walked = mc.player.position().subtract(cabinEntry).horizontalDistance();
                if (walked < .3) {
                    mc.options.keyDown.setDown(true);
                    step = 2;
                    next = ticks + 6;
                    return;
                }
                var feet = mc.player.blockPosition();
                for (BlockPos pos :
                        BlockPos.betweenClosed(feet.offset(-3, -4, -3), feet.offset(3, 2, 3))) {
                    if (mc.level.getBlockEntity(pos)
                            instanceof net.minecraft.world.level.block.entity.ChestBlockEntity) {
                        var hit =
                                new BlockHitResult(
                                        Vec3.atCenterOf(pos).add(0, .49, 0),
                                        Direction.UP,
                                        pos.immutable(),
                                        false);
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                        trace("engineer walked " + walked + " blocks and used a cabin chest");
                        step++;
                        return;
                    }
                }
            }
            case 4, 5, 6 -> {
                if (!(mc.screen
                        instanceof
                        net.minecraft.client.gui.screens.inventory.ContainerScreen
                        screen)) return;
                if (step == 4) picture(mc, "05-engineer-cabin-chest.png");
                var menu = screen.getMenu();
                int index = step == 5 ? menu.slots.size() - 1 : 0;
                var slot = menu.getSlot(index);
                double x = screen.getGuiLeft() + slot.x + 8, y = screen.getGuiTop() + slot.y + 8;
                int mouse = step == 5 ? 1 : 0;
                if (!screen.mouseClicked(x, y, mouse)) return;
                screen.mouseReleased(x, y, mouse);
                step++;
            }
            case 7 -> {
                if (mc.player.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) == 1
                        && mc.player.containerMenu.getSlot(0).getItem().getCount() == 16) {
                    picture(mc, "06-engineer-retrieved-diamond.png");
                    Files.writeString(
                            Path.of("shipyard-gui-client.txt"),
                            "PASS engineer used passenger berth, walked in fixed cabin and clicked"
                                    + " one diamond out of the original chest.\n");
                    saved = true;
                    trace("engineer retained one diamond; chest and player visible");
                }
            }
        }
    }

    private static Button find(Screen s, String key) {
        var text = Component.translatable("shipyard.starbridge." + key).getString();
        return s.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.active && b.visible && b.getMessage().getString().equals(text))
                .findFirst()
                .orElse(null);
    }

    private static boolean has(Screen s, String key) {
        return find(s, key) != null;
    }

    private static boolean click(Screen s, String key) {
        var b = find(s, key);
        if (b == null) return false;
        double x = b.getX() + b.getWidth() / 2., y = b.getY() + b.getHeight() / 2.;
        boolean ok = s.mouseClicked(x, y, 0);
        s.mouseReleased(x, y, 0);
        return ok;
    }

    private static void trace(String s) {
        Starbridge.LOGGER.info("SHIPYARD GUI {}", s);
    }

    private static void picture(Minecraft m, String name) {
        Screenshot.grab(
                m.gameDirectory,
                name,
                m.getMainRenderTarget(),
                message -> trace(message.getString()));
    }
}
