package dev.starbridge.flightqa;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.*;
import java.util.*;

/**
 * Materials prepared for the flight tests. Every oxygen and armour stack is an actual original
 * item.
 */
final class OriginalEquipment {
    static Map<String, Object> equip(ServerPlayer player) throws ReflectiveOperationException {
        var proxy =
                Class.forName("retrofit.forge.world.LegacyPlayers")
                        .getMethod("mirror", Player.class, boolean.class)
                        .invoke(null, player, true);
        var realm = proxy.getClass().getClassLoader();
        var playerType = realm.loadClass("net.minecraft.entity.player.EntityPlayerMP");
        var item = realm.loadClass("net.minecraft.item.Item");
        var stack = realm.loadClass("net.minecraft.item.ItemStack");
        var newStack = stack.getConstructor(item, int.class, int.class);
        var gcItems = realm.loadClass("micdoodle8.mods.galacticraft.core.items.GCItems");
        var statsType =
                realm.loadClass("micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats");
        Object stats = statsType.getMethod("get", playerType).invoke(null, proxy);
        var inventory = statsType.getField("extendedInventory").get(stats);
        var put = inventory.getClass().getMethod("func_70299_a", int.class, stack);
        String[] parts = {"oxMask", "oxygenGear", "oxTankHeavy", "oxTankHeavy"};
        for (int n = 0; n < parts.length; n++)
            put.invoke(
                    inventory, n, newStack.newInstance(gcItems.getField(parts[n]).get(null), 1, 0));
        var world =
                Class.forName("retrofit.forge.world.LegacyContent")
                        .getMethod("legacyWorld", Level.class)
                        .invoke(null, player.serverLevel());
        var ar = realm.loadClass("zmaster587.advancedRocketry.api.AdvancedRocketryItems");
        var toModern =
                Class.forName("retrofit.forge.world.LegacyStacks")
                        .getMethod("toModern", Object.class);
        var slots =
                List.of(
                        EquipmentSlot.HEAD,
                        EquipmentSlot.CHEST,
                        EquipmentSlot.LEGS,
                        EquipmentSlot.FEET);
        String[] names = {"Helmet", "Chest", "Leggings", "Boots"};
        int air = 0;
        var modular = realm.loadClass("zmaster587.libVulpes.api.IModularArmor");
        var fillable = realm.loadClass("zmaster587.advancedRocketry.api.armor.IFillableArmor");
        for (int n = 0; n < slots.size(); n++) {
            Object piece = ar.getField("itemSpaceSuit_" + names[n]).get(null);
            Object legacyStack = newStack.newInstance(piece, 1, 0);
            if (n == 1) {
                Object tank = newStack.newInstance(ar.getField("itemPressureTank").get(null), 1, 0);
                modular.getMethod(
                                "addArmorComponent",
                                realm.loadClass("net.minecraft.world.World"),
                                stack,
                                stack,
                                int.class)
                        .invoke(piece, world, legacyStack, tank, 0);
                int max =
                        ((Number) fillable.getMethod("getMaxAir", stack).invoke(piece, legacyStack))
                                .intValue();
                fillable.getMethod("increment", stack, int.class).invoke(piece, legacyStack, max);
                air =
                        ((Number)
                                        fillable.getMethod("getAirRemaining", stack)
                                                .invoke(piece, legacyStack))
                                .intValue();
                if (air <= 0) throw new IllegalStateException("Native AR chest tank did not fill");
            }
            ItemStack modern = (ItemStack) toModern.invoke(null, legacyStack);
            if (modern.isEmpty())
                throw new IllegalStateException("Missing modern original suit stack " + names[n]);
            player.setItemSlot(slots.get(n), modern);
        }
        return Map.of(
                "GC",
                "mask, oxygen gear and two new heavy tanks in the real GC extended inventory",
                "AR",
                "full original pressure suit and installed tank",
                "ARoxygen",
                air);
    }
}
