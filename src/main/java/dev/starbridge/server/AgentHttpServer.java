package dev.starbridge.server;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.flight.Vec3d;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Opt-in loopback bridge. Only explicitly authorized, already loaded cores are exposed. */
public final class AgentHttpServer {
    private static final Gson JSON = new Gson();
    private static final int PORT = 18765;
    private static final long REQUEST_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static HttpServer http;
    private static ExecutorService executor;
    private static Session session;
    private AgentHttpServer() {}

    public static synchronized Path start(MinecraftServer server) throws IOException {
        Path tokenPath = FMLPaths.CONFIGDIR.get().resolve("starbridge-token.txt");
        if (http != null) return tokenPath.toAbsolutePath();
        Files.createDirectories(tokenPath.getParent());
        String token;
        if (Files.exists(tokenPath)) {
            byte[] bytes;
            try (var input = Files.newInputStream(tokenPath)) { bytes = input.readNBytes(129); }
            if (bytes.length > 128) throw new IOException("Invalid bridge token file");
            token = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!token.matches("[a-f0-9]{64}")) throw new IOException("Invalid bridge token file; expected a 64-character hexadecimal token");
        } else {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            token = HexFormat.of().formatHex(bytes);
            Files.writeString(tokenPath, token + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
        ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), runnable -> daemon(runnable, "starbridge-agent-http"),
                new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor timeouts = new ScheduledThreadPoolExecutor(1,
                runnable -> daemon(runnable, "starbridge-agent-timeout"));
        timeouts.setRemoveOnCancelPolicy(true);
        Session createdSession = new Session(server, ("Bearer " + token).getBytes(StandardCharsets.UTF_8), timeouts);
        try {
            created.setExecutor(workers);
            created.createContext("/v1/ships", exchange -> handle(exchange, createdSession));
            created.start();
            executor = workers;
            session = createdSession;
            http = created;
        } catch (RuntimeException failure) {
            createdSession.open.set(false);
            created.stop(0);
            workers.shutdownNow();
            timeouts.shutdownNow();
            throw new IOException("Could not start the local agent bridge", failure);
        }
        return tokenPath.toAbsolutePath();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public static synchronized boolean running() { return http != null; }
    public static synchronized void stop() {
        if (session != null) {
            session.open.set(false);
            session.pending.forEach(RequestGate::cancel);
            session.timeouts.shutdownNow();
        }
        if (http != null) http.stop(0);
        if (executor != null) executor.shutdownNow();
        session = null;
        http = null;
        executor = null;
    }

    private static void handle(HttpExchange exchange, Session context) {
        RequestGate gate = new RequestGate(System::nanoTime, REQUEST_NANOS, context.open::get);
        context.pending.add(gate);
        ScheduledFuture<?> timeout = null;
        CompletableFuture<Object> task = null;
        try {
            if (!context.open.get()) throw new HttpProblem(503, "Bridge is shutting down");
            // Closing the exchange also unblocks a client which stalls while streaming a request body.
            timeout = context.timeouts.schedule(() -> {
                gate.cancel();
                exchange.close();
            }, REQUEST_NANOS, TimeUnit.NANOSECONDS);
            if (exchange.getRequestHeaders().containsKey("Origin")) throw new HttpProblem(403, "Browser origins are not allowed");
            var headers = exchange.getRequestHeaders().get("Authorization");
            if (headers == null || headers.size() != 1
                    || !MessageDigest.isEqual(context.expected, headers.get(0).getBytes(StandardCharsets.UTF_8))) {
                throw new HttpProblem(401, "Unauthorized");
            }
            Route route = parseRoute(exchange);
            task = context.server.submit(() -> gate.execute(() -> dispatch(route)));
            long remaining = gate.remainingNanos();
            if (remaining <= 0) throw new TimeoutException();
            respond(exchange, 200, task.get(remaining, TimeUnit.NANOSECONDS));
        } catch (HttpProblem failure) {
            respond(exchange, failure.status, Map.of("error", failure.getMessage()));
        } catch (TimeoutException timeoutFailure) {
            gate.cancel();
            if (task != null) task.cancel(false);
            respond(exchange, 503, Map.of("error", "Request expired; query ship status before retrying"));
        } catch (InterruptedException interrupted) {
            gate.cancel();
            if (task != null) task.cancel(false);
            Thread.currentThread().interrupt();
            respond(exchange, 503, Map.of("error", "Bridge shutting down"));
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpProblem known) respond(exchange, known.status, Map.of("error", known.getMessage()));
            else if (cause instanceof RequestExpiredException) respond(exchange, 503, Map.of("error", "Request expired before execution"));
            else respond(exchange, 503, Map.of("error", "Flight operation failed; inspect the core in game"));
        } catch (RejectedExecutionException shuttingDown) {
            respond(exchange, 503, Map.of("error", "Bridge is unavailable"));
        } catch (RuntimeException | IOException invalid) {
            // Never return raw exception messages, request content, headers or backend stack traces.
            respond(exchange, 400, Map.of("error", "Invalid request"));
        } finally {
            gate.cancel();
            if (timeout != null) timeout.cancel(false);
            context.pending.remove(gate);
            exchange.close();
        }
    }

