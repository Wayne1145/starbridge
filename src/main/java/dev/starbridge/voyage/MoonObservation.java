package dev.starbridge.voyage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;

/** The moon orbits along vanilla's rendered celestial track, above an unobstructed horizon. */
final class MoonObservation {
    private static final String FOCUS = "starbridge:telescope_focus";
    private MoonObservation() {}

    static void observe(LivingEntityUseItemEvent.Tick event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getItem().is(Items.SPYGLASS)
                || !player.serverLevel().dimension().equals(Level.OVERWORLD) || player.tickCount % 5 != 0
                || dev.starbridge.compat.unbound.UnboundCompat.enabled()) return;
        ServerLevel level = player.serverLevel();
        VoyageLog log = VoyageLog.get(level.getServer());
        if (log.charted(player.getUUID())) return;
        // Vanilla spyglass use lasts 1200 ticks; a new use always starts with an empty observation.
        int used = event.getItem().getUseDuration() - event.getDuration();
        if (used < 5) player.getPersistentData().remove(FOCUS);
        Vec3 look = player.getLookAngle(), eyes = player.getEyePosition();
        double angle = level.getTimeOfDay(1.0F) * 2.0 * Math.PI;
        boolean visible = !level.isRaining() && aligns(angle, look.x, look.y)
                && level.clip(new ClipContext(eyes, eyes.add(look.scale(192)), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
        int focus = visible ? player.getPersistentData().getInt(FOCUS) + 5 : 0;
        player.getPersistentData().putInt(FOCUS, focus);
        if (focus >= 30) {
            log.chartMoon(player.getUUID());
            player.getPersistentData().remove(FOCUS);
            player.sendSystemMessage(Component.translatable("message.starbridge.moon_charted"));
        }
    }
    static boolean aligns(double angle, double lookX, double lookY) {
        double moonY = -Math.cos(angle);
        return moonY > 0.08 && Math.sin(angle) * lookX + moonY * lookY >= Math.cos(Math.toRadians(12));
    }
}
