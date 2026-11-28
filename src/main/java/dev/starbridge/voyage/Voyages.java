package dev.starbridge.voyage;

import dev.starbridge.orbit.HelmIssuer;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.CelestialPort;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.transit.HullTransit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** All normal ship, computer and agent itineraries resolve against the original world's live registry. */
public final class Voyages {
    public enum Availability { READY, CURRENT, MISSING_WORLD, UNKNOWN_PORT, ASSEMBLY, TECHNOLOGY, FUEL, SETTLING }
    private static final VsShipBackend SHIPS = new VsShipBackend();
    private Voyages() {}

    static Availability availability(ShipCoreBlockEntity core, CelestialPort from, CelestialPort target, boolean settling) {
        if (target.dimension() == null) return Availability.MISSING_WORLD;
        if (from == null) return Availability.UNKNOWN_PORT;
        if (target.key().equals(from.key())) return Availability.CURRENT;
        if (settling) return Availability.SETTLING;
        if (core.originalRocket() == null) return Availability.ASSEMBLY;
        if (core.navigationTier() < target.tier()) return Availability.TECHNOLOGY;
        if (core.energy() < OrbitModule.requiredEnergy(from,target)) return Availability.FUEL;
        return Availability.READY;
    }
    public static Map<String,Object> chart(ShipCoreBlockEntity core) {
        if (!(core.getLevel() instanceof ServerLevel source)) return Map.of();
        var from = OriginalStarMap.byLevel(source);
        boolean settling = HullTransit.inProgress(source.getServer(), core.id());
        Map<String,Object> routes = new LinkedHashMap<>();
        for (var target : OriginalStarMap.ports(source.getServer()).values()) {
            Availability state = availability(core,from,target,settling);
            routes.put(target.key(),Map.of("provider",target.provider(),"dimension",target.dimension() == null ? "" : target.dimension().location().toString(),
                    "name",target.title(),"tier",target.tier(),"available",state==Availability.READY,"current",state==Availability.CURRENT,
                    "condition",state.name(),"fare",from==null || target.key().equals(from.key()) ? 0 : from.fareTo(target),"returnReserve",target.fare(),
                    "launchEnergy",from==null || target.key().equals(from.key()) ? 0 : OrbitModule.requiredEnergy(from,target)));
        }
        return Map.of("energy",core.energy(),"navigationTier",core.navigationTier(),"routes",routes);
    }
    public static void departRemote(ShipCoreBlockEntity core, String name) {
        departRemote(core, name, HelmIssuer.AGENT);
    }
    public static void departRemote(ShipCoreBlockEntity core, String name, HelmIssuer issuer) {
        issuer.requireAuthorized(core);
        if (!(core.getLevel() instanceof ServerLevel source) || core.owner() == null) throw new IllegalStateException("Load the ship and board its bridge");
        ServerPlayer captain = source.getServer().getPlayerList().getPlayer(core.owner());
        if (captain == null) throw new IllegalStateException("The owner must be aboard");
        if (dev.starbridge.network.PilotModule.isBeingPiloted(core.id()) || OrbitModule.manualClaimed(core)) throw new IllegalStateException("Manual pilot has priority");
        if (OrbitModule.active(core)) {
            if (!name.equals(OrbitModule.status(core).get("target"))) throw new IllegalStateException("Finish this orbit before choosing another target");
            OrbitModule.remoteAutopilot(core, true, issuer);
        } else depart(captain, core, name, true, issuer);
    }
    public static void depart(ServerPlayer captain, ShipCoreBlockEntity core, String name, boolean automatic) {
        depart(captain, core, name, automatic, HelmIssuer.HELM);
    }
    public static void depart(ServerPlayer captain, ShipCoreBlockEntity core, String name, boolean automatic, HelmIssuer issuer) {
        issuer.requireAuthorized(core);
        ServerLevel source = captain.serverLevel();
        if (!captain.isAlive() || captain.isSpectator() || !core.authorized(captain) || core.getLevel()!=source || !core.loadedForControl())
            throw new IllegalStateException("nav.starbridge.flight.board");
        CelestialPort from = OriginalStarMap.byLevel(source);
        CelestialPort target = OriginalStarMap.ports(source.getServer()).get(name);
        if (target==null) throw new IllegalArgumentException("orbit.starbridge.choose");
        var state = availability(core, from, target, HullTransit.inProgress(source.getServer(), core.id()));
        if (state != Availability.READY) throw new IllegalStateException("nav.starbridge.state." + state.name().toLowerCase(java.util.Locale.ROOT));
        var fixed = OrbitModule.cabinBounds(core);
        var physics = SHIPS.probe().available() ? SHIPS.inspect(source,core.getBlockPos()) : java.util.Optional.<VsShipBackend.Snapshot>empty();
        if (physics.isEmpty() && (fixed.isEmpty() || !OrbitModule.aboard(captain,core))) throw new IllegalStateException("nav.starbridge.flight.board");
        if (physics.isPresent() && !HullTransit.isAboard(captain,core)) throw new IllegalStateException("nav.starbridge.flight.board");
        if (from.key().equals("home")) {
            Vec3 berth = physics.map(s -> new Vec3(s.position().x(),s.position().y(),s.position().z())).orElse(Vec3.atCenterOf(core.getBlockPos()).add(0,16,0));
            VoyageLog log = VoyageLog.get(source.getServer());
            if (physics.isPresent() || log.home(core.id()) == null) log.rememberHome(core.id(),berth);
        }
        if (physics.isPresent() && !dev.starbridge.transit.SurfaceLiftChunks.ready(captain, core, physics.get().shipId(), name, automatic, issuer)) return;
        ShipCoreBlockEntity cabin = physics.isPresent() ? dev.starbridge.transit.SurfaceDeparture.lift(captain,core) : core;
        OrbitModule.begin(captain, cabin, from.key(), target.key(), automatic, issuer);
    }

