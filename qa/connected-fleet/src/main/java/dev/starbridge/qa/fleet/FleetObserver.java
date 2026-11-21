package dev.starbridge.qa.fleet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;

@Mod.EventBusSubscriber(modid = "starbridgefleetqa", value = Dist.CLIENT)
public final class FleetObserver {
    private static int clock, next, requests, renderedCabinTicks;
    private static boolean captured, moon, home, approach, opening;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) throws Exception {
        if (e.phase != TickEvent.Phase.END
                || !Files.isRegularFile(Path.of("starbridge-fleet-render.fixture"))) return;
        Minecraft mc = Minecraft.getInstance();
        clock++;
        if (mc.player == null) {
            if (mc.getOverlay() == null
                    && clock > next
                    && requests < 4
                    && (mc.screen instanceof TitleScreen
                            || mc.screen instanceof DisconnectedScreen)) {
                String host = "127.0.0.1:25891";
                requests++;
                next = clock + 250;
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(host),
                        new ServerData("Fleet test", host, true),
                        false);
            }
            return;
        }
        if (mc.level == null) return;
        mc.setWindowActive(true);
        if (!captured && mc.screen == null && mc.player.getX() > 230 && ++renderedCabinTicks >= 100) {
            captured = true;
            mc.player.setXRot(-7);
            capture(mc, "three-native-cargo-decks");
        }
        if (!Files.isRegularFile(Path.of("../server/fleet-flight-start"))) return;
        boolean lunar =
                mc.level.dimension().location().toString().equals("retrofit:legacy_dim_-28");
        if (lunar && !moon) {
            moon = true;
            approach = false;
            opening = false;
            capture(mc, "100-block-vs-moon");
        }
        if (moon && !lunar && !home) {
            home = true;
            capture(mc, "100-block-vs-return");
        }
        if (clock % 80 == 0) dev.starbridge.Starbridge.LOGGER.info("FLEET rendered flight stage {} in {} at {}",mc.screen==null?"world":mc.screen.getClass().getSimpleName(),mc.level.dimension().location(),mc.player.blockPosition());
        if (clock >= next && mc.screen == null && !lunar && !moon) {
            var bridge = new net.minecraft.core.BlockPos(242,179,66);
            if (mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(bridge)) < 20) {
                var face = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(bridge).add(0,0,.5),net.minecraft.core.Direction.SOUTH,bridge,false);
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,face);
                next = clock + 25;
            }
            return;
        }
        if (clock < next || mc.screen == null || home) return;
        String gui = mc.screen.getClass().getSimpleName();
        if (gui.equals("ChartScreen")) {
            if (click(mc.screen, "route.starbridge." + (lunar ? "home" : "moon"))
                    && click(mc.screen, "nav.starbridge.cruise")) {
                next = clock + 15;
                approach = false;
                opening = false;
            }
        } else if (gui.equals("OrbitScreen")) {
            if (!opening) {
                capture(mc, lunar ? "return-orbit" : "outbound-orbit");
                if (click(mc.screen, "orbit.starbridge.landing")) opening = true;
                next = clock + 15;
            } else if (!approach && click(mc.screen, "orbit.starbridge.touchdown")) {
                approach = true;
                next = clock + 70;
            } else if (approach && clock > next + 100) {
                approach = false;
                next = clock + 10;
            }
        }
    }

    private static boolean click(Screen screen, String key) {
        for (var node : screen.children())
            if (node instanceof Button b
                    && b.active
                    && b.visible
                    && b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals(key)) {
                double x = b.getX() + b.getWidth() * .5, y = b.getY() + b.getHeight() * .5;
                boolean result = screen.mouseClicked(x, y, 0);
            dev.starbridge.Starbridge.LOGGER.info("FLEET UI {} clicked={}",key,result);
                screen.mouseReleased(x, y, 0);
                return result;
            }
        return false;
    }

    private static void capture(Minecraft mc, String name) {
        Screenshot.grab(
                mc.gameDirectory,
                name + ".png",
                mc.getMainRenderTarget(),
                msg -> {
                    dev.starbridge.Starbridge.LOGGER.info("FLEET rendered {}", name);
                });
    }
}
