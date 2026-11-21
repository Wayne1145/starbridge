package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/** A real, held upstream schematic or working rocket can calibrate the navigation computer. */
public final class OriginalNavigationTech {
    private record Proof(Object item, int metadata) {}
    private static final Map<Proof,Integer> SCHEMATICS = new HashMap<>();
    private static final Map<String,Integer> ROCKETS = new HashMap<>();
    private static ClassLoader indexedLoader;
    private static boolean warned;
    private OriginalNavigationTech() {}

    public static OptionalInt tier(ItemStack stack) {
        if (stack.isEmpty() || !ModList.get().isLoaded("retrofit")) return OptionalInt.empty();
        try {
            Class<?> wrapper = Class.forName("retrofit.forge.world.LegacyStackItem");
            if (!wrapper.isInstance(stack.getItem())) return OptionalInt.empty();
            Object item = wrapper.getMethod("legacyItem").invoke(stack.getItem());
            int metadata = (int) wrapper.getMethod("meta",ItemStack.class).invoke(stack.getItem(),stack);
            ClassLoader loader = OriginalStarMap.gameLoader();
            if (loader == null) return OptionalInt.empty();
            if (loader != indexedLoader) load(loader);
            String type = item.getClass().getName();
            Integer result = SCHEMATICS.get(new Proof(item,metadata));
            if (result == null) result = ROCKETS.get(type);
            return result == null ? OptionalInt.empty() : OptionalInt.of(result);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (!warned) { Starbridge.LOGGER.warn("Unable to read original rocket navigation technology",e); warned=true; }
            return OptionalInt.empty();
        }
    }
    private static void load(ClassLoader loader) throws ReflectiveOperationException {
        SCHEMATICS.clear(); ROCKETS.clear();
        // Known required items from GC3's SchematicTier2Rocket and SchematicTier3Rocket pages.
        // Read the registered items directly so GUI-only page classes need never load on a server.
        addItem(loader,"micdoodle8.mods.galacticraft.core.items.GCItems","schematic",1,2);
        addItem(loader,"micdoodle8.mods.galacticraft.planets.mars.items.MarsItems","schematic",0,3);
        ROCKETS.put("micdoodle8.mods.galacticraft.planets.mars.items.ItemTier2Rocket",2);
        ROCKETS.put("micdoodle8.mods.galacticraft.planets.asteroids.items.ItemTier3Rocket",3);
        for (int tier = 4; tier <= 10; tier++) {
            addItem(loader,"com.mjr.extraplanets.items.ExtraPlanets_Items","schematicTier"+tier,0,tier);
            ROCKETS.put("com.mjr.extraplanets.items.rockets.Tier"+tier+"Rocket",tier);
            if (tier <= 6) ROCKETS.put("galaxyspace.systems.SolarSystem.planets.overworld.items.rockets.ItemTier"+tier+"Rocket",tier);
        }
        indexedLoader=loader;
    }
    private static void addItem(ClassLoader loader,String holder,String field,int metadata,int tier) throws ReflectiveOperationException {
        try {
            Object item=loader.loadClass(holder).getField(field).get(null);
            if (item!=null) SCHEMATICS.put(new Proof(item,metadata),tier);
        } catch(ClassNotFoundException | NoSuchFieldException optional) {
            // An absent addon or tier supplies no upgrade.
        }
    }
}
