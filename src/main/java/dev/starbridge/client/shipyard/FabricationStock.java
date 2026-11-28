package dev.starbridge.client.shipyard;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/** Allocates the units already carried to as many workbench slots as possible. */
final class FabricationStock {
    private FabricationStock() {}

    static int owned(Inventory inventory, ItemStack wanted) {
        if (inventory == null || wanted.isEmpty()) return 0;
        int count = 0;
        for (var stack : inventory.items) {
            if (ItemStack.isSameItemSameTags(stack, wanted)) count += stack.getCount();
        }
        for (var stack : inventory.offhand) {
            if (ItemStack.isSameItemSameTags(stack, wanted)) count += stack.getCount();
        }
        return count;
    }

    static int[] match(boolean[][] accepts, int[] quantities) {
        int[] units = new int[accepts.length * quantities.length];
        int size = 0;
        for (int stack = 0; stack < quantities.length; stack++) {
            for (int unit = 0; unit < Math.min(accepts.length, quantities[stack]); unit++) {
                units[size++] = stack;
            }
        }
        int[] owners = new int[size];
        Arrays.fill(owners, -1);
        for (int slot = 0; slot < accepts.length; slot++) {
            place(slot, accepts, units, owners, new boolean[size]);
        }
        int[] slots = new int[accepts.length];
        Arrays.fill(slots, -1);
        for (int unit = 0; unit < size; unit++) {
            if (owners[unit] >= 0) slots[owners[unit]] = units[unit];
        }
        return slots;
    }

    private static boolean place(int slot, boolean[][] accepts, int[] units, int[] owners, boolean[] visited) {
        for (int unit = 0; unit < owners.length; unit++) {
            if (visited[unit] || !accepts[slot][units[unit]]) continue;
            visited[unit] = true;
            int occupant = owners[unit];
            // Reassign a flexible slot so a more constrained slot can use this unit.
            if (occupant == -1 || place(occupant, accepts, units, owners, visited)) {
                owners[unit] = slot;
                return true;
            }
        }
        return false;
    }
}
