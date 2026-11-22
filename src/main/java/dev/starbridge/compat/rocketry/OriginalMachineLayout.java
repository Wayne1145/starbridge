package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Read the static assembly plan from the same original controllers registered with the LibVulpes projector. */
public record OriginalMachineLayout(int width, int depth, int height, List<Cell> cells) {
    public enum Role { FRAME, CONTROLLER, POWER, FLUID_IN, FLUID_OUT, ITEM_IN, ITEM_OUT }
    public record Cell(int x, int layer, int z, Role role, ResourceLocation item, int metadata) {
        public ItemStack stack() {
            return ForgeRegistries.ITEMS.getDelegate(item).map(delegate -> {
                var stack = new ItemStack(delegate.get());
                if (metadata > 0) stack.getOrCreateTag().putInt("LegacyMeta", metadata);
                return stack;
            }).orElse(ItemStack.EMPTY);
        }
    }
    public static final ResourceLocation PROJECTOR = ResourceLocation.parse("libvulpes:item.holoprojector");

    public static Optional<OriginalMachineLayout> read(boolean reactor) {
        var id = ResourceLocation.parse(reactor ? "advancedrocketry:tile.chemreactor" : "advancedrocketry:tile.electrolyser");
        if (!ModList.get().isLoaded("retrofit") || !ForgeRegistries.BLOCKS.containsKey(id)) return Optional.empty();
        try {
            var realm = OriginalStarMap.gameLoader();
            if (realm == null) return Optional.empty();
            var type = realm.loadClass("zmaster587.advancedRocketry.tile.multiblock.machine." + (reactor ? "TileChemicalReactor" : "TileElectrolyser"));
            var controller = type.getConstructor().newInstance();
            Object[][][] plan = (Object[][][]) type.getMethod("getStructure").invoke(controller);
            var allowed = type.getMethod("getAllowableBlocks", Object.class);
            var host = Class.forName("retrofit.forge.world.LegacyContent").getMethod("byLegacy", Object.class);
            if (plan.length == 0 || plan.length > 8) return Optional.empty();
            int depth = plan[0].length, width = depth == 0 ? 0 : plan[0][0].length;
            if (width < 1 || depth < 1 || width > 8 || depth > 8) return Optional.empty();
            var cells = new ArrayList<Cell>();
            for (int y = 0; y < plan.length; y++) {
                if (plan[y].length != depth) return Optional.empty();
                for (int z = 0; z < depth; z++) {
                    if (plan[y][z].length != width) return Optional.empty();
                    for (int x = 0; x < width; x++) {
                        Object rule = plan[y][z][x];
                        if (rule == null) continue;
                        int layer = plan.length - 1 - y;
                        if (Character.valueOf('c').equals(rule)) {
                            cells.add(new Cell(x, layer, z, Role.CONTROLLER, id, 0));
                            continue;
                        }
                        var choices = (List<?>) allowed.invoke(controller, rule);
                        if (choices.isEmpty()) return Optional.empty();
                        var example = choices.get(0);
                        var legacyBlock = example.getClass().getMethod("getBlock").invoke(example);
                        if (!(host.invoke(null, legacyBlock) instanceof Block block)) return Optional.empty();
                        int meta = (int) example.getClass().getMethod("getMeta").invoke(example);
                        var item = ForgeRegistries.ITEMS.getKey(block.asItem());
                        if (item == null || new ItemStack(block).isEmpty()) return Optional.empty();
                        cells.add(new Cell(x, layer, z, role(rule), item, meta >= 0 && meta <= 15 ? meta : 0));
                    }
                }
            }
            return Optional.of(new OriginalMachineLayout(width, depth, plan.length, List.copyOf(cells)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Starbridge.LOGGER.debug("Could not open the original assembly plan for {}", id, failure);
            return Optional.empty();
        }
    }

    private static Role role(Object rule) {
        if (!(rule instanceof Character c)) return Role.FRAME;
        return switch (c) {
            case 'P' -> Role.POWER;
            case 'L' -> Role.FLUID_IN;
            case 'l' -> Role.FLUID_OUT;
            case 'I' -> Role.ITEM_IN;
            case 'O' -> Role.ITEM_OUT;
            default -> Role.FRAME;
        };
    }
}
