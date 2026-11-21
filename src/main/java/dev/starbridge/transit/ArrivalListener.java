package dev.starbridge.transit;

import net.minecraft.server.level.ServerPlayer;

/** Server-side boundary shared with route planning and optional computer integrations. */
public interface ArrivalListener {
    /** Runs after a crew member and the original physical ship are both verified at the destination. Must be idempotent on replay. */
    void arrived(ServerPlayer player, String route, java.util.UUID voyageId);
}
