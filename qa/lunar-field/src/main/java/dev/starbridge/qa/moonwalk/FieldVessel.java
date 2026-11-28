package dev.starbridge.qa.moonwalk;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VoyageLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.util.*;

/** The only supplied ship. Its identity, cargo and return receipt are witnessed on the server. */
final class FieldVessel {
    private static final Vec3 PORT = new Vec3(.5, 190, -.5);
    private static final UUID ROCKET = UUID.fromString("561f48be-405a-46ab-b452-4661891d07d1");
    private static final VsShipBackend VS = new VsShipBackend();
    private static UUID identity;
    private static BlockPos helm;
    private static int fare, beforeLanding = -1, settled;
    private static boolean returned, boarded, stowed;

    static void supply(ServerPlayer pilot, ServerLevel moon, BlockPos deck) throws Exception {
        FieldStart.require(VS.probe().available(), "Valkyrien Skies active");
        helm = deck.east().above();
        for (int x = -1; x <= 2; x++) for (int z = -1; z <= 1; z++)
            moon.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
        moon.setBlockAndUpdate(helm, Starbridge.SHIP_CORE.get().defaultBlockState());
        moon.setBlockAndUpdate(helm.east(), Blocks.BARREL.defaultBlockState());
        var core = (ShipCoreBlockEntity) moon.getBlockEntity(helm);
        var barrel = (BarrelBlockEntity) moon.getBlockEntity(helm.east());
        FieldStart.require(core != null && barrel != null, "Bridge and original cargo barrel");
        barrel.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 3));
        core.setOwner(pilot.getUUID());
        core.bindOriginalRocket(ROCKET);
        core.getCapability(ForgeCapabilities.ENERGY).resolve().orElseThrow().receiveEnergy(90000, false);
        core.register();
        OrbitModule.bindCabin(core, deck.offset(-1, 0, -1), deck.offset(2, 1, 1));
        identity = core.id();
        var server = moon.getServer();
        VoyageLog.get(server).rememberHome(identity, PORT);
        var ports = OriginalStarMap.ports(server);
        fare = ports.get("moon").fareTo(ports.get("home"));
        pilot.getPersistentData().putUUID("starbridge:selected_core", identity);
        FieldStart.write("field-vessel.json", Map.of("core",identity,"rocket",ROCKET,"pilot",pilot.getUUID(),
                "lunarHelm", List.of(helm.getX(),helm.getY(),helm.getZ()),"home",List.of(PORT.x,PORT.y,PORT.z),
                "startingFE",core.energy(),"fare",fare,"amethystShards",3));
    }

    static void resume(ServerPlayer pilot) throws Exception {
        var saved = FieldStart.read("field-vessel");
        identity = UUID.fromString(saved.get("core").getAsString());
        var pos = saved.getAsJsonArray("lunarHelm");
        helm = new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt());
        fare = saved.get("fare").getAsInt();
        boarded = Files.isRegularFile(Path.of("field-boarded.json"));
        stowed = Files.isRegularFile(Path.of("field-cargo.json"));
        FieldStart.require(pilot.getUUID().toString().equals(saved.get("pilot").getAsString())
                && ROCKET.toString().equals(saved.get("rocket").getAsString())
                && pilot.getPersistentData().getUUID("starbridge:selected_core").equals(identity),"Existing pilot and paired ship");
    }

    static int keys(Player player) {
        int count = 0;
        for (var stack : player.getInventory().items) {
            var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && "galacticraftcore:item.key".equals(id.toString())) count += stack.getCount();
        }
        return count;
    }

    static void observe(ServerPlayer pilot, ServerLevel moon) throws Exception {
        FieldTreasure.observe(pilot);
        if (returned || identity == null || !Files.isRegularFile(Path.of("field-reward.json"))) return;
        var server = moon.getServer();
        var core = CoreRegistry.find(identity).orElse(null);
        if (core == null) return;
        if (core.getLevel() == moon) {
            if (!boarded && pilot.serverLevel() == moon && FieldTreasure.carried(pilot)) {
                boolean physical = HullTransit.isAboard(pilot, core);
                if (physical || OrbitModule.aboard(pilot, core)) {
                    boarded = true;
                    FieldStart.write("field-boarded.json", Map.of("core", identity, "pilot", pilot.getUUID(),
                            "keys", keys(pilot), "tick", pilot.tickCount, "feet", List.of(pilot.getX(), pilot.getY(), pilot.getZ()),
                            "cabin", physical ? "physical" : "registered fixed deck"));
                }
            }
            if (boarded && !stowed && moon.getBlockEntity(core.getBlockPos().east()) instanceof BarrelBlockEntity barrel
                    && FieldTreasure.stowed(barrel,pilot)) {
                stowed=true;
                FieldStart.write("field-cargo.json",Map.of("core",identity,"player",pilot.getUUID(),"item",FieldTreasure.manifest(),"tick",pilot.tickCount));
            }
            if (OrbitModule.active(core) && !core.saveWithoutMetadata().hasUUID("PaidVoyage")) {
                var status = OrbitModule.status(core);
                if ("moon".equals(status.get("source")) && "home".equals(status.get("target"))
                        && Boolean.TRUE.equals(status.get("approach"))) beforeLanding = core.energy();
            }
            return;
        }
        var home = server.overworld();
        if (!stowed || beforeLanding < 0 || pilot.serverLevel() != home || core.getLevel() != home
                || HullTransit.inProgress(server,identity) || !HullTransit.isAboard(pilot,core)) return;
        var body = VS.inspect(home,core.getBlockPos()).orElse(null);
        if (body == null || OrbitModule.active(core)) return;
        FieldStart.require(core.owner().equals(pilot.getUUID()) && ROCKET.equals(core.originalRocket())
                && core.saveWithoutMetadata().hasUUID("PaidVoyage") && beforeLanding - core.energy() == fare,
                "Returned registered ship and paid return fare");
        var storage = core.getBlockPos();
        var barrel = (BarrelBlockEntity)home.getBlockEntity(storage.east());
        FieldStart.require(barrel != null && barrel.getItem(0).is(Items.AMETHYST_SHARD)
                && barrel.getItem(0).getCount() == 3 && FieldTreasure.stowed(barrel,pilot) && moon.getBlockState(helm).isAir(),"Original cargo returned and source released");
        var location = body.worldPosition();
        FieldStart.require(new Vec3(location.x(),location.y(),location.z()).distanceTo(PORT) < .08, "Selected recorded home port");
        if (++settled < 20) return;
        FieldStart.write("field-return.json", Map.ofEntries(Map.entry("core",identity), Map.entry("rocket",ROCKET),
                Map.entry("pilot",pilot.getUUID()),Map.entry("keys",keys(pilot)),Map.entry("fare",fare),
                Map.entry("beforeFE",beforeLanding),Map.entry("afterFE",core.energy()),
                Map.entry("receipt",core.saveWithoutMetadata().getUUID("PaidVoyage")),Map.entry("amethystShards",3),Map.entry("reward",FieldTreasure.manifest()),
                Map.entry("storageCore",List.of(storage.getX(),storage.getY(),storage.getZ())),Map.entry("aboard",true)));
        returned = true;
    }
}