    public record ApproachTarget(ServerLevel level, Vec3 home) {}

    public static ApproachTarget approachTarget(ShipCoreBlockEntity core, String targetKey) {
        var server = ((ServerLevel)core.getLevel()).getServer();
        var target = OriginalStarMap.ports(server).get(targetKey);
        if (target == null || target.dimension() == null || server.getLevel(target.dimension()) == null)
            throw new IllegalStateException("orbit.starbridge.unavailable");
        Vec3 home = targetKey.equals("home") ? VoyageLog.get(server).home(core.id()) : null;
        if (targetKey.equals("home") && home == null) throw new IllegalStateException("orbit.starbridge.site_unavailable");
        return new ApproachTarget(server.getLevel(target.dimension()), home);
    }

    /** The selected berth has already passed the bounded server survey. Recheck the live route before converting blocks. */
    public static void land(ServerPlayer captain, ShipCoreBlockEntity core, String fromKey, String targetKey,
                            ServerLevel destination, Vec3 anchor) {
        ServerLevel source = captain.serverLevel();
        if (core.getLevel()!=source || !core.authorized(captain) || core.originalRocket()==null || !core.loadedForControl())
            throw new IllegalStateException("orbit.starbridge.board");
        var map=OriginalStarMap.ports(source.getServer());
        var from=map.get(fromKey); var target=map.get(targetKey);
        if (from == null || target == null || from.key().equals(target.key())
                || !destination.dimension().equals(target.dimension())) {
            throw new IllegalStateException("orbit.starbridge.landing_route_changed");
        }
        if (core.navigationTier() < target.tier()) {
            throw new IllegalStateException("nav.starbridge.state.technology");
        }
        if (core.energy() < from.requiredEnergy(target)) {
            throw new IllegalStateException("orbit.starbridge.landing_low_energy");
        }
        var fixedCabin = OrbitModule.cabinBounds(core);
        if (fixedCabin.isPresent()) {
            dev.starbridge.transit.CabinApproach.begin(captain,core,fixedCabin.get(),destination,anchor,target.key(),from.fareTo(target));
        } else HullTransit.begin(core,captain,destination,anchor,target.key(),from.fareTo(target));
    }
    public static void arrived(ServerPlayer player,String name,UUID leg) {
        if (Boolean.getBoolean("starbridge.voyageSmoke")) { PrototypeVoyages.arrived(player,name,leg); return; }
        var body=OriginalStarMap.byLevel(player.serverLevel());
        if (body!=null && body.key().equals(name)) player.displayClientMessage(Component.translatable("nav.starbridge.touchdown",body.title()),true);
    }
}
