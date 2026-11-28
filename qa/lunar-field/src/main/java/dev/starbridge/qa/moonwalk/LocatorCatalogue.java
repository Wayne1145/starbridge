package dev.starbridge.qa.moonwalk;

import dev.starbridge.compat.galacticraft.OriginalStarMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** A zero-player dedicated-server query of the loaded legacy item registry and crafting outputs. */
@Mod.EventBusSubscriber(modid = "starbridgelunarfieldqa", value = Dist.DEDICATED_SERVER)
public final class LocatorCatalogue {
  private static boolean original(String id) {
    String namespace = id.split(":", 2)[0].toLowerCase(Locale.ROOT);
    return namespace.startsWith("galacticraft")
        || namespace.equals("extraplanets")
        || namespace.equals("galaxyspace");
  }

  @SubscribeEvent
  public static void inspect(ServerStartedEvent e) throws Exception {
    if (!Files.isRegularFile(Path.of("lunar-locator-server.fixture"))) return;
    var server = e.getServer();
    try {
      FieldStart.require(
          server.isDedicatedServer() && server.getPlayerCount() == 0,
          "Zero-player dedicated server");
      var loader = OriginalStarMap.gameLoader();
      FieldStart.require(loader != null, "Running original loader");
      var itemType = loader.loadClass("net.minecraft.item.Item");
      Object registry = itemType.getField("field_150901_e").get(null);
      var name = registry.getClass().getMethod("func_148750_c", Object.class);
      var stack = loader.loadClass("net.minecraft.item.ItemStack");
      var getItem = stack.getMethod("func_77973_b");
      var damage = stack.getMethod("func_77960_j");
      var translatedKey = stack.getMethod("func_77977_a");
      var manager = loader.loadClass("net.minecraft.item.crafting.CraftingManager");
      var craft = manager.getMethod("func_77594_a").invoke(null);
      var recipes = (List<?>) manager.getMethod("func_77592_b").invoke(craft);
      var output =
          loader.loadClass("net.minecraft.item.crafting.IRecipe").getMethod("func_77571_b");
      var itemNames = new TreeMap<String, String>();
      for (var item : (Iterable<?>) registry) {
        String id = String.valueOf(name.invoke(registry, item));
        if (original(id)) itemNames.put(id, item.getClass().getName());
      }
      var modern =
          ForgeRegistries.ITEMS.getKeys().stream()
              .map(Object::toString)
              .filter(LocatorCatalogue::original)
              .sorted()
              .toList();
      var products = new TreeMap<String, String>();
      var dynamicTypes = new TreeMap<String, Integer>();
      int totalOutput = 0, nullOutput = 0, originals = 0;
      for (var recipe : recipes) {
        Object result = output.invoke(recipe);
        if (result == null || getItem.invoke(result) == null) {
          nullOutput++;
          dynamicTypes.merge(recipe.getClass().getName(), 1, Integer::sum);
          continue;
        }
        totalOutput++;
        Object item = getItem.invoke(result);
        String id = String.valueOf(name.invoke(registry, item));
        if (!original(id)) continue;
        originals++;
        String variant = id + "@" + damage.invoke(result);
        String label;
        try {
          label = String.valueOf(translatedKey.invoke(result));
        } catch (ReflectiveOperationException badDescription) {
          label = "(label unavailable)";
        }
        products.put(variant, label);
      }
      FieldStart.write(
          "locator-result.json",
          Map.ofEntries(
              Map.entry("players", server.getPlayerCount()),
              Map.entry("legacyItems", itemNames),
              Map.entry("forgeMirrors", modern),
              Map.entry("craftingRecipes", recipes.size()),
              Map.entry("recipesWithOutput", totalOutput),
              Map.entry("recipesWithoutStaticOutput", nullOutput),
              Map.entry("dynamicTypes", dynamicTypes),
              Map.entry("originalOutputRecipes", originals),
              Map.entry("originalOutputVariants", products),
              Map.entry("pass", true)));
    } catch (Throwable failure) {
      FieldStart.write("locator-result.json", Map.of("pass", false, "error", failure.toString()));
    } finally {
      server.halt(false);
    }
  }
}
