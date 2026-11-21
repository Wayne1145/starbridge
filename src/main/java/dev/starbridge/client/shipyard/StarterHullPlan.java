package dev.starbridge.client.shipyard;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.List;

/** The materials list and the assembler's example decks describe the very same starter ship. */
public final class StarterHullPlan {
    private StarterHullPlan() {}

    private static final ResourceLocation ENGINE = id("advancedrocketry:rocketmotor");
    private static final ResourceLocation TANK = id("advancedrocketry:fueltank");
    private static final ResourceLocation GUIDANCE = id("advancedrocketry:tile.guidancecomputer");
    private static final ResourceLocation SEAT = id("advancedrocketry:seat");
    private static final ResourceLocation HELM = id("starbridge:ship_core");
    private static final ResourceLocation FLOOR = id("minecraft:iron_block");
    private static final ResourceLocation CHEST = id("minecraft:chest");
    private static final ResourceLocation FURNACE = id("minecraft:furnace");
    private static final List<Part> PARTS = inventory();

    public record Part(ResourceLocation id, int required) {
        public ItemStack item() {
            var item = ForgeRegistries.ITEMS.getValue(id);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }
    }

    private static ResourceLocation id(String name) { return new ResourceLocation(name); }

    private static ResourceLocation hullAt(int x, int y, int z) {
        if (y == 1 && ((x == 1 && (z == 1 || z == 2)) || (x == 3 && z == 1))) return ENGINE;
        if ((y == 2 && x == 1 && z == 2) || (y == 3 && x >= 1 && x <= 3 && (z == 1 || z == 3))
                || (y == 4 && x == 2 && (z == 1 || z == 3))) return TANK;
        if (x == 2 && z == 2) return switch (y) { case 1 -> FLOOR; case 2 -> GUIDANCE; case 3 -> SEAT; default -> null; };
        if (x == 3 && z == 2) return switch (y) { case 1 -> CHEST; case 2 -> FURNACE; default -> null; };
        return y == 2 && x == 1 && z == 3 ? HELM : null;
    }

    static Block blockAt(int x, int y, int z) {
        var ground = LaunchPadPlan.at(x, y, z);
        var part = ground == null ? hullAt(x, y, z) : ground.blockId;
        return part == null ? null : ForgeRegistries.BLOCKS.getValue(part);
    }

    private static List<Part> inventory() {
        var counts = new LinkedHashMap<ResourceLocation, Integer>();
        for (var ground : LaunchPadPlan.values()) counts.put(ground.blockId, ground.count);
        for (int y = 0; y < LaunchPadPlan.HEIGHT; y++) {
            for (int z = 0; z < LaunchPadPlan.WIDTH; z++) {
                for (int x = 0; x < LaunchPadPlan.WIDTH; x++) {
                    var item = hullAt(x, y, z);
                    if (item != null) counts.merge(item, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream().map(e -> new Part(e.getKey(), e.getValue())).toList();
    }

    public static List<Part> materials() { return PARTS; }
}
