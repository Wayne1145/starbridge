package dev.starbridge.survivalqa;

import dev.starbridge.compat.rocketry.OriginalMachineLayout.Role;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Check actual coal energy and steel output in a fresh dedicated diagnostic world. */
final class ArcFurnaceProbe {
    private static final boolean ACTIVE = Files.isRegularFile(Path.of("arc-furnace-diagnostics.fixture"));
    private static final String RESULT = "original-arc-furnace-probe.json";
    private static final BlockPos CONTROLLER = new BlockPos(57, 155, 25);
    private static final int DEADLINE = 7400;
    private static int started = -1, peakPower;
    private static BlockPos output, contact;
    private static Object nativeController;
    private static boolean finished;
    private static String initialFuel;

    static void tick(MinecraftServer server) {
        if (!ACTIVE || finished) return;
        if (!server.getPlayerList().getPlayers().isEmpty()) throw new IllegalStateException("Use an empty diagnostic server");
        try {
            int now = server.getTickCount();
            var level = server.overworld();
            if (started < 0) {
                start(level);
                started = now;
            }
            if ((now - started) % 20 != 0) return;
            peakPower = Math.max(peakPower, PowerProbe.stored(level.getBlockEntity(contact), Direction.UP));
            ItemStack stack = readOutput(level.getBlockEntity(output));
            int elapsed = now - started;
            if (stack.isEmpty() && elapsed < DEADLINE) return;
            boolean steel = Expedition.item(stack).equals("libvulpes:libvulpesproductingot")
                    && stack.hasTag() && stack.getTag().getInt("LegacyMeta") == 6 && stack.getCount() == 1;
            Expedition.save(RESULT, Map.of("pass", steel && peakPower > 0, "elapsedTicks", elapsed,
                    "peakBatteryFE", peakPower, "fuelEntry", initialFuel,
                    "output", stack.save(new CompoundTag()).toString(), "progress",
                    nativeController.getClass().getMethod("getProgress", int.class).invoke(nativeController, 0),
                    "totalProgress", nativeController.getClass().getMethod("getTotalProgress", int.class).invoke(nativeController, 0)));
            finished = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Expedition.save(RESULT, Map.of("pass", false, "problem", failure.toString(), "peakBatteryFE", peakPower));
            finished = true;
        }
    }

    private static void start(ServerLevel level) throws ReflectiveOperationException {
        // This isolated diagnostic has no nearby players to keep the native tiles ticking.
        level.setChunkForced(CONTROLLER.getX() >> 4, CONTROLLER.getZ() >> 4, true);
        var ports = new EnumMap<Role, BlockPos>(Role.class);
        var assembled = WorkshopProbe.assemble(level, Station.ARC_FURNACE, CONTROLLER,
                (cell, pos) -> ports.putIfAbsent(cell.role(), pos));
        if (!Boolean.TRUE.equals(assembled.get("complete"))) throw new IllegalStateException("Native furnace did not form");
        contact = ports.get(Role.POWER);
        output = ports.get(Role.ITEM_OUT);
        var generator = contact.above();
        WorkshopProbe.place(level, generator, ResourceLocation.parse("libvulpes:tile.coalgenerator"), 0);
        initialFuel = PowerProbe.addCoal(level.getBlockEntity(generator));
        var inputTile = nativeTile(level.getBlockEntity(ports.get(Role.ITEM_IN)));
        setInput(inputTile, 0, new ItemStack(Items.IRON_INGOT));
        setInput(inputTile, 1, new ItemStack(Items.CHARCOAL));
        nativeController = nativeTile(level.getBlockEntity(CONTROLLER));
        nativeController.getClass().getMethod("setMachineEnabled", boolean.class).invoke(nativeController, true);
    }

    private static Object nativeTile(BlockEntity tile) throws ReflectiveOperationException {
        if (tile == null) throw new IllegalStateException("Missing native tile");
        return tile.getClass().getMethod("legacyTile").invoke(tile);
    }

    private static void setInput(Object tile, int slot, ItemStack input) throws ReflectiveOperationException {
        var bridge = Class.forName("retrofit.forge.world.LegacyStacks");
        var stack = bridge.getMethod("toLegacy", ItemStack.class).invoke(null, input);
        tile.getClass().getMethod("func_70299_a", int.class, stack.getClass()).invoke(tile, slot, stack);
    }

    private static ItemStack readOutput(BlockEntity hatch) throws ReflectiveOperationException {
        var tile = nativeTile(hatch);
        int size = (int) tile.getClass().getMethod("func_70302_i_").invoke(tile);
        var getStack = tile.getClass().getMethod("func_70301_a", int.class);
        var toModern = Class.forName("retrofit.forge.world.LegacyStacks").getMethod("toModern", Object.class);
        for (int slot = 0; slot < size; slot++) {
            var stack = getStack.invoke(tile, slot);
            if (stack != null) {
                var modern = (ItemStack) toModern.invoke(null, stack);
                if (!modern.isEmpty()) return modern;
            }
        }
        return ItemStack.EMPTY;
    }

    private ArcFurnaceProbe() {}
}
