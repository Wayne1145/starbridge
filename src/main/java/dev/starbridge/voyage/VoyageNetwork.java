package dev.starbridge.voyage;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.CelestialPort;
import dev.starbridge.compat.computer.AttachedComputers;
import dev.starbridge.compat.rocketry.OriginalPropellant;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.galacticraft.OriginalOxygenEquipment;
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
    private static final String PROTOCOL = "8";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Starbridge.ID,"navigation"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    public record Row(String id, String title, String provider, int tier, int fare, int reserve, int launchEnergy, Voyages.Availability state) {}
    public record Chart(UUID core,int energy,int tier,boolean agent,int computer,AttachedComputers.Page devices,List<Row> ports,Component notice,boolean opensScreen) {}
    public enum Action { MANUAL, CRUISE, SURFACE, AGENT, PAIR, REFRESH, DEVICES, PAIR_CONNECTED }
    private record SlateGuide() {}
    private record PersonalEquipment(int request) {}
    private record FuelRequest(int page) {}
    private record FuelResult(int page, OriginalPropellant.Batches batches) {}
    private record EquipmentFailure(int request, OriginalOxygenEquipment.Result reason) {}
    private record Input(UUID core,Action action,String target,int value) {}
    private VoyageNetwork() {}

    static void register() {
        dev.starbridge.survey.FieldNotes.register();
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
        CHANNEL.messageBuilder(PersonalEquipment.class, 3, NetworkDirection.PLAY_TO_SERVER)
                .encoder((packet, buffer) -> buffer.writeInt(packet.request())).decoder(buffer -> new PersonalEquipment(buffer.readInt()))
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) {
                        var result = OriginalOxygenEquipment.open(player);
                        if (result != OriginalOxygenEquipment.Result.OPENED) {
                            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new EquipmentFailure(packet.request(), result));
                        }
                    }
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(EquipmentFailure.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((packet, buffer) -> { buffer.writeInt(packet.request()); buffer.writeEnum(packet.reason()); })
                .decoder(buffer -> new EquipmentFailure(buffer.readInt(), buffer.readEnum(OriginalOxygenEquipment.Result.class)))
                .consumerMainThread((packet, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.starbridge.client.EquipmentAccessScreen.failed(packet.request(), packet.reason()));
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(FuelRequest.class, 5, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> buf.writeInt(msg.page())).decoder(buf -> new FuelRequest(buf.readInt()))
                .consumerMainThread((msg, ctx) -> {
                    var player = ctx.get().getSender();
                    if (player != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new FuelResult(msg.page(), OriginalPropellant.forServer(player.serverLevel().getServer())));
                    ctx.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(FuelResult.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> {
                    var rows = msg.batches(); buf.writeInt(msg.page());
                    buf.writeVarInt(rows.water()); buf.writeVarInt(rows.oxygenMade()); buf.writeVarInt(rows.hydrogenMade());
                    buf.writeVarInt(rows.oxygenUsed()); buf.writeVarInt(rows.hydrogenUsed()); buf.writeVarInt(rows.fuel());
                }).decoder(buf -> new FuelResult(buf.readInt(), new OriginalPropellant.Batches(buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())))
                .consumerMainThread((msg, ctx) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.starbridge.client.PropellantScreen.receive(msg.page(), msg.batches()));
                    ctx.get().setPacketHandled(true);
                }).add();
    }

    public static void requestFuelRecipes(int page) { CHANNEL.sendToServer(new FuelRequest(page)); }

    public static void openPersonalEquipment(int request) {
        CHANNEL.sendToServer(new PersonalEquipment(request));
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
                case REFRESH -> sendChart(player,core,Component.empty(),0);
                case AGENT -> { core.setAgentAllowed(v.value==1); sendChart(player,core,Component.empty(),0); }
                case PAIR -> {
                    core.pairComputer(v.value);
                    sendChart(player, core, Component.empty(), 0);
                }
                case DEVICES -> sendChart(player, core, Component.empty(), v.value);
                case PAIR_CONNECTED -> {
                    var links = core.attachedComputers();
                    boolean present;
                    synchronized (links) {
                        present = links.contains(v.value);
                        if (present) core.pairComputer(v.value);
                    }
                    var feedback = present ? Component.translatable("nav.starbridge.devices.success", v.value)
                            : Component.translatable("nav.starbridge.devices.gone");
                    sendChart(player, core, feedback, links.pageOf(v.value));
                }
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
    private static Chart view(ServerPlayer player,ShipCoreBlockEntity core,Component notice,int devicePage,boolean opensScreen) {
        var from=OriginalStarMap.byLevel(player.serverLevel());
        boolean settling = HullTransit.inProgress(player.serverLevel().getServer(), core.id());
        List<Row> rows=new ArrayList<>();
        for (CelestialPort p : OriginalStarMap.ports(player.serverLevel().getServer()).values()) {
            rows.add(new Row(p.key(),p.title(),p.provider(),p.tier(),from==null||from.key().equals(p.key())?0:from.fareTo(p),p.fare(),from==null||from.key().equals(p.key())?0:dev.starbridge.orbit.OrbitModule.requiredEnergy(from,p),Voyages.availability(core,from,p,settling)));
        }
        return new Chart(core.id(),core.energy(),core.navigationTier(),core.agentAllowed(),core.pairedComputer(),core.attachedComputers().page(devicePage),List.copyOf(rows),notice,opensScreen);
    }
    public static void open(ServerPlayer p,ShipCoreBlockEntity core) {
        if (permitted(p,core) && dev.starbridge.orbit.OrbitModule.active(core) && dev.starbridge.orbit.OrbitModule.aboard(p,core)) {
            dev.starbridge.orbit.OrbitModule.open(p,core);
            return;
        }
        open(p,core,Component.empty());
    }
    public static void open(ServerPlayer p,ShipCoreBlockEntity core,Component notice) {
        if (permitted(p,core)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), view(p,core,notice,0,true));
    }

    // A delayed device-list or terrain refresh must never reopen a helm the player has already left.
    private static void sendChart(ServerPlayer player, ShipCoreBlockEntity core, Component notice, int devicePage) {
        if (permitted(player, core)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), view(player, core, notice, devicePage, false));
    }
    public static void action(UUID core,Action action,String id,int value) { CHANNEL.sendToServer(new Input(core,action,id,value)); }
    private static void encode(Chart v,FriendlyByteBuf b) {
        b.writeUUID(v.core); b.writeVarInt(v.energy); b.writeVarInt(v.tier); b.writeBoolean(v.agent); b.writeInt(v.computer);
        b.writeVarInt(v.devices.index()); b.writeVarInt(v.devices.count());
        b.writeCollection(v.devices.ids(), (buffer, id) -> buffer.writeVarInt(id));
        b.writeCollection(v.ports,(buf,r) -> { buf.writeUtf(r.id,96); buf.writeUtf(r.title,256); buf.writeUtf(r.provider,64); buf.writeVarInt(r.tier);
                buf.writeVarInt(r.fare); buf.writeVarInt(r.reserve); buf.writeVarInt(r.launchEnergy); buf.writeEnum(r.state); });
        b.writeComponent(v.notice); b.writeBoolean(v.opensScreen);
    }
    private static Chart decode(FriendlyByteBuf b) {
        UUID core=b.readUUID(); int energy=b.readVarInt(),tier=b.readVarInt(); boolean agent=b.readBoolean(); int computer=b.readInt();
        int page = b.readVarInt(), pages = b.readVarInt(), size = b.readVarInt();
        if (pages < 1 || page < 0 || page >= pages || size < 0 || size > AttachedComputers.PAGE_SIZE) throw new IllegalArgumentException("Invalid computer list");
        List<Integer> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(b.readVarInt());
        var devices = new AttachedComputers.Page(page, pages, List.copyOf(ids));
        int count=b.readVarInt(); if(count<0 || count>4096) throw new IllegalArgumentException("Invalid destination list");
        List<Row> ports=new ArrayList<>(count);
        for(int i=0;i<count;i++) ports.add(new Row(b.readUtf(96),b.readUtf(256),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readEnum(Voyages.Availability.class)));
        return new Chart(core,energy,tier,agent,computer,devices,List.copyOf(ports),b.readComponent(),b.readBoolean());
    }
}
