package dev.starbridge.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Separate VS 2.4.11's departure and arrival subscriptions when its native teleport reuses a ship record. */
@Pseudo
@Mixin(targets = "org.valkyrienskies.core.impl.shadow.CY", remap = false)
abstract class Vs2411WatchHandoffMixin {
    @ModifyVariable(
            method = "a(Ljava/util/Set;Ljava/util/Set;Ljava/lang/Iterable;Ljava/lang/Iterable;)Lorg/valkyrienskies/core/internal/world/chunks/VsiChunkWatchTasks;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1, allow = 1)
    private Iterable<?> starbridge$afterRetiringWatches(
            Iterable<?> candidates, Set<?> players, Set<?> previousPlayers, Iterable<?> activeShips, Iterable<?> retiringShips) {
        var departures = retiringShips.iterator();
        if (!departures.hasNext()) return candidates;
        Set<Object> retiring = Collections.newSetFromMap(new IdentityHashMap<>());
        departures.forEachRemaining(retiring::add);
        // VS also passes a teleported ShipData in the active list. Its cleanup drops all old watches
        // and schedules unloads for all players. New watches for that same instance belong to the next tick.
        var arrivals = new ArrayList<>();
        for (Object ship : candidates) {
            if (!retiring.contains(ship)) arrivals.add(ship);
        }
        return arrivals;
    }
}
