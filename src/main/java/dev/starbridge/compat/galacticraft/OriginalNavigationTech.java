package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.*;

/** Matches registered original schematic variants and working passenger rockets. */
public final class OriginalNavigationTech {
    public record Sample(ResourceLocation item, int metadata, int tier, boolean rocket) {
        public ItemStack stack() {
            Item type = ForgeRegistries.ITEMS.getValue(item);
            if (type == null) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(type);
            if (metadata != 0) stack.getOrCreateTag().putInt("LegacyMeta", metadata);
            return stack;
        }
    }

    private record Schematic(int metadata, int tier) {}

    private record Index(
            ClassLoader loader,
            Method metadata,
            int variants,
            List<Sample> examples,
            Map<Item, List<Sample>> items) {}

    private static final Set<String> SOURCES =
            Set.of("galacticraftcore", "galacticraftmars", "extraplanets", "galaxyspace");
    private static Index index;
    private static boolean warned;

    private OriginalNavigationTech() {}

    public static OptionalInt tier(ItemStack stack) {
        var found = inspect(stack);
        return found.isEmpty() ? OptionalInt.empty() : OptionalInt.of(found.get().tier());
    }

    public static Optional<Sample> inspect(ItemStack stack) {
        if (stack.isEmpty() || !ModList.get().isLoaded("retrofit")) return Optional.empty();
        // A normal inventory has many unrelated items. Only original wrappers can supply navigation
        // technology.
        String namespace = ForgeRegistries.ITEMS.getKey(stack.getItem()).getNamespace();
        if (!SOURCES.contains(namespace)) return Optional.empty();
        try {
            Index active = active();
            if (active == null) return Optional.empty();
            var candidates = active.items().get(stack.getItem());
            if (candidates == null) return Optional.empty();
            int metadata = (int) active.metadata().invoke(stack.getItem(), stack);
            for (var example : candidates) {
                if (example.rocket() && metadata >= 0 && metadata < active.variants())
                    return Optional.of(new Sample(example.item(), metadata, example.tier(), true));
                if (!example.rocket() && example.metadata() == metadata)
                    return Optional.of(example);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warn(e);
        }
        return Optional.empty();
    }

    public static List<Sample> examples() {
        if (ModList.get().isLoaded("retrofit")) {
            try {
                Index active = active();
                if (active != null) return active.examples();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                warn(e);
            }
        }
        return List.of();
    }

    private static void warn(Throwable error) {
        if (!warned) Starbridge.LOGGER.warn("Unable to read original navigation items", error);
        warned = true;
    }

    private static Index active() throws ReflectiveOperationException {
        var loader = OriginalStarMap.gameLoader();
        if (loader == null) return null;
        if (index == null || index.loader() != loader) index = load(loader);
        return index;
    }

    private static Index load(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> variants;
        try {
            variants =
                    loader.loadClass(
                            "micdoodle8.mods.galacticraft.api.entity.IRocketType$EnumRocketType");
        } catch (ClassNotFoundException absent) {
            return new Index(loader, null, 0, List.of(), Map.of());
        }
        Map<Object, Schematic> schematics = new IdentityHashMap<>();
        Map<String, Integer> rockets = new HashMap<>();
        // The registered item and its exact variant identify the original NASA workbench page on
        // either side.
        schematic(
                loader,
                schematics,
                "micdoodle8.mods.galacticraft.core.items.GCItems",
                "schematic",
                1,
                2);
        schematic(
                loader,
                schematics,
                "micdoodle8.mods.galacticraft.planets.mars.items.MarsItems",
                "schematic",
                0,
                3);
        rockets.put("micdoodle8.mods.galacticraft.planets.mars.items.ItemTier2Rocket", 2);
        rockets.put("micdoodle8.mods.galacticraft.planets.asteroids.items.ItemTier3Rocket", 3);
        for (int tier = 4; tier <= 10; tier++) {
            schematic(
                    loader,
                    schematics,
                    "com.mjr.extraplanets.items.ExtraPlanets_Items",
                    "schematicTier" + tier,
                    0,
                    tier);
            rockets.put("com.mjr.extraplanets.items.rockets.Tier" + tier + "Rocket", tier);
            if (tier <= 6)
                rockets.put(
                        "galaxyspace.systems.SolarSystem.planets.overworld.items.rockets.ItemTier"
                                + tier
                                + "Rocket",
                        tier);
        }
        var wrapper = Class.forName("retrofit.forge.world.LegacyStackItem");
        var unwrap = wrapper.getMethod("legacyItem");
        Map<Item, List<Sample>> items = new IdentityHashMap<>();
        List<Sample> examples = new ArrayList<>();
        for (var registered : ForgeRegistries.ITEMS.getEntries()) {
            Item host = registered.getValue();
            if (!wrapper.isInstance(host)) continue;
            Object original = unwrap.invoke(host);
            ResourceLocation id = registered.getKey().location();
            var paper = schematics.get(original);
            Integer rocket = rockets.get(original.getClass().getName());
            Sample sample;
            if (paper != null) sample = new Sample(id, paper.metadata(), paper.tier(), false);
            else if (rocket != null) sample = new Sample(id, 0, rocket, true);
            else continue;
            examples.add(sample);
            items.put(host, List.of(sample));
        }
        examples.sort(
                Comparator.comparingInt(Sample::tier)
                        .thenComparing(Sample::rocket)
                        .thenComparing(Sample::item));
        return new Index(
                loader,
                wrapper.getMethod("meta", ItemStack.class),
                variants.getEnumConstants().length,
                List.copyOf(examples),
                Map.copyOf(items));
    }

    private static void schematic(
            ClassLoader loader,
            Map<Object, Schematic> out,
            String holder,
            String field,
            int meta,
            int tier)
            throws ReflectiveOperationException {
        try {
            Object item = loader.loadClass(holder).getField(field).get(null);
            if (item != null) out.put(item, new Schematic(meta, tier));
        } catch (ClassNotFoundException | NoSuchFieldException absent) {
            /* Optional original mod or disabled tier. */
        }
    }
}
