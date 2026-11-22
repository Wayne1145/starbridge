package dev.starbridge.qa.moonwalk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Observe the legacy boss's own death phase and the exact key it spawns through Retrofit. */
@Mod.EventBusSubscriber(modid = "starbridgelunarfieldqa")
public final class FieldBossDrop {
    private static final String ID = "galacticraftcore:evolvedskeletonboss";
    private static final String LEGACY = "micdoodle8.mods.galacticraft.core.entities.EntitySkeletonBoss";
    private static LivingEntity attacked;
    private static ServerPlayer attacker;
    private static int lastAttack, deathStarted = -1;

    private FieldBossDrop() {}

    static void attack(ServerPlayer player, LivingEntity boss) {
        if (deathTicks(boss) != 0) return;
        if (boss != attacked || player != attacker) deathStarted = -1;
        attacked = boss;
        attacker = player;
        lastAttack = player.tickCount;
    }

    static void observe(ServerPlayer player) {
        if (player != attacker || attacked.level() != player.level() || deathStarted != -1) return;
        int age = player.tickCount - lastAttack;
        if (age >= 0 && age <= 5 && deathTicks(attacked) > 0) deathStarted = player.tickCount;
    }

    @SubscribeEvent
    public static void spawned(EntityJoinLevelEvent event) throws Exception {
        if (!FieldStart.ON || deathStarted < 0 || event.getLevel() != attacked.level()
                || !(event.getEntity() instanceof ItemEntity item)) return;
        var id = ForgeRegistries.ITEMS.getKey(item.getItem().getItem());
        if (id == null || !id.toString().equals("galacticraftcore:item.key")
                || item.distanceToSqr(attacked) > 9 || deathTicks(attacked) != 200
                || Files.isRegularFile(Path.of("field-boss.json"))) return;
        FieldStart.write("field-boss.json", Map.of(
                "id", ID, "entity", attacked.getUUID(), "killer", attacker.getUUID(),
                "method", "legacy-death-drop", "lastAttack", lastAttack, "deathStarted", deathStarted,
                "nativeDeathTicks", 200, "dropTick", attacker.tickCount, "droppedEntity", item.getUUID(),
                "position", List.of(item.getX(), item.getY(), item.getZ())));
    }

    private static int deathTicks(LivingEntity boss) {
        try {
            Object core = boss.getClass().getMethod("core").invoke(boss);
            Object original = core.getClass().getMethod("legacy").invoke(core);
            if (original != null && original.getClass().getName().equals(LEGACY)) {
                return original.getClass().getField("deathTicks").getInt(original);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Keep the normal Forge death path available on a host without the locked original.
        }
        return -1;
    }
}