    private static Route parseRoute(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getRawQuery() != null) throw new HttpProblem(400, "Query strings are not supported");
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        String[] pieces = path.split("/", -1);
        if (pieces.length < 3 || !pieces[1].equals("v1") || !pieces[2].equals("ships")) throw new HttpProblem(404, "Unknown route");
        boolean list = pieces.length == 3 && method.equals("GET");
        boolean read = pieces.length == 4 && method.equals("GET");
        boolean write = pieces.length == 5 && method.equals("POST") && Set.of("navigate", "hold", "stop").contains(pieces[4]);
        boolean chart = pieces.length == 5 && method.equals("GET") && pieces[4].equals("chart");
        boolean voyage = pieces.length == 6 && method.equals("POST") && pieces[4].equals("voyage");
        String destination = voyage ? pieces[5] : null;
        if (voyage && !dev.starbridge.compat.galacticraft.CelestialPort.validKey(destination)) throw new HttpProblem(400, "Invalid destination");
        if (!list && !read && !write && !chart && !voyage) throw new HttpProblem(404, "Unknown route or method");
        UUID id = null;
        if (!list) {
            id = UUID.fromString(pieces[3]);
            if (!id.toString().equalsIgnoreCase(pieces[3])) throw new HttpProblem(400, "Invalid ship UUID");
        }
        Vec3d target = null;
        if (write || voyage) {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json"))
                throw new HttpProblem(415, "Content-Type must be application/json");
            byte[] bytes = exchange.getRequestBody().readNBytes(4097);
            if (bytes.length > 4096) throw new HttpProblem(413, "Request body exceeds 4096 bytes");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            target = AgentRequestBody.parse(pieces[4], text);
        }
        return new Route(list, id, write || voyage || chart ? pieces[4] : null, target, destination);
    }

    private static Object dispatch(Route route) {
        if (route.list) return CoreRegistry.all().stream().filter(ShipCoreBlockEntity::agentAllowed).map(ShipCoreBlockEntity::status).toList();
        ShipCoreBlockEntity core = CoreRegistry.find(route.id).filter(ShipCoreBlockEntity::agentAllowed)
                .orElseThrow(() -> new HttpProblem(404, "Ship is unavailable or agent access is disabled"));
        try {
            if (route.action != null) switch (route.action) {
                case "chart" -> { return dev.starbridge.voyage.Voyages.chart(core); }
                case "voyage" -> {
                    if (dev.starbridge.network.PilotModule.isBeingPiloted(core.id())) throw new IllegalStateException("Manual pilot has priority");
                    dev.starbridge.voyage.Voyages.departRemote(core, route.destination);
                }
                case "navigate" -> core.navigateFromAgent(route.target);
                case "hold" -> core.holdFromAgent();
                case "stop" -> core.stopFromAgent();
                default -> throw new HttpProblem(404, "Unknown action");
            }
            return core.status();
        } catch (IllegalArgumentException invalid) {
            throw new HttpProblem(400, "Flight command failed validation");
        } catch (IllegalStateException conflict) {
            throw new HttpProblem(409, "Core rejected control; check pilot ownership, fuel and backend readiness in game");
        }
    }

    private static void respond(HttpExchange exchange, int status, Object body) {
        try {
            byte[] bytes = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException disconnected) { /* The game action never retries because a client disconnected. */ }
    }

    private record Route(boolean list, UUID id, String action, Vec3d target, String destination) {}
    private static final class HttpProblem extends RuntimeException {
        final int status;
        HttpProblem(int status, String message) { super(message); this.status = status; }
    }
    private static final class Session {
        final MinecraftServer server;
        final byte[] expected;
        final ScheduledThreadPoolExecutor timeouts;
        final AtomicBoolean open = new AtomicBoolean(true);
        final Set<RequestGate> pending = ConcurrentHashMap.newKeySet();
        Session(MinecraftServer server, byte[] expected, ScheduledThreadPoolExecutor timeouts) {
            this.server = server; this.expected = expected; this.timeouts = timeouts;
        }
    }
}

/** Linearizable cancellation: after cancel returns, no queued action can start or remain executing. */
final class RequestGate {
    private final LongSupplier clock;
    private final BooleanSupplier active;
    private final long deadline;
    private boolean cancelled;
    private boolean executed;

    RequestGate(LongSupplier clock, long timeoutNanos, BooleanSupplier active) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("Timeout must be positive");
        this.clock = clock;
        this.active = active;
        this.deadline = clock.getAsLong() + timeoutNanos;
    }

    synchronized <T> T execute(Supplier<T> operation) {
        if (cancelled || executed || !active.getAsBoolean() || clock.getAsLong() - deadline >= 0)
            throw new RequestExpiredException();
        executed = true;
        return operation.get();
    }

    synchronized void cancel() { cancelled = true; }
    long remainingNanos() { return Math.max(0, deadline - clock.getAsLong()); }
}

final class RequestExpiredException extends IllegalStateException {
    RequestExpiredException() { super("Request expired or was cancelled"); }
}

/** Parse only the tiny flat command schema; nested inputs never recurse through a generic JSON tree. */
final class AgentRequestBody {
    private AgentRequestBody() {}

    static Vec3d parse(String action, String text) {
        Set<String> required = action.equals("navigate") ? Set.of("x", "y", "z") : Set.of();
        Map<String, Double> values = new HashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!required.contains(name) || values.containsKey(name) || reader.peek() != JsonToken.NUMBER)
                    throw new IllegalArgumentException("Unexpected, duplicate or non-numeric field");
                double value = reader.nextDouble();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Coordinates must be finite");
                values.put(name, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !values.keySet().equals(required))
                throw new IllegalArgumentException("Missing fields or trailing JSON");
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Invalid JSON command", malformed);
        }
        return action.equals("navigate") ? new Vec3d(values.get("x"), values.get("y"), values.get("z")) : null;
    }
}
