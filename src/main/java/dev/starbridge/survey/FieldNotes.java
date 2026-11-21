package dev.starbridge.survey;

import dev.starbridge.Starbridge;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;

/** A field notebook travels with the slate. A mark always comes from its holder's real feet. */
public final class FieldNotes {
    private static final String ID = "StarbridgeSurveyId", NOTES = "StarbridgeFieldNotes";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Starbridge.ID, "field_survey"), () -> "1", "1"::equals, "1"::equals);
    public enum Marker { SHIP, SITE }
    public enum Notice { OPEN, SAVED, MOVED, CHANGED_WORLD }
    public record View(UUID id, InteractionHand hand, ResourceLocation dimension, BlockPos ship, BlockPos site, Notice notice) {
        public BlockPos at(Marker marker) { return marker == Marker.SHIP ? ship : site; }
    }
    private record Open(InteractionHand hand) {}
    private record SetMark(UUID id, InteractionHand hand, ResourceLocation dimension, Marker marker) {}
    private FieldNotes() {}

    public static void register() {
        CHANNEL.messageBuilder(Open.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((p, b) -> b.writeEnum(p.hand())).decoder(b -> new Open(b.readEnum(InteractionHand.class)))
                .consumerMainThread((p, ctx) -> { var player = ctx.get().getSender(); if (player != null) open(player, p.hand()); ctx.get().setPacketHandled(true); }).add();
        CHANNEL.messageBuilder(SetMark.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((p, b) -> { b.writeUUID(p.id()); b.writeEnum(p.hand()); b.writeResourceLocation(p.dimension()); b.writeEnum(p.marker()); })
                .decoder(b -> new SetMark(b.readUUID(), b.readEnum(InteractionHand.class), b.readResourceLocation(), b.readEnum(Marker.class)))
                .consumerMainThread((p, ctx) -> { var player = ctx.get().getSender(); if (player != null) mark(player, p); ctx.get().setPacketHandled(true); }).add();
        CHANNEL.messageBuilder(View.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((p, b) -> { b.writeUUID(p.id()); b.writeEnum(p.hand()); b.writeResourceLocation(p.dimension()); b.writeNullable(p.ship(), FriendlyByteBuf::writeBlockPos); b.writeNullable(p.site(), FriendlyByteBuf::writeBlockPos); b.writeEnum(p.notice()); })
                .decoder(b -> new View(b.readUUID(), b.readEnum(InteractionHand.class), b.readResourceLocation(), b.readNullable(FriendlyByteBuf::readBlockPos), b.readNullable(FriendlyByteBuf::readBlockPos), b.readEnum(Notice.class)))
                .consumerMainThread((view, ctx) -> { DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.starbridge.client.survey.SurfaceSurveyScreen.receive(view)); ctx.get().setPacketHandled(true); }).add();
    }

    public static InteractionHand held(Player player) {
        for (var hand : InteractionHand.values()) if (player.getItemInHand(hand).is(VoyageModule.SLATE.get())) return hand;
        return null;
    }
    public static void askOpen(InteractionHand hand) { if (hand != null) CHANNEL.sendToServer(new Open(hand)); }
    public static void record(View view, Marker marker) { CHANNEL.sendToServer(new SetMark(view.id(), view.hand(), view.dimension(), marker)); }

    public static void open(ServerPlayer player, InteractionHand hand) {
        var slate = player.getItemInHand(hand);
        if (!usable(player, slate)) return;
        var tag = slate.getOrCreateTag();
        if (!tag.hasUUID(ID)) tag.putUUID(ID, UUID.randomUUID());
        send(player, read(slate, hand, player.level().dimension().location(), Notice.OPEN));
    }
    private static boolean usable(ServerPlayer player, ItemStack slate) {
        return player.isAlive() && !player.isSpectator() && slate.is(VoyageModule.SLATE.get());
    }
    private static View read(ItemStack slate, InteractionHand hand, ResourceLocation world, Notice notice) {
        var tag = slate.getOrCreateTag();
        var marks = tag.getCompound(NOTES).getCompound(world.toString());
        return new View(tag.getUUID(ID), hand, world, get(marks, Marker.SHIP), get(marks, Marker.SITE), notice);
    }
    /** Read only a mark the server already saved on this specific terminal. */
    public static BlockPos markedOn(ItemStack slate, UUID id, ResourceLocation dimension, Marker marker) {
        var tag = slate.getTag();
        if (!slate.is(VoyageModule.SLATE.get()) || tag == null || !tag.hasUUID(ID) || !id.equals(tag.getUUID(ID))) return null;
        var pages = slate.getTagElement(NOTES);
        if (pages == null || !pages.contains(dimension.toString(), Tag.TAG_COMPOUND)) return null;
        return get(pages.getCompound(dimension.toString()), marker);
    }
    private static BlockPos get(CompoundTag page, Marker key) {
        return page.contains(key.name(), Tag.TAG_LONG) ? BlockPos.of(page.getLong(key.name())) : null;
    }
    private static void mark(ServerPlayer player, SetMark request) {
        var slate = player.getItemInHand(request.hand());
        var world = player.level().dimension().location();
        if (!usable(player, slate) || slate.getTag() == null || !slate.getTag().hasUUID(ID) || !slate.getTag().getUUID(ID).equals(request.id())) {
            send(player, new View(request.id(), request.hand(), request.dimension(), null, null, Notice.MOVED));
            return;
        }
        if (!world.equals(request.dimension())) {
            send(player, new View(request.id(), request.hand(), request.dimension(), null, null, Notice.CHANGED_WORLD));
            return;
        }
        var pages = slate.getOrCreateTagElement(NOTES);
        var page = pages.getCompound(world.toString());
        page.putLong(request.marker().name(), player.blockPosition().asLong());
        pages.put(world.toString(), page);
        player.getInventory().setChanged();
        send(player, read(slate, request.hand(), world, Notice.SAVED));
    }
    private static void send(ServerPlayer player, View view) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), view); }
}
