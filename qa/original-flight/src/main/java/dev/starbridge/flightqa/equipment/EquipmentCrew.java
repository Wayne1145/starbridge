package dev.starbridge.flightqa.equipment;

import com.google.gson.GsonBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Disposable non-op voyage crew. The fixture supplies items only in the vanilla inventory. */
@Mod.EventBusSubscriber(modid = "starbridge_flightqa")
public final class EquipmentCrew {
    static final boolean ACTIVE = Files.isRegularFile(Path.of("starbridge-equipment-ui.fixture"));
    static final boolean MISSING =
            Files.isRegularFile(Path.of("starbridge-equipment-missing.fixture"));
    static final boolean NO_HANDLER =
            Files.isRegularFile(Path.of("starbridge-equipment-no-handler.fixture"));

    static boolean expectsFailure() {
        return MISSING || NO_HANDLER;
    }

    private static Runnable restoreHandler;
    private static final Set<UUID> prepared = new HashSet<>();
    private static int start;

    static void write(String file, Object value) throws Exception {
        Files.writeString(
                Path.of(file), new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }

    private static List<String> hotbar(ServerPlayer player) {
        var items = new ArrayList<String>();
        for (int i = 0; i < 4; i++) items.add(player.getInventory().getItem(i).toString());
        return items;
    }

    private static Object mirror(ServerPlayer player) throws Exception {
        return Class.forName("retrofit.forge.world.LegacyPlayers")
                .getMethod("mirror", Player.class, boolean.class)
                .invoke(null, player, true);
    }

    private static List<String> slots(ServerPlayer player) throws Exception {
        Object mirror = mirror(player);
        var realm = mirror.getClass().getClassLoader();
        var gc = realm.loadClass("micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats");
        var stats =
                gc.getMethod("get", realm.loadClass("net.minecraft.entity.player.EntityPlayerMP"))
                        .invoke(null, mirror);
        var inventory = gc.getField("extendedInventory").get(stats);
        var result = new ArrayList<String>();
        for (int i = 0; i < 4; i++)
            result.add(
                    String.valueOf(
                            inventory
                                    .getClass()
                                    .getMethod("func_70301_a", int.class)
                                    .invoke(inventory, i)));
        return result;
    }
    /**
     * Simulate a registered original mod declining GUI 5, only in this explicitly armed test
     * server.
     */
    @SuppressWarnings("unchecked")
    private static void declineEquipmentMenu(ServerPlayer player) throws Exception {
        if (!NO_HANDLER || restoreHandler != null) return;
        var realm = mirror(player).getClass().getClassLoader();
        var instance =
                realm.loadClass("micdoodle8.mods.galacticraft.core.GalacticraftCore")
                        .getField("instance")
                        .get(null);
        var fml = realm.loadClass("cpw.mods.fml.common.FMLCommonHandler");
        var container =
                fml.getMethod("findContainerFor", Object.class)
                        .invoke(fml.getMethod("instance").invoke(null), instance);
        var network = realm.loadClass("cpw.mods.fml.common.network.NetworkRegistry");
        var field = network.getDeclaredField("serverGuiHandlers");
        field.setAccessible(true);
        var handlers = (Map<Object, Object>) field.get(network.getField("INSTANCE").get(null));
        var previous = Objects.requireNonNull(handlers.get(container));
        var callback = realm.loadClass("cpw.mods.fml.common.network.IGuiHandler");
        Object delegate =
                java.lang.reflect.Proxy.newProxyInstance(
                        realm,
                        new Class<?>[] {callback},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getServerGuiElement") && args[0].equals(5))
                                return null;
                            return method.invoke(previous, args);
                        });
        handlers.put(container, delegate);
        restoreHandler = () -> handlers.replace(container, delegate, previous);
    }

    @SubscribeEvent
    public static void stop(net.minecraftforge.event.server.ServerStoppingEvent event) {
        if (restoreHandler != null) {
            restoreHandler.run();
            restoreHandler = null;
        }
    }

