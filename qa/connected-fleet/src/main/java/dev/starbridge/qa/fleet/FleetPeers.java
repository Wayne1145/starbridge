package dev.starbridge.qa.fleet;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local benchmark peers use ordinary native Forge login. Only the test connection's play listener
 * is headless.
 */
@Mod.EventBusSubscriber(modid = "starbridgefleetqa", value = Dist.CLIENT)
public final class FleetPeers {
    private static final boolean SOAK = Files.isRegularFile(Path.of("starbridge-soak-peers.fixture"));
    private static final boolean WIDE = Files.isRegularFile(Path.of("starbridge-wide-load-peers.fixture"));
    private static final int PORT = SOAK ? 26750 :
            Integer.getInteger("starbridge.fleet.port", Files.isRegularFile(Path.of("starbridge-native-fleet.fixture")) ? 25891 : 25890);
    private static final Path REQUEST = Path.of(SOAK ? "../server/soak-peers.txt" : "../server/fleet-peer-request.txt");
    private static final List<Peer> peers = new ArrayList<>();
    private static int tick, next;
    private static boolean failed;
    private static int entranceRetries;
    private static final Map<Integer, Integer> loginRetries = new HashMap<>();

    private static final class Peer {
        final int index;
        final Connection net;
        final long started = System.nanoTime();
        final AtomicLong chunks = new AtomicLong(),
                keepAlive = new AtomicLong(),
                packets = new AtomicLong(),
                move = new AtomicLong();
        volatile boolean joined, positioned, closed;
        volatile int playerId;
        volatile double x, y, z, phase;

        Peer(int index, Connection net) {
            this.index = index;
            this.net = net;
        }

        Object onPacket(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class)
                return switch (method.getName()) {
                    case "toString" -> "fleet-local-" + index;
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> proxy == args[0];
                };
            if (args != null && args.length >= 1 && args[0] instanceof Packet<?> packet) {
                packets.incrementAndGet();
                if (packet instanceof ClientboundLoginPacket login) {
                    playerId = login.playerId();
                    joined = true;
                    net.send(
                            new ServerboundClientInformationPacket(
                                    "en_us",
                                    3,
                                    ChatVisiblity.FULL,
                                    true,
                                    0,
                                    HumanoidArm.RIGHT,
                                    false,
                                    true));
                } else if (packet instanceof ClientboundPlayerPositionPacket pos) {
                    x = pos.getX();
                    y = pos.getY();
                    z = pos.getZ();
                    phase = 0;
                    net.send(new ServerboundAcceptTeleportationPacket(pos.getId()));
                    net.send(
                            new ServerboundMovePlayerPacket.PosRot(
                                    x, y, z, pos.getYRot(), pos.getXRot(), true));
                    positioned = true;
                } else if (packet instanceof ClientboundKeepAlivePacket k) {
                    keepAlive.incrementAndGet();
                    net.send(new ServerboundKeepAlivePacket(k.getId()));
                } else if (packet instanceof ClientboundPingPacket ping)
                    net.send(new ServerboundPongPacket(ping.getId()));
                else if (packet instanceof ClientboundLevelChunkWithLightPacket)
                    chunks.incrementAndGet();
                else if (packet instanceof ClientboundDisconnectPacket dc) {
                    closed = true;
                    net.disconnect(dc.getReason());
                }
            }
            if (method.getReturnType() == boolean.class) return !closed && net.isConnected();
            if (method.getReturnType() == Connection.class) return net;
            return null;
        }

