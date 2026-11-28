package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.LunarKitScreen;
import dev.starbridge.client.LunarRationsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.util.*;

/** Follow the departure checklist into the original ration recipe and back again. */
final class RationsCheck {
    private static int step;
    static boolean tick(Minecraft game) throws Exception {
        if (step == 0 && game.screen instanceof LunarKitScreen kit) {
            if (HabitatClient.click(kit,"nav.starbridge.kit.rations.title")) step++;
            return false;
        }
        if (step == 1 && game.screen instanceof LunarRationsScreen page) {
            var field=LunarRationsScreen.class.getDeclaredField("recipe");field.setAccessible(true);
            var recipe=(CraftingRecipe)field.get(page);
            if (recipe==null) throw new IllegalStateException("World did not publish the original ration recipe");
            var result=recipe.getResultItem(game.level.registryAccess());
            var match=ForgeRegistries.ITEMS.getKey(result.getItem());
            if (match==null || !match.toString().equals("galacticraftcore:item.basicitem") || result.getTag()==null || result.getTag().getInt("LegacyMeta")!=18) throw new IllegalStateException("Wrong ration shown");
            var ingredients=recipe.getIngredients().stream().filter(i->!i.isEmpty()).map(i->Arrays.stream(i.getItems()).map(item->String.valueOf(ForgeRegistries.ITEMS.getKey(item.getItem()))+" "+String.valueOf(item.getTag())).toList()).toList();
            if (ingredients.isEmpty()) throw new IllegalStateException("Empty ration recipe");
            HabitatClient.snap(game,"ration-recipe");
            if (!HabitatClient.click(page,"nav.starbridge.kit.rations.back") || !(game.screen instanceof LunarKitScreen)) return false;
            Files.writeString(Path.of("ration-recipe-result.json"),new Gson().toJson(Map.of("pass",true,"recipe",recipe.getId().toString(),"ingredients",ingredients,"result",match.toString(),"tag",result.getTag().toString(),"returnedToKit",true)));
            step++;
        }
        return step==2;
    }
}
