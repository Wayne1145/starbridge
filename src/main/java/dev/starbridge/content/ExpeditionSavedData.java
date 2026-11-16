package dev.starbridge.content;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Stored on the overworld so expedition state survives dimension unloading. */
public final class ExpeditionSavedData extends SavedData {
    boolean initialized;
    BlockPos outpost = BlockPos.ZERO;
    BlockPos vault = BlockPos.ZERO;
    UUID custodian;
    boolean defeated;
    boolean reinforcements;
    final Set<UUID> claimed = new HashSet<>();
    final Map<UUID, ReturnPoint> returns = new HashMap<>();

    public record ReturnPoint(String dimension, double x, double y, double z, float yaw, float pitch) {
        static ReturnPoint of(ServerPlayer player) {
            return new ReturnPoint(player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    public static ExpeditionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ExpeditionSavedData::load, ExpeditionSavedData::new, "starbridge_expeditions");
    }

    public boolean hasClaimed(UUID player) { return claimed.contains(player); }

    /**
     * Runs an atomic one-item inventory delivery only while this player is eligible.
     * The callback must return false without side effects if the inventory cannot accept the reward.
     */
    public boolean deliverReward(UUID player, BooleanSupplier delivery) {
        if (!defeated || !claimed.add(player)) return false;
        boolean delivered = false;
        try {
            delivered = delivery.getAsBoolean();
            return delivered;
        } finally {
            if (delivered) setDirty();
            else claimed.remove(player);
        }
    }

    public static ExpeditionSavedData load(CompoundTag tag) {
        ExpeditionSavedData data = new ExpeditionSavedData();
        data.initialized = tag.getBoolean("Initialized");
        data.outpost = BlockPos.of(tag.getLong("Outpost"));
        data.vault = BlockPos.of(tag.getLong("Vault"));
        if (tag.hasUUID("Custodian")) data.custodian = tag.getUUID("Custodian");
        data.defeated = tag.getBoolean("Defeated");
        data.reinforcements = tag.getBoolean("Reinforcements");
        for (Tag item : tag.getList("Claims", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) item;
            if (entry.hasUUID("Player")) data.claimed.add(entry.getUUID("Player"));
        }
        for (Tag item : tag.getList("Returns", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) item;
            if (!entry.hasUUID("Player")) continue;
            data.returns.put(entry.getUUID("Player"), new ReturnPoint(entry.getString("Dimension"),
                    entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"),
                    entry.getFloat("Yaw"), entry.getFloat("Pitch")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Schema", 1);
        tag.putBoolean("Initialized", initialized);
        tag.putLong("Outpost", outpost.asLong());
        tag.putLong("Vault", vault.asLong());
        if (custodian != null) tag.putUUID("Custodian", custodian);
        tag.putBoolean("Defeated", defeated);
        tag.putBoolean("Reinforcements", reinforcements);
        ListTag claims = new ListTag();
        for (UUID player : claimed) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            claims.add(entry);
        }
        tag.put("Claims", claims);
        ListTag returnTags = new ListTag();
        returns.forEach((player, point) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putString("Dimension", point.dimension());
            entry.putDouble("X", point.x());
            entry.putDouble("Y", point.y());
            entry.putDouble("Z", point.z());
            entry.putFloat("Yaw", point.yaw());
            entry.putFloat("Pitch", point.pitch());
            returnTags.add(entry);
        });
        tag.put("Returns", returnTags);
        return tag;
    }
}
