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
  private static UUID opener;
  private static int interacted, taken, heldBefore, keysBefore;
  private static boolean lockedBefore;
  private static AbstractContainerMenu menuBefore;
  private static AbstractContainerMenu menu;
  private static ItemStack loot = ItemStack.EMPTY;

  static boolean nativeChest(String id) {
    return id != null && id.startsWith("galacticraftcore:") && id.contains("treasure");
  }

  @SubscribeEvent
  public static void clicked(PlayerInteractEvent.RightClickBlock e) throws Exception {
    if (!FieldStart.ON || !(e.getEntity() instanceof ServerPlayer p) || p.hasPermissions(2)) return;
    var pos = e.getPos();
    var id = ForgeRegistries.BLOCKS.getKey(p.level().getBlockState(pos).getBlock());
    if (id == null || !nativeChest(id.toString())) return;
    var held = ForgeRegistries.ITEMS.getKey(e.getItemStack().getItem());
    var lock = lockState(p, pos);
    if (lock == null) return;
    int keys = FieldVessel.keys(p);
    boolean holdingKey = keys > 0 && held != null && held.toString().equals("galacticraftcore:item.key");
    if (!holdingKey && (lock || !sameUnlockedChest(p, pos))) return;
    chest = pos.immutable();
    opener = p.getUUID();
    lockedBefore = lock;
    keysBefore = keys;
    interacted = p.tickCount;
    menuBefore = p.containerMenu;
  }

  private static Boolean lockState(ServerPlayer p, BlockPos pos) {
    var block = p.level().getBlockEntity(pos);
    if (block == null) return null;
    var legacy = block.saveWithoutMetadata().getCompound("legacy");
    return legacy.contains("isLocked") ? legacy.getBoolean("isLocked") : null;
  }

  private static List<Integer> coordinates(BlockPos pos) {
    return List.of(pos.getX(), pos.getY(), pos.getZ());
  }

  private static boolean sameUnlockedChest(ServerPlayer p, BlockPos pos) throws Exception {
    if (!Files.isRegularFile(Path.of("field-chest-unlocked.json"))) return false;
    var unlocked = FieldStart.read("field-chest-unlocked");
    var xyz = unlocked.getAsJsonArray("position");
    return unlocked.get("player").getAsString().equals(p.getStringUUID())
        && unlocked.get("dimension").getAsString().equals(p.level().dimension().location().toString())
        && xyz.get(0).getAsInt() == pos.getX() && xyz.get(1).getAsInt() == pos.getY()
        && xyz.get(2).getAsInt() == pos.getZ();
  }

  @SubscribeEvent
  public static void open(PlayerContainerEvent.Open e) throws Exception {
    if (FieldStart.ON && e.getEntity() instanceof ServerPlayer p && followingClick(p)) {
      opened(p, e.getContainer());
    }
  }

  private static boolean followingClick(ServerPlayer p) {
    return chest != null && p.getUUID().equals(opener) && p.tickCount - interacted <= 10;
  }

  private static void opened(ServerPlayer p, AbstractContainerMenu candidate) throws Exception {
    if (!loot.isEmpty() || menu == candidate || menuBefore == candidate
        || !candidate.getClass().getName().equals("retrofit.forge.world.LegacyMenu")
        || !Boolean.FALSE.equals(lockState(p, chest))) return;
    menu = candidate;
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
            coordinates(chest),
            "tick",
            p.tickCount,
            "keysBefore",
            keysBefore,
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
    if (!loot.isEmpty()) return;
    if (followingClick(p)) {
      int keys = FieldVessel.keys(p);
      if (lockedBefore && keysBefore - keys == 1 && Boolean.FALSE.equals(lockState(p, chest))) {
        FieldStart.write("field-chest-unlocked.json", Map.of(
            "player", p.getUUID(), "position", coordinates(chest), "tick", p.tickCount,
            "dimension", p.level().dimension().location().toString(),
            "keysBefore", keysBefore, "keysAfter", keys));
        lockedBefore = false;
      }
      // The legacy inventory can replace the menu directly without a modern Open event.
      opened(p, p.containerMenu);
    }
    if (menu == null) return;
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
