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
    public static void boss(net.minecraftforge.event.entity.living.LivingDeathEvent e)
            throws Exception {
        if (!ON
                || !(e.getSource().getEntity() instanceof ServerPlayer p)
                || !Files.isRegularFile(Path.of("field-native-interaction.json"))) return;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(e.getEntity().getType());
        if (id != null && id.toString().equals("galacticraftcore:evolvedskeletonboss")) {
            write(
                    "field-boss.json",
                    Map.of(
                            "id",
                            id.toString(),
                            "entity",
                            e.getEntity().getUUID(),
                            "killer",
                            p.getUUID(),
                            "tick",
                            p.tickCount));
        }
    }

    @SubscribeEvent
    public static void key(net.minecraftforge.event.entity.player.PlayerEvent.ItemPickupEvent e)
            throws Exception {
        if (!ON
                || !(e.getEntity() instanceof ServerPlayer p)
                || !Files.isRegularFile(Path.of("field-boss.json"))) return;
        var stack = e.getStack();
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && id.toString().equals("galacticraftcore:item.key") && !stack.isEmpty()) {
            write(
                    "field-key.json",
                    Map.of(
                            "item",
                            id.toString(),
                            "stack",
                            stack.save(new net.minecraft.nbt.CompoundTag()).toString(),
                            "player",
                            p.getUUID(),
                            "tick",
                            p.tickCount));
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
