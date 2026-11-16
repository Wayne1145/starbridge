package dev.starbridge.testing;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.server.AgentHttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Real loopback HTTP requests against a disposable game's actual authorization and loaded-core state. */
@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class AgentBridgeGameTests {
    private AgentBridgeGameTests() {}

    @GameTest(template = "empty", batch = "starbridge_agent_http", timeoutTicks = 1200)
    public static void agentHttpHonorsAuthenticationAndPerCoreRevocation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server instanceof GameTestServer, "HTTP integration tests require the disposable GameTest server");
        helper.assertTrue(!AgentHttpServer.running(), "HTTP integration test must not replace an existing bridge");
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(position, Starbridge.SHIP_CORE.get().defaultBlockState());
        ShipCoreBlockEntity core = (ShipCoreBlockEntity) helper.getLevel().getBlockEntity(position);
        helper.assertTrue(core != null, "A real flight core block entity must be present");
        core.setOwner(UUID.randomUUID());
        helper.assertTrue(core.addFuel(new ItemStack(Items.COAL), false), "HTTP fixture must have real fuel");
        core.register();
        helper.assertTrue(!core.agentAllowed(), "A new core must start with agent access disabled");
        UUID coreId = core.id();
        int initialEnergy = core.energy();
        boolean physicsInstalled = ModList.get().isLoaded("valkyrienskies");

        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "starbridge-http-gametest");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                core.setAgentAllowed(false);
                core.release();
                AgentHttpServer.stop();
            }
        };

        final String token;
        try {
            AgentHttpServer.start(server);
            token = Files.readString(FMLPaths.CONFIGDIR.get().resolve("starbridge-token.txt"), StandardCharsets.UTF_8).trim();
            helper.assertTrue(token.matches("[a-f0-9]{64}"), "Game bridge must generate a valid token file");
        } catch (Exception startupFailure) {
            cleanup.run();
            worker.shutdownNow();
            helper.fail("HTTP integration fixture could not start the local bridge or read its token file");
            return;
        }

        CompletableFuture<Void> checks = CompletableFuture.runAsync(() -> {
            try {
                expect(request("GET", "/v1/ships", null, null), 401, "Missing credentials must be rejected");
                check(originStatus(token) == 403, "A browser Origin must be rejected even with a valid token");

                Response hiddenList = request("GET", "/v1/ships", token, null);
                expect(hiddenList, 200, "Authenticated listing should succeed");
                check(!containsCore(hiddenList.body, coreId), "An unauthorized core must not appear in the list");
                expect(request("GET", "/v1/ships/" + coreId, token, null), 404,
                        "An unauthorized core must not expose its status");

                onServer(server, () -> { core.setAgentAllowed(true); return null; });
                Response visibleList = request("GET", "/v1/ships", token, null);
                expect(visibleList, 200, "Authorized listing should succeed");
                check(containsCore(visibleList.body, coreId), "Explicitly authorized core must appear in the list");
                Response status = request("GET", "/v1/ships/" + coreId, token, null);
                expect(status, 200, "Authorized status query should succeed");
                check(status.body.isJsonObject() && coreId.toString().equals(status.body.getAsJsonObject().get("id").getAsString()),
                        "HTTP status must identify the actual registered core");
                check(status.body.getAsJsonObject().get("energy").getAsInt() == initialEnergy,
                        "Reading the core must not consume or fabricate energy");

                // No ship is assembled. Verify refusal rather than issuing a real flight command.
                Response refused = request("POST", "/v1/ships/" + coreId + "/navigate", token,
                        "{\"x\":0,\"y\":100,\"z\":0}");
                expect(refused, physicsInstalled ? 409 : 503,
                        "Navigation must reject an unassembled core or absent physical backend");
                check(onServer(server, core::energy) == initialEnergy,
                        "Rejected navigation must not consume energy");

                onServer(server, () -> { core.setAgentAllowed(false); return null; });
                expect(request("GET", "/v1/ships/" + coreId, token, null), 404,
                        "Revocation must immediately remove status access");
                Response revokedList = request("GET", "/v1/ships", token, null);
                expect(revokedList, 200, "Listing should remain available after revocation");
                check(!containsCore(revokedList.body, coreId), "Revoked core must disappear from the list");
                check(onServer(server, core::energy) == initialEnergy,
                        "Authentication checks must leave the fixture energy unchanged");
            } catch (Exception failure) {
                // Do not report URLs, HTTP payloads, token-bearing headers or arbitrary exception text.
                String detail = failure instanceof CheckFailure ? failure.getMessage() : "Local HTTP request failed without retry";
                throw new CompletionException(new CheckFailure(detail));
            } finally {
                try { onServer(server, () -> { cleanup.run(); return null; }); }
                catch (Exception cleanupDelayed) { server.execute(cleanup); }
            }
        }, worker);

        helper.runAtTickTime(1190, () -> {
            cleanup.run();
            checks.cancel(false);
            worker.shutdownNow();
        });
        helper.succeedWhen(() -> {
            // GameTest advances ticks without the normal 50ms pause. Give actual socket/server
            // workers a small wall-clock budget while keeping the game thread free to run their tasks.
            if (!checks.isDone()) LockSupport.parkNanos(5_000_000L);
            helper.assertTrue(checks.isDone(), "Waiting for real loopback HTTP authorization checks");
            cleanup.run();
            worker.shutdownNow();
            try { checks.join(); }
            catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                helper.fail(cause instanceof CheckFailure ? cause.getMessage() : "HTTP integration request failed");
            } catch (java.util.concurrent.CancellationException cancelled) {
                helper.fail("HTTP integration test timed out");
            }
            helper.assertTrue(!AgentHttpServer.running() && !core.agentAllowed(), "Test cleanup must close the bridge and revoke access");
            Starbridge.LOGGER.info("STARBRIDGE_AGENT_HTTP_PASS authentication, opt-in, status, rejection and revocation");
        });
    }

    private static <T> T onServer(MinecraftServer server, Supplier<T> action) throws Exception {
        return server.submit(action).get(2, TimeUnit.SECONDS);
    }

    private static Response request(String method, String path, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:18765" + path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "close");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                check(input != null, "Game API must return a JSON response body");
                byte[] bytes = input.readNBytes(65_537);
                check(bytes.length <= 65_536, "Test HTTP response exceeded its bound");
                String response = new String(bytes, StandardCharsets.UTF_8);
                check(token == null || !response.contains(token), "Game API must never echo its bearer token");
                return new Response(status, JsonParser.parseString(response));
            }
        } finally { connection.disconnect(); }
    }

    private static int originStatus(String token) throws Exception {
        // HttpURLConnection intentionally suppresses Origin. A single direct loopback socket
        // verifies the actual wire header without changing JVM-wide restricted-header settings.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 18765), 2_000);
            socket.setSoTimeout(2_000);
            String request = "GET /v1/ships HTTP/1.1\r\nHost: 127.0.0.1:18765\r\n"
                    + "Origin: https://untrusted.invalid\r\nAuthorization: Bearer " + token
                    + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String status = input.readLine();
            check(status != null && status.startsWith("HTTP/1.1 "), "Origin test must receive an HTTP status line");
            return Integer.parseInt(status.split(" ", 3)[1]);
        }
    }

    private static boolean containsCore(JsonElement body, UUID coreId) {
        check(body.isJsonArray(), "Ship listing must be a JSON array");
        for (JsonElement ship : body.getAsJsonArray()) {
            if (ship.isJsonObject() && ship.getAsJsonObject().has("id")
                    && coreId.toString().equals(ship.getAsJsonObject().get("id").getAsString())) return true;
        }
        return false;
    }

    private static void expect(Response response, int expected, String message) {
        check(response.status == expected, message + " (expected " + expected + ", received " + response.status + ")");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new CheckFailure(message); }
    private record Response(int status, JsonElement body) {}
    private static final class CheckFailure extends RuntimeException {
        CheckFailure(String message) { super(message); }
    }
}
