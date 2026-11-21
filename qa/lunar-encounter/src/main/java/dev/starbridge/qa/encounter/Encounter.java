package dev.starbridge.qa.encounter;

import com.google.gson.GsonBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod("starbridgelunarqa")
@Mod.EventBusSubscriber(modid = "starbridgelunarqa")
public class Encounter {
    static final EncounterSite SITE = EncounterSite.SELECTED;
    static final ResourceKey<Level> TARGET =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation("retrofit", SITE.path()));
    static final BlockPos SPAWNER = SITE.spawner(), CHEST = SITE.chest();
    static final Map<String, Object> out = new LinkedHashMap<>();
    static final List<String> history = new ArrayList<>();
    static int joined, sent, chestAt, lastStatus;
    static boolean issuedSlate, done, seen, keyDrop, keyHeld;
    static ResourceLocation keyId;

    static CompoundTag tile(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        return be == null ? new CompoundTag() : be.saveWithoutMetadata().getCompound("legacy");
    }

    static void write(boolean end) {
        try {
            Files.writeString(
                    Path.of(end ? "encounter-result.json" : "encounter-live.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(out));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void suit(ServerPlayer player, EquipmentSlot slot, String part, Item fallback) {
        var match =
                ForgeRegistries.ITEMS.getKeys().stream()
                        .filter(
                                id ->
                                        id.getNamespace().equals("extraplanets")
                                                && id.getPath()
                                                        .endsWith("tier4_space_suit_" + part))
                        .findFirst();
        var piece =
                new ItemStack(
                        match.isPresent() ? ForgeRegistries.ITEMS.getValue(match.get()) : fallback);
        piece.enchant(Enchantments.ALL_DAMAGE_PROTECTION, 4);
        player.setItemSlot(slot, piece);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || done
                || !Files.exists(Path.of("starbridge-lunar-encounter.fixture"))) return;
        var server = event.getServer();
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var p = players.get(0);
        int tick = server.getTickCount();
        if (joined == 0) joined = tick;
        if (tick - joined < 260) return;
        var moon = server.getLevel(TARGET);
        if (!issuedSlate) {
            issuedSlate = true;
            var item =
                    ForgeRegistries.ITEMS.getValue(
                            new ResourceLocation("starbridge", "navigation_slate"));
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        }
        if (sent == 0
                && !Files.exists(Path.of("../LunarTester/slate-guide-done"))
                && tick - joined < 500) return;
        if (sent == 0) {
            out.put(
                    "unpairedSlateGuideCompleted",
                    Files.exists(Path.of("../LunarTester/slate-guide-done")));
            out.put("site", SITE.name());
            out.put(
                    "keyCandidates",
                    ForgeRegistries.ITEMS.getKeys().stream()
                            .filter(SITE::isKey)
                            .map(Object::toString)
                            .toList());
            out.put("beforeSpawner", tile(moon, SPAWNER).toString());
            out.put("beforeChest", tile(moon, CHEST).toString());
            out.put("operator", server.getPlayerList().isOp(p.getGameProfile()));
            out.put("gamemode", p.gameMode.getGameModeForPlayer().getName());
            out.put("history", history);
            var sword = new ItemStack(Items.NETHERITE_SWORD);
            sword.enchant(Enchantments.SHARPNESS, 5);
            p.setItemSlot(EquipmentSlot.MAINHAND, sword);
            suit(p, EquipmentSlot.HEAD, "helmet", Items.NETHERITE_HELMET);
            suit(p, EquipmentSlot.CHEST, "chest", Items.NETHERITE_CHESTPLATE);
            suit(p, EquipmentSlot.LEGS, "legings", Items.NETHERITE_LEGGINGS);
            suit(p, EquipmentSlot.FEET, "boots", Items.NETHERITE_BOOTS);
            p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 12000, 2));
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 12000, 1));
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 12000, 1));
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 12000));
            out.put(
                    "fixtureArmor",
                    java.util.stream.StreamSupport.stream(p.getArmorSlots().spliterator(), false)
                            .map(stack -> ForgeRegistries.ITEMS.getKey(stack.getItem()).toString())
                            .toList());
            p.teleportTo(
                    moon, SPAWNER.getX() + 1.5, SPAWNER.getY() - 2, SPAWNER.getZ() + 2.5, 135, 0);
            sent = tick;
            write(false);
            return;
        }
        if (!p.level().dimension().equals(TARGET)) return;
        var box = new AABB(SPAWNER).inflate(25);
        for (var e : moon.getEntities((Entity) null, box, e -> true)) {
            var type = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            if (type != null && type.getPath().toLowerCase(Locale.ROOT).contains("boss")) {
                if (!seen) history.add("Boss observed at " + e.blockPosition() + " ID=" + type);
                seen = true;
                if (e instanceof LivingEntity living) out.put("lastSeenBossHP", living.getHealth());
            }
            if (e instanceof ItemEntity drop
                    && SITE.isKey(ForgeRegistries.ITEMS.getKey(drop.getItem().getItem()))) {
                keyId = ForgeRegistries.ITEMS.getKey(drop.getItem().getItem());
                out.put("registeredKey", keyId.toString());
                out.put("keyMeta", drop.getItem().getOrCreateTag().getInt("LegacyMeta"));
                if (!keyDrop) history.add("Original key dropped " + drop.blockPosition());
                keyDrop = true;
            }
        }
        if (keyId != null
                && !keyHeld
                && p.getInventory()
                        .hasAnyMatching(
                                stack ->
                                        keyId.equals(
                                                ForgeRegistries.ITEMS.getKey(stack.getItem())))) {
            keyHeld = true;
            history.add("Player picked up original key");
            p.teleportTo(moon, CHEST.getX() + .5, CHEST.getY(), CHEST.getZ() + 2.5, 180, 12);
            chestAt = tick;
        }
        if (tick - lastStatus >= 25 || tick - sent > 2350) {
            lastStatus = tick;
            out.put("observedBoss", seen);
            out.put("observedDroppedKey", keyDrop);
            out.put("keyInInventory", keyHeld);
            out.put("afterSpawner", tile(moon, SPAWNER).toString());
            CompoundTag chest = tile(moon, CHEST);
            out.put("afterChest", chest.toString());
            out.put("menu", p.containerMenu.getClass().getName());
            out.put("ticksSinceArrival", tick - sent);
            out.put("position", p.blockPosition().toShortString());
            out.put("hp", p.getHealth());
            out.put(
                    "inventory",
                    p.getInventory().items.stream()
                            .filter(s -> !s.isEmpty())
                            .map(
                                    s ->
                                            ForgeRegistries.ITEMS.getKey(s.getItem())
                                                    + " x"
                                                    + s.getCount())
                            .toList());
            boolean reward =
                    keyHeld
                            && p.getInventory().items.stream()
                                    .anyMatch(
                                            s ->
                                                    !s.isEmpty()
                                                            && s.getItem() != Items.NETHERITE_SWORD
                                                            && !keyId.equals(
                                                                    ForgeRegistries.ITEMS.getKey(
                                                                            s.getItem())));
            boolean success =
                    seen
                            && keyHeld
                            && chest.contains("isLocked")
                            && !chest.getBoolean("isLocked")
                            && reward;
            if (success || !p.isAlive() || tick - sent > 2350) {
                out.put("pass", success);
                done = true;
                write(true);
            } else write(false);
        }
    }
}
