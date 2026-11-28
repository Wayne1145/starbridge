package dev.starbridge.campaign;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Pilot charts and a server-wide relay ledger, stored on the overworld across dimension unloads. */
final class CampaignData extends SavedData {
    static final String ID = "starbridge_campaign";
    static final int ARRAYS_REQUIRED = 3;
    static final int COPPER_REQUIRED = 32;
    static final int POWER_REQUIRED = 8;
    private final Map<UUID, Integer> pilots = new HashMap<>();
    private final Map<String, BlockPos> sites = new HashMap<>();
    private int arrays;
    private int copper;
    private int power;
    private long contracts;

    static CampaignData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(CampaignData::load, CampaignData::new, ID);
    }
    int rank(UUID player) { return pilots.getOrDefault(player, 0); }
    void learn(UUID player, int rank) {
        if (rank > rank(player) && rank <= 2) { pilots.put(player, rank); setDirty(); }
    }
    BlockPos site(String key) { return sites.get(key); }
    void setSite(String key, BlockPos pos) { sites.put(key, pos.immutable()); setDirty(); }

    enum Material { ARRAYS, COPPER, POWER }
    int count(Material material) {
        return switch (material) { case ARRAYS -> arrays; case COPPER -> copper; case POWER -> power; };
    }
    int required(Material material) {
        return switch (material) { case ARRAYS -> ARRAYS_REQUIRED; case COPPER -> COPPER_REQUIRED; case POWER -> POWER_REQUIRED; };
    }
    int deposit(Material material, int offered) {
        int accepted = Math.min(Math.max(offered, 0), required(material) - count(material));
        if (accepted > 0) {
            switch (material) { case ARRAYS -> arrays += accepted; case COPPER -> copper += accepted; case POWER -> power += accepted; }
            setDirty();
        }
        return accepted;
    }
    boolean online() { return arrays == ARRAYS_REQUIRED && copper == COPPER_REQUIRED && power == POWER_REQUIRED; }
    enum Contract { SUPPLY, SURVEY, RESCUE }
    Contract contract() { return Contract.values()[(int)(contracts % Contract.values().length)]; }
    long contractNumber() { return contracts; }
    void finishContract() { contracts = contracts == Long.MAX_VALUE ? 0 : contracts + 1; setDirty(); }

    static CampaignData load(CompoundTag tag) {
        CampaignData data = new CampaignData();
        for (Tag tagEntry : tag.getList("Pilots", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tagEntry;
            if (entry.hasUUID("Player")) data.learn(entry.getUUID("Player"), entry.getInt("Rank"));
        }
        CompoundTag sites = tag.getCompound("Sites");
        for (String key : sites.getAllKeys()) if (sites.contains(key, Tag.TAG_LONG)) data.sites.put(key, BlockPos.of(sites.getLong(key)));
        data.arrays = bounded(tag.getInt("Arrays"), ARRAYS_REQUIRED);
        data.copper = bounded(tag.getInt("Copper"), COPPER_REQUIRED);
        data.power = bounded(tag.getInt("Power"), POWER_REQUIRED);
        data.contracts = Math.max(0L, tag.getLong("Contracts"));
        data.setDirty(false);
        return data;
    }
    private static int bounded(int n, int max) { return Math.max(0, Math.min(n, max)); }
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        pilots.forEach((player, rank) -> {
            CompoundTag entry = new CompoundTag(); entry.putUUID("Player", player); entry.putInt("Rank", rank); entries.add(entry);
        });
        tag.put("Pilots", entries);
        CompoundTag siteTag = new CompoundTag();
        sites.forEach((key, pos) -> siteTag.putLong(key, pos.asLong()));
        tag.put("Sites", siteTag);
        tag.putInt("Arrays", arrays); tag.putInt("Copper", copper); tag.putInt("Power", power); tag.putLong("Contracts", contracts);
        return tag;
    }
}
