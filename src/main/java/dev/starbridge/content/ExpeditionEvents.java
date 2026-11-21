package dev.starbridge.content;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

public final class ExpeditionEvents {
    private static final UUID MOON_GRAVITY = UUID.fromString("542c232d-5e17-4f1e-bdc5-b37a829837ea");
    private final ServerBossEvent bossBar = new ServerBossEvent(Component.literal("Vault Custodian"),
            BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("starbridge_expedition")
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("enter").requires(source -> source.hasPermission(2)).executes(context ->
                        ExpeditionService.enterMoon(context.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("return").requires(source -> source.hasPermission(2)).executes(context ->
                        ExpeditionService.returnFromMoon(context.getSource().getPlayerOrException()) ? 1 : 0)));
    }

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        String message = ExpeditionService.status(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        boolean onMoon = player.level().dimension().equals(ContentModule.MOON);
        boolean airless = onMoon || player.level().dimension().equals(dev.starbridge.campaign.CampaignModule.MARS)
                || player.level().dimension().equals(dev.starbridge.campaign.CampaignModule.BELT)
                || player.level().dimension().equals(dev.starbridge.campaign.CampaignModule.RELAY);
        AttributeInstance gravity = player.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
        if (gravity != null) {
            if (onMoon && gravity.getModifier(MOON_GRAVITY) == null) gravity.addTransientModifier(
                    new AttributeModifier(MOON_GRAVITY, "Survey Moon gravity", -0.75, AttributeModifier.Operation.MULTIPLY_TOTAL));
            if (!onMoon && gravity.getModifier(MOON_GRAVITY) != null) gravity.removeModifier(MOON_GRAVITY);
        }
        // Reconcile transient gravity every tick, including after a dimension change/rejoin.
        // Oxygen consumption remains exactly once per second.
        if (!airless || player.tickCount % 20 != 0 || player.isSpectator() || player.getAbilities().instabuild || !player.isAlive()) return;
        if (dev.starbridge.voyage.CabinPressure.breathable(player)) return;
        ExpeditionSavedData data = onMoon ? ExpeditionService.ensureSites(player.serverLevel()) : null;
        if (data != null && ExpeditionService.inOutpostOxygen(player, data)) {
            OxygenCanisterItem.setOxygen(player, OxygenCanisterItem.oxygen(player) + 200);
            if (player.tickCount % 40 == 0) player.displayClientMessage(Component.literal(
                    "Outpost oxygen field — reservoir " + OxygenCanisterItem.oxygen(player) / 20 + " s"), true);
            return;
        }
        int remaining = OxygenCanisterItem.oxygen(player);
        boolean masked = player.getItemBySlot(EquipmentSlot.HEAD).is(ContentModule.OXYGEN_MASK.get());
        if (masked && remaining > 0) {
            OxygenCanisterItem.setOxygen(player, remaining - 20);
            if (player.tickCount % 40 == 0) player.displayClientMessage(Component.literal(
                    "Oxygen " + Math.max(0, remaining - 20) / 20 + " s" + (data == null ? "" : " | Vault: " + data.vault.getX() + ", " + data.vault.getZ())), true);
        } else {
            player.hurt(player.damageSources().drown(), 2.0F);
            player.displayClientMessage(Component.literal(masked ? "No oxygen — use a canister or return to the outpost!"
                    : "Vacuum — equip your oxygen mask!"), true);
        }
    }

    @SubscribeEvent
    public void fall(LivingFallEvent event) {
        if (event.getEntity().level().dimension().equals(ContentModule.MOON))
            event.setDistance(event.getDistance() * 0.25F);
    }

    @SubscribeEvent
    public void terminal(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.level().dimension().equals(ContentModule.MOON)) return;
        ExpeditionSavedData data = ExpeditionService.ensureSites(player.serverLevel());
        if (!event.getPos().equals(data.vault) || !player.level().getBlockState(event.getPos()).is(Blocks.LODESTONE)) return;
        ExpeditionService.activateVault(player);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void levelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
                || !level.dimension().equals(ContentModule.MOON) || level.getGameTime() % 10 != 0) return;
        ExpeditionSavedData data = ExpeditionSavedData.get(level.getServer());
        Entity entity = data.custodian == null ? null : level.getEntity(data.custodian);
        if (data.defeated || !(entity instanceof IronGolem guardian) || !guardian.isAlive()) {
            bossBar.removeAllPlayers();
            return;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            boolean inArena = player.level() == level && player.distanceToSqr(guardian) < 64 * 64;
            if (inArena) bossBar.addPlayer(player); else bossBar.removePlayer(player);
        }
        bossBar.setProgress(Math.max(0, Math.min(1, guardian.getHealth() / guardian.getMaxHealth())));
        LivingEntity target = guardian.getTarget();
        if (target == null || !target.isAlive() || target.distanceToSqr(guardian) > 40 * 40
                || (target instanceof Player targetPlayer && (targetPlayer.isSpectator() || targetPlayer.getAbilities().instabuild))) {
            ServerPlayer nearest = null;
            double distance = 40 * 40;
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator() || player.getAbilities().instabuild || !player.isAlive()) continue;
                double candidate = player.distanceToSqr(guardian);
                if (candidate < distance) { nearest = player; distance = candidate; }
            }
            guardian.setTarget(nearest);
        }
        if (!data.reinforcements && guardian.getHealth() < guardian.getMaxHealth() * 0.55F)
            ExpeditionService.spawnReinforcements(level, data);
    }

    @SubscribeEvent
    public void death(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)
                || !level.dimension().equals(ContentModule.MOON)
                || !event.getEntity().getPersistentData().getBoolean(ExpeditionService.CUSTODIAN_TAG)) return;
        ExpeditionSavedData data = ExpeditionSavedData.get(level.getServer());
        if (!event.getEntity().getUUID().equals(data.custodian)) return;
        data.defeated = true;
        data.setDirty();
        bossBar.removeAllPlayers();
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(Vec3.atCenterOf(data.vault)) < 128 * 128)
                player.sendSystemMessage(Component.literal("The vault is unlocked. Right-click the lodestone terminal to recover your survey core."));
        }
    }

    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) {
        bossBar.removeAllPlayers();
    }
}
