package dev.starbridge.survivalqa;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

final class FieldActions {
    private static int nextStep;
    private static BlockPos pursuing;
    private static Vec3 aimedAt;
    private static boolean eating;
    private static boolean fighting;

    private FieldActions() {}

    static boolean equipShield(Minecraft game) {
        if (game.player.getOffhandItem().is(Items.SHIELD)) return true;
        if (game.screen == null) {
            game.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(game.player));
            return false;
        }
        if (game.player.containerMenu != game.player.inventoryMenu) { game.player.closeContainer(); return false; }
        var menu = game.player.inventoryMenu;
        for (int slot=9; slot<=44; slot++) {
            if (!menu.getSlot(slot).getItem().is(Items.SHIELD)) continue;
            game.gameMode.handleInventoryMouseClick(menu.containerId,slot,40,ClickType.SWAP,game.player);
            break;
        }
        return false;
    }

    private static boolean blocking;

    static boolean respondToDanger(Minecraft mc) {
        if (mc.screen instanceof DeathScreen screen) {
            for (var component : screen.children()) {
                if (component instanceof Button button
                        && button.active
                        && button.getMessage().getContents() instanceof TranslatableContents text
                        && text.getKey().equals("deathScreen.respawn")) {
                    double x = button.getX() + button.getWidth() / 2.0;
                    double y = button.getY() + button.getHeight() / 2.0;
                    Expedition.log("Used ordinary death screen Respawn at " + mc.player.position());
                    screen.mouseClicked(x, y, 0);
                    screen.mouseReleased(x, y, 0);
                    break;
                }
            }
            return true;
        }
        if (mc.player.isDeadOrDying()) return true;
        var mob =
                mc
                        .level
                        .getEntitiesOfClass(
                                Monster.class,
                                mc.player.getBoundingBox().inflate(3.2),
                                other ->
                                        other.isAlive() && (!(other instanceof net.minecraft.world.entity.monster.EnderMan enderman) || enderman.isCreepy())
                                                && mc.player.hasLineOfSight(other)
                                                && mc.player.distanceToSqr(other) < 9.0)
                        .stream()
                        .min(Comparator.comparingDouble(mc.player::distanceToSqr))
                        .orElse(null);
        if (mob == null) {
            if (blocking) { mc.gameMode.releaseUsingItem(mc.player); mc.options.keyUse.setDown(false); blocking = false; }
            fighting = false;
            return false;
        }
        if (!fighting) {
            fighting = true;
            ExpeditionClient.interruptNavigation();
            Expedition.log("Defending against " + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()) + " at " + mc.player.position());
        }
        if (mc.screen != null) { mc.screen.onClose(); return true; }
        if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0 && mc.player.getOffhandItem().is(Items.SHIELD)) {
            mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, creeper.getEyePosition());
            if (!blocking) { mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND); mc.options.keyUse.setDown(true); blocking = true; }
            return true;
        }
        if (blocking) { mc.gameMode.releaseUsingItem(mc.player); mc.options.keyUse.setDown(false); blocking = false; }
        if (mc.player.getAttackStrengthScale(0) < 0.80f) return true;
        for (String tool :
                List.of(
                        "minecraft:iron_axe",
                        "minecraft:stone_axe",
                        "minecraft:stone_sword",
                        "minecraft:iron_pickaxe",
                        "minecraft:stone_pickaxe",
                        "minecraft:wooden_pickaxe")) {
            if (ExpeditionClient.count(mc, tool) == 0) continue;
            if (!ExpeditionClient.select(mc, tool)) return true;
            break;
        }
        mc.player.lookAt(
                net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                mob.getEyePosition());
        mc.gameMode.attack(mc.player, mob);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    static boolean recover(Minecraft mc, String items, BlockPos lastSeen, int tick) {
        if (List.of(items.split(",")).stream().allMatch(id -> ExpeditionClient.count(mc, id) > 0))
            return true;
        if (mc.screen != null) {
            mc.screen.onClose();
            return false;
        }
        var box = mc.player.getBoundingBox().inflate(24);
        var drops =
                mc.level.getEntitiesOfClass(
                        ItemEntity.class, box, item -> mc.player.hasLineOfSight(item));
        var drop =
                drops.stream()
                        .min(Comparator.comparingDouble(mc.player::distanceToSqr))
                        .orElse(null);
        approach(drop == null ? lastSeen : drop.blockPosition(), 0, tick);
        return false;
    }

    static boolean eatIfHungry(Minecraft mc) {
        if (eating) {
            if (mc.player.isUsingItem()) return true;
            eating = false;
            mc.options.keyUse.setDown(false);
        }
        int food = mc.player.getFoodData().getFoodLevel();
        if (food==20 || (food>14 && mc.player.getHealth()>=mc.player.getMaxHealth())) return false;
        if (mc.screen!=null && mc.player.getHealth()>=10) return false;
        String choice = foodFromInventory(mc);
        if (choice.isEmpty()) return false;
        if (mc.screen!=null) { mc.screen.onClose(); return true; }
        if (!ExpeditionClient.select(mc, choice)) return true;
        mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(true);
        eating=true;
        return true;
    }

    private static String foodFromInventory(Minecraft mc) {
        String emergency = "";
        for (int index=0; index<36;index++) {
            var meal=mc.player.getInventory().getItem(index);
            if(!meal.isEdible() || meal.is(Items.POISONOUS_POTATO) || meal.is(Items.PUFFERFISH) || meal.is(Items.SPIDER_EYE)) continue;
            if (meal.is(Items.ROTTEN_FLESH) || meal.is(Items.CHICKEN) || meal.is(Items.PORKCHOP)
                    || meal.is(Items.BEEF) || meal.is(Items.MUTTON) || meal.is(Items.RABBIT)) {
                emergency=Expedition.item(meal);
                continue;
            }
            return Expedition.item(meal);
        }
        return mc.player.getHealth()<10 || mc.player.getFoodData().getFoodLevel()<10 ? emergency : "";
    }

    static boolean store(Minecraft mc,String itemNames) {
        if (!(mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu chest)) { ExpeditionClient.open(mc,"minecraft:chest"); return false; }
        var items=List.of(itemNames.split(","));
        for (int slot=chest.getRowCount()*9;slot<chest.slots.size();slot++) {
            var stack=chest.slots.get(slot).getItem();
            if (!stack.isEmpty() && items.stream().anyMatch(id->ExpeditionClient.matches(stack,id))) {
                mc.gameMode.handleInventoryMouseClick(chest.containerId,slot,0,ClickType.QUICK_MOVE,mc.player);
                return false;
            }
        }
        return true;
    }

    static boolean take(Minecraft mc,String item,int count) {
        if (ExpeditionClient.count(mc,item)>=count) return true;
        if (!(mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu chest)) { ExpeditionClient.open(mc,"minecraft:chest"); return false; }
        for (int slot=0;slot<chest.getRowCount()*9;slot++) {
            if (ExpeditionClient.matches(chest.slots.get(slot).getItem(),item)) {
                mc.gameMode.handleInventoryMouseClick(chest.containerId,slot,0,ClickType.QUICK_MOVE,mc.player);
                return false;
            }
        }
        return false;
    }

    static boolean smelt(Minecraft mc, String material, String fuel, String output, int amount) {
        if (ExpeditionClient.count(mc, output) >= amount) return true;
        if (!(mc.player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            ExpeditionClient.open(mc,"minecraft:furnace");
            return false;
        }
        var finished = menu.getSlot(2).getItem();
        // A previous crew member may have left a different product in the same furnace.
        // Shift-click it into this survivor's inventory before asking the furnace for the next item.
        if (!finished.isEmpty()) {
            mc.gameMode.handleInventoryMouseClick(
                    menu.containerId, 2, 0, ClickType.QUICK_MOVE, mc.player);
            return false;
        }
        for (int ingredients = 0; ingredients < 2; ingredients++) {
            if (menu.getSlot(ingredients).hasItem()) continue;
            String id = ingredients == 0 ? material : fuel;
            for (int slot = 3; slot < menu.slots.size(); slot++) {
                var stack=menu.slots.get(slot).getItem();
                if (List.of(id.split(",")).stream().noneMatch(name -> ExpeditionClient.matches(stack,name))) continue;
                mc.gameMode.handleInventoryMouseClick(
                        menu.containerId, slot, 0, ClickType.QUICK_MOVE, mc.player);
                return false;
            }
        }
        return false;
    }

    static boolean hunt(Minecraft mc, String species, String wanted, int amount, int tick) {
        if (ExpeditionClient.count(mc, wanted) >= amount) return true;
        var speciesNames=List.of(species.split(","));
        var wantedItems=List.of(wanted.split(","));
        if (mc.screen != null) {
            mc.screen.onClose();
            return false;
        }
        var feet = mc.player.blockPosition();
        var nearby = mc.player.getBoundingBox().inflate(48, 24, 48);
        ItemEntity loose =
                mc
                        .level
                        .getEntitiesOfClass(
                                ItemEntity.class,
                                nearby,
                                e ->
                                        wantedItems.contains(Expedition.item(e.getItem()))
                                                && mc.player.hasLineOfSight(e))
                        .stream()
                        .min(Comparator.comparingDouble(mc.player::distanceToSqr))
                        .orElse(null);
        if (loose != null) {
            approach(loose.blockPosition(), 0, tick);
            return false;
        }
        var animals =
                mc.level.getEntitiesOfClass(
                        LivingEntity.class,
                        nearby,
                        e ->
                                e.isAlive()
                                        && (!(e instanceof Animal animal) || !animal.isBaby())
                                        && (wanted.equals("minecraft:white_wool")
                                                ? e
                                                                instanceof
                                                                net.minecraft.world.entity.animal
                                                                        .Sheep
                                                                sheep
                                                        && sheep.getColor()
                                                                == net.minecraft.world.item.DyeColor
                                                                        .WHITE
                                                        && !sheep.isSheared()
                                                : true)
                                        && speciesNames.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())
                                        && mc.player.hasLineOfSight(e));
        var animal =
                animals.stream()
                        .min(Comparator.comparingDouble(mc.player::distanceToSqr))
                        .orElse(null);
        if (animal == null) {
            // Look for wildlife on foot. The destination uses only the player's present position.
            if (tick >= nextStep
                    && !BaritoneAPI.getProvider()
                            .getPrimaryBaritone()
                            .getPathingBehavior()
                            .isPathing()) {
                int direction = (tick / 97) & 3;
                approach(feet.relative(Direction.from2DDataValue(direction), 28), 2, tick);
            }
            return false;
        }
        if (mc.player.distanceToSqr(animal) > 8.5) {
            approach(animal.blockPosition(), 1, tick);
            return false;
        }
        if (animal instanceof net.minecraft.world.entity.animal.Sheep
                && wanted.equals("minecraft:white_wool")
                && ExpeditionClient.count(mc, "minecraft:shears") > 0) {
            if (ExpeditionClient.select(mc, "minecraft:shears"))
                mc.gameMode.interact(mc.player, animal, InteractionHand.MAIN_HAND);
        } else if (mc.player.getAttackStrengthScale(0) > .98f) {
            for (String tool :
                    List.of(
                            "minecraft:stone_axe",
                            "minecraft:stone_sword",
                            "minecraft:wooden_axe")) {
                if (ExpeditionClient.count(mc, tool) > 0 && !ExpeditionClient.select(mc, tool))
                    return false;
                if (ExpeditionClient.count(mc, tool) > 0) break;
            }
            mc.player.lookAt(
                    net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    animal.getEyePosition());
            mc.gameMode.attack(mc.player, animal);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        return false;
    }

    private static void approach(BlockPos place, int within, int tick) {
        if (tick < nextStep && place.equals(pursuing)) return;
        nextStep = tick + 100;
        pursuing = place.immutable();
        BaritoneAPI.getProvider()
                .getPrimaryBaritone()
                .getCustomGoalProcess()
                .setGoalAndPath(new GoalNear(place, within));
    }

    static boolean fillBucket(Minecraft mc, int tick, boolean lava) {
        if (ExpeditionClient.count(mc, lava ? "minecraft:lava_bucket" : "minecraft:water_bucket") > 0) return true;
        if (mc.screen != null) {
            mc.screen.onClose();
            return false;
        }
        if (!ExpeditionClient.select(mc, "minecraft:bucket")) return false;
        var eye = mc.player.getEyePosition();
        var feet = mc.player.blockPosition();
        double best = Double.MAX_VALUE;
        BlockPos target = null;
        for (BlockPos candidate :
                BlockPos.betweenClosed(feet.offset(-18, -8, -18), feet.offset(18, 6, 18))) {
            var state = mc.level.getFluidState(candidate);
            if (!state.isSource() || !state.is(lava ? net.minecraft.tags.FluidTags.LAVA : net.minecraft.tags.FluidTags.WATER)) continue;
            Vec3 center = Vec3.atCenterOf(candidate);
            if (eye.distanceToSqr(center) >= best) continue;
            var ray =
                    mc.level.clip(
                            new ClipContext(
                                    eye,
                                    center,
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.SOURCE_ONLY,
                                    mc.player));
            if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(candidate))
                continue;
            best = eye.distanceToSqr(center);
            target = candidate.immutable();
        }
        if (target == null) return false;
        if (best > 16) {
            approach(target, 2, tick);
            return false;
        }
        Vec3 point = Vec3.atCenterOf(target);
        mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, point);
        if (!point.equals(aimedAt)) {
            aimedAt = point;
            return false;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return false;
    }
}
