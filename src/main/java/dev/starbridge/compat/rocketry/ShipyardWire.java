package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;

public final class ShipyardWire {
    public enum Action {
        REFRESH,
        RECHECK,
        SCAN,
        ASSEMBLE,
        FUEL,
        BOARD,
        LAUNCH,
        CLOSE
    }

    public record View(
            boolean open,
            UUID token,
            BlockPos console,
            BlockPos min,
            BlockPos max,
            String status,
            String job,
            String notice,
            int progress,
            int energy,
            int weight,
            int thrust,
            int capacity,
            int need,
            int tank,
            int machineCount,
            int crewCount,
            BlockPos firstMachine,
            boolean canScan,
            boolean canBuild,
            boolean canBoard,
            boolean canLaunch) {}

    private record Input(UUID token, Action action) {}

    private record Close(UUID token) {}

    private static final class Session {
        final UUID token = UUID.randomUUID();
        final ResourceKey<Level> world;
        final BlockPos pos;
        long lastRead = Long.MIN_VALUE, lastAction = Long.MIN_VALUE, expires, cacheUntil;
        int machineCount;
        BlockPos firstMachine;
        String notice = "";

        Session(ServerPlayer player, BlockPos pos) {
            this.pos = pos.immutable();
            world = player.level().dimension();
            expires = player.level().getGameTime() + 100;
        }
    }

    private static final Map<UUID, Session> OPEN = new HashMap<>();
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL =
            NetworkRegistry.newSimpleChannel(
                    new ResourceLocation(Starbridge.ID, "original_shipyard"),
                    () -> VERSION,
                    VERSION::equals,
                    VERSION::equals);

    private ShipyardWire() {}

