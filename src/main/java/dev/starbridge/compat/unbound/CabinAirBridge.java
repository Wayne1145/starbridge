package dev.starbridge.compat.unbound;

import dev.starbridge.Starbridge;
import dev.starbridge.voyage.CabinPressure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Expose each supplied crew member's current breathing point to Unbound's airless-world simulation. */
@Mod.EventBusSubscriber(modid = Starbridge.ID)
public final class CabinAirBridge {
    private record Lease(ServerLevel level, BlockPos key) {}
    private static final Map<UUID, Lease> leases = new HashMap<>();
    private static int nextLease;

    private CabinAirBridge() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.player instanceof ServerPlayer player) || !UnboundCompat.enabled()) return;
        ServerLevel level = player.serverLevel();
        UUID person = player.getUUID();
        Lease prior = leases.get(person);
        if (prior != null && prior.level != level) release(person);
        if (!level.dimension().location().getNamespace().equals("unbound") || !player.isAlive()
                || player.isSpectator() || player.getAbilities().instabuild || !CabinPressure.breathable(player)) {
            release(person);
            return;
        }
        // Upstream indexes a zone by source position but tests coverage against the supplied world-space centre.
        // Reserve a source below build height, keeping real Unbound pumps and other players' leases independent.
        Lease lease = leases.computeIfAbsent(person, unused -> {
            int id = nextLease++;
            return new Lease(level, new BlockPos(id >>> 16, level.getMinBuildHeight() - 1, id & 0xffff));
        });
        if (!UnboundCompat.holdAir(level, lease.key, player.getEyePosition())) release(person);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) release(player.getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        for (Lease lease : leases.values()) UnboundCompat.releaseAir(lease.level, lease.key);
        leases.clear();
        nextLease = 0;
    }

    private static void release(UUID person) {
        Lease lease = leases.remove(person);
        if (lease != null) UnboundCompat.releaseAir(lease.level, lease.key);
    }
}
