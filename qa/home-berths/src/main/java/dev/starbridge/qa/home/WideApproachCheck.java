package dev.starbridge.qa.home;

import com.mojang.authlib.GameProfile;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.qa.home.landing.ApproachTiming;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.Voyages;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One simulated non-operator with no client process or socket. Ship and pilot both come from the audited home save. */
final class WideApproachCheck {
    static final Path MARKER = Path.of("starbridge-wide-approach.fixture");
    private static final BlockPos START = new BlockPos(-28665863,128,12289899);
    private static final UUID CAPTAIN = UUID.fromString("26b30ff7-0ad8-3061-adaf-6017614fa42b");
    private static final class NoClient extends Connection {
        NoClient() { super(PacketFlow.SERVERBOUND); }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isMemoryConnection() { return true; }
        @Override public void send(Packet<?> packet) { }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { }
    }
    private ServerPlayer pilot;
    private UUID identity,rocket;
    private int step,departed,preFare,stable, lastMapRequest=-100;
    private boolean positioned;
    private BlockPos fixed;
    private final VsShipBackend vs = new VsShipBackend();

    boolean tick(DedicatedServer server, Map<String,Object> proof) throws Exception {
        var home = server.overworld();
        var moon = server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("retrofit:legacy_dim_-28")));
        if (moon==null || server.getTickCount()>2200) throw new IllegalStateException("Cannot complete landing at step "+step);
        if (step==0) {
            var link = new NoClient();
            new EmbeddedChannel(link);
            pilot = new ServerPlayer(server,home,new GameProfile(CAPTAIN,"FleetCaptain"));
            server.getPlayerList().placeNewPlayer(link,pilot);
            if (server.getPlayerList().isOp(pilot.getGameProfile()) || pilot.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL) throw new IllegalStateException("Expected survival non-operator");
            var core=(ShipCoreBlockEntity)home.getBlockEntity(START);
            if (core==null || !CAPTAIN.equals(core.owner()) || !HullTransit.isAboard(pilot,core)) throw new IllegalStateException("The original captain must be aboard their returned ship");
            identity=core.id(); rocket=core.originalRocket(); proof.put("core",identity); proof.put("rocket",rocket);
            proof.put("pilot",CAPTAIN); proof.put("simulatedPilots",1); proof.put("clientProcesses",0);
            core.register();
            var bounds=vs.storageBoundsById(home,182).orElseThrow();
            for (int x=bounds.minX()>>4;x<=bounds.maxX()>>4;x++) for(int z=bounds.minZ()>>4;z<=bounds.maxZ()>>4;z++) home.getChunk(x,z);
            Voyages.depart(pilot,core,"moon",false);
            step++; return false;
        }
        var core=CoreRegistry.find(identity).orElse(null);
        if (server.getTickCount()%40==0) {
            var diag=new java.util.LinkedHashMap<String,Object>(); diag.put("tick",server.getTickCount());diag.put("step",step);
            diag.put("pilot",pilot.position().toString());diag.put("pilotWorld",pilot.serverLevel().dimension().location().toString());
            diag.put("pilotAlive",pilot.isAlive());diag.put("stillInPlayerList",server.getPlayerList().getPlayer(CAPTAIN)==pilot);
            if(core!=null){diag.put("core",vec(core.getBlockPos()));diag.put("coreWorld",core.getLevel().dimension().location().toString());
                diag.put("fixed",OrbitModule.cabinBounds(core).isPresent());diag.put("orbit",OrbitModule.active(core));diag.put("transit",HullTransit.inProgress(server,identity));}
            var jobMap = Class.forName("dev.starbridge.orbit.LandingControl").getDeclaredField("jobs");
            jobMap.setAccessible(true);
            var survey = ((Map<?,?>)jobMap.get(null)).get(identity);
            if (survey != null) {
                for (String field : List.of("result", "confirm", "cursor", "held", "touched")) {
                    var value = survey.getClass().getDeclaredField(field); value.setAccessible(true);
                    diag.put(field, value.get(survey));
                }
            }
            java.nio.file.Files.writeString(Path.of("wide-approach-state.json"),new com.google.gson.Gson().toJson(diag));
        }
        if (step==1) {
            if (core==null || OrbitModule.cabinBounds(core).isEmpty() || !OrbitModule.active(core)) return false;
            fixed=core.getBlockPos();proof.put("fixedCore",vec(fixed));
            if (!positioned) {
                var ledgerClass=Class.forName("dev.starbridge.orbit.OrbitLedger");
                var get=ledgerClass.getDeclaredMethod("get",net.minecraft.server.MinecraftServer.class); get.setAccessible(true);
                var legs=ledgerClass.getDeclaredField("legs");legs.setAccessible(true);
                var leg=((Map<?,?>)legs.get(get.invoke(null,server))).get(identity);
                var pose=leg.getClass().getDeclaredField("pose");pose.setAccessible(true);
                pose.set(leg,new dev.starbridge.orbit.OrbitalFlight.Pose(dev.starbridge.orbit.OrbitalFlight.body("moon").add(new dev.starbridge.flight.Vec3d(0,0,100)),dev.starbridge.flight.Vec3d.ZERO,0,0));
                positioned=true; OrbitModule.open(pilot,core);
            }
            var jobClass=Class.forName("dev.starbridge.orbit.LandingControl");
            var jobs=jobClass.getDeclaredField("jobs");jobs.setAccessible(true);
            var job=((Map<?,?>)jobs.get(null)).get(identity);
            if (job==null) {
                if (server.getTickCount()-lastMapRequest>=12) {
                    sendControl(dev.starbridge.orbit.OrbitWire.Action.SURVEY); lastMapRequest=server.getTickCount();
                }
                return false;
            }
            var clear=job.getClass().getDeclaredField("lastClear");clear.setAccessible(true);
            if (!clear.getBoolean(job)) return false;
            var chest = (ChestBlockEntity) home.getBlockEntity(fixed.offset(0,-3,-1));
            if (chest==null || !chest.getItem(0).is(Items.DIAMOND) || chest.getItem(0).getCount()!=17 || !chest.getItem(26).isEmpty()) throw new IllegalStateException("Missing original diamond chest");
            chest.setItem(26,chest.removeItemNoUpdate(0)); chest.setChanged();
            preFare=core.energy(); proof.put("preFare",preFare); proof.put("fixedCargo",vec(chest.getBlockPos()));
            sendControl(dev.starbridge.orbit.OrbitWire.Action.LAND);
            var confirmFlag=job.getClass().getDeclaredField("confirm");confirmFlag.setAccessible(true);
            if (!confirmFlag.getBoolean(job)) throw new IllegalStateException("Landing confirmation was rejected");
            proof.put("serverActions",List.of("SURVEY","CLEAR","LAND"));
            departed=server.getTickCount(); step++; return false;
        }
        if (server.getTickCount()%40==0) ApproachTiming.checkpoint();
        if (core==null || core.getLevel()!=moon || pilot.serverLevel()!=moon || HullTransit.inProgress(server,identity)) return false;
        var chest = (ChestBlockEntity) moon.getBlockEntity(core.getBlockPos().offset(0,-3,-1));
        if (!rocket.equals(core.originalRocket()) || chest==null || !chest.getItem(0).isEmpty() || !chest.getItem(26).is(Items.DIAMOND)
                || chest.getItem(26).getCount()!=17 || !core.saveWithoutMetadata().hasUUID("PaidVoyage") || preFare-core.energy()!=12000
                || !HullTransit.isAboard(pilot,core)) throw new IllegalStateException("Pilot, fare or last-minute cargo was not delivered");
        if (++stable < 10) return false;
        var nativeShip=vs.inspect(moon,core.getBlockPos()).orElseThrow();
        var box=vs.storageBoundsById(moon,nativeShip.shipId()).orElseThrow();
        proof.put("ship",nativeShip.shipId()); proof.put("destinationCore",vec(core.getBlockPos())); proof.put("arrivalCargo",vec(chest.getBlockPos()));
        proof.put("nativeBounds",List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()));
        proof.put("preFare",preFare); proof.put("postFare",core.energy()); proof.put("receipt",core.saveWithoutMetadata().getUUID("PaidVoyage"));
        proof.put("deliveryTicks",server.getTickCount()-departed); proof.put("paidOnce",true);proof.put("pilotAboard",true);
        ApproachTiming.stop(); return true;
    }
    private void sendControl(dev.starbridge.orbit.OrbitWire.Action action) throws Exception {
        var control=OrbitModule.class.getDeclaredMethod("control",ServerPlayer.class,dev.starbridge.orbit.OrbitWire.Control.class);
        control.setAccessible(true);
        control.invoke(null,pilot,new dev.starbridge.orbit.OrbitWire.Control(identity,action,0,0,0,0,0));
    }
    private static List<Integer> vec(BlockPos pos) {return List.of(pos.getX(),pos.getY(),pos.getZ());}
}