    private static void prepare(ServerPlayer player, boolean captain) throws Exception {
        require(
                !player.hasPermissions(2) && !player.isCreative() && !player.isSpectator(),
                "Ordinary survival player required");
        player.getInventory()
                .setItem(
                        4,
                        new ItemStack(
                                ForgeRegistries.ITEMS.getValue(
                                        new ResourceLocation("starbridge", "navigation_slate"))));
        player.getInventory().selected = 4;
        if (!MISSING) {
            declineEquipmentMenu(player);
            var realm = mirror(player).getClass().getClassLoader();
            var gcItems = realm.loadClass("micdoodle8.mods.galacticraft.core.items.GCItems");
            var stack =
                    realm.loadClass("net.minecraft.item.ItemStack")
                            .getConstructor(
                                    realm.loadClass("net.minecraft.item.Item"),
                                    int.class,
                                    int.class);
            var modern =
                    Class.forName("retrofit.forge.world.LegacyStacks")
                            .getMethod("toModern", Object.class);
            String[] parts = {"oxMask", "oxygenGear", "oxTankHeavy", "oxTankHeavy"};
            for (int i = 0; i < 4; i++)
                player.getInventory()
                        .setItem(
                                i,
                                (ItemStack)
                                        modern.invoke(
                                                null,
                                                stack.newInstance(
                                                        gcItems.getField(parts[i]).get(null),
                                                        1,
                                                        0)));
            require(
                    slots(player).equals(List.of("null", "null", "null", "null")),
                    "GC equipment was not empty before play");
        } else
            player.getInventory()
                    .setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
        var level = player.serverLevel();
        var helm = new BlockPos(1, 120, 1);
        for (int x = -1; x <= 8; x++)
            for (int z = -1; z <= 4; z++)
                level.setBlockAndUpdate(new BlockPos(x, 119, z), Blocks.STONE.defaultBlockState());
        if (captain) {
            var block =
                    ForgeRegistries.BLOCKS.getValue(
                            new ResourceLocation("starbridge", "ship_core"));
            level.setBlockAndUpdate(helm, block.defaultBlockState());
            block.setPlacedBy(level, helm, block.defaultBlockState(), player, new ItemStack(block));
        }
        player.teleportTo(level, captain ? 1.5 : 6.5, 120, 3.5, 180, 10);
        player.inventoryMenu.broadcastChanges();
        write(
                "equipment-" + player.getGameProfile().getName() + "-baseline.json",
                Map.of(
                        "nonOpSurvival",
                        true,
                        "hotbar",
                        hotbar(player),
                        "ownsHelm",
                        captain,
                        "alreadyLinked",
                        player.getPersistentData().hasUUID("starbridge:selected_core")));
    }

    private static void require(boolean condition, String why) {
        if (!condition) throw new IllegalStateException(why);
    }

    private static List<String> expected() {
        return List.of(
                "1xitem.oxygenMask@0",
                "1xitem.oxygenGear@0",
                "1xitem.oxygenTankHeavyFull@0",
                "1xitem.oxygenTankHeavyFull@0");
    }

    private static boolean done(String who) {
        return Files.isRegularFile(Path.of("..", who, "equipment-ui-result.json"));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (!ACTIVE || event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        if (start == 0) start = server.getTickCount();
        if (server.getTickCount() % 5 != 0) return;
        try {
            var captain = server.getPlayerList().getPlayerByName("OrbitCaptain");
            var engineer = server.getPlayerList().getPlayerByName("OrbitEngineer");
            for (var person : server.getPlayerList().getPlayers())
                if (prepared.add(person.getUUID())) prepare(person, person == captain);
            if (server.getTickCount() - start > 7000)
                throw new IllegalStateException("Crew interaction timed out");
            if (captain == null || engineer == null) return;
            if (!expectsFailure()
                    && done("OrbitCaptain")
                    && !Files.isRegularFile(Path.of("captain-checked.json"))) {
                require(
                        slots(captain).equals(expected())
                                && slots(engineer).equals(List.of("null", "null", "null", "null")),
                        "Another player's inventory changed");
                write(
                        "captain-checked.json",
                        Map.of(
                                "captainSlots",
                                slots(captain),
                                "engineerSlots",
                                slots(engineer),
                                "engineerHotbar",
                                hotbar(engineer)));
            }
            if (!done("OrbitCaptain") || !done("OrbitEngineer")) return;
            var report = new LinkedHashMap<String, Object>();
            for (var person : List.of(captain, engineer)) {
                boolean linked = person.getPersistentData().hasUUID("starbridge:selected_core");
                require(linked == (person == captain), "Unexpected helm binding");
                var items = hotbar(person);
                var equipment = MISSING ? List.of() : slots(person);
                if (expectsFailure()) {
                    require(
                            person.containerMenu == person.inventoryMenu,
                            "A failed request replaced an open container");
                    if (MISSING)
                        require(
                                items.equals(List.of("3 diamond", "0 air", "0 air", "0 air")),
                                "Lost sample inventory");
                    else
                        require(
                                equipment.equals(List.of("null", "null", "null", "null"))
                                        && items.equals(
                                                List.of(
                                                        "1 item.oxygenmask",
                                                        "1 item.oxygengear",
                                                        "1 item.oxygentankheavyfull",
                                                        "1 item.oxygentankheavyfull")),
                                "Original equipment was moved after denial");
                } else
                    require(
                            equipment.equals(expected())
                                    && items.equals(List.of("0 air", "0 air", "0 air", "0 air")),
                            "Unexpected original equipment");
                report.put(
                        person.getGameProfile().getName(),
                        Map.of(
                                "linked",
                                linked,
                                "hotbar",
                                items,
                                "originalSlots",
                                equipment,
                                "menu",
                                person.containerMenu.getClass().getName()));
            }
            report.put("pass", true);
            report.put("missing", MISSING);
            report.put("nativeMenuUnavailable", NO_HANDLER);
            write("equipment-ui-result.json", report);
            server.halt(false);
        } catch (Exception failure) {
            write(
                    "equipment-ui-result.json",
                    Map.of("pass", false, "exception", failure.toString()));
            server.halt(false);
        }
    }
}
