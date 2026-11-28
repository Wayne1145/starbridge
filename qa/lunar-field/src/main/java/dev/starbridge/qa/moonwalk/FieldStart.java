package dev.starbridge.qa.moonwalk;

import com.google.gson.GsonBuilder;

import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.voyage.VoyageModule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@Mod("starbridgelunarfieldqa")
@Mod.EventBusSubscriber(modid = "starbridgelunarfieldqa")
public final class FieldStart {
    static final boolean ON =
            "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                    && "Linux".equals(System.getenv("RUNNER_OS"))
                    && "true".equals(System.getenv("GITHUB_ACTIONS"))
                    && Files.isRegularFile(Path.of("lunar-field.fixture"));
    // Regenerate the old expedition's world and start at the engineer's recorded ship X/Z.
    static boolean resuming() { return Files.isRegularFile(Path.of("field-lineage.json")); }
    static com.google.gson.JsonObject read(String name) throws Exception {
        return com.google.gson.JsonParser.parseString(Files.readString(Path.of(name + ".json"))).getAsJsonObject();
    }
    static final long SEED = -7579832739163479874L;
    private static boolean supplied, stopped;
    private static int began;

    static void write(String file, Object value) throws Exception {
        Path dst = Path.of(file), tmp = dst.resolveSibling(dst.getFileName() + ".partial");
        Files.writeString(
                tmp, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value));
        Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    @SubscribeEvent
    public static void server(TickEvent.ServerTickEvent e) throws Exception {
        if (!ON || stopped || e.phase != TickEvent.Phase.END) return;
        var server = e.getServer();
        if (server.isDedicatedServer() || server.getPlayerList().getPlayers().isEmpty()) return;
        var player = server.getPlayerList().getPlayers().get(0);
        try {
            var moon = OriginalStarMap.ports(server).get("moon");
            if (moon == null || moon.dimension() == null) return;
            var world = server.getLevel(moon.dimension());
            require(
                    world != null && "Galacticraft".equals(moon.provider()),
                    "Original Moon registry");
            if (!supplied && resuming()) {
                if (player.serverLevel() != world) return;
                require(!server.getWorldData().getAllowCommands() && !player.hasPermissions(2)
                        && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                        "Resumed ordinary survival player");
                var prior = read("field-lineage");
                var feet = prior.getAsJsonArray("playerFeet");
                require(player.position().distanceTo(new net.minecraft.world.phys.Vec3(feet.get(0).getAsDouble(),
                        feet.get(1).getAsDouble(),feet.get(2).getAsDouble())) < 3.0,"Saved explorer resumed in place");
                FieldVessel.resume(player);
                supplied = true;
                began = server.getTickCount();
                int keys = FieldVessel.keys(player);
                int savedKeys = prior.has("carriedKeys") ? prior.get("carriedKeys").getAsInt() : keys;
                require(keys == savedKeys, "Rejoined inventory matches the saved original key count");
                write("field-rejoined.json",Map.of("run",prior.get("run").getAsLong(),"pilot",player.getUUID(),"feet",List.of(player.getX(),player.getY(),player.getZ()),
                        "keys",keys,"world",world.dimension().location().toString()));
                if (keys > 0 && !Files.isRegularFile(Path.of("field-key.json"))
                        && !Files.isRegularFile(Path.of("field-key-carried.json"))) {
                    write("field-key-carried.json", Map.of("player", player.getUUID(), "keys", keys,
                            "run", prior.get("run").getAsLong(), "worldSha256", prior.get("worldSha256").getAsString()));
                }
                return;
            }
            if (!supplied) {
                require(
                        !server.getWorldData().getAllowCommands()
                                && !player.hasPermissions(2)
                                && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                        "Non-operator survival");
                // No other column, structure metadata or underground material is consulted during
                // setup.
                BlockPos pos = new BlockPos(0, world.getMaxBuildHeight() - 1, -1);
                world.getChunk(0, -1);
                while (pos.getY() > world.getMinBuildHeight() && world.getBlockState(pos).isAir())
                    pos = pos.below();
                require(pos.getY() > world.getMinBuildHeight(), "Ground at recorded ship");
                var deck = pos.above();
                FieldVessel.supply(player, world, deck);
                player.teleportTo(
                        world, deck.getX() + .5, deck.getY() + 1.0, deck.getZ() + .5, 0f, 12f);
                player.getInventory().selected = 0;
                player.getInventory().setItem(0, new ItemStack(VoyageModule.SLATE.get()));
                player.getInventory().setItem(1, new ItemStack(Items.IRON_SWORD));
                player.getInventory().setItem(2, new ItemStack(Items.COOKED_BEEF, 24));
                player.getInventory()
                        .setItem(
                                3,
                                PotionUtils.setPotion(
                                        new ItemStack(Items.POTION), Potions.LONG_SLOW_FALLING));
                player.getInventory().setItem(4, new ItemStack(Items.IRON_PICKAXE));
                player.getInventory().setItem(5, new ItemStack(Items.LADDER, 48));
                player.getInventory().setItem(6, new ItemStack(Items.COBBLESTONE, 64));
                player.getInventory().setItem(7, new ItemStack(Items.GOLDEN_APPLE, 3));
                var boots = new ItemStack(Items.IRON_BOOTS);
                boots.enchant(Enchantments.FALL_PROTECTION, 4);
                player.setItemSlot(EquipmentSlot.FEET, boots);
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                equipOxygen(player);
                write(
                        "field-start.json",
                        Map.of(
                                "originalMoon",
                                world.dimension().location().toString(),
                                "seed",
                                SEED,
                                "deck",
                                List.of(deck.getX(), deck.getY(), deck.getZ()),
                                "ordinarySurvival",
                                true,
                                "cheats",
                                false,
                                "shipMarkerSource",
                                "Previous expedition's visible SHIP mark X/Z; fresh terrain and"
                                    + " registered expedition ship",
                                "preparedOxygen",
                                List.of("GC oxygen mask", "oxygen gear", "two heavy tanks")));
                supplied = true;
                began = server.getTickCount();
                return;
            }
            FieldBossDrop.observe(player);
            FieldVessel.observe(player, world);
            if (server.getTickCount() % 100 == 0) {
                write(
                        "field-health.json",
                        Map.of(
                                "living",
                                player.isAlive(),
                                "health",
                                player.getHealth(),
                                "feet",
                                List.of(player.getX(), player.getY(), player.getZ()),
                                "dimension",
                                player.serverLevel().dimension().location().toString(),
                                "elapsedTicks",
                                server.getTickCount() - began));
            }
        } catch (Exception failure) {
            stopped = true;
            write("field-failed.json", Map.of("source", "server", "error", failure.toString()));
        }
    }

