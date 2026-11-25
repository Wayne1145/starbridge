package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Checks that an offline-ready Retrofit client exposes the original stacks to both item menus. */
final class OriginalInventoryCheck {
    static final Path MARKER = Path.of("starbridge-original-inventory.fixture");
    private static final Path RESULT = Path.of("original-inventory-client.json");
    private static final List<String> ORIGINALS = List.of("advancedrocketry", "galacticraftcore", "extraplanets", "galaxyspace");
    private static boolean opened;

    private OriginalInventoryCheck() {}

    static void tick(Minecraft mc) throws Exception {
        if (Files.isRegularFile(RESULT)) return;
        if (!opened && mc.screen == null) {
            KeyMapping.click(mc.options.keyInventory.getKey());
            opened = true;
            return;
        }
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) return;
        var jei = Class.forName("mezz.jei.common.Internal");
        var runtime = (java.util.Optional<?>) jei.getMethod("getOptionalJeiRuntime").invoke(null);
        if (runtime.isEmpty()) return;
        var filter = Class.forName("mezz.jei.api.runtime.IJeiRuntime")
                .getMethod("getIngredientFilter").invoke(runtime.get());
        var filterApi = Class.forName("mezz.jei.api.runtime.IIngredientFilter");
        var displayed = (Collection<?>) filterApi.getMethod("getFilteredItemStacks").invoke(filter);
        var creative = new TreeMap<String, Integer>();
        for (var tab : CreativeModeTabs.tabs()) {
            for (var stack : tab.getDisplayItems()) count(creative, stack);
        }
        var ingredients = new TreeMap<String, Integer>();
        for (Object stack : displayed) if (stack instanceof ItemStack item) count(ingredients, item);
        boolean present = ORIGINALS.stream().allMatch(id -> creative.containsKey(id) && ingredients.containsKey(id));
        HabitatClient.log("Opened the creative inventory using the bound inventory key");
        HabitatClient.log("Counted original items in visible creative tabs and the active JEI item list");
        HabitatClient.snap(mc, "original-inventory-creative");
        Files.writeString(RESULT, new Gson().toJson(Map.of("pass", present,
                "creative", creative, "jei", ingredients)));
    }

    private static void count(Map<String, Integer> totals, ItemStack stack) {
        if (!stack.isEmpty()) totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace(), 1, Integer::sum);
    }
}
