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
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;

final class FieldActions {
    private static int nextStep;
    private static BlockPos pursuing;
    private static Vec3 aimedAt;
    private static boolean eating;
    private static String gravelStep = "";
    private static int stepUntil = -1;
    private static BlockPos waterOrigin;
    private static int waterLeg;
    private static boolean fighting;
    private static boolean surfacing;
    private static boolean swimmingForInk;
    private static final ArrayDeque<BlockPos> surveyedBanks = new ArrayDeque<>();

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
    private static AbstractSkeleton guardedArcher;
    private static long guardUntil;

    static boolean surfaceForAir(Minecraft mc) {
        var player = mc.player;
        if (surfacing && (player.isDeadOrDying() || !player.isInWater()
                || player.getAirSupply() >= player.getMaxAirSupply() - 15)) {
            mc.options.keyJump.setDown(false);
            surfacing = false;
        }
        if (!surfacing && !player.isDeadOrDying() && player.getAirSupply() <= 100
                && player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
            stopInkSwim(mc);
            ExpeditionClient.interruptNavigation();
            Expedition.log("Stopped the current route to breathe at " + player.position());
            surfacing = true;
        }
        if (surfacing) mc.options.keyJump.setDown(true);
        return surfacing;
    }

    static boolean respondToDanger(Minecraft mc) {
        if (mc.screen instanceof DeathScreen screen) {
            guardedArcher = null;
            blocking = false;
            mc.options.keyUse.setDown(false);
            stopInkSwim(mc);
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
            if (guardAgainstArrows(mc)) return true;
            if (blocking) { mc.gameMode.releaseUsingItem(mc.player); mc.options.keyUse.setDown(false); blocking = false; }
            fighting = false;
            return false;
        }
        guardedArcher = null;
        if (!fighting) {
            stopInkSwim(mc);
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

    private static boolean guardAgainstArrows(Minecraft mc) {
        var player = mc.player;
        if (!player.getOffhandItem().is(Items.SHIELD)) {
            guardedArcher = null;
            return false;
        }

        long now = mc.level.getGameTime();
        var drawing = mc.level.getEntitiesOfClass(AbstractSkeleton.class, player.getBoundingBox().inflate(14),
                        archer -> archer.isAlive() && archer.getUseItem().is(Items.BOW)
                                && archer.getTicksUsingItem() >= 10 && player.hasLineOfSight(archer)
                                && player.distanceToSqr(archer) <= 14 * 14)
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (drawing != null) {
            if (guardedArcher == null) {
                stopInkSwim(mc);
                ExpeditionClient.interruptNavigation();
                Expedition.log("Raised a shield against a skeleton at " + player.position());
            }
            guardedArcher = drawing;
            // Keep covering the same direction while a fired arrow can still be in flight.
            guardUntil = now + 10;
        }
        if (guardedArcher == null || !guardedArcher.isAlive() || now >= guardUntil) {
            guardedArcher = null;
            return false;
        }

        if (mc.screen != null) {
            mc.screen.onClose();
            return true;
        }
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, guardedArcher.getEyePosition());
        if (!blocking) {
            if (player.isUsingItem()) mc.gameMode.releaseUsingItem(player);
            mc.gameMode.useItem(player, InteractionHand.OFF_HAND);
            mc.options.keyUse.setDown(true);
            blocking = true;
        }
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

    /** Work the gravel from a fixed spot beside the camp. */
    static boolean siftGravel(Minecraft mc, BlockPos pos, BlockPos bank, int tick) {
        String flint = "minecraft:flint";
        String gravel = "minecraft:gravel";
        if (ExpeditionClient.count(mc, flint) > 0) return true;
        if (mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.GRAVEL)) {
            traceGravel("breaking the placed gravel", mc);
            if (PlaceAt.stand(mc, bank)) DigAt.one(mc, pos);
            return false;
        }
        if (ExpeditionClient.count(mc, gravel) > 0) {
            traceGravel("placing carried gravel", mc);
            if (PlaceAt.stand(mc, bank) && clearGravelCell(mc, pos, bank, tick)) {
                PlaceAt.tick(mc, gravel, pos);
            }
            return false;
        }
        var drop = mc.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2),
                        entity -> (ExpeditionClient.matches(entity.getItem(), gravel)
                                || ExpeditionClient.matches(entity.getItem(), flint)) )
                .stream().min(Comparator.comparingDouble(mc.player::distanceToSqr));
        if (drop.isPresent()) {
            traceGravel("picking up " + Expedition.item(drop.get().getItem()), mc);
            approach(drop.get().blockPosition(), 0, tick);
        } else {
            traceGravel("checking the empty gravel cell for a drop", mc);
            PlaceAt.stand(mc, pos);
        }
        return false;
    }

    /** A coordinate order can finish on the edge of a cell while the player still obstructs placement. */
    private static boolean clearGravelCell(Minecraft game, BlockPos gravel, BlockPos bank, int tick) {
        if (!game.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(gravel))) return true;
        pressForwardToward(game, bank, tick);
        return false;
    }

    /** Walk a few steps inside a room without asking the pathfinder to start a route. */
    static boolean stepTo(Minecraft game, BlockPos destination, int tick) {
        if (game.player.blockPosition().equals(destination)) return true;
        if (game.screen == null && game.player.onGround()
                && game.player.blockPosition().getY() == destination.getY()
                && game.player.position().distanceToSqr(Vec3.atBottomCenterOf(destination)) <= 9.0) {
            pressForwardToward(game, destination, tick);
        }
        return false;
    }

    private static void pressForwardToward(Minecraft game, BlockPos destination, int tick) {
        if (stepUntil >= 0) return;
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        var center = Vec3.atBottomCenterOf(destination).with(net.minecraft.core.Direction.Axis.Y, game.player.getEyeY());
        game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, center);
        game.options.keyUp.setDown(true);
        stepUntil = tick + 4;
    }

    static void tickShortStep(Minecraft game, int tick) {
        if (stepUntil < 0) return;
        if (tick < stepUntil && game.screen == null && game.player != null && game.player.isAlive()) return;
        game.options.keyUp.setDown(false);
        stepUntil = -1;
    }

    private static void traceGravel(String step, Minecraft mc) {
        if (gravelStep.equals(step)) return;
        gravelStep = step;
        Expedition.log("Gravel: " + step + " at " + mc.player.position());
    }

    static boolean collect(Minecraft mc, String item, int quantity, int tick) {
        if (ExpeditionClient.count(mc, item) >= quantity) return true;
        var drop = mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(12),
                        entity -> ExpeditionClient.matches(entity.getItem(), item) && mc.player.hasLineOfSight(entity))
                .stream().min(Comparator.comparingDouble(mc.player::distanceToSqr));
        drop.ifPresent(entity -> approach(entity.blockPosition(), 0, tick));
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
        stopInkSwim(mc);
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

    /** Use the visible villager menu and its real payment slots. */
    static boolean trade(Minecraft game, String profession, String payment, String product, int quantity, int tick) {
        if (ExpeditionClient.count(game, product) >= quantity) return true;
        if (game.player.containerMenu instanceof MerchantMenu menu) {
            for (int i = 0; i < menu.getOffers().size(); i++) {
                var offer = menu.getOffers().get(i);
                if (offer.isOutOfStock() || !offer.getCostB().isEmpty()
                        || !ExpeditionClient.matches(offer.getCostA(), payment)
                        || !ExpeditionClient.matches(offer.getResult(), product)) continue;
                var output = menu.getSlot(2).getItem();
                if (ExpeditionClient.matches(output, product)) {
                    game.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, game.player);
                } else if (ExpeditionClient.count(game, payment) >= offer.getCostA().getCount()) {
                    menu.setSelectionHint(i);
                    menu.tryMoveItems(i);
                    game.getConnection().send(new ServerboundSelectTradePacket(i));
                }
                return false;
            }
            return false;
        }
        if (game.screen != null) {
            game.screen.onClose();
            return false;
        }
        var villager = game.level.getEntitiesOfClass(Villager.class, game.player.getBoundingBox().inflate(18),
                        npc -> npc.isAlive() && !npc.isBaby() && profession.equals(
                                BuiltInRegistries.VILLAGER_PROFESSION.getKey(npc.getVillagerData().getProfession()).toString()))
                .stream().min(Comparator.comparingDouble(game.player::distanceToSqr)).orElse(null);
        if (villager == null) return false;
        if (game.player.distanceToSqr(villager) > 10 || !game.player.hasLineOfSight(villager)) {
            approach(villager.blockPosition(), 2, tick);
            return false;
        }
        game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, villager.getEyePosition());
        game.gameMode.interact(game.player, villager, InteractionHand.MAIN_HAND);
        return false;
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
        if (ExpeditionClient.count(mc, wanted) >= amount) {
            stopInkSwim(mc);
            return true;
        }
        boolean ink = wanted.equals("minecraft:ink_sac");
        var speciesNames=List.of(species.split(","));
        var wantedItems=List.of(wanted.split(","));
        if (mc.screen != null) {
            stopInkSwim(mc);
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
            if (ink && swimToInk(mc, loose.position())) return false;
            stopInkSwim(mc);
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
            stopInkSwim(mc);
            // Look for wildlife on foot. The destination uses only the player's present position.
            if (tick >= nextStep
                    && !BaritoneAPI.getProvider()
                            .getPrimaryBaritone()
                            .getPathingBehavior()
                            .isPathing()) {
                BlockPos bank = ink ? nearbyBank(mc, feet) : null;
                if (bank != null) {
                    surveyedBanks.addLast(bank);
                    if (surveyedBanks.size() > 12) surveyedBanks.removeFirst();
                    approach(bank, 0, tick);
                } else {
                    int direction = (tick / 97) & 3;
                    approach(feet.relative(Direction.from2DDataValue(direction), 28), 2, tick);
                }
            }
            return false;
        }
        if (mc.player.distanceToSqr(animal) > 8.5) {
            if (ink && swimToInk(mc, animal.position().add(0, .4, 0))) return false;
            stopInkSwim(mc);
            approach(animal.blockPosition(), 1, tick);
            return false;
        }
        stopInkSwim(mc);
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

    private static BlockPos nearbyBank(Minecraft mc, BlockPos feet) {
        var eye = mc.player.getEyePosition();
        double nearest = Double.MAX_VALUE;
        BlockPos best = null;
        for (int x = feet.getX() - 18; x <= feet.getX() + 18; x++) {
            for (int z = feet.getZ() - 18; z <= feet.getZ() + 18; z++) {
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
                int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                var candidate = new BlockPos(x, y, z);
                var ground = candidate.below();
                if (!mc.level.getFluidState(ground).isEmpty() || mc.level.getBlockState(ground).isAir()) continue;
                double distance = candidate.distSqr(feet);
                if (distance < 16 || distance >= nearest || Math.abs(y - feet.getY()) > 10
                        || surveyedBanks.stream().anyMatch(bank -> bank.distSqr(candidate) < 25)) continue;
                boolean besideWater = false;
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (mc.level.getFluidState(ground.relative(direction)).is(net.minecraft.tags.FluidTags.WATER)) {
                        besideWater = true;
                        break;
                    }
                }
                if (!besideWater) continue;
                var hit = mc.level.clip(new ClipContext(eye, Vec3.atBottomCenterOf(candidate).add(0, 1, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() != HitResult.Type.MISS) continue;
                best = candidate;
                nearest = distance;
            }
        }
        return best;
    }

    private static boolean swimToInk(Minecraft mc, Vec3 target) {
        var player = mc.player;
        if (!player.isInWater() || player.position().distanceToSqr(target) > 24 * 24) return false;
        if (!swimmingForInk) {
            ExpeditionClient.interruptNavigation();
            swimmingForInk = true;
        }
        var feet = player.position();
        double horizontalDistance = Math.hypot(feet.x - target.x, feet.z - target.z);
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target);
        mc.options.keyUp.setDown(horizontalDistance > .35);
        mc.options.keyJump.setDown(target.y >= feet.y - .5);
        mc.options.keyShift.setDown(target.y < feet.y - .5);
        return true;
    }

    private static void stopInkSwim(Minecraft mc) {
        if (!swimmingForInk) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        swimmingForInk = false;
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

    static boolean fillBucketAt(Minecraft game, BlockPos target, boolean lava) {
        String filled = lava ? "minecraft:lava_bucket" : "minecraft:water_bucket";
        if (ExpeditionClient.count(game, filled) > 0) return true;
        var desired = lava ? net.minecraft.tags.FluidTags.LAVA : net.minecraft.tags.FluidTags.WATER;
        return useAtSource(game, target, desired, "minecraft:bucket");
    }

    static boolean fillWaterBottlesAt(Minecraft game, BlockPos target, int wanted) {
        int filled = 0;
        var inventory = game.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var item = inventory.getItem(slot);
            if (item.is(Items.POTION) && PotionUtils.getPotion(item) == Potions.WATER) filled += item.getCount();
        }
        if (filled >= wanted) return true;
        return useAtSource(game, target, net.minecraft.tags.FluidTags.WATER, "minecraft:glass_bottle");
    }

    private static boolean useAtSource(Minecraft game, BlockPos target,
                                       net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> desired, String container) {
        if (game.screen != null) { game.screen.onClose(); return false; }
        var fluid = game.level.getFluidState(target);
        if (game.player.isUnderWater() || game.player.isInLava() || !fluid.isSource() || !fluid.is(desired)
                || !ExpeditionClient.select(game, container)) return false;
        var eye = game.player.getEyePosition();
        var center = Vec3.atCenterOf(target);
        // A source in a wall can be visible from the bank through only part of the opening.
        double[] offsets = {0, .4, -.4};
        for (double height : new double[]{.35, .10}) {
            for (double dx : offsets) for (double dz : offsets) {
                var point = center.add(dx, height, dz);
                if (eye.distanceToSqr(point) > 16) continue;
                var hit = game.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, game.player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) continue;
                game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, point);
                if (!point.equals(aimedAt)) { aimedAt = point; return false; }
                game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
                game.player.swing(InteractionHand.MAIN_HAND);
                return false;
            }
        }
        return false;
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
        if (target == null) {
            if (!lava && tick >= nextStep && !BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                if (waterOrigin == null) waterOrigin = feet;
                // Walk a widening surface search. A bucket is used only on an actual visible source.
                int radius = 10 + Math.min(54, (waterLeg / 8) * 9);
                double angle = Math.PI / 4 * (waterLeg++ % 8);
                int x = waterOrigin.getX() + (int)Math.round(radius*Math.cos(angle));
                int z = waterOrigin.getZ() + (int)Math.round(radius*Math.sin(angle));
                var surface = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                approach(new BlockPos(x,surface,z), 2, tick);
            }
            return false;
        }
        waterOrigin = null;
        waterLeg = 0;
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
