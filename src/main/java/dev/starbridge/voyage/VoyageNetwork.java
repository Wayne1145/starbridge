package dev.starbridge.voyage;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.UUID;

public final class VoyageNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Starbridge.ID, "navigation"),
            () -> "1", "1"::equals, "1"::equals);
    public record Chart(UUID core, int current, int known, int energy) {}
    private record SetCourse(UUID core, Route route) {}
    private VoyageNetwork() {}
    static void register() {
        CHANNEL.messageBuilder(Chart.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((v,b) -> { b.writeUUID(v.core()); b.writeInt(v.current()); b.writeInt(v.known()); b.writeInt(v.energy()); })
                .decoder(b -> new Chart(b.readUUID(), b.readInt(), b.readInt(), b.readInt()))
                .consumerMainThread((v,ctx) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.starbridge.client.ChartScreen.open(v));
                    ctx.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(SetCourse.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((v,b) -> { b.writeUUID(v.core()); b.writeEnum(v.route()); })
                .decoder(b -> new SetCourse(b.readUUID(), b.readEnum(Route.class)))
                .consumerMainThread((v,ctx) -> {
                    ServerPlayer player = ctx.get().getSender();
                    if (player != null) CoreRegistry.find(v.core()).ifPresent(core -> {
                        try { Voyages.depart(player, core, v.route()); }
                        catch (IllegalArgumentException | IllegalStateException rejected) { player.sendSystemMessage(Component.literal(rejected.getMessage())); }
                    });
                    ctx.get().setPacketHandled(true);
                }).add();
    }
    public static void open(ServerPlayer player, ShipCoreBlockEntity core) {
        if (!core.authorized(player) || core.getLevel() != player.serverLevel()) return;
        Route origin = Voyages.here(player.serverLevel());
        int known = 0;
        for (Route route : Route.values()) if (Voyages.unlocked(player, route)) known |= 1 << route.ordinal();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Chart(core.id(), origin == null ? -1 : origin.ordinal(), known, core.energy()));
    }
    public static void setCourse(UUID core, Route route) { CHANNEL.sendToServer(new SetCourse(core, route)); }
}
