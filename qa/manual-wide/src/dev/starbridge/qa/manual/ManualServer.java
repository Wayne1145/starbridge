package dev.starbridge.qa.manual;

import com.google.gson.Gson;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;

import net.minecraft.core.BlockPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Read the server flight state independently of the pilot's key and button log. */
@Mod.EventBusSubscriber(modid = "starbridgewideqa")
public final class ManualServer {
    private static final boolean HOST = Files.isRegularFile(Path.of("wide-fleet-host.fixture"));
    private static final Gson JSON = new Gson();
    private static UUID ship;

    @SubscribeEvent
    public static void observe(TickEvent.ServerTickEvent event) throws Exception {
        var server = event.getServer();
        int tick = server.getTickCount();
        if (!HOST || event.phase != TickEvent.Phase.END || tick % 4 != 0) return;
        if (ship == null) {
            Path status = Path.of("fleet-flight-start");
            if (!Files.isRegularFile(status)) return;
            String[] fields = Files.readString(status).trim().split(" +");
            var bridge =
                    new BlockPos(
                            Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2]),
                            Integer.parseInt(fields[3]));
            if (!(server.overworld().getBlockEntity(bridge) instanceof ShipCoreBlockEntity core))
                return;
            ship = core.id();
        }
        var core = CoreRegistry.find(ship).orElse(null);
        if (core == null) return;
        var row = new LinkedHashMap<>(OrbitModule.status(core));
        if (!Boolean.TRUE.equals(row.get("active"))) return;
        row.put("tick", tick);
        row.put("core", ship);
        row.put("rocket", core.originalRocket());
        if (Boolean.getBoolean("starbridge.qa.manualBoundary")) {
            row.put("energy", core.energy());
            row.put("pendingPassage", dev.starbridge.transit.HullTransit.inProgress(server, ship));
            var state = core.saveWithoutMetadata();
            row.put(
                    "paidPassage",
                    state.hasUUID("PaidVoyage") ? state.getUUID("PaidVoyage").toString() : "");
        }
        Files.writeString(
                Path.of("manual-server.jsonl"),
                JSON.toJson(row) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
