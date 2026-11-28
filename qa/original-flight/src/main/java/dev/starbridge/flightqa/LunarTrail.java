package dev.starbridge.flightqa;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

/** Observation only after lunar touchdown. Navigation is decided by the engineer's visible client. */
@Mod.EventBusSubscriber(modid = "starbridge_flightqa")
public final class LunarTrail {
    static final String MOON = "retrofit:legacy_dim_-28";
    static final boolean ENABLED = Files.isRegularFile(Path.of("starbridge-lunar-trail.fixture"));
    private static final Map<String,Object> results = new LinkedHashMap<>();
    private static UUID coreId, rocket;
    private static int phase, began, lunarTick, lastWalkSample;
    private static BlockPos shipMark, siteMark;
    private static net.minecraft.world.phys.Vec3 previous;
    private static double distance, biggestStep;
    private static int walkingSamples, standingTicks;
    private static final dev.starbridge.compat.vs.VsShipBackend PHYSICS = new dev.starbridge.compat.vs.VsShipBackend();
    private LunarTrail() {}

    static void record(Path path, Object value) throws Exception {
        var temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(value));
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }

    // An observer can briefly hold the previous snapshot open on Windows. The next sample refreshes it.
    static void recordLive(Path path, Object value) throws Exception {
        try {
            record(path, value);
        } catch (AccessDeniedException busy) {
            if (!path.toString().equals(busy.getOtherFile()) || !Files.isRegularFile(path)) throw busy;
        }
    }

    private static BlockPos marker(ServerPlayer player, String name) {
        var tag=player.getInventory().getItem(0).getTag();
        if(tag==null) return null;
        var notes=tag.getCompound("StarbridgeFieldNotes").getCompound(MOON);
        return notes.contains(name,4)?BlockPos.of(notes.getLong(name)):null;
    }
    private static void signal(String action) throws Exception {
        Files.writeString(Path.of("lunar-trail-phase.txt"),action);
        dev.starbridge.Starbridge.LOGGER.info("WALK phase {}",action);
    }
    private static void check(boolean good,String message) { if(!good)throw new IllegalStateException(message); }
    private static Map<String,Object> deckContact(ServerPlayer player, ShipCoreBlockEntity core) {
        var level=player.serverLevel(); var ship=PHYSICS.inspect(level,core.getBlockPos()).orElse(null);
        if (ship==null) return null;
        var feet=HullTransit.toShipLocal(level,ship.shipId(),player.position()).orElse(null);
        if (feet==null) return null;
        var block=BlockPos.containing(feet.x,feet.y-.08,feet.z); var state=level.getBlockState(block);
        var hit=state.getCollisionShape(level,block).clip(feet.add(0,.09,0),feet.add(0,-.09,0),block);
        if (hit==null || hit.getDirection()!=net.minecraft.core.Direction.UP || Math.abs(hit.getLocation().y-feet.y)>.075) return null;
        return Map.of("support",state.toString(),"localFeet",feet.toString(),"worldFeet",player.position().toString(),"verticalGap",feet.y-hit.getLocation().y);
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e) throws Exception {
        if(!ENABLED || e.phase!=TickEvent.Phase.END || phase==4) return;
        var server=e.getServer(); int now=server.getTickCount();
        var pilot=server.getPlayerList().getPlayerByName("OrbitCaptain");
        var engineer=server.getPlayerList().getPlayerByName("OrbitEngineer");
        boolean resume = Files.isRegularFile(Path.of("lunar-deck-checkpoint.json"));
        if (engineer == null || (pilot == null && !resume)) return;
        try {
            if(began==0) began=now;
            if (phase == 0 && resume) {
                var checkpoint = JsonParser.parseString(Files.readString(Path.of("lunar-deck-checkpoint.json"))).getAsJsonObject();
                coreId = UUID.fromString(checkpoint.get("core").getAsString());
                rocket = UUID.fromString(checkpoint.get("rocket").getAsString());
                var docked = CoreRegistry.find(coreId).orElse(null);
                boolean fieldReturn = Files.isRegularFile(Path.of("lunar-return-from-field.fixture"));
                boolean fieldSearch = Files.isRegularFile(Path.of("lunar-continue-field-search.fixture"));
                boolean dungeon = Files.isRegularFile(DungeonWitness.ACTIVE);
                if (dungeon) {
                    var observed = JsonParser.parseString(Files.readString(Path.of("observed-entrance.json"))).getAsJsonObject();
                    check(observed.get("capturedFacingTheMasonry").getAsBoolean() && observed.get("identifiedInFieldGuide").getAsBoolean(), "Need the original entrance sighting recorded in this expedition");
                    results.put("dungeonExpedition", true);
                }
                if (!fieldReturn && !fieldSearch && !dungeon && (docked == null || !HullTransit.isAboard(engineer, docked))) return;
                check(engineer.serverLevel().dimension().location().toString().equals(MOON)
                        && (docked == null || rocket.equals(docked.originalRocket())), "Not the original lunar expedition");
                if (fieldReturn) {
                    check(marker(engineer,"SHIP") != null && engineer.blockPosition().distManhattan(marker(engineer,"SHIP")) > 16,
                            "A field checkpoint must start on foot away from the mark");
                    results.put("fieldReturnOnly", true);
                }
                check(!engineer.hasPermissions(2) && !engineer.isCreative() && marker(engineer,"SHIP") != null, "Missing survival engineer or ship mark");
                if (fieldSearch) {
                    results.put("continuedFromSurfaceSurvey",true);
                    boolean firstSupply = !checkpoint.has("continuedFromSurfaceSurvey") && !checkpoint.has("preparedSurfaceRations");
                    results.put("preparedSurfaceRations", ExpeditionFood.prepare(engineer, firstSupply));
                    results.put("initialHunger",engineer.getFoodData().getFoodLevel());
                }
                results.put("resumedOnExistingDeck", !fieldReturn && !fieldSearch && !dungeon);
                results.put("core", coreId); results.put("rocket", rocket);
                results.put("onlineAtResume", server.getPlayerList().getPlayerCount());
                phase = 2; lunarTick = now; signal("walk"); return;
            }
            if(phase==0) {
                if(!Files.exists(Path.of("shipyard-gui-server.txt")) || !Files.exists(Path.of("../OrbitEngineer/shipyard-gui-client.txt"))) return;
                check(Files.readString(Path.of("shipyard-gui-server.txt")).startsWith("PASS"),"Original rocket did not launch");
                var core=CoreRegistry.all().stream().filter(c->c.originalRocket()!=null && OrbitModule.aboard(pilot,c)).findFirst().orElse(null);
                if(core==null || !OrbitModule.aboard(engineer,core))return;
                check(!engineer.hasPermissions(2) && !pilot.hasPermissions(2) && !engineer.isCreative() && !pilot.isCreative(),"Crew not ordinary survival players");
                coreId=core.id();rocket=core.originalRocket();core.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new).receiveEnergy(100000,false);
                var suits = List.of(OriginalEquipment.equip(pilot), OriginalEquipment.equip(engineer));
                results.put("crewEquipment", suits);
                check(suits.stream().allMatch(suit -> suit.containsKey("radiationSuit")), "This traversal requires both sealed GalaxySpace suits");
                for(var person:List.of(pilot,engineer)) {person.getInventory().setItem(0,new ItemStack(VoyageModule.SLATE.get()));person.getInventory().selected=0;}
                var sword=new ItemStack(Items.NETHERITE_SWORD);sword.enchant(Enchantments.SHARPNESS,5);
                engineer.getInventory().setItem(1,sword);engineer.getInventory().setItem(2,new ItemStack(Items.BAKED_POTATO,64));
                engineer.getInventory().setItem(3,new ItemStack(Items.SCAFFOLDING,64));engineer.getInventory().setItem(4,new ItemStack(Items.COBBLESTONE,64));
                engineer.getInventory().setItem(5,new ItemStack(Items.DIAMOND_PICKAXE));engineer.getInventory().setItem(6,new ItemStack(Items.TORCH,64));
                engineer.getItemBySlot(EquipmentSlot.FEET).enchant(Enchantments.FALL_PROTECTION,4);
                results.put("core",coreId);results.put("rocket",rocket); results.put("preparation","Original AR sample pad and cabin, full suits, oxygen, chart terminals, food and ordinary expedition tools. No supplied dungeon coordinates or modified lunar terrain.");
                phase=1;signal("outbound");return;
            }
            check(engineer.isAlive() && (pilot == null || pilot.isAlive()),"Crew died during phase "+phase);
            var core=CoreRegistry.find(coreId).orElse(null);
            DungeonWitness.tick(engineer,core,rocket);
            if(phase==1) {
                if(core!=null && engineer.serverLevel().dimension().location().toString().equals(MOON) && !HullTransit.inProgress(server,coreId)
                        && HullTransit.isAboard(engineer,core) && HullTransit.isAboard(pilot,core)) {
                    phase=2;lunarTick=now;results.put("crewLanded",true);signal("walk");
                }
                return;
            }
            if(phase==2) {
                check(engineer.serverLevel().dimension().location().toString().equals(MOON),"Walk left the original Moon");
                var ship=marker(engineer,"SHIP");var site=marker(engineer,"SITE");
                if(ship!=null && shipMark==null) {
                    if (!Boolean.TRUE.equals(results.get("fieldReturnOnly")) && !Boolean.TRUE.equals(results.get("continuedFromSurfaceSurvey")) && !Boolean.TRUE.equals(results.get("dungeonExpedition"))) check(core!=null && HullTransit.isAboard(engineer,core),"Ship was marked off deck");shipMark=ship;results.put("shipMark",ship.toShortString());
                }
                if(shipMark!=null && now!=lastWalkSample) {
                    var feet=engineer.position();if(previous!=null) {double step=feet.distanceTo(previous);distance+=step;biggestStep=Math.max(biggestStep,step);}
                    previous=feet;lastWalkSample=now;walkingSamples++;
                }
                if(site!=null && siteMark==null) { siteMark=site;results.put("siteMark",site.toShortString()); }
                if(now%100==0){results.put("elapsedWalkTicks",now-lunarTick);results.put("feet",engineer.position().toString());results.put("distance",distance);results.put("largestTickStep",biggestStep);results.put("health",engineer.getHealth());results.put("hunger",engineer.getFoodData().getFoodLevel());results.put("foodSlot",engineer.getInventory().getItem(2).toString());recordLive(Path.of("lunar-trail-live.json"),results);}
                Path near = Path.of(resume && !server.isDedicatedServer() ? "trail-near-hull" : "../OrbitEngineer/trail-near-hull");
                boolean nearHull = (Files.isRegularFile(near) || DungeonWitness.readyForReturn()) && core != null && engineer.onGround() && HullTransit.isAboard(engineer,core);
                var floor = nearHull ? deckContact(engineer,core) : null;
                standingTicks = floor != null ? standingTicks + 1 : 0;
                if(standingTicks >= 10 && (!Files.isRegularFile(DungeonWitness.ACTIVE) || DungeonWitness.readyForReturn())) {
                    results.put("footing",floor); results.put("stableTicks",standingTicks);
                    check(siteMark!=null && shipMark!=null && shipMark.distManhattan(siteMark)>16 && biggestStep<10,"Missing walking or notebook evidence");
                    results.put("walkSamples",walkingSamples);results.put("walkingDistance",distance);results.put("largestTickStep",biggestStep);results.put("walkedBackAboard",true);results.put("returnedRocket",core.originalRocket());
                    check(rocket.equals(core.originalRocket()),"The return ship did not match the launch ship");
                    record(Path.of("lunar-trail-deck-return.json"),results);phase=3;signal("home");
                }
            } else if(phase==3 && pilot!=null && core!=null && !HullTransit.inProgress(server,coreId) && engineer.serverLevel()==server.overworld()
                    && HullTransit.isAboard(engineer,core) && HullTransit.isAboard(pilot,core) && rocket.equals(core.originalRocket())
                    && (!Files.isRegularFile(DungeonWitness.ACTIVE) || DungeonWitness.homeVerified())) {
                results.put("bothReturned",true);results.put("totalTicks",now-began);results.put("pass",true);phase=4;record(Path.of("lunar-trail-result.json"),results);server.halt(false);
            }
        } catch(Exception|LinkageError failure){results.put("pass",false);results.put("error",failure.toString());phase=4;record(Path.of("lunar-trail-result.json"),results);server.halt(false);}
    }
}