    static void register() {
        CHANNEL.messageBuilder(View.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ShipyardWire::write)
                .decoder(ShipyardWire::read)
                .consumerMainThread(
                        (v, c) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT,
                                    () ->
                                            () ->
                                                    dev.starbridge.client.shipyard.ShipyardScreen
                                                            .receive(v));
                            c.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(Input.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(
                        (v, b) -> {
                            b.writeUUID(v.token());
                            b.writeEnum(v.action());
                        })
                .decoder(b -> new Input(b.readUUID(), b.readEnum(Action.class)))
                .consumerMainThread(
                        (v, c) -> {
                            var p = c.get().getSender();
                            if (p != null) input(p, v);
                            c.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(Close.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((v, b) -> b.writeUUID(v.token()))
                .decoder(b -> new Close(b.readUUID()))
                .consumerMainThread(
                        (v, c) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT,
                                    () ->
                                            () ->
                                                    dev.starbridge.client.shipyard.ShipyardScreen
                                                            .close(v.token()));
                            c.get().setPacketHandled(true);
                        })
                .add();
        MinecraftForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedOutEvent e) -> OPEN.remove(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> OPEN.clear());
    }

    static boolean permitted(ServerPlayer p, BlockPos pos) {
        return p.isAlive()
                && !p.isSpectator()
                && p.serverLevel().isLoaded(pos)
                && p.serverLevel().mayInteract(p, pos)
                && OriginalRocketApi.isConsole(p.serverLevel().getBlockState(pos))
                && (p.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 36
                        || RocketTransfer.riding(p, pos));
    }

    static void open(ServerPlayer p, BlockPos pos) {
        if (!permitted(p, pos)) return;
        var s = new Session(p, pos);
        OPEN.put(p.getUUID(), s);
        send(p, s, true);
    }

    private static void input(ServerPlayer p, Input input) {
        var s = OPEN.get(p.getUUID());
        if (s == null || !s.token.equals(input.token())) return;
        if (input.action() == Action.CLOSE) {
            OPEN.remove(p.getUUID());
            return;
        }
        long now = p.level().getGameTime();
        if (s.world != p.level().dimension() || now > s.expires || !permitted(p, s.pos)) {
            OPEN.remove(p.getUUID());
            return;
        }
        if (input.action() == Action.REFRESH) {
            if (s.lastRead != Long.MIN_VALUE && now - s.lastRead < 5) return;
            s.lastRead = now;
        } else {
            if (s.lastAction != Long.MIN_VALUE && now - s.lastAction < 2) return;
            s.lastAction = now;
            s.cacheUntil = 0;
        }
        s.expires = now + 100;
        try {
            if (input.action() != Action.REFRESH) {
                s.notice = "";
                var tile =
                        OriginalRocketApi.builder(p.serverLevel().getBlockEntity(s.pos))
                                .orElseThrow(() -> new IllegalStateException("unavailable"));
                switch (input.action()) {
                    case RECHECK -> RocketTransfer.retry(p, s.pos);
                    case SCAN, ASSEMBLE -> {
                        if (!p.mayBuild() || (boolean) OriginalRocketApi.call(tile, "isScanning"))
                            throw new IllegalStateException("busy");
                        var box = ShipyardHull.pad(OriginalRocketApi.bounds(tile, p, s.pos));
                        if (box == null) throw new IllegalStateException("pad");
                        CabinChunks.prepare(p.serverLevel(), box, BlockPos.ZERO);
                        if (input.action() == Action.ASSEMBLE)
                            RocketTransfer.build(p, s.pos, tile, box);
                        else {
                            if (RocketTransfer.busy(p.serverLevel(), s.pos))
                                throw new IllegalStateException("busy");
                            OriginalRocketApi.press(tile, p, false);
                        }
                        if (!(boolean) OriginalRocketApi.call(tile, "isScanning"))
                            throw new IllegalStateException("too_small");
                    }
                    case FUEL -> RocketTransfer.fuel(p, s.pos);
                    case BOARD -> RocketTransfer.board(p, s.pos);
                    case LAUNCH -> RocketTransfer.launch(p, s.pos);
                    default -> {}
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            s.notice = key(e.getMessage());
            if (Boolean.getBoolean("starbridge.shipyardGuiFixture"))
                Starbridge.LOGGER.info(
                        "SHIPYARD GUI action {} declined {}", input.action(), s.notice);
        } catch (ReflectiveOperationException | LinkageError e) {
            s.notice = "unavailable";
            Starbridge.LOGGER.warn(
                    "Original assembler at {}: {}", s.pos, OriginalRocketApi.error(e));
        }
        send(p, s, false);
    }

    private static String key(String s) {
        return s != null && s.matches("[a-z_]{1,40}") ? s : "unavailable";
    }

    private static void send(ServerPlayer p, Session s, boolean open) {
        var level = p.serverLevel();
        var job = RocketTransfer.job(level, s.pos);
        var tile = OriginalRocketApi.builder(level.getBlockEntity(s.pos));
        if (job != null && job.ready()) {
            sendPacked(p, s, job, tile, open);
            return;
        }
        BlockPos min = s.pos, max = s.pos, first = s.pos;
        String status = "UNAVAILABLE";
        int progress = 0, energy = 0, weight = 0, thrust = 0, cap = 0, need = 0, count = 0;
        boolean scan = false, build = false;
        if (tile.isPresent())
            try {
                Object t = tile.get();
                var box = ShipyardHull.pad(OriginalRocketApi.bounds(t, p, s.pos));
                status = ((Enum<?>) OriginalRocketApi.call(t, "getStatus")).name();
                boolean moving = (boolean) OriginalRocketApi.call(t, "isScanning");
                progress =
                        moving
                                ? (int)
                                        Math.min(
                                                100,
                                                Math.max(
                                                        0,
                                                        100
                                                                * ((Number)
                                                                                OriginalRocketApi
                                                                                        .call(
                                                                                                t,
                                                                                                "getNormallizedProgress"))
                                                                        .doubleValue()))
                                : -1;
                energy = OriginalRocketApi.number(t, "getEnergyStored");
                weight = OriginalRocketApi.number(t, "getWeight");
                thrust = OriginalRocketApi.number(t, "getThrust");
                cap = OriginalRocketApi.number(t, "getFuel");
                need =
                        (int)
                                Math.ceil(
                                        ((Number) OriginalRocketApi.call(t, "getNeededFuel"))
                                                .doubleValue());
                if (box != null) {
                    min = box.min();
                    max = box.max();
                    if (level.getGameTime() >= s.cacheUntil) {
                        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
                            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                                if (chunk == null) continue;
                                for (var entry : chunk.getBlockEntities().entrySet()) {
                                    BlockPos b = entry.getKey();
                                    if (b.getX() < min.getX()
                                            || b.getY() < min.getY()
                                            || b.getZ() < min.getZ()
                                            || b.getX() > max.getX()
                                            || b.getY() > max.getY()
                                            || b.getZ() > max.getZ()) continue;
                                    if (!entry.getValue()
                                            .getClass()
                                            .getName()
                                            .equals("retrofit.forge.world.LegacyBlockEntity")) {
                                        if (count++ == 0) first = b.immutable();
                                    }
                                }
                            }
                        s.machineCount = count;
                        s.firstMachine = first;
                        s.cacheUntil = level.getGameTime() + 60;
                    } else {
                        count = s.machineCount;
                        first = s.firstMachine;
                    }
                } else if (!moving) status = "PAD";
                if (moving && energy < OriginalRocketApi.number(t, "getPowerPerOperation"))
                    status = "POWER";
                else if (moving) status = "SCANNING";
                scan = !moving && box != null && job == null && p.mayBuild();
                build = scan && status.equals("SUCCESS");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                status = "UNAVAILABLE";
            }
        int fuel = job == null ? 0 : job.fuel(level);
        boolean owner = job != null && job.owner.equals(p.getUUID());
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> p),
                new View(
                        open,
                        s.token,
                        s.pos,
                        min,
                        max,
                        status,
                        job == null ? "" : job.stage.name(),
                        s.notice.isEmpty() && owner ? job.problem : s.notice,
                        progress,
                        energy,
                        weight,
                        thrust,
                        cap,
                        need,
                        fuel,
                        count,
                        job == null ? 0 : job.riders.size(),
                        first,
                        scan,
                        build,
                        job != null && job.ready() && !RocketTransfer.riding(p, s.pos),
                        owner && job.ready() && RocketTransfer.riding(p, s.pos)));
    }

