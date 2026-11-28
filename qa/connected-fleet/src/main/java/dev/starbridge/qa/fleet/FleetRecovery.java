package dev.starbridge.qa.fleet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only checks after reopening an isolated copy of the failed home transfer save. */
@Mod.EventBusSubscriber(modid = "starbridgefleetqa")
public final class FleetRecovery {
    private static JsonObject proof;
    private static int counted;
    private static boolean done;

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (done
                || event.phase != TickEvent.Phase.END
                || !event.getServer().isDedicatedServer()
                || !Files.isRegularFile(Path.of("starbridge-fleet-recovery.fixture"))) return;
        var s = event.getServer();
        if (proof == null)
            proof =
                    JsonParser.parseString(Files.readString(Path.of("crash-proof.json")))
                            .getAsJsonObject();
        if (s.getPort() != 25891 || !s.getLocalIp().equals("127.0.0.1"))
            throw new IllegalStateException("Recovery requires the isolated local port");
        var p = s.getPlayerList().getPlayerByName("FleetCaptain");
        if (p == null) return;
        ++counted;
        if (counted % 20 != 0) return;
        UUID id = UUID.fromString(proof.get("core").getAsString()),
                rocket = UUID.fromString(proof.get("rocket").getAsString()),
                receipt = UUID.fromString(proof.get("leg").getAsString());
        var core = CoreRegistry.find(id).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pilotOnlineTicks", counted);
        out.put("pilotAlive", p.isAlive());
        out.put("pilotDimension", p.serverLevel().dimension().location().toString());
        out.put("pilotPosition", p.position().toString());
        out.put("pending", HullTransit.inProgress(s, id));
        boolean pass = false;
        if (core != null && ((BlockEntity) core).getLevel() instanceof ServerLevel level) {
            var pos = ((BlockEntity) core).getBlockPos();
            var tag = ((BlockEntity) core).saveWithoutMetadata();
            boolean paid = tag.hasUUID("PaidVoyage") && tag.getUUID("PaidVoyage").equals(receipt);
            boolean aboard = HullTransit.isAboard(p, core);
            out.put("core", core.id().toString());
            out.put("rocket", String.valueOf(core.originalRocket()));
            out.put("fareReceiptMatchesPendingLeg", paid);
            out.put("energy", core.energy());
            out.put("aboard", aboard);
            out.put("coreDimension", level.dimension().location().toString());
            int diamonds = 0, ore = 0;
            for (int rel : new int[] {1, 26, 51, 76, 98}) {
                var chestPos = pos.offset(0, -3, rel - 2);
                if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity box)
                    diamonds += box.getItem(0).getCount();
                if (level.getBlockEntity(chestPos.offset(2, 0, 0))
                        instanceof AbstractFurnaceBlockEntity f)
                    ore += f.getItem(0).getCount() + f.getItem(2).getCount();
            }
            out.put("diamonds", diamonds);
            out.put("furnaceInputsAndOutputs", ore);
            pass =
                    aboard
                            && p.isAlive()
                            && level == s.overworld()
                            && rocket.equals(core.originalRocket())
                            && paid
                            && core.energy() == proof.get("expectedEnergy").getAsInt()
                            && !HullTransit.inProgress(s, id)
                            && diamonds == 85
                            && ore == 40;
        }
        out.put("passed", pass);
        Files.writeString(
                Path.of("recovery-live.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(out));
        if (pass || counted >= 600) {
            done = true;
            Files.writeString(
                    Path.of("fleet-recovery-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(out));
            s.halt(false);
        }
    }
}
