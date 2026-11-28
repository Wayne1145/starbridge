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
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Look through open corridors, use the dropped key and handle the actual container slots. */
final class TreasureHands {
  private static BlockPos seen, assignedBarrel;
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
      if (cargo && !(menu instanceof net.minecraft.world.inventory.ChestMenu)) {
        screen.onClose();
        return true;
      }
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
        if (cargo && !Files.isRegularFile(Path.of("field-cargo-slot.json"))) {
          FieldStart.write("field-cargo-slot.json", java.util.Map.of(
              "container", menu.getClass().getName(), "slot", best, "tick", tick,
              "stack", reward.save(new net.minecraft.nbt.CompoundTag()).toString()));
        }
      }
      return true;
    }
    if (mc.screen != null) {
      if (cargo) mc.screen.onClose();
      return true;
    }
    if (cargo) {
      if (assignedBarrel == null) {
        var helm = FieldStart.read("field-vessel").getAsJsonArray("lunarHelm");
        assignedBarrel = new BlockPos(helm.get(0).getAsInt(), helm.get(1).getAsInt(), helm.get(2).getAsInt()).east();
      }
      if (!target(mc, assignedBarrel, true)) return false;
      seen = assignedBarrel;
    } else if (tick % 5 == 0) seen = visible(mc);
    if (seen == null) return false;
    var face = visibleFace(mc, seen);
    if (face == null) return false;
    p.lookAt(Anchor.EYES, face.getLocation());
    double distance = face.getLocation().distanceTo(p.getEyePosition());
    if (distance > 4.2) {
      if (!FieldCavern.firmGroundAhead(mc)) return false;
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
    } else {
      mc.options.keyShift.setDown(false);
      p.getInventory().selected = 0;
    }
    var interaction = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, face);
    p.swing(InteractionHand.MAIN_HAND);
    lastInput = tick;
    if (cargo && !Files.isRegularFile(Path.of("field-cargo-input.json"))) {
      FieldStart.write("field-cargo-input.json", java.util.Map.of(
          "barrel", java.util.List.of(seen.getX(), seen.getY(), seen.getZ()),
          "face", face.getDirection().getName(), "reach", distance,
          "action", interaction.name(), "tick", tick));
    }
    return true;
  }

  private static BlockPos visible(Minecraft mc) {
    if (seen != null && target(mc, seen, false) && visibleFace(mc, seen) != null) return seen;
    return visibleChest(mc, mc.player.getEyePosition());
  }

  private static BlockHitResult visibleFace(Minecraft mc, BlockPos target) {
    var center = Vec3.atCenterOf(target);
    for (double x : new double[]{0, -.43, .43})
      for (double y : new double[]{0, .43, -.43})
        for (double z : new double[]{0, -.43, .43}) {
          var hit = ray(mc, center.add(x, y, z));
          if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return hit;
        }
    return null;
  }

  private static BlockPos visibleChest(Minecraft mc, Vec3 eye) {
    int range = 30;
    int minX = ((int) Math.floor(eye.x) - range) >> 4;
    int maxX = ((int) Math.floor(eye.x) + range) >> 4;
    int minZ = ((int) Math.floor(eye.z) - range) >> 4;
    int maxZ = ((int) Math.floor(eye.z) + range) >> 4;
    double nearest = range * range;
    BlockPos best = null;
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        var chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) continue;
        for (var pos : chunk.getBlockEntities().keySet()) {
          Vec3 center = Vec3.atCenterOf(pos);
          double distance = center.distanceToSqr(eye);
          if (distance >= nearest || !target(mc, pos, false)) continue;
          // A long narrow opening may let the player see the chest between the sampled view angles.
          if (visibleFace(mc, pos) == null) continue;
          nearest = distance;
          best = pos.immutable();
        }
      }
    }
    return best;
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
