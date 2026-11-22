package dev.starbridge.qa.moonwalk;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Look through open corridors, use the dropped key and handle the actual container slots. */
final class TreasureHands {
  private static BlockPos seen;
  private static int lastInput;

  static boolean work(Minecraft mc, int tick, boolean cargo) throws Exception {
    var p = mc.player;
    if (mc.screen instanceof AbstractContainerScreen<?> screen) {
      if (tick - lastInput < 12) return true;
      if (cargo && Files.isRegularFile(Path.of("field-cargo.json"))
          || !cargo && Files.isRegularFile(Path.of("field-reward.json"))) {
        screen.onClose();
        return true;
      }
      if (!cargo && !Files.isRegularFile(Path.of("field-chest-open.json"))) return true;
      var menu = screen.getMenu();
      int best = -1, score = Integer.MIN_VALUE;
      var reward =
          cargo
              ? ItemStack.of(
                  TagParser.parseTag(FieldStart.read("field-reward").get("stack").getAsString()))
              : ItemStack.EMPTY;
      for (int i = 0; i < menu.slots.size(); i++) {
        var slot = menu.getSlot(i);
        var stack = slot.getItem();
        if (stack.isEmpty() || (slot.container == p.getInventory()) != cargo) continue;
        if (cargo) {
          if (ItemStack.isSameItemSameTags(stack, reward)) {
            best = i;
            break;
          }
          continue;
        }
        var id = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        int ranking =
            id.contains("schematic")
                ? 40
                : id.contains("name_tag") ? 30 : id.contains("amethyst") ? -30 : 0;
        if (p.getInventory().items.stream().noneMatch(s -> ItemStack.isSameItemSameTags(s, stack)))
          ranking += 10;
        if (ranking > score) {
          score = ranking;
          best = i;
        }
      }
      if (best >= 0) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, best, 0, ClickType.QUICK_MOVE, p);
        lastInput = tick;
      }
      return true;
    }
    if (mc.screen != null) return true;
    if (tick % 5 == 0) seen = visible(mc, cargo);
    if (seen == null) return false;
    p.lookAt(Anchor.EYES, Vec3.atCenterOf(seen));
    double distance = Vec3.atCenterOf(seen).distanceTo(p.getEyePosition());
    if (distance > 4.2) {
      mc.options.keyUp.setDown(true);
      mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
      return true;
    }
    mc.options.keyUp.setDown(false);
    mc.options.keyJump.setDown(false);
    if (tick - lastInput < 9) return true;
    if (!cargo) {
      for (int i = 0; i < p.getInventory().items.size(); i++) {
        if (!"galacticraftcore:item.key"
            .equals(
                String.valueOf(
                    ForgeRegistries.ITEMS.getKey(p.getInventory().getItem(i).getItem())))) continue;
        if (i < 9) p.getInventory().selected = i;
        else {
          mc.gameMode.handleInventoryMouseClick(
              p.inventoryMenu.containerId, i, 8, ClickType.SWAP, p);
          p.getInventory().selected = 8;
        }
        break;
      }
    } else p.getInventory().selected = 0;
    var feedback = ray(mc, Vec3.atCenterOf(seen));
    if (feedback.getType() == HitResult.Type.BLOCK && feedback.getBlockPos().equals(seen)) {
      mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, feedback);
      p.swing(InteractionHand.MAIN_HAND);
      lastInput = tick;
    }
    return true;
  }

  private static BlockPos visible(Minecraft mc, boolean cargo) {
    var eye = mc.player.getEyePosition();
    if (seen != null) {
      var hit = ray(mc, Vec3.atCenterOf(seen));
      if (hit.getType() == HitResult.Type.BLOCK
          && hit.getBlockPos().equals(seen)
          && target(mc, seen, cargo)) return seen;
    }
    int distance = cargo ? 6 : 30;
    for (int pitch : new int[] {0, 12, 24, 40, 58, 75, -15})
      for (int yaw = 0; yaw < 360; yaw += 6) {
        var direction = Vec3.directionFromRotation(pitch, yaw);
        var hit = ray(mc, eye.add(direction.scale(distance)));
        if (hit.getType() == HitResult.Type.BLOCK && target(mc, hit.getBlockPos(), cargo))
          return hit.getBlockPos().immutable();
      }
    return null;
  }

  private static boolean target(Minecraft mc, BlockPos pos, boolean cargo) {
    var id = ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(pos).getBlock());
    if (id == null) return false;
    return cargo
        ? "minecraft:barrel".equals(id.toString())
        : FieldTreasure.nativeChest(id.toString());
  }

  private static BlockHitResult ray(Minecraft mc, Vec3 to) {
    return mc.level.clip(
        new ClipContext(
            mc.player.getEyePosition(),
            to,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            mc.player));
  }
}
