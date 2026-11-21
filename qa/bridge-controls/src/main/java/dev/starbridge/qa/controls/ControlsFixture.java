package dev.starbridge.qa.controls;

import com.google.gson.GsonBuilder;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.AgentHttpServer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod("starbridgecontrolsqa")
@Mod.EventBusSubscriber(modid = "starbridgecontrolsqa")
public final class ControlsFixture {
    static final BlockPos CORE = new BlockPos(0, 123, 0), COMPUTER = CORE.east();
    static final Map<String, Object> report = new LinkedHashMap<>();
    static Path disk;
    static int id, join;
    static boolean done, sawCruise, manual, moving;

    private static void signal(String file) throws Exception {
        Path p = disk.resolve(file);
        if (!Files.exists(p)) Files.writeString(p, "1");
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) throws Exception {
        if (e.phase != TickEvent.Phase.END
                || done
                || !Files.exists(Path.of("starbridge-controls.fixture"))) return;
        var server = e.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        int t = server.getTickCount();
        if (join == 0) join = t;
        if (t - join < 65) return;
        var p = server.getPlayerList().getPlayers().get(0);
        var world = server.overworld();
        try {
            if (disk == null) {
                for (var cell :
                        BlockPos.betweenClosed(CORE.offset(-2, -1, -2), CORE.offset(3, -1, 3)))
                    world.setBlockAndUpdate(cell, Blocks.IRON_BLOCK.defaultBlockState());
                world.setBlockAndUpdate(CORE, Starbridge.SHIP_CORE.get().defaultBlockState());
                var core = (ShipCoreBlockEntity) world.getBlockEntity(CORE);
                core.setOwner(p.getUUID());
                core.bindOriginalRocket(UUID.randomUUID());
                OrbitModule.bindCabin(core, CORE.offset(-2, -1, -2), CORE.offset(3, 2, 3));
                var charge =
                        core.getCapability(ForgeCapabilities.ENERGY)
                                .orElseThrow(IllegalStateException::new);
                for (int i = 0; i < 200; i++) charge.receiveEnergy(10000, false);
                core.register();
                var computer =
                        ForgeRegistries.BLOCKS.getValue(
                                new ResourceLocation("computercraft", "computer_advanced"));
                world.setBlockAndUpdate(COMPUTER, computer.defaultBlockState());
                var tile = world.getBlockEntity(COMPUTER);
                Object machine = tile.getClass().getMethod("createServerComputer").invoke(tile);
                id = (int) tile.getClass().getMethod("getComputerID").invoke(tile);
                disk =
                        server.getWorldPath(LevelResource.ROOT)
                                .resolve("computercraft/computer")
                                .resolve(Integer.toString(id));
                Files.createDirectories(disk);
                try (var lua = ControlsFixture.class.getResourceAsStream("/flight-startup.lua")) {
                    Files.copy(Objects.requireNonNull(lua), disk.resolve("startup.lua"));
                }
                machine.getClass().getMethod("turnOn").invoke(machine);
                p.teleportTo(world, .5, 123, 2.5, 180, 12);
                Path token = AgentHttpServer.start(server);
                report.put("core", core.id().toString());
                report.put("computer", id);
                report.put("actualBlock", ForgeRegistries.BLOCKS.getKey(computer).toString());
                report.put("ownerNonOp", !server.getPlayerList().isOp(p.getGameProfile()));
                report.put("survival", p.gameMode.getGameModeForPlayer().getName());
                Files.writeString(
                        Path.of("controls-ticket.json"), new GsonBuilder().create().toJson(report));
                return;
            }
            if (t % 3 != 0) return;
            var core = (ShipCoreBlockEntity) world.getBlockEntity(CORE);
            var state = OrbitModule.status(core);
            if (core.agentAllowed()
                    && core.pairedComputer() == id
                    && Files.exists(Path.of("mcp-before-launch.ok"))) signal("paired.signal");
            if (OrbitModule.active(core)) {
                sawCruise |= Boolean.TRUE.equals(state.get("cruise"));
                Object speed = state.get("speed");
                moving |= speed instanceof Number num && num.doubleValue() > .5;
                if (OrbitModule.manualClaimed(core)) {
                    manual = true;
                    signal("takeover.signal");
                }
                if (manual
                        && !OrbitModule.manualClaimed(core)
                        && Files.exists(disk.resolve("manual-denied.txt"))
                        && Files.exists(Path.of("mcp-denied.ok"))) signal("released.signal");
            }
            report.put("observedCruise", sawCruise);
            report.put("moving", moving);
            report.put("manualClaim", manual);
            report.put("status", state);
            for (String entry :
                    List.of(
                            "boot",
                            "unauthorized",
                            "chart",
                            "departed",
                            "manual-denied",
                            "braked",
                            "error"))
                if (Files.exists(disk.resolve(entry + ".txt")))
                    report.put(entry, Files.readString(disk.resolve(entry + ".txt")));
            if (report.containsKey("manual-denied")
                    && !Files.exists(Path.of("controls-lua-denied.ok")))
                Files.writeString(
                        Path.of("controls-lua-denied.ok"),
                        "real peripheral denied while pilot held the helm");
            boolean pass =
                    sawCruise
                            && moving
                            && manual
                            && report.containsKey("braked")
                            && Files.exists(Path.of("mcp-complete.ok"));
            if (pass || report.containsKey("error") || t - join > 2500) {
                report.put("pass", pass);
                done = true;
            }
        } catch (Exception err) {
            report.put("error", err.toString() + " / " + err.getCause());
            report.put("pass", false);
            done = true;
        }
        if (done || t % 30 == 0)
            Files.writeString(
                    Path.of(done ? "controls-result.json" : "controls-live.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
}
