package dev.starbridge.orbit;

import dev.starbridge.Starbridge;
import dev.starbridge.client.orbit.OrbitClient;
import dev.starbridge.flight.Vec3d;

import net.minecraft.network.FriendlyByteBuf;
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

public final class OrbitWire {
    private static final String VERSION = "3";
    private static final SimpleChannel NETWORK =
            NetworkRegistry.newSimpleChannel(
                    new ResourceLocation(Starbridge.ID, "orbital_helm"),
                    () -> VERSION,
                    VERSION::equals,
                    VERSION::equals);

    public enum Action {
        INPUT,
        BRAKE,
        CRUISE,
        MANUAL,
        LAND,
        LEAVE,
        SURVEY,
        DISMISS_SURVEY,
        GO_AROUND
    }

    public record Control(
            UUID core,
            Action action,
            int thrust,
            int turn,
            int pitch,
            int approachX,
            int approachZ) {}

    public record Contact(UUID core, Vec3d position, double yaw, double pitch) {}

    public record Frame(
            UUID core,
            boolean open,
            boolean closed,
            String target,
            Vec3d position,
            Vec3d velocity,
            double yaw,
            double pitch,
            Vec3d body,
            int fuel,
            int reserve,
            boolean cruise,
            List<Contact> contacts,
            String notice) {}

    public record Voxel(int x, int y, int z, int width, int height, int depth, int block) {}

    public record Hull(UUID ship, int width, int height, int depth, List<Voxel> voxels) {}

    public enum Clearance {
        PENDING,
        CLEAR,
        BORDER,
        LIQUID,
        VOID,
        OCCUPIED,
        HEIGHT,
        BUSY,
        UNAVAILABLE
    }

    /** Cell 0 is unknown, 1 void, 2 fluid, and 3 through 18 indicate rising terrain. */
    public record Site(
            UUID ship,
            int x,
            int z,
            int centerX,
            int centerZ,
            int width,
            int height,
            int depth,
            int mapMinX,
            int mapMinZ,
            int spanX,
            int spanZ,
            int floor,
            int relief,
            Clearance clearance,
            int markX,
            int markZ,
            byte[] cells) {
        public static final int GRID = 12;
    }

    private OrbitWire() {}

