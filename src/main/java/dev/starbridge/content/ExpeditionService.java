package dev.starbridge.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Original, one-time survey outpost and shared vault encounter. All mutations run on the server thread. */
public final class ExpeditionService {
    public static final String CUSTODIAN_TAG = "starbridge.vault_custodian";

    private ExpeditionService() {}

    /** Development transport only. Production travel must move the whole ship via the flight service. */
    public static boolean enterMoon(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerLevel moon = server.getLevel(ContentModule.MOON);
        if (moon == null) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.moon_unavailable"));
            return false;
        }
        ExpeditionSavedData data = ensureSites(moon);
        if (!player.level().dimension().equals(ContentModule.MOON)) {
            data.returns.put(player.getUUID(), ExpeditionSavedData.ReturnPoint.of(player));
            data.setDirty();
        }
        player.teleportTo(moon, data.outpost.getX() + 0.5, data.outpost.getY() + 1,
                data.outpost.getZ() + 0.5, 90, 0);
        player.fallDistance = 0;
        player.sendSystemMessage(Component.translatable("starbridge.expedition.admin_enter", coordinates(data.vault)));
        return true;
    }

    public static boolean returnFromMoon(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !player.level().dimension().equals(ContentModule.MOON)) return false;
        ExpeditionSavedData data = ExpeditionSavedData.get(server);
        ExpeditionSavedData.ReturnPoint point = data.returns.get(player.getUUID());
        if (point == null) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.no_return"));
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(point.dimension());
        ServerLevel destination = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (destination == null) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.return_missing"));
            return false;
        }
        BlockPos feet = BlockPos.containing(point.x(), point.y(), point.z());
        if (!destination.getBlockState(feet).getCollisionShape(destination, feet).isEmpty()
                || !destination.getBlockState(feet.above()).getCollisionShape(destination, feet.above()).isEmpty()) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.return_blocked"));
            return false;
        }
        player.teleportTo(destination, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        player.fallDistance = 0;
        data.returns.remove(player.getUUID());
        data.setDirty();
        return true;
    }

    public static ExpeditionSavedData ensureSites(ServerLevel moon) {
        if (!moon.dimension().equals(ContentModule.MOON)) throw new IllegalArgumentException("Survey sites belong only on Survey Moon");
        ExpeditionSavedData data = ExpeditionSavedData.get(moon.getServer());
        if (data.initialized) return data;
        // Level#getHeight returns minY for unloaded chunks; load the two destination columns first.
        moon.getChunk(0, 0);
        moon.getChunk(128 >> 4, 0);
        int outpostY = moon.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        int vaultY = moon.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 128, 0);
        data.outpost = new BlockPos(0, Math.max(16, outpostY), 0);
        data.vault = new BlockPos(128, Math.max(16, vaultY), 0);
        buildOutpost(moon, data.outpost);
        buildVault(moon, data.vault);
        data.initialized = true;
        data.setDirty();
        return data;
    }

    /** A character-sized rendezvous point. It is not a collision-safe landing solution for a ship. */
    public static Vec3 outpostRendezvous(ServerLevel moon) {
        BlockPos outpost = ensureSites(moon).outpost;
        return new Vec3(outpost.getX() + 0.5, outpost.getY() + 1, outpost.getZ() + 0.5);
    }

    static boolean inOutpostOxygen(ServerPlayer player, ExpeditionSavedData data) {
        return data.initialized && player.position().distanceToSqr(Vec3.atCenterOf(data.outpost)) <= 49
                && player.serverLevel().getBlockState(data.outpost).is(Blocks.BEACON);
    }

    /** Returns false if the site is not ready; a missing saved boss is never blindly duplicated. */
    public static boolean activateVault(ServerPlayer player) {
        if (!player.level().dimension().equals(ContentModule.MOON)) return false;
        ServerLevel level = player.serverLevel();
        ExpeditionSavedData data = ensureSites(level);
        if (player.position().distanceToSqr(Vec3.atCenterOf(data.vault)) > 24 * 24) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_reach", coordinates(data.vault)));
            return false;
        }
        if (data.defeated) return claimReward(player, data);
        if (data.custodian != null) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_active"));
            return false;
        }
        IronGolem guardian = EntityType.IRON_GOLEM.create(level);
        if (guardian == null) return false;
        guardian.moveTo(data.vault.getX() + 0.5, data.vault.getY() + 1, data.vault.getZ() + 8.5, 180, 0);
        guardian.setCustomName(Component.translatable("entity.starbridge.vault_custodian"));
        guardian.setCustomNameVisible(true);
        guardian.setPersistenceRequired();
        guardian.setPlayerCreated(false);
        guardian.getPersistentData().putBoolean(CUSTODIAN_TAG, true);
        if (guardian.getAttribute(Attributes.MAX_HEALTH) != null) guardian.getAttribute(Attributes.MAX_HEALTH).setBaseValue(180);
        guardian.setHealth(180);
        if (guardian.getAttribute(Attributes.MOVEMENT_SPEED) != null) guardian.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.23);
        guardian.setTarget(player);
        if (!level.addFreshEntity(guardian)) return false;
        data.custodian = guardian.getUUID();
        data.setDirty();
        player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_awakened"));
        return true;
    }

    static void spawnReinforcements(ServerLevel level, ExpeditionSavedData data) {
        for (int offset : new int[]{-7, 7}) {
            Stray sentry = EntityType.STRAY.create(level);
            if (sentry == null) continue;
            sentry.moveTo(data.vault.getX() + offset + 0.5, data.vault.getY() + 1,
                    data.vault.getZ() + 8.5, 180, 0);
            sentry.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            sentry.setCustomName(Component.translatable("entity.starbridge.vault_sentry"));
            sentry.setPersistenceRequired();
            level.addFreshEntity(sentry);
        }
        data.reinforcements = true;
        data.setDirty();
    }

    static boolean claimReward(ServerPlayer player, ExpeditionSavedData data) {
        if (!data.defeated) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_defeat_first"));
            return false;
        }
        if (data.claimed.contains(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_claimed"));
            return false;
        }
        // Refuse a full inventory rather than drop an unowned progression item on the floor.
        ItemStack reward = new ItemStack(ContentModule.SURVEY_CORE.get());
        if (!data.deliverReward(player.getUUID(), () -> player.getInventory().add(reward))) {
            player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_inventory_full"));
            return false;
        }
        player.sendSystemMessage(Component.translatable("starbridge.expedition.vault_reward"));
        return true;
    }

    public static Component status(ServerPlayer player) {
        ExpeditionSavedData data = ExpeditionSavedData.get(player.getServer());
        Component sites = data.initialized
                ? Component.translatable("starbridge.expedition.status_sites", coordinates(data.outpost), coordinates(data.vault))
                : Component.translatable("starbridge.expedition.status_ungenerated");
        String custodian = data.defeated ? "defeated" : data.custodian == null ? "dormant" : "active";
        return Component.translatable("starbridge.expedition.status", sites,
                Component.translatable("starbridge.expedition.custodian_" + custodian),
                Component.translatable(data.claimed.contains(player.getUUID()) ? "starbridge.expedition.core_claimed" : "starbridge.expedition.core_unclaimed"),
                OxygenCanisterItem.oxygen(player) / 20);
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void buildOutpost(ServerLevel level, BlockPos center) {
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            BlockPos floor = center.offset(x, -1, z);
            set(level, floor, Blocks.SMOOTH_STONE.defaultBlockState());
            for (int y = 0; y <= 4; y++) set(level, center.offset(x, y, z), Blocks.AIR.defaultBlockState());
            if (Math.abs(x) == 6 || Math.abs(z) == 6) {
                for (int y = 0; y <= 3; y++) {
                    if (x == 6 && Math.abs(z) <= 1 && y < 3) continue;
                    set(level, center.offset(x, y, z), Blocks.GLASS.defaultBlockState());
                }
            }
            set(level, center.offset(x, 4, z), Blocks.GLASS.defaultBlockState());
        }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            set(level, center.offset(x, -1, z), Blocks.IRON_BLOCK.defaultBlockState());
        set(level, center, Blocks.BEACON.defaultBlockState());
        set(level, center.offset(-4, 0, -4), Blocks.CRAFTING_TABLE.defaultBlockState());
        set(level, center.offset(-4, 0, 4), Blocks.FURNACE.defaultBlockState());
        set(level, center.offset(4, -1, 4), Blocks.SEA_LANTERN.defaultBlockState());
        set(level, center.offset(4, -1, -4), Blocks.SEA_LANTERN.defaultBlockState());
    }

    private static void buildVault(ServerLevel level, BlockPos center) {
        for (int x = -12; x <= 12; x++) for (int z = -5; z <= 18; z++) {
            set(level, center.offset(x, -1, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            for (int y = 0; y <= 7; y++) {
                boolean wall = Math.abs(x) == 12 || z == -5 || z == 18;
                boolean doorway = z == -5 && Math.abs(x) <= 2 && y < 4;
                set(level, center.offset(x, y, z), wall && !doorway
                        ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            set(level, center.offset(x, 8, z), Blocks.DEEPSLATE_TILES.defaultBlockState());
        }
        for (int x : new int[]{-9, 9}) for (int z : new int[]{-2, 7, 15}) {
            for (int y = 0; y <= 7; y++) set(level, center.offset(x, y, z), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
            set(level, center.offset(x, 6, z), Blocks.SEA_LANTERN.defaultBlockState());
        }
        set(level, center, Blocks.LODESTONE.defaultBlockState());
        set(level, center.offset(0, -1, 8), Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = -2; x <= 2; x++) for (int z = -12; z < -5; z++)
            set(level, center.offset(x, -1, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
    }
}
