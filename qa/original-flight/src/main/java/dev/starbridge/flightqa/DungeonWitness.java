package dev.starbridge.flightqa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.ItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.transit.HullTransit;

/** Follow the engineer's real combat, pickup and container events after first-person discovery. */
@Mod.EventBusSubscriber(modid="starbridge_flightqa")
public final class DungeonWitness {
    static final Path ACTIVE = Path.of("lunar-continue-dungeon.fixture");
    private static final Path RECORD = Path.of("lunar-dungeon-audit.json");
    private static final Map<String,Object> facts = new LinkedHashMap<>();
    private static final List<Offering> offers = new ArrayList<>();
    private static ItemStack reward = ItemStack.EMPTY;
    private static int before, amount, interactedAt;
    private static BlockPos interaction, cargoOffset;
    private static AbstractContainerMenu source;
    private static boolean nativeTreasure, learned, bossKilled, keyPicked;
    private record Offering(ItemStack stack,int owned,int available) {}

    private static boolean eligible(ServerPlayer p) {
        return LunarTrail.ENABLED && Files.isRegularFile(ACTIVE) && !p.hasPermissions(2) && !p.isCreative()
                && p.getGameProfile().getName().equals("OrbitEngineer");
    }
    private static void write(String event,Object value) throws Exception {facts.put(event,value);LunarTrail.record(RECORD,facts);}
    private static int count(Container container,ItemStack type) {
        int count=0;
        for (int i=0;i<container.getContainerSize();++i) {
            var stack=container.getItem(i);if (ItemStack.isSameItemSameTags(stack,type)) count+=stack.getCount();
        }
        return count;
    }
    private static int offered(AbstractContainerMenu menu,ServerPlayer p,ItemStack type) {
        return menu.slots.stream().filter(slot->slot.container!=p.getInventory() && ItemStack.isSameItemSameTags(slot.getItem(),type))
                .mapToInt(slot->slot.getItem().getCount()).sum();
    }
    @SubscribeEvent public static void death(LivingDeathEvent e) throws Exception {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || !eligible(p)) return;
        String id=String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(e.getEntity().getType()));
        if (!id.equals("galacticraftcore:evolvedskeletonboss")) return;
        bossKilled=true;write("boss",Map.of("id",id,"killer",p.getUUID(),"entity",e.getEntity().getUUID(),"gameTime",p.level().getGameTime()));
    }
    @SubscribeEvent public static void pickup(ItemPickupEvent e) throws Exception {
        if (!(e.getEntity() instanceof ServerPlayer p) || !eligible(p) || !bossKilled) return;
        ItemStack picked = e.getStack();
        String id = String.valueOf(ForgeRegistries.ITEMS.getKey(picked.getItem()));
        if (!"galacticraftcore:item.key".equals(id) || picked.isEmpty()) return;
        int inInventory = count(p.getInventory(), picked);
        if (inInventory < picked.getCount()) return;
        keyPicked = true;
        write("pickedUpKey", Map.of("item", id, "pickedUp", picked.getCount(), "inventoryAfter", inInventory,
                "stack", picked.save(new CompoundTag()).toString(), "entity", e.getOriginalEntity().getUUID(),
                "player", p.getUUID(), "gameTime", p.level().getGameTime()));
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !eligible(p)) return;
        interaction=e.getPos().immutable();interactedAt=p.tickCount;
        var id=ForgeRegistries.BLOCKS.getKey(p.level().getBlockState(interaction).getBlock());
        nativeTreasure=id!=null && id.getNamespace().equals("galacticraftcore") && id.getPath().contains("treasure");
    }
    @SubscribeEvent public static void opened(PlayerContainerEvent.Open e) throws Exception {
        if (!(e.getEntity() instanceof ServerPlayer p) || !eligible(p) || !keyPicked || !nativeTreasure || interaction==null || p.tickCount-interactedAt>8) return;
        source=e.getContainer(); offers.clear();
        for (var slot:source.slots) {
            var item=slot.getItem();
            if (slot.container==p.getInventory() || item.isEmpty() || offers.stream().anyMatch(o->ItemStack.isSameItemSameTags(o.stack(),item))) continue;
            offers.add(new Offering(item.copy(),count(p.getInventory(),item),offered(source,p,item)));
        }
        write("originalTreasureOpened",Map.of("position",interaction.asLong(),"gameTime",p.level().getGameTime(),"offers",offers.stream().map(o->o.stack().save(new CompoundTag()).toString()).toList()));
    }
    static void tick(ServerPlayer p, ShipCoreBlockEntity core, UUID expeditionRocket) throws Exception {
        if (!eligible(p)) return;
        if (!learned) {restore();learned=true;}
        if (reward.isEmpty() && source!=null) {
            for (var o:offers) {
                int gained=count(p.getInventory(),o.stack())-o.owned();
                if (gained<=0 || o.available()-offered(source,p,o.stack())<gained) continue;
                reward=o.stack().copy(); reward.setCount(gained);before=o.owned();amount=gained;
                write("reward",Map.of("stack",reward.save(new CompoundTag()).toString(),"previouslyOwned",before,"quantity",amount,"gameTime",p.level().getGameTime()));
                source=null;break;
            }
        }
        if (reward.isEmpty() || core==null || !expeditionRocket.equals(core.originalRocket()) || !HullTransit.isAboard(p,core)) return;
        if (cargoOffset==null && interaction!=null && !nativeTreasure && core.getBlockPos().distManhattan(interaction)<60
                && p.level().getBlockEntity(interaction) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest && count(chest,reward)>=amount && count(p.getInventory(),reward)==before) {
            cargoOffset=interaction.subtract(core.getBlockPos());
            write("aboardOriginalShip",Map.of("offset",cargoOffset.asLong(),"rocket",expeditionRocket.toString(),"quantity",count(chest,reward),"gameTime",p.level().getGameTime()));
        }
        if (cargoOffset!=null && p.serverLevel()==p.server.overworld() && !HullTransit.inProgress(p.server,core.id())) {
            var storage=p.level().getBlockEntity(core.getBlockPos().offset(cargoOffset));
            var tag = core.saveWithoutMetadata();
            if (storage instanceof Container c && count(c,reward)>=amount && tag.hasUUID("PaidVoyage") && !facts.containsKey("home"))
                write("home",Map.of("receipt",tag.getUUID("PaidVoyage").toString(),"rocket",expeditionRocket.toString(),"quantity",count(c,reward),"engineer",p.getUUID().toString(),"gameTime",p.level().getGameTime()));
        }
    }
    private static void restore() throws Exception {
        if (!Files.isRegularFile(RECORD)) return;
        var saved=JsonParser.parseString(Files.readString(RECORD)).getAsJsonObject();
        saved.entrySet().forEach(row->facts.put(row.getKey(),row.getValue()));
        bossKilled=saved.has("boss"); keyPicked=saved.has("pickedUpKey");
        if (saved.has("reward")) {
            var row=saved.getAsJsonObject("reward");reward=ItemStack.of(TagParser.parseTag(row.get("stack").getAsString()));
            amount=row.get("quantity").getAsInt();before=row.get("previouslyOwned").getAsInt();
        }
        if (saved.has("aboardOriginalShip")) cargoOffset=BlockPos.of(saved.getAsJsonObject("aboardOriginalShip").get("offset").getAsLong());
    }
    static boolean readyForReturn() {return cargoOffset!=null;}
    static boolean homeVerified() {return facts.containsKey("home");}
}
