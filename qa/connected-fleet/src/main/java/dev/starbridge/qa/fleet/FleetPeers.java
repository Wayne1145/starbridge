package dev.starbridge.qa.fleet;

import com.google.gson.GsonBuilder;

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
    private static final int PORT = Files.isRegularFile(Path.of("starbridge-native-fleet.fixture")) ? 25891 : 25890;
    private static final Path REQUEST = Path.of("../server/fleet-peer-request.txt");
    private static final List<Peer> peers = new ArrayList<>();
    private static int tick, next;
    private static boolean failed;

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

    private static void connect(Minecraft mc, int i) throws Exception {
        // This fixture accepts no host or port input and caps the total number of participants at
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
                || !Files.exists(Path.of("starbridge-fleet.fixture"))
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
        if (tick % 20 == 0) {
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
        if (peers.stream()
                .anyMatch(
                        p ->
                                p.closed
                                        || !p.net.isConnected()
                                        || (!p.joined
                                                && System.nanoTime() - p.started
                                                        > 45_000_000_000L))) {
            failed = true;
            Files.writeString(
                    Path.of("fleet-peer-error"),
                    "A bounded Forge client login failed. See the server and Forge client logs.");
            return;
        }
        if (peers.size() < wanted && live == peers.size() && tick >= next) {
            try {
                connect(mc, peers.size() + 1);
                next = tick + 6;
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
