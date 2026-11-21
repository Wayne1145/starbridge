package dev.starbridge.flightqa;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Resolve the installed recipes' real output: GalaxySpace's own suit made airtight by the original
 * reactor.
 */
final class RadiationEquipment {
    static Optional<Map<String, Object>> tryEquip(
            ServerPlayer player, Object legacy, ClassLoader realm, Method toModern)
            throws ReflectiveOperationException {
        if (!MissionSignals.JOINED && !LunarTrail.ENABLED
                && !java.nio.file.Files.isRegularFile(java.nio.file.Path.of("starbridge-split-worlds.fixture"))) {
            return Optional.empty();
        }
        Class<?> items;
        try {
            items = realm.loadClass("galaxyspace.core.registers.items.GSItems");
        } catch (ClassNotFoundException missing) {
            return Optional.empty();
        }
        if (java.nio.file.Files.exists(java.nio.file.Path.of("starbridge-ar-suit-control.fixture")))
            return Optional.empty();
        var stack = realm.loadClass("net.minecraft.item.ItemStack");
        var getItem = stack.getMethod("func_77973_b");
        var assembler =
                realm.loadClass(
                        "galaxyspace.systems.SolarSystem.planets.overworld.recipe.AssemberRecipes");
        var assemblerRecipes =
                (List<?>)
                        assembler
                                .getMethod("getRecipeList")
                                .invoke(assembler.getField("instance").get(null));
        var recipeOutput =
                realm.loadClass("net.minecraft.item.crafting.IRecipe").getMethod("func_77571_b");
        var machine = realm.loadClass("zmaster587.libVulpes.recipe.RecipesMachine");
        var reactor =
                realm.loadClass(
                        "zmaster587.advancedRocketry.tile.multiblock.machine.TileChemicalReactor");
        var machineRecipes =
                (List<?>)
                        machine.getMethod("getRecipes", Class.class)
                                .invoke(machine.getMethod("getInstance").invoke(null), reactor);
        var iface = realm.loadClass("zmaster587.libVulpes.interfaces.IRecipe");
        var outputs = iface.getMethod("getOutput");
        var recipeRows = new ArrayList<Object>();
        var slots =
                List.of(
                        EquipmentSlot.HEAD,
                        EquipmentSlot.CHEST,
                        EquipmentSlot.LEGS,
                        EquipmentSlot.FEET);
        var names = List.of("SpacesuitHelmet", "SpacesuitPlate", "SpacesuitLeg", "SpacesuitBoots");
        for (int i = 0; i < slots.size(); i++) {
            Object piece = items.getField(names.get(i)).get(null);
            if (!realm.loadClass("galaxyspace.api.item.IItemRadiation").isInstance(piece))
                throw new IllegalStateException("Missing genuine radiation suit: " + names.get(i));
            String baseRecipe = null;
            for (Object recipe : assemblerRecipes) {
                Object out = recipeOutput.invoke(recipe);
                if (out != null && getItem.invoke(out) == piece) {
                    baseRecipe = recipe.getClass().getName();
                    break;
                }
            }
            if (baseRecipe == null)
                throw new IllegalStateException("No original assembler recipe for " + names.get(i));
            Object sealed = null, sourceRecipe = null;
            for (Object recipe : machineRecipes) {
                for (Object out : (List<?>) outputs.invoke(recipe))
                    if (getItem.invoke(out) == piece) {
                        sealed = out;
                        sourceRecipe = recipe;
                        break;
                    }
                if (sealed != null) break;
            }
            if (sealed == null)
                throw new IllegalStateException(
                        "No original chemical reactor seal recipe for " + names.get(i));
            ItemStack modern =
                    (ItemStack)
                            toModern.invoke(null, stack.getMethod("func_77946_l").invoke(sealed));
            if (modern.isEmpty())
                throw new IllegalStateException("Unmapped native spacesuit piece");
            player.setItemSlot(slots.get(i), modern);
            recipeRows.add(
                    Map.of(
                            "equipment",
                            MissionSignals.id(modern),
                            "assemblerRecipe",
                            baseRecipe,
                            "reactorInputs",
                            String.valueOf(iface.getMethod("getIngredients").invoke(sourceRecipe)),
                            "reactorTicks",
                            iface.getMethod("getTime").invoke(sourceRecipe),
                            "reactorPower",
                            iface.getMethod("getPower").invoke(sourceRecipe),
                            "originalTag",
                            String.valueOf(modern.getTag())));
        }
        FlightDamageTrace.bind(player, legacy);
        return Optional.of(
                Map.of(
                        "GC",
                        "original mask, oxygen gear and two filled heavy tanks",
                        "radiationSuit",
                        "actual GalaxySpace spacesuit, all four pieces, each fitted with the output"
                            + " of its Advanced Rocketry chemical reactor recipe",
                        "recipes",
                        recipeRows));
    }
}