        void movement() {
            if (!joined || !positioned || !net.isConnected()) return;
            phase += .31;
            net.send(new ServerboundMovePlayerPacket.Pos(x + Math.sin(phase) * .18, y, z, true));
            move.incrementAndGet();
        }
    }

    private static void observeTransport(Peer peer, String name) {
        if (SOAK && peer.net.channel() != null) {
            peer.net.channel().pipeline().addBefore("packet_handler", "fleet_diagnostics", new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                    LogUtils.getLogger().warn("{} transport error (joined={}, positioned={})", name, peer.joined, peer.positioned, failure);
                    context.fireExceptionCaught(failure);
                }

                @Override
                public void channelInactive(ChannelHandlerContext context) {
                    LogUtils.getLogger().info("{} transport closed (joined={}, positioned={})", name, peer.joined, peer.positioned);
                    context.fireChannelInactive();
                }
            });
        }
    }

    private static void connect(Minecraft mc, int i) throws Exception {
        // Keep the connection on loopback and cap the total number of participants at
        // thirty.
        String name = "FleetQA" + String.format(Locale.ROOT, "%02d", i);
        var net =
                Connection.connectToServer(
                        new InetSocketAddress(
                                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), PORT),
                        false);
        var peer = new Peer(i, net);
        peers.add(peer);
        var data = new ServerData("Local fleet benchmark", "127.0.0.1:" + PORT, true);
        net.setListener(
                new ClientHandshakePacketListenerImpl(
                        net, mc, data, mc.screen, false, Duration.ZERO, unused -> {}) {
                    @Override
                    public void handleGameProfile(ClientboundGameProfilePacket welcome) {
                        // The channel is live when the remote game profile arrives.
                        observeTransport(peer, name);
                        super.handleGameProfile(welcome);
                        var play =
                                (ClientGamePacketListener)
                                        Proxy.newProxyInstance(
                                                ClientGamePacketListener.class.getClassLoader(),
                                                new Class<?>[] {ClientGamePacketListener.class},
                                                peer::onPacket);
                        net.setListener(play);
                    }

                    @Override
                    public void onDisconnect(Component reason) {
                        peer.closed = true;
                    }
                });
        net.send(new ClientIntentionPacket("127.0.0.1", PORT, ConnectionProtocol.LOGIN));
        net.send(
                new ServerboundHelloPacket(
                        name,
                        Optional.of(
                                UUID.nameUUIDFromBytes(
                                        ("OfflinePlayer:" + name)
                                                .getBytes(StandardCharsets.UTF_8)))));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END
                || failed
                || (!SOAK && !Files.exists(Path.of("starbridge-fleet.fixture")))
                || !Files.exists(REQUEST)) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
        ++tick;
        for (var peer : peers) {
            if (peer.net.isConnected()) {
                peer.net.tick();
                if ((tick + peer.index) % 5 == 0) peer.movement();
            }
        }
        int wanted = Math.max(0, Math.min(30, Integer.parseInt(Files.readString(REQUEST).trim())));
        long live = peers.stream().filter(p -> p.joined && p.net.isConnected()).count();
        if (tick % (SOAK ? 120 : 20) == 0) {
            List<Object> results = new ArrayList<>();
            for (var p : peers)
                results.add(
                        Map.of(
                                "name",
                                p.index,
                                "joined",
                                p.joined,
                                "alive",
                                p.net.isConnected(),
                                "positioned",
                                p.positioned,
                                "chunks",
                                p.chunks.get(),
                                "packets",
                                p.packets.get(),
                                "movement",
                                p.move.get(),
                                "keepalives",
                                p.keepAlive.get()));
            Files.writeString(
                    Path.of("peer-counts.json"), new GsonBuilder().create().toJson(results));
        }
        for (int idx = 0; idx < peers.size(); idx++) {
            var p = peers.get(idx);
            boolean expired = !p.joined && System.nanoTime() - p.started > 60_000_000_000L;
            if (!p.closed && p.net.isConnected() && !expired) continue;
            Component reason = p.net.getDisconnectedReason();
            String why = String.valueOf(reason);
            boolean timedOut = expired || (reason != null && reason.getContents() instanceof
                    net.minecraft.network.chat.contents.TranslatableContents message && "disconnect.timeout".equals(message.getKey()));
            boolean retry = SOAK || WIDE ? loginRetries.getOrDefault(p.index, 0) < 6 : entranceRetries < 3;
            if ((SOAK || Files.isRegularFile(Path.of("starbridge-native-fleet.fixture")))
                    && !p.joined
                    && idx == peers.size() - 1
                    && retry && (!WIDE || timedOut)) {
                if (SOAK || WIDE) loginRetries.merge(p.index, 1, Integer::sum);
                else entranceRetries++;
                if (p.net.isConnected())
                    p.net.disconnect(Component.literal("Local fixture login timed out"));
                peers.remove(idx);
                next = tick + 80;
                Files.writeString(
                        Path.of("native-peer-retry.log"),
                        p.index + " " + why + "\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                return;
            }
            failed = true;
            Files.writeString(
                    Path.of("fleet-peer-error"),
                    "Local Forge client "
                            + p.index
                            + " closed; joined="
                            + p.joined
                            + ", reason="
                            + why);
            return;
        }
        if (peers.size() < wanted && live == peers.size() && tick >= next) {
            try {
                connect(mc, peers.size() + 1);
                next = tick + (SOAK ? 30 : 6);
            } catch (Exception problem) {
                failed = true;
                Files.writeString(
                        Path.of("fleet-peer-error"),
                        "Local connection failed: "
                                + problem.getClass().getName()
                                + " "
                                + problem.getMessage());
            }
        }
    }
}
