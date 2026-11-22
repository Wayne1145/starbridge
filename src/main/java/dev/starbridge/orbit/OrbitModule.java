package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.CelestialPort;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.rocketry.OriginalShipyard;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.voyage.Voyages;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The orbital pose belongs to the server. Crew and cargo remain in a fixed, ticking block cabin.
 */
public final class OrbitModule {
    private static final Map<UUID, Console> consoles = new HashMap<>();
    private static final Map<UUID, HullSurvey> surveys = new HashMap<>();
    private static final Map<UUID, OrbitWire.Hull> hulls = new HashMap<>();

    private static final class Console {
        final UUID player;
        final Set<UUID> sentHulls = new HashSet<>();
        boolean manual;
        long lastInput = -100, lastButton = -100, lastSurvey = -100;
        dev.starbridge.flight.Vec3d goAround;
        OrbitalFlight.Controls input = OrbitalFlight.Controls.NEUTRAL;
        String notice = "";

        Console(UUID player, boolean manual) {
            this.player = player;
            this.manual = manual;
        }
    }

    private OrbitModule() {}

    public static void register(IEventBus bus) {
        OrbitWire.register();
        MinecraftForge.EVENT_BUS.addListener(OrbitModule::tick);
        MinecraftForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedOutEvent e) ->
                        consoles.values().removeIf(c -> c.player.equals(e.getEntity().getUUID())));
        MinecraftForge.EVENT_BUS.addListener(
                (ServerStoppedEvent e) -> {
                    LandingControl.clear();
                    consoles.clear();
                    surveys.clear();
                    hulls.clear();
                    CabinChunks.clear();
                });
    }

    public static void bindCabin(ShipCoreBlockEntity core, BlockPos min, BlockPos max) {
        var level = level(core);
        var bounds =
                new StorageBounds(
                        min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        HullSurvey.validate(bounds);
        if (!inside(bounds, core.getBlockPos()))
            throw new IllegalArgumentException("Core must be inside its cabin");
        var ledger = OrbitLedger.get(level.getServer());
        ledger.cabins.put(
                core.id(),
                new OrbitLedger.Cabin(
                        level.dimension().location().toString(),
                        core.getBlockPos(),
                        min.immutable(),
                        max.immutable()));
        ledger.setDirty();
    }

    private static boolean inside(StorageBounds b, BlockPos p) {
        return p.getX() >= b.minX()
                && p.getX() <= b.maxX()
                && p.getY() >= b.minY()
                && p.getY() <= b.maxY()
                && p.getZ() >= b.minZ()
                && p.getZ() <= b.maxZ();
    }

    public static Optional<StorageBounds> cabinBounds(ShipCoreBlockEntity core) {
        var original = OriginalShipyard.cabinBounds(core);
        if (original.isPresent()) return original;
        var cabin = OrbitLedger.get(level(core).getServer()).cabins.get(core.id());
        return cabin != null && cabin.matches(core)
                ? Optional.of(cabin.bounds())
                : Optional.empty();
    }

    private static ServerLevel level(ShipCoreBlockEntity core) {
        if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("A live server cabin is required");
        return level;
    }

    public static boolean aboard(ServerPlayer player, ShipCoreBlockEntity core) {
        if (core.getLevel() != player.level()
                || !core.loadedForControl()
                || player.isSpectator()
                || !player.isAlive()) return false;
        var bounds = cabinBounds(core);
        if (bounds.isEmpty()) return false;
        // Include the two blocks of headroom above a deck, e.g. jumping and walking down a stair
        // between decks.
        var b = bounds.get();
        var feet = player.blockPosition();
        if (inside(b, feet)) return true;
        for (int i = 1; i <= 2; i++) {
            var floor = feet.below(i);
            if (inside(b, floor)
                    && !player.level()
                            .getBlockState(floor)
                            .getCollisionShape(player.level(), floor)
                            .isEmpty()) return true;
        }
        return false;
    }

    private static void requirePilot(ServerPlayer player, ShipCoreBlockEntity core) {
        if (!core.authorized(player) || !aboard(player, core))
            throw new IllegalStateException("orbit.starbridge.board");
    }

    private static OrbitLedger.Leg leg(ShipCoreBlockEntity core) {
        return OrbitLedger.get(level(core).getServer()).legs.get(core.id());
    }

    public static boolean active(ShipCoreBlockEntity core) {
        return leg(core) != null;
    }

    public static boolean manualClaimed(ShipCoreBlockEntity core) {
        var console = consoles.get(core.id());
        return console != null && console.manual;
    }

    public static boolean manualPilot(ShipCoreBlockEntity core) {
        return manualClaimed(core);
    }

    public static Map<String, Object> status(ShipCoreBlockEntity core) {
        var flight = leg(core);
        if (flight == null) return Map.of("active", false);
        var position = flight.pose.position();
        var target = OrbitalFlight.body(flight.to);
        return Map.ofEntries(
                Map.entry("active", true),
                Map.entry("source", flight.from),
                Map.entry("target", flight.to),
                Map.entry("cruise", flight.cruise),
                Map.entry("x", position.x()),
                Map.entry("y", position.y()),
                Map.entry("z", position.z()),
                Map.entry("speed", flight.pose.velocity().length()),
                Map.entry("distance", position.subtract(target).length()),
                Map.entry("approach", OrbitalFlight.canEnter(flight.pose, target)),
                Map.entry("protectedFE", flight.reserve),
                Map.entry("manual", manualClaimed(core)),
                Map.entry("paused", flight.suspended));
    }

    private static int protectedEnergy(CelestialPort from, CelestialPort target) {
        return Math.addExact(
                from.requiredEnergy(target),
                target.key().equals("home") ? 0 : OrbitalFlight.CRUISE_ALLOWANCE);
    }

    public static int requiredEnergy(CelestialPort from, CelestialPort target) {
        return Math.addExact(protectedEnergy(from, target), OrbitalFlight.CRUISE_ALLOWANCE);
    }

    public static void start(
            ServerPlayer player,
            ShipCoreBlockEntity core,
            String from,
            String target,
            boolean automatic) {
        begin(player, core, from, target, automatic);
    }

    public static void begin(
            ServerPlayer player,
            ShipCoreBlockEntity core,
            String from,
            String target,
            boolean automatic) {
        requirePilot(player, core);
        var server = level(core).getServer();
        var ledger = OrbitLedger.get(server);
        if (ledger.legs.containsKey(core.id()))
            throw new IllegalStateException("orbit.starbridge.already");
        var chart = OriginalStarMap.ports(server);
        var origin = chart.get(from);
        var destination = chart.get(target);
        if (origin == null
                || destination == null
                || destination.dimension() == null
                || from.equals(target))
            throw new IllegalArgumentException("orbit.starbridge.choose");
        if (core.energy() < requiredEnergy(origin, destination))
            throw new IllegalStateException("orbit.starbridge.launch_fuel");
        var survey =
                new HullSurvey(
                        cabinBounds(core)
                                .orElseThrow(
                                        () -> new IllegalStateException("orbit.starbridge.board")));
        var flight =
                new OrbitLedger.Leg(
                        core.id(),
                        player.getUUID(),
                        from,
                        target,
                        OrbitalFlight.launch(from, target));
        flight.reserve = protectedEnergy(origin, destination);
        flight.cruise = automatic;
        ledger.legs.put(core.id(), flight);
        ledger.setDirty();
        surveys.put(core.id(), survey);
        core.register();
        open(player, core);
    }

    private static OrbitLedger.Leg supervised(ServerPlayer player, ShipCoreBlockEntity core) {
        requirePilot(player, core);
        var flight = leg(core);
        if (flight == null || !flight.captain.equals(player.getUUID()))
            throw new IllegalStateException("orbit.starbridge.no_pilot");
        if (flight.reserve < 0) {
            var server = level(core).getServer();
            var chart = OriginalStarMap.ports(server);
            var from = chart.get(flight.from);
            var target = chart.get(flight.to);
            if (from == null || target == null)
                throw new IllegalStateException("orbit.starbridge.choose");
            flight.reserve = protectedEnergy(from, target);
            OrbitLedger.get(server).setDirty();
        }
        return flight;
    }

    public static void open(ServerPlayer player, ShipCoreBlockEntity core) {
        var flight = supervised(player, core);
        flight.suspended = false;
        cabinBounds(core).ifPresent(box -> CabinChunks.occupy(core, level(core), box));
        var console = new Console(player.getUUID(), !flight.cruise);
        consoles.put(core.id(), console);
        if (!surveys.containsKey(core.id()) && !hulls.containsKey(core.id()))
            cabinBounds(core).ifPresent(b -> surveys.put(core.id(), new HullSurvey(b)));
        OrbitWire.send(
                player,
                frame(flight, core, OrbitLedger.get(level(core).getServer()), true, false, ""));
    }

    public static void autopilot(ServerPlayer player, ShipCoreBlockEntity core, boolean enabled) {
        var flight = supervised(player, core);
        mode(flight, enabled, false);
        var console = consoles.get(core.id());
        if (console != null) console.manual = !enabled;
    }

    public static void brake(ServerPlayer player, ShipCoreBlockEntity core) {
        mode(supervised(player, core), false, true);
        var console = consoles.get(core.id());
        if (console != null) console.manual = true;
    }

    private static void mode(OrbitLedger.Leg flight, boolean cruise, boolean brake) {
        flight.cruise = cruise;
        flight.braking = brake;
        flight.suspended = false;
        var console = consoles.get(flight.id);
        if (console != null) {
            console.input = OrbitalFlight.Controls.NEUTRAL;
            console.goAround = null;
            console.notice = "";
        }
    }

    private static OrbitLedger.Leg remote(ShipCoreBlockEntity core) {
        if (manualClaimed(core)) throw new IllegalStateException("orbit.starbridge.human");
        var flight = leg(core);
        if (flight == null) throw new IllegalStateException("orbit.starbridge.no_pilot");
        var captain = level(core).getServer().getPlayerList().getPlayer(flight.captain);
        if (captain == null) throw new IllegalStateException("orbit.starbridge.board");
        var result = supervised(captain, core);
        cabinBounds(core).ifPresent(box -> CabinChunks.occupy(core, level(core), box));
        return result;
    }

    public static void remoteAutopilot(ShipCoreBlockEntity core, boolean cruise) {
        mode(remote(core), cruise, !cruise);
    }

    public static void remoteBrake(ShipCoreBlockEntity core) {
        mode(remote(core), false, true);
    }

    static void control(ServerPlayer player, OrbitWire.Control data) {
        var console = consoles.get(data.core());
        if (console == null || !console.player.equals(player.getUUID())) return;
        if (data.action() == OrbitWire.Action.LEAVE) {
            LandingControl.dismiss(data.core());
            consoles.remove(data.core());
            return;
        }
        var core = CoreRegistry.find(data.core()).orElse(null);
        if (core == null) return;
        var server = level(core).getServer();
        var ledger = OrbitLedger.get(server);
        var flight = ledger.legs.get(core.id());
        if (flight == null
                || !flight.captain.equals(player.getUUID())
                || !core.authorized(player)
                || !aboard(player, core)) return;
        long now = server.getTickCount();
        try {
            if (data.action() == OrbitWire.Action.INPUT) {
                if (console.lastInput == now) return;
                var input =
                        new OrbitalFlight.Controls(data.thrust(), data.turn(), data.pitch(), false);
                console.input = input;
                console.lastInput = now;
                if (!input.equals(OrbitalFlight.Controls.NEUTRAL)) {
                    console.manual = true;
                    console.goAround = null;
                    console.notice = "";
                    flight.cruise = flight.braking = false;
                }
                return;
            }
            if (data.action() == OrbitWire.Action.DISMISS_SURVEY) {
                LandingControl.dismiss(core.id());
                return;
            }
            if (data.action() == OrbitWire.Action.SURVEY) {
                if (now - console.lastSurvey < 10
                        || !LandingMap.contains(data.approachX(), data.approachZ())) return;
                console.lastSurvey = now;
                LandingControl.select(player, core, flight.to, data.approachX(), data.approachZ());
                return;
            }
            if (console.lastButton == now) return;
            console.lastButton = now;
            switch (data.action()) {
                case CRUISE, MANUAL -> autopilot(
                        player, core, data.action() == OrbitWire.Action.CRUISE);
                case BRAKE -> brake(player, core);
                case LAND -> {
                    if (!OrbitalFlight.canEnter(flight.pose, OrbitalFlight.body(flight.to))) {
                        console.notice = "orbit.starbridge.approach_required";
                        return;
                    }
                    if (!LandingMap.contains(data.approachX(), data.approachZ())) {
                        console.notice = "orbit.starbridge.site";
                        return;
                    }
                    LandingControl.confirm(player, core.id(), data.approachX(), data.approachZ());
                }
                case GO_AROUND -> {
                    LandingControl.dismiss(core.id());
                    mode(flight, false, false);
                    console.manual = true;
                    var body = OrbitalFlight.body(flight.to);
                    var away = flight.pose.position().subtract(body);
                    if (away.length() < 1) away = new dev.starbridge.flight.Vec3d(0, 0, 1);
                    console.goAround =
                            body.add(away.normalized().multiply(OrbitalFlight.ENTRY + 380));
                    console.notice = "orbit.starbridge.going_around";
                }
                default -> {}
            }
        } catch (RuntimeException failure) {
            console.notice = Objects.toString(failure.getMessage(), "orbit.starbridge.unavailable");
        }
    }

    static ServerPlayer seatedPilot(MinecraftServer server, UUID id) {
        var console = consoles.get(id);
        return console == null ? null : server.getPlayerList().getPlayer(console.player);
    }

    static boolean finishLanding(MinecraftServer server, ServerPlayer pilot, LandingSurvey site) {
        var core = CoreRegistry.find(site.id).orElse(null);
        var ledger = OrbitLedger.get(server);
        var flight = ledger.legs.get(site.id);
        if (core == null
                || flight == null
                || !flight.captain.equals(pilot.getUUID())
                || !core.authorized(pilot)
                || !aboard(pilot, core)
                || !cabinBounds(core).filter(site.hull::equals).isPresent()
                || !OrbitalFlight.canEnter(flight.pose, OrbitalFlight.body(flight.to)))
            return false;
        var close = frame(flight, core, ledger, false, true, "");
        Voyages.land(pilot, core, flight.from, flight.to, site.level, site.anchor());
        complete(server, site.id);
        OrbitWire.send(pilot, close);
        return true;
    }

    public static void complete(MinecraftServer server, UUID id) {
        var ledger = OrbitLedger.get(server);
        ledger.legs.remove(id);
        ledger.cabins.remove(id);
        ledger.setDirty();
        LandingControl.dismiss(id);
        consoles.remove(id);
        hulls.remove(id);
        surveys.remove(id);
        CabinChunks.release(id);
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        CabinChunks.tick(server.getTickCount());
        LandingControl.tick(server);
        var ledger = OrbitLedger.get(server);
        for (var flight : List.copyOf(ledger.legs.values())) {
            var core = CoreRegistry.find(flight.id).orElse(null);
            var pilot = server.getPlayerList().getPlayer(flight.captain);
            var console = consoles.get(flight.id);
            if (core == null || pilot == null || !core.authorized(pilot) || !aboard(pilot, core)) {
                if (pilot != null && core != null && console != null)
                    OrbitWire.send(
                            pilot,
                            frame(
                                    flight,
                                    core,
                                    ledger,
                                    false,
                                    true,
                                    "orbit.starbridge.leave_cabin"));
                consoles.remove(flight.id);
                flight.suspended = true;
                flight.cruise = flight.braking = false;
                continue;
            }
            if (flight.suspended) continue;
            var survey = surveys.get(flight.id);
            if (survey != null && survey.progress(level(core))) {
                hulls.put(flight.id, survey.result(flight.id));
                surveys.remove(flight.id);
            }
            var input =
                    console != null && server.getTickCount() - console.lastInput <= 7
                            ? console.input
                            : OrbitalFlight.Controls.NEUTRAL;
            if (flight.braking) input = new OrbitalFlight.Controls(0, 0, 0, true);
            var target = OrbitalFlight.body(flight.to);
            if (console != null
                    && console.goAround != null
                    && target.subtract(flight.pose.position()).length()
                            >= OrbitalFlight.ENTRY + 60) {
                console.goAround = null;
                flight.braking = true;
                console.notice = "orbit.starbridge.holding";
            }
            boolean escaping = console != null && console.goAround != null;
            var step =
                    OrbitalFlight.advance(
                            flight.pose,
                            input,
                            escaping ? console.goAround : target,
                            escaping || flight.cruise,
                            true);
            if (step.energy() > 0) {
                var energy = core.getCapability(ForgeCapabilities.ENERGY).resolve().orElse(null);
                if (energy == null
                        || core.energy() - step.energy() < flight.reserve
                        || energy.extractEnergy(step.energy(), true) != step.energy()) {
                    step = OrbitalFlight.advance(flight.pose, input, target, false, false);
                    flight.cruise = false;
                    if (console != null) console.notice = "orbit.starbridge.no_fuel";
                } else energy.extractEnergy(step.energy(), false);
            }
            if (!flight.pose.equals(step.pose())) {
                flight.pose = step.pose();
                ledger.setDirty();
            }
            if (console != null && server.getTickCount() % 3 == 0) {
                var frame = frame(flight, core, ledger, false, false, console.notice);
                OrbitWire.send(pilot, frame);
                sendHull(pilot, console, flight.id);
                for (var contact : frame.contacts()) sendHull(pilot, console, contact.core());
            }
        }
    }

    private static void sendHull(ServerPlayer player, Console console, UUID id) {
        var hull = hulls.get(id);
        if (hull != null && console.sentHulls.add(id)) OrbitWire.send(player, hull);
    }

    private static OrbitWire.Frame frame(
            OrbitLedger.Leg flight,
            ShipCoreBlockEntity core,
            OrbitLedger ledger,
            boolean open,
            boolean closed,
            String notice) {
        var players = level(core).getServer().getPlayerList();
        var contacts =
                ledger.legs.values().stream()
                        .filter(other -> !other.id.equals(flight.id))
                        .filter(
                                other ->
                                        other.pose
                                                        .position()
                                                        .subtract(flight.pose.position())
                                                        .length()
                                                < OrbitalFlight.CONTACT_RANGE)
                        .sorted(
                                Comparator.comparingDouble(
                                        other ->
                                                other.pose
                                                        .position()
                                                        .subtract(flight.pose.position())
                                                        .length()))
                        .limit(24)
                        .map(other -> {
                            var captain = players.getPlayer(other.captain);
                            return new OrbitWire.Contact(other.id, other.pose.position(),
                                    other.suspended ? dev.starbridge.flight.Vec3d.ZERO : other.pose.velocity(),
                                    other.pose.yaw(), other.pose.pitch(),
                                    captain == null ? "" : captain.getGameProfile().getName());
                        })
                        .toList();
        return new OrbitWire.Frame(
                flight.id,
                open,
                closed,
                flight.to,
                flight.pose.position(),
                flight.pose.velocity(),
                flight.pose.yaw(),
                flight.pose.pitch(),
                OrbitalFlight.body(flight.to),
                core.energy(),
                flight.reserve,
                flight.cruise,
                contacts,
                notice.length() > 256 ? notice.substring(0, 256) : notice);
    }
}
