package dev.starbridge.qa.fuel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.Map;

/**
 * Select the supplied stack or an empty hand, then use an exposed face through the normal game
 * connection.
 */
final class WorkshopHands {
    private static int activeOrder = -1, step, attempts, observations;

    static void tick(Minecraft game, FactoryTrial.Order order) throws Exception {
        if (activeOrder != order.seq()) {
            activeOrder = order.seq();
            step = 0;
            attempts = 0;
            observations = 0;
        }
        var player = game.player;
        BlockPos target = BlockPos.of(order.pos());
        boolean building = order.action().equals("place");
        if (game.getOverlay() != null || game.screen != null && (building || step < 2)) return;
        boolean sneak = building && game.level.getBlockEntity(target.below()) != null;
        game.options.keyShift.setDown(sneak);
        if (++observations == 2 || observations % 20 == 0) {
            var hand = player.getMainHandItem();
            FactoryTrial.write(
                    Path.of("workshop-input-" + order.seq() + ".json"),
                    Map.of(
                            "step",
                            step,
                            "hand",
                            hand.toString(),
                            "position",
                            player.position().toString(),
                            "sneak",
                            player.isShiftKeyDown(),
                            "expectedSneak",
                            sneak,
                            "screen",
                            game.screen == null ? "world" : game.screen.getClass().getName(),
                            "appears",
                            game.level.getBlockState(target).toString(),
                            "support",
                            game.level.getBlockState(target.below()).toString()));
        }
        if (step == 0) {
            if (!select(game, order, building)) return;
            step++;
            return;
        }
        if (step == 1) {
            if (building && !matches(player.getMainHandItem(), order)
                    || !building && !player.getMainHandItem().isEmpty()) return;
            if (player.isShiftKeyDown() != sneak || !use(game, target, building)) return;
            step++;
            return;
        }
        if (building) {
            var block = game.level.getBlockState(target).getBlock();
            if (!order.input().equals(ForgeRegistries.ITEMS.getKey(block.asItem()).toString())) {
                if (++attempts % 10 == 0 && game.screen == null) step = 1;
                return;
            }
        } else {
            if (game.screen == null
                    || !player.containerMenu
                            .getClass()
                            .getName()
                            .equals("retrofit.forge.world.LegacyMenu")) {
                if (++attempts % 10 == 0 && game.screen == null) step = 1;
                return;
            }
            Screenshot.grab(
                    game.gameDirectory,
                    "assembled-original-" + order.seq() + ".png",
                    game.getMainRenderTarget(),
                    ignore -> {});
            player.closeContainer();
        }
        FactoryTrial.write(
                Path.of("factory-done-" + order.seq() + ".json"),
                Map.of(
                        "input",
                        order.input(),
                        "metadata",
                        order.output(),
                        "position",
                        target.toShortString(),
                        "action",
                        building ? "placed original part" : "opened original controller"));
    }

    private static boolean matches(ItemStack stack, FactoryTrial.Order order) {
        if (stack.isEmpty()
                || !order.input().equals(ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()))
            return false;
        int meta = stack.hasTag() ? stack.getTag().getInt("LegacyMeta") : 0;
        return Integer.toString(meta).equals(order.output());
    }

    private static boolean select(Minecraft game, FactoryTrial.Order order, boolean building) {
        var player = game.player;
        for (var slot : player.inventoryMenu.slots) {
            if (slot.container != player.getInventory()
                    || slot.getSlotIndex() < 0
                    || slot.getSlotIndex() >= 36) continue;
            var stack = slot.getItem();
            if (building ? !matches(stack, order) : !stack.isEmpty()) continue;
            if (slot.getSlotIndex() < 9) {
                player.getInventory().selected = slot.getSlotIndex();
                return true;
            }
            game.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId, slot.index, 0, ClickType.SWAP, player);
            player.getInventory().selected = 0;
            return true;
        }
        throw new IllegalStateException("Missing held part or empty hand for " + order);
    }

    private static boolean use(Minecraft game, BlockPos target, boolean building) throws Exception {
        BlockPos clicked = building ? target.below() : target;
        Vec3 aim = Vec3.atCenterOf(clicked).add(0, building ? .498 : 0, 0);
        var player = game.player;
        var eyes = player.getEyePosition();

        var hit =
                game.level.clip(
                        new ClipContext(
                                eyes,
                                aim,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                player));
        if (++attempts % 10 == 1)
            FactoryTrial.write(
                    Path.of("workshop-ray-" + activeOrder + ".json"),
                    Map.of(
                            "eye",
                            eyes.toString(),
                            "target",
                            aim.toString(),
                            "distance",
                            eyes.distanceTo(aim),
                            "hit",
                            hit.getType().toString(),
                            "block",
                            hit.getBlockPos().toShortString(),
                            "face",
                            hit.getDirection().toString()));
        if (eyes.distanceTo(aim) > 4.48) return false;
        if (hit.getType() != HitResult.Type.BLOCK
                || !clicked.equals(hit.getBlockPos())
                || building && hit.getDirection() != Direction.UP) return false;
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        var result = game.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        FactoryTrial.write(
                Path.of("workshop-use-" + activeOrder + ".json"),
                Map.of(
                        "result",
                        result.toString(),
                        "point",
                        hit.getLocation().toString(),
                        "sneak",
                        player.isShiftKeyDown(),
                        "worldVisible",
                        game.screen == null && game.getOverlay() == null));
        return true;
    }

    private WorkshopHands() {}
}
