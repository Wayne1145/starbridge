package dev.starbridge.voyage;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.CelestialPort;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.network.PilotModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class VoyageNetwork {
    private static final String PROTOCOL = "5";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Starbridge.ID,"navigation"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    public record Row(String id, String title, String provider, int tier, int fare, int reserve, int launchEnergy, Voyages.Availability state) {}
    public record Chart(UUID core,int energy,int tier,boolean agent,int computer,List<Row> ports,Component notice) {}
    public enum Action { MANUAL, CRUISE, SURFACE, AGENT, PAIR, REFRESH }
    private record SlateGuide() {}
    private record Input(UUID core,Action action,String target,int value) {}
    private VoyageNetwork() {}

    static void register() {
        CHANNEL.messageBuilder(Chart.class,0,NetworkDirection.PLAY_TO_CLIENT).encoder(VoyageNetwork::encode).decoder(VoyageNetwork::decode)
                .consumerMainThread((packet,ctx) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,() -> () -> dev.starbridge.client.ChartScreen.open(packet));
                    ctx.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(Input.class,1,NetworkDirection.PLAY_TO_SERVER)
                .encoder((v,b) -> { b.writeUUID(v.core); b.writeEnum(v.action); b.writeUtf(v.target,96); b.writeVarInt(v.value); })
                .decoder(b -> new Input(b.readUUID(),b.readEnum(Action.class),b.readUtf(96),b.readVarInt()))
                .consumerMainThread((v,ctx) -> {
                    ServerPlayer p=ctx.get().getSender();
                    if(p!=null) CoreRegistry.find(v.core).filter(core -> permitted(p,core)).ifPresent(core -> act(p,core,v));
                    ctx.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(SlateGuide.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((guide, buffer) -> {}).decoder(buffer -> new SlateGuide())
                .consumerMainThread((guide, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.starbridge.client.FirstFlightScreen.open());
                    context.get().setPacketHandled(true);
                }).add();
    }

    static void openSlateGuide(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SlateGuide());
    }

    private static void act(ServerPlayer player,ShipCoreBlockEntity core,Input v) {
        try {
            switch (v.action) {
                case MANUAL, CRUISE -> Voyages.depart(player,core,v.target,v.action==Action.CRUISE);
                case SURFACE -> {
                    if (HullTransit.inProgress(player.getServer(), core.id())) throw new IllegalStateException("nav.starbridge.state.settling");
                    if (!HullTransit.isAboard(player,core)) throw new IllegalStateException("Board the ship to fly it");
                    if (Boolean.TRUE.equals(core.status().get("static"))) core.launch();
                    PilotModule.open(player,core);
                }
                case REFRESH -> open(player,core,Component.empty());
                case AGENT -> { core.setAgentAllowed(v.value==1); open(player,core); }
                case PAIR -> { core.pairComputer(v.value); open(player,core); }
            }
        } catch (IllegalStateException | IllegalArgumentException rejected) {
            String reason = rejected.getMessage();
            if (reason == null) reason = "";
            Component notice = (reason.startsWith("nav.starbridge.") || reason.startsWith("surface.starbridge.")) ? Component.translatable(reason) : Component.literal(reason);
            open(player,core,notice);
        }
    }
    private static boolean permitted(ServerPlayer player,ShipCoreBlockEntity core) {
        return player.isAlive() && !player.isSpectator() && core.authorized(player) && core.getLevel()==player.serverLevel()
            && core.loadedForControl() && player.getPersistentData().hasUUID("starbridge:selected_core")
            && core.id().equals(player.getPersistentData().getUUID("starbridge:selected_core"));
    }
    static Chart view(ServerPlayer player,ShipCoreBlockEntity core,Component notice) {
        var from=OriginalStarMap.byLevel(player.serverLevel());
        boolean settling = HullTransit.inProgress(player.serverLevel().getServer(), core.id());
        List<Row> rows=new ArrayList<>();
        for (CelestialPort p : OriginalStarMap.ports(player.serverLevel().getServer()).values()) {
            rows.add(new Row(p.key(),p.title(),p.provider(),p.tier(),from==null||from.key().equals(p.key())?0:from.fareTo(p),p.fare(),from==null||from.key().equals(p.key())?0:dev.starbridge.orbit.OrbitModule.requiredEnergy(from,p),Voyages.availability(core,from,p,settling)));
        }
        return new Chart(core.id(),core.energy(),core.navigationTier(),core.agentAllowed(),core.pairedComputer(),List.copyOf(rows),notice);
    }
    public static void open(ServerPlayer p,ShipCoreBlockEntity core) {
        if (permitted(p,core) && dev.starbridge.orbit.OrbitModule.active(core) && dev.starbridge.orbit.OrbitModule.aboard(p,core)) {
            dev.starbridge.orbit.OrbitModule.open(p,core);
            return;
        }
        open(p,core,Component.empty());
    }
    public static void open(ServerPlayer p,ShipCoreBlockEntity core,Component notice) {
        if (permitted(p,core)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),view(p,core,notice));
    }
    public static void action(UUID core,Action action,String id,int value) { CHANNEL.sendToServer(new Input(core,action,id,value)); }
    private static void encode(Chart v,FriendlyByteBuf b) {
        b.writeUUID(v.core); b.writeVarInt(v.energy); b.writeVarInt(v.tier); b.writeBoolean(v.agent); b.writeInt(v.computer);
        b.writeCollection(v.ports,(buf,r) -> { buf.writeUtf(r.id,96); buf.writeUtf(r.title,256); buf.writeUtf(r.provider,64); buf.writeVarInt(r.tier);
                buf.writeVarInt(r.fare); buf.writeVarInt(r.reserve); buf.writeVarInt(r.launchEnergy); buf.writeEnum(r.state); });
        b.writeComponent(v.notice);
    }
    private static Chart decode(FriendlyByteBuf b) {
        UUID core=b.readUUID(); int energy=b.readVarInt(),tier=b.readVarInt(); boolean agent=b.readBoolean(); int computer=b.readInt();
        int count=b.readVarInt(); if(count<0 || count>4096) throw new IllegalArgumentException("Invalid destination list");
        List<Row> ports=new ArrayList<>(count);
        for(int i=0;i<count;i++) ports.add(new Row(b.readUtf(96),b.readUtf(256),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readEnum(Voyages.Availability.class)));
        return new Chart(core,energy,tier,agent,computer,List.copyOf(ports),b.readComponent());
    }
}