    static void register() {
        NETWORK.messageBuilder(Control.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(OrbitWire::encodeControl)
                .decoder(OrbitWire::decodeControl)
                .consumerMainThread(
                        (packet, context) -> {
                            var sender = context.get().getSender();
                            if (sender != null) OrbitModule.control(sender, packet);
                            context.get().setPacketHandled(true);
                        })
                .add();
        NETWORK.messageBuilder(Frame.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OrbitWire::encodeFrame)
                .decoder(OrbitWire::decodeFrame)
                .consumerMainThread(
                        (packet, context) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT, () -> () -> OrbitClient.frame(packet));
                            context.get().setPacketHandled(true);
                        })
                .add();
        NETWORK.messageBuilder(Hull.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OrbitWire::encodeHull)
                .decoder(OrbitWire::decodeHull)
                .consumerMainThread(
                        (packet, context) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT, () -> () -> OrbitClient.hull(packet));
                            context.get().setPacketHandled(true);
                        })
                .add();
        NETWORK.messageBuilder(Site.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OrbitWire::encodeSite)
                .decoder(OrbitWire::decodeSite)
                .consumerMainThread(
                        (packet, context) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT, () -> () -> OrbitClient.site(packet));
                            context.get().setPacketHandled(true);
                        })
                .add();
    }

    public static void send(Control packet) {
        NETWORK.sendToServer(packet);
    }

    static void send(ServerPlayer player, Object packet) {
        NETWORK.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static void encodeSite(Site site, FriendlyByteBuf out) {
        out.writeUUID(site.ship());
        out.writeShort(site.x());
        out.writeShort(site.z());
        out.writeInt(site.centerX());
        out.writeInt(site.centerZ());
        out.writeShort(site.width());
        out.writeShort(site.height());
        out.writeShort(site.depth());
        out.writeInt(site.mapMinX());
        out.writeInt(site.mapMinZ());
        out.writeShort(site.spanX());
        out.writeShort(site.spanZ());
        out.writeInt(site.floor());
        out.writeInt(site.relief());
        out.writeEnum(site.clearance());
        out.writeInt(site.markX());
        out.writeInt(site.markZ());
        out.writeByteArray(site.cells());
    }

    private static Site decodeSite(FriendlyByteBuf in) {
        var site =
                new Site(
                        in.readUUID(),
                        in.readShort(),
                        in.readShort(),
                        in.readInt(),
                        in.readInt(),
                        in.readUnsignedShort(),
                        in.readUnsignedShort(),
                        in.readUnsignedShort(),
                        in.readInt(),
                        in.readInt(),
                        in.readUnsignedShort(),
                        in.readUnsignedShort(),
                        in.readInt(),
                        in.readInt(),
                        in.readEnum(Clearance.class),
                        in.readInt(),
                        in.readInt(),
                        in.readByteArray(Site.GRID * Site.GRID));
        if (site.cells().length != Site.GRID * Site.GRID || site.spanX() < 1 || site.spanZ() < 1)
            throw new IllegalArgumentException("Invalid landing chart");
        return site;
    }

    private static void encodeControl(Control control, FriendlyByteBuf buf) {
        buf.writeUUID(control.core);
        buf.writeEnum(control.action);
        buf.writeByte(control.thrust);
        buf.writeByte(control.turn);
        buf.writeByte(control.pitch);
        buf.writeShort(control.approachX);
        buf.writeShort(control.approachZ);
    }

    private static Control decodeControl(FriendlyByteBuf buf) {
        return new Control(
                buf.readUUID(),
                buf.readEnum(Action.class),
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
                buf.readShort(),
                buf.readShort());
    }

    private static void vector(FriendlyByteBuf buf, Vec3d v) {
        buf.writeDouble(v.x());
        buf.writeDouble(v.y());
        buf.writeDouble(v.z());
    }

    private static Vec3d vector(FriendlyByteBuf buf) {
        return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void encodeFrame(Frame frame, FriendlyByteBuf buf) {
        buf.writeUUID(frame.core);
        buf.writeBoolean(frame.open);
        buf.writeBoolean(frame.closed);
        buf.writeUtf(frame.target, 128);
        vector(buf, frame.position);
        vector(buf, frame.velocity);
        buf.writeFloat((float) frame.yaw);
        buf.writeFloat((float) frame.pitch);
        vector(buf, frame.body);
        buf.writeVarInt(frame.fuel);
        buf.writeVarInt(frame.reserve);
        buf.writeBoolean(frame.cruise);
        buf.writeCollection(
                frame.contacts,
                (out, other) -> {
                    out.writeUUID(other.core);
                    vector(out, other.position);
                    out.writeFloat((float) other.yaw);
                    out.writeFloat((float) other.pitch);
                });
        buf.writeUtf(frame.notice, 256);
    }

    private static Frame decodeFrame(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        boolean open = buf.readBoolean(), closed = buf.readBoolean();
        String target = buf.readUtf(128);
        Vec3d position = vector(buf), velocity = vector(buf);
        double yaw = buf.readFloat(), pitch = buf.readFloat();
        Vec3d body = vector(buf);
        int energy = buf.readVarInt(), reserve = buf.readVarInt();
        boolean cruise = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > 32) throw new IllegalArgumentException("Invalid orbital contacts");
        var contacts = new ArrayList<Contact>(count);
        for (int i = 0; i < count; i++)
            contacts.add(
                    new Contact(buf.readUUID(), vector(buf), buf.readFloat(), buf.readFloat()));
        return new Frame(
                id,
                open,
                closed,
                target,
                position,
                velocity,
                yaw,
                pitch,
                body,
                energy,
                reserve,
                cruise,
                List.copyOf(contacts),
                buf.readUtf(256));
    }

    private static void encodeHull(Hull hull, FriendlyByteBuf buf) {
        buf.writeUUID(hull.ship);
        buf.writeShort(hull.width);
        buf.writeShort(hull.height);
        buf.writeShort(hull.depth);
        buf.writeCollection(
                hull.voxels,
                (out, voxel) -> {
                    out.writeShort(voxel.x);
                    out.writeShort(voxel.y);
                    out.writeShort(voxel.z);
                    out.writeByte(voxel.width);
                    out.writeByte(voxel.height);
                    out.writeByte(voxel.depth);
                    out.writeVarInt(voxel.block);
                });
    }

    private static Hull decodeHull(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int w = buf.readUnsignedShort(),
                h = buf.readUnsignedShort(),
                d = buf.readUnsignedShort(),
                count = buf.readVarInt();
        if (count < 0
                || count > HullSurvey.MAX_SAMPLES
                || w < 1
                || w > 320
                || h < 1
                || h > 320
                || d < 1
                || d > 320) throw new IllegalArgumentException("Invalid hull survey dimensions");
        var blocks = new ArrayList<Voxel>(count);
        for (int i = 0; i < count; i++) {
            var cell =
                    new Voxel(
                            buf.readUnsignedShort(),
                            buf.readUnsignedShort(),
                            buf.readUnsignedShort(),
                            buf.readUnsignedByte(),
                            buf.readUnsignedByte(),
                            buf.readUnsignedByte(),
                            buf.readVarInt());
            if (cell.width < 1
                    || cell.height < 1
                    || cell.depth < 1
                    || cell.x + cell.width > w
                    || cell.y + cell.height > h
                    || cell.z + cell.depth > d)
                throw new IllegalArgumentException("Hull sample outside the surveyed cabin");
            blocks.add(cell);
        }
        return new Hull(id, w, h, d, List.copyOf(blocks));
    }
}
