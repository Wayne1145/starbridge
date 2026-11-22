package dev.starbridge.qa.moonwalk;

import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Observe exactly which originally generated stack moves out of the opened Galacticraft chest. */
@Mod.EventBusSubscriber(modid = "starbridgelunarfieldqa")
public final class FieldTreasure {
  private record Offering(ItemStack stack, int held, int available) {}

  private static final List<Offering> offers = new ArrayList<>();
  private static BlockPos chest;
  private static int interacted, taken, heldBefore;
  private static AbstractContainerMenu menu;
  private static ItemStack loot = ItemStack.EMPTY;

  static boolean nativeChest(String id) {
    return id != null && id.startsWith("galacticraftcore:") && id.contains("treasure");
  }

  @SubscribeEvent
  public static void clicked(PlayerInteractEvent.RightClickBlock e) {
    if (!FieldStart.ON
        || !(e.getEntity() instanceof ServerPlayer p)
        || !Files.isRegularFile(Path.of("field-key.json"))) return;
    var id = ForgeRegistries.BLOCKS.getKey(p.level().getBlockState(e.getPos()).getBlock());
    chest = id != null && nativeChest(id.toString()) ? e.getPos().immutable() : null;
    interacted = p.tickCount;
  }

  @SubscribeEvent
  public static void open(PlayerContainerEvent.Open e) throws Exception {
    if (!FieldStart.ON
        || !(e.getEntity() instanceof ServerPlayer p)
        || chest == null
        || p.tickCount - interacted > 10
        || !Files.isRegularFile(Path.of("field-key.json"))
        || !loot.isEmpty()) return;
    menu = e.getContainer();
    offers.clear();
    for (var slot : menu.slots) {
      var item = slot.getItem();
      if (slot.container == p.getInventory()
          || item.isEmpty()
          || offers.stream().anyMatch(o -> ItemStack.isSameItemSameTags(o.stack(), item))) continue;
      offers.add(
          new Offering(item.copy(), count(p.getInventory(), item), available(menu, p, item)));
    }
    FieldStart.write(
        "field-chest-open.json",
        Map.of(
            "player",
            p.getUUID(),
            "position",
            List.of(chest.getX(), chest.getY(), chest.getZ()),
            "tick",
            p.tickCount,
            "offerings",
            offers.stream().map(o -> o.stack().save(new CompoundTag()).toString()).toList()));
  }

  private static int count(Container c, ItemStack kind) {
    int sum = 0;
    for (int i = 0; i < c.getContainerSize(); i++)
      if (ItemStack.isSameItemSameTags(kind, c.getItem(i))) sum += c.getItem(i).getCount();
    return sum;
  }

  private static int available(AbstractContainerMenu open, ServerPlayer p, ItemStack kind) {
    return open.slots.stream()
        .filter(
            s -> s.container != p.getInventory() && ItemStack.isSameItemSameTags(s.getItem(), kind))
        .mapToInt(s -> s.getItem().getCount())
        .sum();
  }

  static void observe(ServerPlayer p) throws Exception {
    if (loot.isEmpty() && Files.isRegularFile(Path.of("field-reward.json"))) {
      var old = FieldStart.read("field-reward");
      loot = ItemStack.of(TagParser.parseTag(old.get("stack").getAsString()));
      taken = old.get("quantity").getAsInt();
      heldBefore = old.get("previouslyHeld").getAsInt();
    }
    if (!loot.isEmpty() || menu == null) return;
    for (var item : offers) {
      int delta = count(p.getInventory(), item.stack()) - item.held();
      if (delta <= 0 || item.available() - available(menu, p, item.stack()) < delta) continue;
      loot = item.stack().copy();
      loot.setCount(delta);
      taken = delta;
      heldBefore = item.held();
      var stack = loot.save(new CompoundTag());
      FieldStart.write(
          "field-reward.json",
          Map.ofEntries(
              Map.entry("player", p.getUUID()),
              Map.entry("previouslyHeld", heldBefore),
              Map.entry("heldAfter", count(p.getInventory(), item.stack())),
              Map.entry("sourceBefore", item.available()),
              Map.entry("sourceAfter", available(menu, p, item.stack())),
              Map.entry("quantity", taken),
              Map.entry("stack", stack.toString()),
              Map.entry("item", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, stack)),
              Map.entry("tick", p.tickCount)));
      menu = null;
      break;
    }
  }

  static boolean carried(ServerPlayer p) {
    return !loot.isEmpty() && count(p.getInventory(), loot) >= heldBefore + taken;
  }

  static boolean stowed(Container barrel, ServerPlayer p) {
    return !loot.isEmpty()
        && count(barrel, loot) >= taken
        && count(p.getInventory(), loot) <= heldBefore;
  }

  static Object manifest() {
    return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, loot.save(new CompoundTag()));
  }
}