    /** The assembler resets its scan after cutting the blocks. The carrier owns the live readings. */
    private static void sendPacked(ServerPlayer player, Session session, RocketTransfer.Job job,
                                   Optional<Object> assembler, boolean open) {
        int energy = -1, weight = -1, thrust = -1, capacity = -1, fuel = -1;
        try {
            if (assembler.isPresent()) energy = OriginalRocketApi.number(assembler.get(), "getEnergyStored");
            var entity = job.rocket == null ? null : player.serverLevel().getEntity(job.rocket);
            Object rocket = entity == null ? null : OriginalRocketApi.rocket(entity);
            if (rocket != null) {
                var stats = OriginalRocketApi.call(rocket, "getRocketStats");
                weight = OriginalRocketApi.number(stats, "getWeight");
                thrust = OriginalRocketApi.number(stats, "getThrust");
                capacity = OriginalRocketApi.number(rocket, "getFuelCapacity");
                fuel = OriginalRocketApi.number(rocket, "getFuelAmount");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // A client can reopen the dock while its saved carrier is still loading.
        }
        boolean owner = player.getUUID().equals(job.owner);
        boolean aboard = RocketTransfer.riding(player, session.pos);
        var min = job.hull == null ? session.pos : job.hull.box.min();
        var max = job.hull == null ? session.pos : job.hull.box.max();
        var view = new View(open, session.token, session.pos, min, max, "FINISHED",
                job.stage.name(), session.notice.isEmpty() && owner ? job.problem : session.notice,
                -1, energy, weight, thrust, capacity, 0, fuel, 0, job.riders.size(), session.pos,
                false, false, !aboard, owner && aboard);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), view);
    }

    static void leaveDock(ServerPlayer player) {
        var session = OPEN.remove(player.getUUID());
        if (session != null)
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Close(session.token));
    }

    public static void press(UUID token, Action action) {
        CHANNEL.sendToServer(new Input(token, action));
    }

    private static void write(View v, FriendlyByteBuf b) {
        b.writeBoolean(v.open);
        b.writeUUID(v.token);
        b.writeBlockPos(v.console);
        b.writeBlockPos(v.min);
        b.writeBlockPos(v.max);
        b.writeUtf(v.status, 64);
        b.writeUtf(v.job, 40);
        b.writeUtf(v.notice, 64);
        for (int i :
                new int[] {
                    v.progress,
                    v.energy,
                    v.weight,
                    v.thrust,
                    v.capacity,
                    v.need,
                    v.tank,
                    v.machineCount,
                    v.crewCount
                }) b.writeVarInt(i);
        b.writeBlockPos(v.firstMachine);
        b.writeBoolean(v.canScan);
        b.writeBoolean(v.canBuild);
        b.writeBoolean(v.canBoard);
        b.writeBoolean(v.canLaunch);
    }

    private static View read(FriendlyByteBuf b) {
        return new View(
                b.readBoolean(),
                b.readUUID(),
                b.readBlockPos(),
                b.readBlockPos(),
                b.readBlockPos(),
                b.readUtf(64),
                b.readUtf(40),
                b.readUtf(64),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readBlockPos(),
                b.readBoolean(),
                b.readBoolean(),
                b.readBoolean(),
                b.readBoolean());
    }
}