    /** Only record actual server interaction events with the first surface the player can reach. */
    @SubscribeEvent
    public static void interact(PlayerInteractEvent.RightClickBlock e) throws Exception {
        if (!ON
                || !(e.getEntity() instanceof ServerPlayer p)
                || !supplied
                || !p.serverLevel().dimension().location().getNamespace().equals("retrofit"))
            return;
        var pos = e.getPos();
        var state = p.level().getBlockState(pos);
        var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !id.toString().equals("galacticraftcore:tile.moonblock")) return;
        boolean brick =
                state.getValues().entrySet().stream()
                        .anyMatch(
                                a ->
                                        a.getKey().getName().equals("meta")
                                                && a.getValue().equals(14));
        if (brick
                && p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos))
                        <= 5.5)
            write(
                    "field-native-interaction.json",
                    Map.of(
                            "block",
                            id.toString(),
                            "meta",
                            14,
                            "face",
                            pos.toShortString(),
                            "player",
                            p.getUUID(),
                            "tick",
                            p.tickCount,
                            "interaction",
                            "right click on original dungeon brick"));
    }

    @SubscribeEvent
    public static void attack(net.minecraftforge.event.entity.player.AttackEntityEvent event)
            throws Exception {
        if (!ON || !(event.getEntity() instanceof ServerPlayer player) || player.hasPermissions(2)
                || !(event.getTarget() instanceof net.minecraft.world.entity.LivingEntity target)
                || !skeletonBoss(target) || !Files.isRegularFile(Path.of("field-native-interaction.json"))) return;
        FieldBossDrop.attack(player, target);
        write("field-boss-contact.json", Map.of("entity", target.getUUID(), "player", player.getUUID(),
                "position", List.of(target.getX(), target.getY(), target.getZ()),
                "healthBeforeHit", target.getHealth(), "tick", player.tickCount));
    }

    private static boolean skeletonBoss(net.minecraft.world.entity.Entity entity) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && id.toString().equals("galacticraftcore:evolvedskeletonboss");
    }

    @SubscribeEvent
    public static void hurt(net.minecraftforge.event.entity.living.LivingDamageEvent event) throws Exception {
        if (!ON || !(event.getEntity() instanceof ServerPlayer player) || event.getAmount() <= 0
                || !Files.isRegularFile(Path.of("field-native-interaction.json"))) return;
        var cause = event.getSource().getEntity();
        write("field-player-damage.json", Map.of("player", player.getUUID(), "tick", player.tickCount,
                "feet", List.of(player.getX(), player.getY(), player.getZ()), "beforeHealth", player.getHealth(),
                "damage", event.getAmount(), "damageType", event.getSource().getMsgId(),
                "attacker", cause == null ? "none" : String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(cause.getType()))));
    }

    @SubscribeEvent
    public static void boss(net.minecraftforge.event.entity.living.LivingDeathEvent event)
            throws Exception {
        var defeated = event.getEntity();
        if (!ON || defeated.level().isClientSide || !Files.isRegularFile(Path.of("field-native-interaction.json"))) return;
        var source = event.getSource().getEntity();
        if (defeated instanceof ServerPlayer player) {
            write("field-player-death.json", Map.of("player", player.getUUID(), "tick", player.tickCount,
                    "feet", List.of(player.getX(), player.getY(), player.getZ()),
                    "damageType", event.getSource().getMsgId(),
                    "attacker", source == null ? "none" : String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(source.getType())),
                    "originalKeysAtDeath", FieldVessel.keys(player)));
            return;
        }
        if (!skeletonBoss(defeated)) return;
        write("field-boss-death-source.json", Map.of("entity", defeated.getUUID(),
                "source", source == null ? "none" : source.getStringUUID(),
                "damage", event.getSource().getMsgId(), "tick", defeated.tickCount));
        if (source instanceof ServerPlayer player && !player.hasPermissions(2)) {
            write("field-boss.json", Map.of("id", "galacticraftcore:evolvedskeletonboss",
                    "entity", defeated.getUUID(), "killer", player.getUUID(), "tick", player.tickCount));
        }
    }

    @SubscribeEvent
    public static void key(net.minecraftforge.event.entity.player.PlayerEvent.ItemPickupEvent event)
            throws Exception {
        if (!ON || !(event.getEntity() instanceof ServerPlayer player) || player.hasPermissions(2)
                || !Files.isRegularFile(Path.of("field-native-interaction.json"))) return;
        var stack = event.getStack();
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        int keys = FieldVessel.keys(player);
        if (stack.isEmpty() || id == null || !id.toString().equals("galacticraftcore:item.key")
                || keys < stack.getCount()) return;
        var receipt = Map.of("item", id.toString(),
                "stack", stack.save(new net.minecraft.nbt.CompoundTag()).toString(),
                "keysInInventory", keys, "player", player.getUUID(), "tick", player.tickCount,
                "droppedEntity", event.getOriginalEntity().getUUID());
        write("field-loose-key-pickup.json", receipt);
        if (Files.isRegularFile(Path.of("field-boss.json"))) {
            var boss = read("field-boss");
            if (boss.get("killer").getAsString().equals(player.getStringUUID())
                    && (!boss.has("droppedEntity") || boss.get("droppedEntity").getAsString()
                            .equals(event.getOriginalEntity().getStringUUID()))) {
                write("field-key.json", receipt);
            }
        }
    }

    private static void equipOxygen(ServerPlayer p) throws Exception {
        var mirror =
                Class.forName("retrofit.forge.world.LegacyPlayers")
                        .getMethod("mirror", Player.class, boolean.class)
                        .invoke(null, p, true);
        var realm = mirror.getClass().getClassLoader();
        var stats =
                realm.loadClass("micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats");
        Object inventory =
                stats.getField("extendedInventory")
                        .get(
                                stats.getMethod(
                                                "get",
                                                realm.loadClass(
                                                        "net.minecraft.entity.player.EntityPlayerMP"))
                                        .invoke(null, mirror));
        var item = realm.loadClass("net.minecraft.item.Item");
        var stack = realm.loadClass("net.minecraft.item.ItemStack");
        var parts = realm.loadClass("micdoodle8.mods.galacticraft.core.items.GCItems");
        var set = inventory.getClass().getMethod("func_70299_a", int.class, stack);
        String[] ids = {"oxMask", "oxygenGear", "oxTankHeavy", "oxTankHeavy"};
        for (int i = 0; i < ids.length; i++)
            set.invoke(
                    inventory,
                    i,
                    stack.getConstructor(item, int.class, int.class)
                            .newInstance(parts.getField(ids[i]).get(null), 1, 0));
    }
}
