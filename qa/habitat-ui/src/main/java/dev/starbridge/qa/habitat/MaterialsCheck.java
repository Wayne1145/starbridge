package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.client.shipyard.LaunchPadPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Use the visible blueprint, ingredient and back buttons in a real client with the original recipes. */
final class MaterialsCheck {
    private static int step, next;
    private static Screen guide, tower;
    private static List<String> potions;
    private static String rod;
    private MaterialsCheck() {}

    static boolean tick(Minecraft game, int ticks) throws Exception {
        if (step == 11) return true;
        if (ticks < next) return false;
        Screen page = game.screen;
        switch (step) {
            case 0 -> {
                if (!(page instanceof FirstFlightScreen) || !HabitatClient.click(page, "slate.starbridge.materials.title")) return false;
                guide = page;
            }
            case 1 -> {
                if (!(page instanceof FabricationScreen) || ingredients(page).size() != 3) return false;
                HabitatClient.snap(game,"00-materials-launch-pad");
                var name = LaunchPadPlan.TOWER.item().getHoverName().getString();
                var part = page.children().stream().filter(b -> b instanceof Button button && button.getMessage().getString().equals(name))
                        .map(b -> (Button) b).findFirst().orElseThrow();
                click(page, part);
            }
            case 2 -> {
                var slots = ingredients(page);
                if (slots.size() != 7 || slots.stream().map(b -> b.getMessage().getString()).distinct().count() != 1) return false;
                rod = slots.get(0).getMessage().getString();
                HabitatClient.snap(game,"00-materials-tower");
                click(page, slots.get(0));
            }
            case 3 -> {
                if (!(page instanceof FabricationScreen)) return false;
                HabitatClient.snap(game,"00-materials-rod");
                if (!HabitatClient.click(page,"slate.starbridge.materials.back_ingredient")) return false;
            }
            case 4 -> {
                if (ingredients(page).size() != 7 || !HabitatClient.click(page, "slate.starbridge.materials.workshop")) return false;
                tower = page;
            }
            case 5 -> {
                var slots = ingredients(page);
                if (slots.size() != 4 || count(slots, Items.PISTON.getDefaultInstance()) != 1 || count(slots, Items.IRON_INGOT.getDefaultInstance()) != 3)
                    throw new IllegalStateException("The workshop must open on the piston and three iron ingots");
                HabitatClient.snap(game,"00-workshop-press");
                selectIcon(page, "advancedrocketry:utilblock");
            }
            case 6 -> {
                var slots = ingredients(page);
                var resistance = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.FIRE_RESISTANCE);
                var strength = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH);
                if (slots.size() != 6 || count(slots, resistance) != 1 || count(slots, strength) != 1 || count(slots, new ItemStack(Items.BRICKS)) != 4)
                    throw new IllegalStateException("Brick ingredients displayed: " + slots.stream().map(b -> b.getMessage().getString()).toList());
                boolean another = page.children().stream().anyMatch(b -> b instanceof Button btn && btn.getMessage().getString().equals("›"));
                if (another) throw new IllegalStateException("Misleading brick recipe still offered in the locked pack");
                potions = List.of(resistance.getHoverName().getString(), strength.getHoverName().getString());
                HabitatClient.snap(game,"00-workshop-bricks");
                selectIcon(page, "libvulpes:tile.coalgenerator");
            }
            case 7 -> {
                var slots = ingredients(page);
                var battery = item("libvulpes:battery");
                if (slots.size() != 2 || count(slots, battery) != 1 || count(slots, new ItemStack(Items.FURNACE)) != 1)
                    throw new IllegalStateException("Original generator ingredients missing");
                click(page, slots.stream().filter(b -> b.getMessage().getString().equals(inspectLabel(battery))).findFirst().orElseThrow());
            }
            case 8 -> {
                if (count(ingredients(page), new ItemStack(Items.REDSTONE)) != 2 || !HabitatClient.click(page,"slate.starbridge.materials.back_ingredient")) return false;
            }
            case 9 -> {
                if (!HabitatClient.click(page,"slate.starbridge.materials.back_parts") || game.screen != tower || ingredients(game.screen).size() != 7)
                    throw new IllegalStateException("Workshop lost the selected original tower");
                if (!HabitatClient.click(game.screen,"slate.starbridge.materials.back_plan")) return false;
            }
            case 10 -> {
                if (game.screen != guide) throw new IllegalStateException("Lost the original launch pad guide");
                var result = Map.of("pass",true,"role","ordinary survival host", "padIngredients",3,"towerIngredients",7,
                        "inspected",rod,"returnsToSamePlan",true,"mainHand",game.player.getMainHandItem().toString(),
                        "press", "1 piston and 3 iron ingots", "brickPotions", potions, "generatorLinksToBattery",true);
                Files.writeString(Path.of("materials-ui-result.json"), new Gson().toJson(result));
            }
            default -> throw new IllegalStateException();
        }
        step++; next = ticks + 15;
        return step == 11;
    }

    private static ItemStack item(String id) {
        return BuiltInRegistries.ITEM.getOptional(new ResourceLocation(id)).orElseThrow().getDefaultInstance();
    }
    private static void selectIcon(Screen page, String id) {
        String label = item(id).getHoverName().getString();
        click(page, page.children().stream().filter(b -> b instanceof Button button && button.getMessage().getString().equals(label))
                .map(b -> (Button)b).findFirst().orElseThrow());
    }
    private static String inspectLabel(ItemStack stack) {
        return Component.translatable("slate.starbridge.materials.inspect", stack.getHoverName()).getString();
    }
    private static long count(List<Button> slots, ItemStack item) {
        return slots.stream().filter(b -> b.getMessage().getString().equals(inspectLabel(item))).count();
    }
    private static List<Button> ingredients(Screen page) {
        return page.children().stream().filter(b -> b instanceof Button button
                && button.getMessage().getContents() instanceof TranslatableContents label
                && label.getKey().equals("slate.starbridge.materials.inspect")).map(b -> (Button) b).toList();
    }
    private static void click(Screen page, Button button) {
        int x = button.getX() + button.getWidth()/2, y = button.getY() + button.getHeight()/2;
        page.mouseClicked(x, y, 0); page.mouseReleased(x, y, 0);
    }
}
