package dev.starbridge.testing;

import com.google.gson.GsonBuilder;
import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.unbound.UnboundCompat;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.Route;
import dev.starbridge.voyage.VoyageLog;
import dev.starbridge.voyage.Voyages;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Runs only in an explicitly marked disposable dedicated server with two separately connected game clients. */
public final class DedicatedVoyageSmoke {
    private final VsShipBackend vs = new VsShipBackend();
    private final Map<String,Object> results = new LinkedHashMap<>();
    private static final BlockPos ORIGIN = new BlockPos(160, 165, 160);
    private static final String CAPTAIN = "OrbitCaptain", ENGINEER = "OrbitEngineer";
    private final long deadline = System.nanoTime() + Duration.ofMinutes(12).toNanos();
    private int stage, since, ticks, initialEnergy;
    private long ship;
    private UUID coreId;
    private BlockPos storage;
    private boolean done;
    public static void register() {
        if (Boolean.getBoolean("starbridge.voyageSmoke")) MinecraftForge.EVENT_BUS.register(new DedicatedVoyageSmoke());
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || !(event.getServer() instanceof DedicatedServer server)) return;
        if (!Files.isRegularFile(Path.of("starbridge-disposable-voyage.fixture"))) { done = true; return; }
        ticks++;
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Crew/ship timed out at stage " + stage);
            if (ticks % 5 != 0) return;
            var captain = server.getPlayerList().getPlayerByName(CAPTAIN);
            var engineer = server.getPlayerList().getPlayerByName(ENGINEER);
            if (captain == null || engineer == null) return;
            require(server.getPlayerList().getPlayerCount() == 2, "Isolated run requires the two named clients");
            var home = server.overworld(); var moon = server.getLevel(Voyages.dimension(Route.MOON));
            require(moon != null, "Destination is registered");
            if (stage > 0 && ticks - since > 1200) throw new IllegalStateException("No verified arrival at stage " + stage);
            switch (stage) {
                case 0 -> {
                    for (var player : List.of(captain,engineer)) { player.setGameMode(GameType.CREATIVE); player.teleportTo(home, 162,170,162,0,0); }
                    results.put("unbound", UnboundCompat.enabled()); results.put("moon", moon.dimension().location().toString());
                    if (UnboundCompat.enabled()) fixtureDiscovery(captain, engineer); else VoyageLog.get(server).chartMoon(captain.getUUID());
                    require(Voyages.unlocked(captain, Route.MOON), "Captain's discovery unlocks the chart");
                    next(1);
                }
                case 1 -> { if (ticks - since >= 30) { construct(home,captain); next(2); } }
                case 2 -> {
                    var bounds = vs.storageBoundsById(home, ship).orElse(null);
                    if (bounds == null || vs.inspectById(home,ship).isEmpty()) return;
                    for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ())) {
                        if (home.getBlockEntity(pos) instanceof ShipCoreBlockEntity core && core.id().equals(coreId)) { storage=pos.immutable(); break; }
                    }
                    if (storage == null) return;
                    putOnDeck(home,captain,storage.offset(1,0,1)); putOnDeck(home,engineer,storage.offset(2,0,1)); next(3);
                }
                case 3 -> {
                    var core = requireCore(home); if (ticks - since < 10 || !HullTransit.isAboard(captain,core) || !HullTransit.isAboard(engineer,core)) return;
                    checkCargo(home); initialEnergy = core.energy(); results.put("departureFE",initialEnergy);
                    Voyages.depart(captain,core,Route.MOON); next(4);
                }
                case 4 -> {
                    if (captain.serverLevel()!=moon || engineer.serverLevel()!=moon) return;
                    var core=requireCore(moon); checkCargo(moon);
                    require(HullTransit.isAboard(captain,core) && HullTransit.isAboard(engineer,core), "Both passengers on destination deck");
                    require(core.energy()==initialEnergy-12_000, "One outbound fare");
                    if (UnboundCompat.enabled()) {
                        var journal=Class.forName("me.moltea.unbound.api.journal.Journal").getMethod("has",ServerPlayer.class,String.class);
                        require((boolean)journal.invoke(null,captain,"world/moon") && (boolean)journal.invoke(null,engineer,"world/moon"),"Both real players have a landing entry");
                    }
                    var chest=(ChestBlockEntity)moon.getBlockEntity(storage.east());
                    // One crew member uses the destination container and adds a uniquely counted return cargo.
                    engineer.openMenu(chest); chest.setItem(1,new ItemStack(Items.AMETHYST_SHARD,5)); chest.setChanged(); engineer.closeContainer();
                    results.put("outboundFE",core.energy()); results.put("outboundCrew",2); next(5);
                }
                case 5 -> { if (ticks-since>=35) { Voyages.depart(captain,requireCore(moon),Route.HOME); next(6); } }
                case 6 -> {
                    if (captain.serverLevel()!=home || engineer.serverLevel()!=home) return;
                    var core=requireCore(home); checkCargo(home);
                    require(HullTransit.isAboard(captain,core) && HullTransit.isAboard(engineer,core), "Both passengers home");
                    require(core.energy()==initialEnergy-24_000, "Exactly one fare per leg");
                    require(((ChestBlockEntity)home.getBlockEntity(storage.east())).getItem(1).getCount()==5,"Return cargo survived");
                    require(vs.belongsTo(home,ship) && !vs.belongsTo(moon,ship),"Original native ship has one home");
                    require(!(moon.getBlockEntity(storage) instanceof ShipCoreBlockEntity),"No duplicate core at moon");
                    results.put("returnedFE",core.energy()); results.put("returnCrew",2); results.put("shipId",ship);
                    results.put("returnedDiamonds",17); results.put("returnedAmethyst",5); finish(server,null);
                }
                default -> throw new IllegalStateException("Unknown test stage");
            }
        } catch (Exception | LinkageError error) { finish(server,error); }
    }
    private static void fixtureDiscovery(ServerPlayer captain, ServerPlayer engineer) throws ReflectiveOperationException {
        // Only the test setup seeds a simulated earned observation. Runtime voyage code must read the actual journal.
        require(!Voyages.unlocked(captain,Route.MOON),"Fresh Unbound journal must not unlock an undiscovered Moon");
        var grant = Class.forName("me.moltea.unbound.api.journal.Journal").getMethod("grant",ServerPlayer.class,String.class);
        for (var player : List.of(captain, engineer)) {
            for (String key : List.of("journal", "optics", "refractor", "first_light", "refinery", "lunar_rocket")) grant.invoke(null,player,key);
        }
        grant.invoke(null,captain,"body/moon");
    }
    private void construct(ServerLevel level,ServerPlayer captain) {
        Set<BlockPos> hull=new LinkedHashSet<>();
        for(int x=0;x<7;x++) for(int z=0;z<7;z++) hull.add(ORIGIN.offset(x,0,z));
        BlockPos pos=ORIGIN.offset(2,1,2); hull.add(pos);hull.add(pos.east());hull.add(pos.west());
        for(BlockPos cell:hull) require(level.getBlockState(cell).isAir(),"Test berth must be empty");
        for(BlockPos cell:hull) level.setBlockAndUpdate(cell,(cell.equals(pos)?Starbridge.SHIP_CORE.get():cell.equals(pos.east())?Blocks.CHEST:cell.equals(pos.west())?Blocks.FURNACE:Blocks.IRON_BLOCK).defaultBlockState());
        var core=(ShipCoreBlockEntity)level.getBlockEntity(pos);core.setOwner(captain.getUUID());core.addFuel(new ItemStack(Items.COAL_BLOCK),false);core.register();coreId=core.id();
        var box=(ChestBlockEntity)level.getBlockEntity(pos.east());box.setItem(0,new ItemStack(Items.DIAMOND,17));box.setChanged();
        var oven=(FurnaceBlockEntity)level.getBlockEntity(pos.west());oven.setItem(0,new ItemStack(Items.IRON_ORE,8));oven.setItem(1,new ItemStack(Items.COAL,3));oven.setChanged();
        ship=vs.assemble(level,hull,1,true);
    }
    private ShipCoreBlockEntity requireCore(ServerLevel level) {
        require(level.getBlockEntity(storage) instanceof ShipCoreBlockEntity, "Core exists in destination ship storage");
        var core=(ShipCoreBlockEntity)level.getBlockEntity(storage);
        require(core.id().equals(coreId) && vs.inspect(level,storage).orElseThrow().shipId()==ship,"Same core and native ship ID"); return core;
    }
    private void checkCargo(ServerLevel level) {
        var box=(ChestBlockEntity)level.getBlockEntity(storage.east());
        require(box!=null && box.getItem(0).is(Items.DIAMOND) && box.getItem(0).getCount()==17,"Unique manifest of 17 diamonds");
        var oven=(FurnaceBlockEntity)level.getBlockEntity(storage.west());
        require(oven!=null && oven.getItem(0).getCount()+oven.getItem(2).getCount()==8,"Eight ore/ingots conserved in live furnace");
    }
    private void putOnDeck(ServerLevel level,ServerPlayer p,BlockPos pos) {
        Vec3 feet=HullTransit.toWorld(level,ship,new Vec3(pos.getX()+.5,pos.getY()+.02,pos.getZ()+.5)).orElseThrow();
        p.teleportTo(level,feet.x,feet.y,feet.z,0,0); p.setGameMode(GameType.SURVIVAL); p.fallDistance=0;
    }
    private void next(int n) {stage=n; since=ticks; Starbridge.LOGGER.info("STARBRIDGE_VOYAGE_SMOKE stage={}",n);}
    private static void require(boolean ok,String message) { if(!ok) throw new IllegalStateException(message); }
    private void finish(DedicatedServer server,Throwable error) {
        done=true; results.put("pass",error==null); results.put("stage",stage); results.put("elapsedTicks",ticks);
        if (error!=null) { results.put("error",error.toString());Starbridge.LOGGER.error("STARBRIDGE_VOYAGE_SMOKE_FAILED",error); }
        try {Files.writeString(Path.of("voyage-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(results));}
        catch(Exception failure) {Starbridge.LOGGER.error("Cannot write test result",failure);}
        server.halt(false);
    }
}
