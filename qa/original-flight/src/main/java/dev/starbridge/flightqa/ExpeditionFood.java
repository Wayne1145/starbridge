package dev.starbridge.flightqa;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Map;

/** Additional test supplies for a long walk. Resolve a real recipe from the installed GC release. */
final class ExpeditionFood {
    static Map<String,Object> prepare(ServerPlayer engineer, boolean firstSupply) throws ReflectiveOperationException {
        Object mirror = Class.forName("retrofit.forge.world.LegacyPlayers")
                .getMethod("mirror",Player.class,boolean.class).invoke(null,engineer,true);
        var realm = mirror.getClass().getClassLoader();
        var craft = realm.loadClass("net.minecraft.item.crafting.CraftingManager");
        Object manager = craft.getMethod("func_77594_a").invoke(null);
        var legacyStack = realm.loadClass("net.minecraft.item.ItemStack");
        Object foodItem = realm.loadClass("micdoodle8.mods.galacticraft.core.items.GCItems").getField("basicItem").get(null);
        String[] variants = (String[])foodItem.getClass().getField("names").get(null);
        int potato = java.util.Arrays.asList(variants).indexOf("dehydratedPotato");
        if (potato < 0) throw new IllegalStateException("No original potato ration in " + java.util.Arrays.toString(variants));
        var item = legacyStack.getMethod("func_77973_b");
        var damage = legacyStack.getMethod("func_77960_j");
        var output = realm.loadClass("net.minecraft.item.crafting.IRecipe").getMethod("func_77571_b");
        var convert = Class.forName("retrofit.forge.world.LegacyStacks").getMethod("toModern",Object.class);
        for (Object recipe : (List<?>)craft.getMethod("func_77592_b").invoke(manager)) {
            Object candidate = output.invoke(recipe);
            if (candidate==null || item.invoke(candidate) != foodItem || ((Number)damage.invoke(candidate)).intValue() != potato) continue;
            ItemStack food=(ItemStack)convert.invoke(null,legacyStack.getMethod("func_77946_l").invoke(candidate));
            if (food.isEmpty()) throw new IllegalStateException("Missing original lunar ration on host");
            ItemStack carried=engineer.getInventory().getItem(2);
            boolean retained=ItemStack.isSameItemSameTags(carried,food);
            boolean issued = !retained && firstSupply;
            int quantity = retained ? carried.getCount() : 0;
            if (issued) {
                quantity = Math.min(32, food.getMaxStackSize());
                food.setCount(quantity);
                engineer.getInventory().setItem(2, food);
            }
            return Map.of("item",MissionSignals.id(food),"variant",variants[potato],"metadata",potato,"quantity",quantity,"retained",retained,"issued",issued,"originalRecipe",recipe.getClass().getName());
        }
        throw new IllegalStateException("No original registered crafting recipe for dehydrated potatoes");
    }
}
