package dev.starbridge.voyage;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.OriginalNavigationTech;
import dev.starbridge.compat.galacticraft.OriginalNavigationTech.Sample;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.transit.HullTransit;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;

/** Server-side reading of the originals. The request just identifies what the player saw. */
public final class NavigationCalibration {
    public enum Result {
        LEARNED,
        KNOWN,
        CHANGED,
        RETURN,
        DENIED,
        UNKNOWN
    }

    public record Entry(Sample sample, int slot) {}

    public record Choice(ResourceLocation item, int metadata, int slot) {}

    private NavigationCalibration() {}

    public static List<Entry> entries(ServerPlayer player) {
        var held = new HashMap<ResourceLocation, Entry>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!usableSlot(slot)) continue;
            var found = OriginalNavigationTech.inspect(player.getInventory().getItem(slot));
            if (found.isPresent()) {
                var sample = found.get();
                held.putIfAbsent(sample.item(), new Entry(sample, slot));
            }
        }
        return OriginalNavigationTech.examples().stream()
                .map(sample -> held.getOrDefault(sample.item(), new Entry(sample, -1)))
                .toList();
    }

    private static boolean usableSlot(int slot) {
        return (slot >= 0 && slot < 36) || slot == 40;
    }

    public static Result apply(
            ServerPlayer player, ShipCoreBlockEntity core, String destination, Choice choice) {
        if (!VoyageNetwork.permitted(player, core)) return Result.DENIED;
        var port = OriginalStarMap.ports(player.serverLevel().getServer()).get(destination);
        if (port == null || port.dimension() == null) return Result.UNKNOWN;
        if (core.navigationTier() >= port.tier()) return Result.KNOWN;
        if (player.distanceToSqr(Vec3.atCenterOf(core.getBlockPos())) > 36
                && !OrbitModule.aboard(player, core)
                && !HullTransit.isAboard(player, core)) return Result.RETURN;
        if (!usableSlot(choice.slot())) return Result.CHANGED;
        var stack = player.getInventory().getItem(choice.slot());
        var sample = OriginalNavigationTech.inspect(stack);
        if (sample.isEmpty()
                || !sample.get().item().equals(choice.item())
                || sample.get().metadata() != choice.metadata()
                || sample.get().tier() < port.tier()) return Result.CHANGED;
        return core.learnNavigationTechnology(stack) ? Result.LEARNED : Result.KNOWN;
    }
}
