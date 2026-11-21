package dev.starbridge.client.shipyard;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** The ground equipment in the original assembler's six-layer starter example. */
public enum LaunchPadPlan {
    PAD("launchpad", "pad", 9, 0xff739eaa),
    TOWER("structuretower", "tower", 6, 0xffdfaf69),
    ASSEMBLER("rocketbuilder", "assembler", 1, 0xff75d8bd);

    public static final int WIDTH = 5, HEIGHT = 6;
    public final ResourceLocation blockId;
    public final String label;
    public final int count, color;

    LaunchPadPlan(String block, String label, int count, int color) {
        this.blockId = new ResourceLocation("advancedrocketry", block);
        this.label = label;
        this.count = count;
        this.color = color;
    }

    public ItemStack item() {
        var block = ForgeRegistries.BLOCKS.getValue(blockId);
        return block == null ? ItemStack.EMPTY : new ItemStack(block);
    }

    public static LaunchPadPlan at(int x, int y, int z) {
        if (y == 0 && x >= 1 && x <= 3 && z >= 1 && z <= 3) return PAD;
        if (x == 0 && z == 2 && y >= 0 && y < HEIGHT) return TOWER;
        if (y == 1 && x == 2 && z == 0) return ASSEMBLER;
        return null;
    }

    /** Top view looks down. The elevation looks into the yard from the assembler's screen. */
    public static LaunchPadPlan visibleCell(boolean side, int column, int row) {
        if (side) {
            for (int z = 0; z < WIDTH; z++) {
                var cell = at(column, HEIGHT - row - 1, z);
                if (cell != null) return cell;
            }
        } else {
            for (int y = HEIGHT - 1; y >= 0; y--) {
                var cell = at(column, y, row);
                if (cell != null) return cell;
            }
        }
        return null;
    }
}
