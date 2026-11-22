package dev.starbridge.qa.headless;

import com.google.gson.Gson;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

/** Single loopback login using Forge's unmodified negotiation handlers in its server distribution. */
@Mod("starbridgeheadlessdiag")
@Mod.EventBusSubscriber(modid = "starbridgeheadlessdiag", value = Dist.DEDICATED_SERVER)
public final class DedicatedLoginProbe implements ClientLoginPacketListener {
    private static final Path MARKER = Path.of("try-stock-login.fixture");
    private static boolean attempted;
    private static final String NAME = "SBHeadlessProbe";
    private final Connection connection;
    private final Map<String, Object> record = new LinkedHashMap<>();
    private int received;
    private boolean completed;

    private DedicatedLoginProbe(Connection connection) { this.connection = connection; }
    public DedicatedLoginProbe() { this.connection = null; } // Forge's mod container.

    @SubscribeEvent
    public static void start(TickEvent.ServerTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || attempted || event.getServer().getTickCount() < 100) return;
        attempted = true;
        if (!Files.isRegularFile(MARKER)) return;
        var server = event.getServer();
        if (!server.isDedicatedServer() || !"127.0.0.1".equals(server.getLocalIp()) || server.getPort() != 26942)
            throw new IllegalStateException("The headless probe uses the isolated loopback server on port 26942");
        int tick = server.getTickCount();
        // This endpoint is a logical client using the common network library on a physical server.
        Thread endpoint = new Thread(net.minecraftforge.fml.util.thread.SidedThreadGroups.CLIENT, () -> {
            try { open(tick); }
            catch (Exception | LinkageError problem) {
                DedicatedLoginProbe failed = new DedicatedLoginProbe();
                failed.record.put("end", "Dedicated transport could not open: " + problem);
                failed.save();
            }
        }, "Starbridge headless loopback endpoint");
        endpoint.setDaemon(true);
        endpoint.start();
    }

    private static void open(int tick) throws Exception {
        var network = Connection.connectToServer(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 26942), false);
        var probe = new DedicatedLoginProbe(network);
        probe.record.put("dedicatedServer", true);
        probe.record.put("awtHeadless", System.getProperty("java.awt.headless"));
        probe.record.put("serverTick", tick);
        probe.save();
        network.setListener(probe);
        network.send(new ClientIntentionPacket("127.0.0.1", 26942, ConnectionProtocol.LOGIN));
        UUID identity = UUID.nameUUIDFromBytes(("OfflinePlayer:" + NAME).getBytes(StandardCharsets.UTF_8));
        network.send(new ServerboundHelloPacket(NAME, Optional.of(identity)));
    }

    private void save() {
        try {
            var temp = Path.of("stock-login.tmp");
            Files.writeString(temp, new Gson().toJson(record));
            Files.move(temp, Path.of("stock-login.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private synchronized void finish(String reason) {
        if (completed) return;
        completed = true;
        record.put("end", reason);
        save();
        connection.disconnect(Component.literal("Dedicated login diagnostic finished"));
    }

    @Override public void handleCustomQuery(ClientboundCustomQueryPacket packet) {
        record.put("forgeHandshakeRegistered", connection.channel().attr(io.netty.util.AttributeKey.valueOf("fml:handshake")).get() != null);
        record.put("logicalSide", net.minecraftforge.fml.util.thread.EffectiveSide.get().name());
        record.put("expectedSide", packet.getDirection().getReceptionSide().name());
        record.put("lastQuery", packet.getIdentifier().toString());
        record.put("customQueries", ++received);
        try {
            if (!NetworkHooks.onCustomPayload(packet, connection)) {
                // Match the vanilla/Forge listener: an unhandled query is declined, never acknowledged.
                record.put("lastDeclined", received);
                save();
                connection.send(new ServerboundCustomQueryPacket(packet.getTransactionId(), null));
            } else {
                record.put("handledQueries", received);
                save();
            }
        } catch (RuntimeException | LinkageError error) {
            record.put("exception", error.getClass().getName());
            record.put("message", String.valueOf(error.getMessage()));
            record.put("stack", java.util.Arrays.stream(error.getStackTrace()).limit(12).map(Object::toString).toList());
            finish("Stock Forge handler failed on the dedicated runtime");
        }
    }
    @Override public void handleHello(ClientboundHelloPacket packet) { finish("Authenticated server outside this local fixture"); }
    @Override public void handleGameProfile(ClientboundGameProfilePacket packet) { finish("Login accepted. Play protocol was not measured by this diagnostic"); }
    @Override public void handleDisconnect(ClientboundLoginDisconnectPacket packet) { finish(packet.getReason().getString()); }
    @Override public void onDisconnect(Component reason) { finish(reason.getString()); }
    @Override public void handleCompression(ClientboundLoginCompressionPacket packet) { connection.setupCompression(packet.getCompressionThreshold(), false); }
    @Override public boolean isAcceptingMessages() { return connection.isConnected(); }
}
