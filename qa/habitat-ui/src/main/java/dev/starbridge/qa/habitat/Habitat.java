package dev.starbridge.qa.habitat;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.content.ContentModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VentMenu;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;

@Mod("starbridgehabitatqa")
@Mod.EventBusSubscriber(modid="starbridgehabitatqa")
public final class Habitat {
    static final BlockPos VENT = new BlockPos(60, 149, 62), HATCH = new BlockPos(64, 149, 62);
    private static int phase, driveTicks;
    private static UUID console;
    private static final VsShipBackend physics = new VsShipBackend();
    private static final Path touchdown = Path.of("starbridge-touchdown.fixture");
    private static long surfaceShip;
    private static Vec3 seat;
    private static int boardDelay, settled, descentTicks;
    private static double initialClearance = Double.NaN, fastestDescent;

    private static boolean openedHatch, boxCharged;
    private static final Path marker = Path.of("starbridge-habitat.host");

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || !Files.isRegularFile(marker)) return;
        var server = event.getServer();
        if (server.isDedicatedServer() || server.getPlayerList().getPlayers().isEmpty()) return;
        var level = server.overworld();
        ServerPlayer host = server.getPlayerList().getPlayers().get(0);
        boolean supplyTest = Files.isRegularFile(Path.of("starbridge-supply.fixture"));
        if (supplyTest && phase > 0 && server.getTickCount() % 4 == 0) SupplyWitness.sample(level, host);
        if (phase == 0) {
            for (BlockPos p: BlockPos.betweenClosed(60,148,60,64,151,64)) {
                if (p.getY()==148 || p.getY()==151 || p.getX()==60 || p.getX()==64 || p.getZ()==60 || p.getZ()==64)
                    level.setBlockAndUpdate(p, Blocks.GLASS.defaultBlockState());
            }
            level.setBlockAndUpdate(VENT, VoyageModule.VENT.get().defaultBlockState());
            host.teleportTo(level,62.5,149,62.5,90,10);
            host.getInventory().setItem(1, new ItemStack(ContentModule.OXYGEN_CANISTER.get(),2));
            host.getInventory().selected = 0;
            if (Files.isRegularFile(Path.of("starbridge-starter.fixture"))) {
                level.setBlockAndUpdate(new BlockPos(61,149,61), Blocks.CRAFTING_TABLE.defaultBlockState());
                var materials = new ItemStack[] {
                        new ItemStack(net.minecraft.world.item.Items.IRON_INGOT,5), new ItemStack(net.minecraft.world.item.Items.REDSTONE,4),
                        new ItemStack(net.minecraft.world.item.Items.DIAMOND,2), new ItemStack(net.minecraft.world.item.Items.COMPARATOR),
                        new ItemStack(net.minecraft.world.item.Items.GLASS_PANE), new ItemStack(net.minecraft.world.item.Items.COMPASS),
                        new ItemStack(net.minecraft.world.item.Items.PAPER)};
                for (int i=0; i<materials.length; i++) host.getInventory().setItem(9+i,materials[i]);
                if (Files.isRegularFile(WorkshopCraft.MARKER)) {
                    host.getInventory().setItem(20, new ItemStack(net.minecraft.world.item.Items.PISTON));
                    host.getInventory().setItem(21, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 3));
                    if (Files.isRegularFile(Path.of("starbridge-press-cycle.fixture"))) {
                        level.setBlockAndUpdate(new BlockPos(63,148,61), Blocks.OBSIDIAN.defaultBlockState());
                        var tin = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
                                new net.minecraft.resources.ResourceLocation("libvulpes:libvulpesmetal0")).orElseThrow().getDefaultInstance();
                        tin.getOrCreateTag().putInt("LegacyMeta",5);
                        host.getInventory().setItem(4,tin);
                        host.getInventory().setItem(5,new ItemStack(net.minecraft.world.item.Items.LEVER));
                    }
                }
                if (supplyTest) SupplyWitness.prepare(level,host);
            }
            phase = 1;
        }
        if (phase == 1 && host.containerMenu instanceof VentMenu menu) {
            if (menu.charge() == 120 && menu.sealed() && menu.canisters() == 1 && !openedHatch) {
                boxCharged = true;
                level.setBlockAndUpdate(HATCH,Blocks.AIR.defaultBlockState());
                openedHatch = true;
            }
        }
        if (phase == 1 && boxCharged && Files.isRegularFile(Path.of("qa-vent-inspected")) && host.containerMenu == host.inventoryMenu) {
            var block = new BlockPos(96,148,96);
            if (Files.isRegularFile(touchdown)) {
                // The pilot starts beside a loaded landing pad. The pad clears a 21×21 test area.
                for (BlockPos pos : BlockPos.betweenClosed(86,144,86,106,144,106))
                    level.setBlockAndUpdate(pos, Blocks.SMOOTH_STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(100,147,96), Blocks.GLASS.defaultBlockState());
                host.teleportTo(level,100.5,148,96.5,90,10);
            }
            var cells = new HashSet<BlockPos>();
            for (BlockPos p: BlockPos.betweenClosed(block.offset(-2,-1,-2),block.offset(2,-1,2))) {
                cells.add(p.immutable()); level.setBlockAndUpdate(p,Blocks.IRON_BLOCK.defaultBlockState());
            }
            cells.add(block); level.setBlockAndUpdate(block,Starbridge.SHIP_CORE.get().defaultBlockState());
            var core = (ShipCoreBlockEntity) level.getBlockEntity(block);
            core.setOwner(host.getUUID()); core.register(); console = core.id();
            core.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new).receiveEnergy(180000,false);
            var ship = new VsShipBackend().assembleCabin(level,cells,block,console);
            var moved = (ShipCoreBlockEntity) level.getBlockEntity(block.offset(ship.dx(),ship.dy(),ship.dz()));
            if (moved == null || !moved.id().equals(console)) throw new IllegalStateException("Bridge did not relocate");
            moved.register();
            var feet = HullTransit.toWorld(level,ship.shipId(),new Vec3(block.getX()+ship.dx()+1.5,block.getY()+ship.dy()+.02,block.getZ()+ship.dz()+.5)).orElseThrow();
            surfaceShip = ship.shipId();
            seat = feet;
            if (!Files.isRegularFile(touchdown)) host.teleportTo(level,feet.x,feet.y,feet.z,90,10);
            host.getPersistentData().putUUID("starbridge:selected_core",console);
            host.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(VoyageModule.SLATE.get()));
            phase = Files.isRegularFile(touchdown) ? 4 : 2;
        }
        if (phase == 4 && ++boardDelay >= 120) {
            // Boarding position is the fixture setup. Do not reposition either ship or pilot during flight.
            if (physics.inspectById(level, surfaceShip).isEmpty()) return;
            initialClearance = physics.surfaceClearance(level, surfaceShip).orElseThrow();
            host.teleportTo(level, seat.x, seat.y, seat.z, 90, 10);
            phase = 2;
        }
        if (phase == 2) {
            var core = CoreRegistry.find(console).orElse(null);
            if (server.getTickCount()%20 == 0) Files.writeString(Path.of("habitat-helm-status.log"),
                    "registered="+(core!=null)+" selected="+host.getPersistentData().get("starbridge:selected_core")+
                    " expected="+console+" aboard="+(core!=null && HullTransit.isAboard(host,core))+
                    " authorized="+(core!=null && core.authorized(host))+" loaded="+(core!=null && core.loadedForControl())+
                    " pos="+host.position()+" mode="+(core==null ? "" : core.status().get("mode")));
            if (core != null && "MANUAL".equals(core.status().get("mode"))) driveTicks++;
            if (Files.isRegularFile(touchdown) && core != null) {
                var ship = physics.inspectById(level, surfaceShip).orElse(null);
                double height = physics.surfaceClearance(level, surfaceShip).orElse(Double.NaN);
                if (ship != null && Double.isFinite(height) && initialClearance > 1 && driveTicks > 0) {
                    fastestDescent = Math.min(fastestDescent, ship.velocity().y());
                    if (ship.velocity().y() < -.3) descentTicks++;
                    boolean contact = height < .18 && height > -.20 && Math.abs(ship.velocity().y()) < .35
                            && HullTransit.isAboard(host, core) && host.isAlive() && !ship.isStatic() && descentTicks >= 3;
                    settled = contact ? settled + 1 : 0;
                    if (settled == 12) {
                        var result = new com.google.gson.JsonObject();
                        result.addProperty("startClearance", initialClearance);
                        result.addProperty("contactClearance", height);
                        result.addProperty("contactVerticalSpeed", ship.velocity().y());
                        result.addProperty("fastestDescent", fastestDescent);
                        result.addProperty("consecutiveContactTicks", settled);
                        result.addProperty("livingPilotOnDeck", true);
                        result.addProperty("pass", true);
                        Files.writeString(Path.of("touchdown-result.json"),result.toString());
                    }
                }
                if (server.getTickCount() % 20 == 0) Files.writeString(Path.of("surface-live.json"),
                        "height="+height+" start="+initialClearance+" settle="+settled+" downwardTicks="+descentTicks+" minV="+fastestDescent+" ship="+ship);
            }
            if (driveTicks >= 8 && !Files.exists(Path.of("qa-manual-input"))) Files.writeString(Path.of("qa-manual-input"),Integer.toString(driveTicks));
            if (Files.isRegularFile(Path.of("qa-ui-finished")) && driveTicks > 2 && server.getPlayerList().getPlayerCount() == 2) {
                var guest = server.getPlayerList().getPlayers().get(1);
                if (!guest.isAlive() || guest.isCreative() || host.hasPermissions(2) || guest.hasPermissions(2)) throw new IllegalStateException("Expected two ordinary survival players");
                Files.writeString(Path.of("habitat-result.json"), "{\"pass\":true,\"integratedServer\":true,\"survivalPlayers\":2,\"allowCommands\":false,\"oneCartridgeConsumed\":true,\"oxygenAfterRefill\":120,\"leakDisplayed\":true,\"serverManualInputTicks\":"+driveTicks+"}");
                phase=3;
            }
        }
    }
}
