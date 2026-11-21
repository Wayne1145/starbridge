package dev.starbridge.voyage;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.campaign.CampaignModule;
import dev.starbridge.compat.unbound.UnboundCompat;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.content.ContentModule;
import dev.starbridge.content.ExpeditionService;
import dev.starbridge.transit.HullTransit;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class Voyages {
    private static final VsShipBackend SHIPS = new VsShipBackend();
    private Voyages() {}
    public static ResourceKey<Level> dimension(Route route) {
        return switch (route) {
            case HOME -> Level.OVERWORLD;
            case MOON -> UnboundCompat.enabled() ? UnboundCompat.destination("moon") : ContentModule.MOON;
            case MARS -> UnboundCompat.enabled() ? UnboundCompat.destination("mars") : CampaignModule.MARS;
            case BELT -> CampaignModule.BELT;
            case RELAY -> CampaignModule.RELAY;
        };
    }
    static Route routeForLevel(ServerLevel level) {
        for (Route route : Route.values()) if (dimension(route).equals(level.dimension())) return route;
        return null;
    }
    public static boolean unlocked(ServerPlayer player, Route target) {
        if (target == Route.HOME) return true;
        if (!CampaignModule.unlocked(player, target.key())) return false;
        if (UnboundCompat.enabled() && (target == Route.MOON || target == Route.MARS)) return UnboundCompat.discovered(player, target.key());
        return target != Route.MOON || VoyageLog.get(player.serverLevel().getServer()).charted(player.getUUID());
    }
    public static Map<String, Object> chart(ShipCoreBlockEntity core) {
        if (!(core.getLevel() instanceof ServerLevel level)) return Map.of();
        ServerPlayer owner = core.owner() == null ? null : level.getServer().getPlayerList().getPlayer(core.owner());
        Route source = routeForLevel(level);
        Map<String,Object> rows = new LinkedHashMap<>();
        for (Route target : Route.values()) {
            boolean known = target == Route.HOME || owner != null && unlocked(owner, target);
            rows.put(target.key(), Map.of("available", known, "current", target == source, "fare", source == null || target == source ? 0 : source.fareTo(target), "returnReserve", target.returnReserve()));
        }
        return Map.of("energy", core.energy(), "routes", rows);
    }
    public static void departRemote(ShipCoreBlockEntity core, Route target) {
        if (!(core.getLevel() instanceof ServerLevel level) || core.owner() == null) throw new IllegalStateException("Loaded owned ship required");
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(core.owner());
        if (owner == null) throw new IllegalStateException("The ship owner must be aboard to supervise an expedition");
        depart(owner, core, target);
    }
    public static void depart(ServerPlayer captain, ShipCoreBlockEntity core, Route target) {
        ServerLevel source = captain.serverLevel();
        if (!captain.isAlive() || captain.isSpectator() || !core.authorized(captain) || core.getLevel() != source || !core.loadedForControl())
            throw new IllegalStateException("Select your active flight core and board the ship");
        Route from = routeForLevel(source);
        if (from == null) throw new IllegalStateException("This world has no expedition route");
        if (!unlocked(captain, target)) throw new IllegalStateException("Destination undiscovered. Consult the star journal or calibrate a chart");
        from.checkFuel(target, core.energy());
        ServerLevel destination = source.getServer().getLevel(dimension(target));
        if (destination == null) throw new IllegalStateException("The destination world is unavailable");
        if (!SHIPS.probe().available()) throw new IllegalStateException("Install Valkyrien Skies to sail a block-built ship");
        VsShipBackend.Snapshot ship = SHIPS.inspect(source, core.getBlockPos()).orElseThrow(() -> new IllegalStateException("Assemble a ship and wait for it to load"));
        if (!HullTransit.isAboard(captain, core)) throw new IllegalStateException("Board the ship to start an expedition");
        Vec3 origin = new Vec3(ship.position().x(), ship.position().y(), ship.position().z());
        VoyageLog log = VoyageLog.get(source.getServer());
        Vec3 anchor = target == Route.HOME ? log.home(core.id()) : approach(destination, target);
        if (anchor == null) throw new IllegalStateException("Home berth unknown. Depart from your home port on the first expedition");
        if (from == Route.HOME) {
            log.rememberHome(core.id(), origin);
            source.getServer().overworld().getDataStorage().save();
        }
        HullTransit.begin(core, captain, destination, anchor, target.key(), from.fareTo(target));
        captain.sendSystemMessage(Component.translatable("message.starbridge.voyage_begun", Component.translatable("route.starbridge." + target.key())));
    }
    private static Vec3 approach(ServerLevel level, Route target) {
        int x = 0, z = 0;
        level.getChunk(x >> 4, z >> 4);
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int altitude = (target == Route.BELT || target == Route.RELAY) ? 136 : terrain + 16;
        return new Vec3(x + 0.5, Math.max(level.getMinBuildHeight() + 8, Math.min(level.getMaxBuildHeight() - 16, altitude)), z + 0.5);
    }
    public static void arrived(ServerPlayer player, String name, UUID voyageId) {
        Route route = Route.named(name);
        if (!dimension(route).equals(player.serverLevel().dimension())) return;
        if (route == Route.MOON && !UnboundCompat.enabled()) ExpeditionService.ensureSites(player.serverLevel());
        UnboundCompat.arrived(player, name);
        CampaignModule.arrived(player, name);
        player.sendSystemMessage(Component.translatable("message.starbridge.voyage_arrived", Component.translatable("route.starbridge." + name)));
    }
}
