package dev.starbridge.qa.fuel;

import dev.starbridge.client.shipyard.ShipyardScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "starbridgenativefuelqa", value = Dist.CLIENT)
public final class FuelPilot {
    private static int ticks, last, seq = -1, sub, from;
    private static boolean failed;
    private static final Path SERVER = Path.of("../server");

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || failed || !Files.isRegularFile(FactoryTrial.MARK))
            return;
        Minecraft game = Minecraft.getInstance();
        ticks++;
        try {
            game.setWindowActive(true);
            if (game.player == null) {
                if (game.getOverlay() == null
                        && (game.screen instanceof TitleScreen
                                || game.screen instanceof DisconnectedScreen)
                        && ticks - last > 80) {
                    String addr = Files.readString(Path.of("lunar-server.txt")).trim();
                    ConnectScreen.startConnecting(
                            game.screen,
                            game,
                            ServerAddress.parseString(addr),
                            new ServerData("Native fuel workshop", addr, true),
                            false);
                    last = ticks;
                }
                return;
            }
            if (ticks - last < 5 || !Files.exists(SERVER.resolve("factory-order.json"))) return;
            var order =
                    FactoryTrial.JSON.fromJson(
                            Files.readString(SERVER.resolve("factory-order.json")),
                            FactoryTrial.Order.class);
            if (Files.isRegularFile(Path.of("factory-done-" + order.seq() + ".json"))) return;
            last = ticks;
            if (seq != order.seq()) {
                seq = order.seq();
                sub = 0;
                log(
                        "Start "
                                + seq
                                + " "
                                + order.action()
                                + " at "
                                + BlockPos.of(order.pos()).toShortString());
            }
            if (order.action().equals("inspect")) inspect(game, order);
            else if (order.action().equals("rocket")) dock(game, order);
            else if (order.action().equals("switch")) switchOn(game, order);
            else machine(game, order);
        } catch (Exception | LinkageError error) {
            failed = true;
            try {
                FactoryTrial.write(
                        Path.of("factory-client-failure.json"),
                        Map.of("seq", seq, "step", sub, "error", error.toString()));
            } catch (Exception ignored) {
            }
        }
    }

    private static void switchOn(Minecraft game, FactoryTrial.Order order) throws Exception {
        if (sub == 0) {
            if (game.screen == null && open(game, BlockPos.of(order.pos()))) sub++;
            return;
        }
        if (!game.player
                .containerMenu
                .getClass()
                .getName()
                .equals("retrofit.forge.world.LegacyMenu")) return;
        if (sub == 1) {
            Object nativeScreen =
                    game.player
                            .containerMenu
                            .getClass()
                            .getMethod("legacyScreen")
                            .invoke(game.player.containerMenu);
            var buttons = (List<?>) readField(nativeScreen, "field_146292_n", "buttonList");
            var set = new java.util.ArrayList<Map<String, Object>>();
            Object target = null;
            for (Object b : buttons) {
                String kind = b.getClass().getName();
                int x = (int) readField(b, "field_146128_h", "xPosition"),
                        y = (int) readField(b, "field_146129_i", "yPosition");
                int w = (int) readField(b, "field_146120_f", "width"),
                        h = (int) readField(b, "field_146121_g", "height");
                set.add(Map.of("type", kind, "x", x, "y", y, "w", w, "h", h));
                if (kind.toLowerCase(java.util.Locale.ROOT).contains("toggle")
                        || (w == 11 && h == 26)) target = b;
            }
            FactoryTrial.write(Path.of("original-controller-buttons-" + seq + ".json"), set);
            snap(game, "factory-controller-off-" + seq);
            FactoryTrial.require(
                    target != null, "Original power switch could not be identified: " + set);
            int x =
                    (int) readField(target, "field_146128_h", "xPosition")
                            + (int) readField(target, "field_146120_f", "width") / 2;
            int y =
                    (int) readField(target, "field_146129_i", "yPosition")
                            + (int) readField(target, "field_146121_g", "height") / 2;
            game.screen.mouseClicked(x, y, 0);
            game.screen.mouseReleased(x, y, 0);
            log("Clicked native power switch at " + x + ", " + y);
            sub++;
            return;
        }
        if (Files.isRegularFile(SERVER.resolve("factory-enabled-" + seq + ".json"))) {
            snap(game, "factory-controller-on-" + seq);
            game.player.closeContainer();
            done(order, "original switch on");
        }
    }

    private static Object readField(Object source, String... names) throws Exception {
        for (Class<?> cls = source.getClass(); cls != null; cls = cls.getSuperclass())
            for (String name : names) {
                try {
                    var f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(source);
                } catch (NoSuchFieldException missing) {
                }
            }
        throw new NoSuchFieldException(String.join(" / ", names));
    }

    private static void machine(Minecraft game, FactoryTrial.Order order) throws Exception {
        var player = game.player;
        var menu = player.containerMenu;
        if (sub == 0) {
            if (game.screen == null && open(game, BlockPos.of(order.pos()))) sub++;
            return;
        }
        if (!menu.getClass().getName().equals("retrofit.forge.world.LegacyMenu")) return;
        List<Slot> nativeSlots =
                menu.slots.stream().filter(s -> s.container != player.getInventory()).toList();
        boolean transferBucket = !order.output().isEmpty();
        FactoryTrial.require(
                nativeSlots.size() == (transferBucket ? 2 : 1),
                "Unexpected original menu size " + nativeSlots.size());
        switch (sub) {
            case 1 -> {
                var inventory =
                        menu.slots.stream()
                                .filter(
                                        s ->
                                                s.container == player.getInventory()
                                                        && key(s.getItem()).equals(order.input()))
                                .findFirst()
                                .orElseThrow();
                from = inventory.index;
                click(game, from, 0);
                sub++;
            }
            case 2 -> {
                if (!key(menu.getCarried()).equals(order.input())) return;
                click(game, nativeSlots.get(0).index, transferBucket ? 1 : 0);
                sub++;
            }
            case 3 -> {
                if (!menu.getCarried().isEmpty()) click(game, from, 0);
                sub++;
            }
            case 4 -> {
                if (!menu.getCarried().isEmpty()) return;
                if (!transferBucket) {
                    player.closeContainer();
                    done(order, "coal inserted");
                    return;
                }
                String observed = key(nativeSlots.get(1).getItem());
                if (observed.equals("minecraft:air")) return;
                boolean matches =
                        order.output().equals("gas")
                                ? List.of(
                                                "advancedrocketry:item.bucketoxygen",
                                                "advancedrocketry:item.buckethydrogen")
                                        .contains(observed)
                                : observed.equals(order.output());
                FactoryTrial.require(matches, "Unexpected native output " + observed);
                snap(game, "factory-output-" + seq);
                log("Visible native output " + observed);
                click(game, nativeSlots.get(1).index, 0);
                sub++;
            }
            case 5 -> {
                if (menu.getCarried().isEmpty()) return;
                var free =
                        menu.slots.stream()
                                .filter(
                                        s ->
                                                s.container == player.getInventory()
                                                        && s.getSlotIndex() < 9
                                                        && !s.hasItem())
                                .findFirst()
                                .orElseThrow();
                click(game, free.index, 0);
                sub++;
            }
            case 6 -> {
                if (!menu.getCarried().isEmpty()) return;
                player.closeContainer();
                done(order, "took original output");
            }
        }
    }

    private static void inspect(Minecraft game, FactoryTrial.Order order) throws Exception {
        if (sub == 0) {
            if (game.screen == null && open(game, BlockPos.of(order.pos()))) sub++;
            return;
        }
        if (!(game.screen instanceof ShipyardScreen screen)) return;
        var field = ShipyardScreen.class.getDeclaredField("view");
        field.setAccessible(true);
        var view = (dev.starbridge.compat.rocketry.ShipyardWire.View) field.get(screen);
        if (!view.job().equals("PACKED") || view.tank() < 0) return;
        FactoryTrial.require(
                view.tank() == 1000
                        && view.capacity() == 4500
                        && view.weight() == 18
                        && view.thrust() == 30,
                "Saved carrier's tank, rated thrust or mass did not appear on the shipyard screen: "
                        + view);
        var guide = ShipyardScreen.class.getDeclaredField("guide");
        guide.setAccessible(true);
        if (sub == 1) {
            FactoryTrial.require(
                    !guide.getBoolean(screen), "Expected the actual preflight page on opening");
            snap(game, "dock-preflight-tank");
            FactoryTrial.require(
                    button(screen, "shipyard.starbridge.guide"), "Missing blueprint entry");
            sub++;
        } else if (sub == 2) {
            FactoryTrial.require(
                    guide.getBoolean(screen) && button(screen, "shipyard.starbridge.preflight"),
                    "Cannot return from the example to the rocket");
            sub++;
        } else {
            FactoryTrial.require(!guide.getBoolean(screen), "The example covered the saved rocket");
            FactoryTrial.write(
                    Path.of("dock-instruments.json"),
                    Map.of(
                            "tank",
                            view.tank(),
                            "capacity",
                            view.capacity(),
                            "weight",
                            view.weight(),
                            "thrust",
                            view.thrust(),
                            "crew",
                            view.crewCount(),
                            "returnedFromExample",
                            true));
            done(order, "Viewed the persisted original rocket and returned from its blueprint");
        }
    }

    private static void dock(Minecraft game, FactoryTrial.Order order) throws Exception {
        if (sub == 0) {
            for (int i = 0; i < 9; i++)
                if (key(game.player.getInventory().getItem(i)).equals(FactoryTrial.FUEL)) {
                    game.player.getInventory().selected = i;
                    break;
                }
            if (!key(game.player.getMainHandItem()).equals(FactoryTrial.FUEL))
                throw new IllegalStateException("No processed original bucket in hotbar");
            if (game.screen == null && open(game, BlockPos.of(order.pos()))) sub++;
            return;
        }
        if (!(game.screen instanceof ShipyardScreen screen)) return;
        switch (sub) {
            case 1 -> {
                if (button(screen, "shipyard.starbridge.scan")) sub++;
            }
            case 2 -> {
                if (button(screen, "shipyard.starbridge.assemble")) sub++;
            }
            case 3 -> {
                if (Files.exists(SERVER.resolve("empty-original-rocket.json"))
                        && button(screen, "shipyard.starbridge.refuel")) sub++;
            }
            case 4 -> {
                if (key(game.player.getMainHandItem()).equals(FactoryTrial.FUEL)) return;
                snap(game, "factory-fuel-in-original-rocket");
                done(order, "refuelled assembled rocket through shipyard");
            }
        }
    }

    private static boolean open(Minecraft game, BlockPos target) throws Exception {
        var eyes = game.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        if (eyes.distanceTo(center) > 4.5) return false;
        var ray =
                game.level.clip(
                        new ClipContext(
                                eyes,
                                center,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                game.player));
        if (ray.getType() != HitResult.Type.BLOCK || !target.equals(ray.getBlockPos()))
            return false;
        game.player.lookAt(EntityAnchorArgument.Anchor.EYES, ray.getLocation());
        game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND, ray);
        log("Used visible face " + ray.getDirection() + " at " + target.toShortString());
        return true;
    }

    private static boolean button(ShipyardScreen screen, String key) throws Exception {
        for (var child : screen.children())
            if (child instanceof Button b
                    && b.active
                    && b.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals(key)) {
                int x = b.getX() + b.getWidth() / 2, y = b.getY() + b.getHeight() / 2;
                screen.mouseClicked(x, y, 0);
                screen.mouseReleased(x, y, 0);
                log("Clicked " + key);
                return true;
            }
        return false;
    }

    private static void click(Minecraft game, int slot, int button) {
        game.gameMode.handleInventoryMouseClick(
                game.player.containerMenu.containerId, slot, button, ClickType.PICKUP, game.player);
    }

    private static String key(ItemStack item) {
        return ForgeRegistries.ITEMS.getKey(item.getItem()).toString();
    }

    private static void snap(Minecraft game, String name) {
        Screenshot.grab(
                game.gameDirectory, name + ".png", game.getMainRenderTarget(), ignore -> {});
    }

    private static void done(FactoryTrial.Order order, String outcome) throws Exception {
        FactoryTrial.write(
                Path.of("factory-done-" + order.seq() + ".json"),
                Map.of("input", order.input(), "output", order.output(), "action", outcome));
    }

    private static void log(String s) throws Exception {
        Files.writeString(
                Path.of("factory-input.log"),
                ticks + " " + s + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
