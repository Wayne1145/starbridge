package dev.starbridge.flightqa;

import dev.starbridge.client.survey.SurfaceSurveyScreen;
import dev.starbridge.survey.FieldNotes;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;

/** The same surface contours the survey screen displays and a first-person sight line at the rim. */
final class LunarWalker {
    private enum Step { SHIP, SURVEY, DETAIL, WALK, RIM, SITE, BEARING, BACK }
    private Step step=Step.SHIP;
    private final BoardingSteps boarding = new BoardingSteps();
    private final LedgeStep ledge = new LedgeStep();
    private final FieldAscent ascent = new FieldAscent();
    private final RimGround ground = new RimGround();
    private BlockHitResult sighted;
    private int sightSince;
    private boolean boardingNow, eating;
    private final Set<Long> difficultSteps = new HashSet<>();
    private Deque<BlockPos> walkingRoute = new ArrayDeque<>();
    private int routeStepSince;
    private BlockPos localCourse;
    private boolean checkedWideMap, photographedCloseMap, contourWalk;
    private final boolean wideSearch = Files.isRegularFile(Path.of("lunar-wide-recon.fixture"));
    private final List<Integer> observedSpans = new ArrayList<>();
    private int stowedSlot, mealStarted, hungerAtMeal, nextMeal;
    private boolean sawGuide;
    private int guideOpened, routeStalled, lastLeg;
    private double leastRemaining = Double.POSITIVE_INFINITY;
    private int opened, nextUse, leg, legCornerX, legCornerZ, samples, lastSample, rimAttempt;
    private int lastProgress, lastMotionTick, escapeTurn;
    private Vec3 motionAnchor;
    private BlockPos ship, goal, scanOrigin, pit, site;
    private final List<BlockPos> rejected=new ArrayList<>();
    private final ArrayList<String> path=new ArrayList<>();


    void tick(Minecraft game,int tick) throws Exception {
        var player=game.player;
        if(!game.level.dimension().location().toString().equals(LunarTrail.MOON)) return;
        if (sighted != null) {
            confirmSight(game, tick);
            return;
        }
        if (game.screen instanceof dev.starbridge.client.survey.SurfaceCluesScreen guide) {
            stop(game);
            if (tick - guideOpened > 10) {
                FlightClient.snap(game, "field-moon-crater-guide");
                if (FlightClient.click(guide, "slate.starbridge.field.clues.back")) sawGuide = true;
            }
            return;
        }
        if (game.screen == null && !boardingNow && (eating || player.getFoodData().getFoodLevel() <= 14)) {
            if (!eating && tick>=nextMeal && !player.getInventory().getItem(2).isEmpty()) {
                stop(game); stowedSlot = player.getInventory().selected; player.getInventory().selected = 2;
                hungerAtMeal=player.getFoodData().getFoodLevel(); mealStarted=tick;
                game.options.keyUse.setDown(true);
                game.gameMode.useItem(player, InteractionHand.MAIN_HAND); eating = true;
                FlightClient.log("Tried held ration " + player.getMainHandItem().getHoverName().getString() + ", hunger=" + hungerAtMeal); return;
            }
            if (eating) {
                int hunger=player.getFoodData().getFoodLevel();
                if (hunger <= hungerAtMeal && tick-mealStarted<60) return;
                game.options.keyUse.setDown(false);game.gameMode.releaseUsingItem(player);
                player.getInventory().selected = stowedSlot; eating = false; nextMeal=tick+(hunger>hungerAtMeal?20:450);
                FlightClient.log("Ration use completed with hunger " + hungerAtMeal + " -> " + hunger);
            }
        }
        if (tick % 80 == 0 && (step == Step.WALK || step == Step.RIM || step == Step.BACK)) {
            var input = new LinkedHashMap<String,Object>();
            input.put("feet", player.position().toString()); input.put("screen", game.screen == null ? "world" : game.screen.getClass().getSimpleName());
            input.put("forwardKey", game.options.keyUp.isDown()); input.put("backKey", game.options.keyDown.isDown());
            input.put("leftKey", game.options.keyLeft.isDown()); input.put("rightKey", game.options.keyRight.isDown());
            input.put("sneak", player.isShiftKeyDown()); input.put("jump", game.options.keyJump.isDown());
            input.put("forwardImpulse", player.input.forwardImpulse); input.put("leftImpulse", player.input.leftImpulse);
            input.put("horizontalCollision", player.horizontalCollision); input.put("ground", player.onGround()); input.put("passenger", player.isPassenger());
            input.put("moving", player.getDeltaMovement().toString()); input.put("usingItem", player.isUsingItem());
            input.put("speed",player.getSpeed()); input.put("lookYaw",player.getYRot()); input.put("escapeTurn",escapeTurn);
            input.put("effects",player.getActiveEffects().toString()); input.put("key",game.options.keyUp.getTranslatedKeyMessage().getString());
            LunarTrail.recordLive(Path.of("walking-input-live.json"), input);
            if (samples <= 2 && tick % 240 == 0) FlightClient.snap(game, "field-walking-deck");
        }
        if(tick%100==0){
            path.add(tick+" "+step+" "+player.blockPosition().toShortString());
            LunarTrail.recordLive(Path.of("lunar-walk-live.json"),Map.of("phase",step.name(),"feet",player.position().toString(),"samples",samples,
                    "rejected",rejected.size(),"visitedPits",rejected.stream().map(BlockPos::asLong).toList(),"target",String.valueOf(goal),"pit",String.valueOf(pit),"sampledPath",path));
        }
        if (ascent.active() && game.screen == null && (step==Step.WALK || step==Step.RIM)) {
            if (!ascent.tick(game, goal, tick)) {step=Step.SURVEY;opened=0;}
            return;
        }
        if(step==Step.SHIP || step==Step.SURVEY || step==Step.DETAIL || step==Step.SITE || step==Step.BEARING){
            stop(game);
            if(!(game.screen instanceof SurfaceSurveyScreen screen)) {openNotebook(game,tick);return;}
            game.options.keyShift.setDown(false);
            if(opened==0) opened=tick;
            var view=(FieldNotes.View) field(screen,"view");
            if(step==Step.SHIP && view.ship()==null){if(tick-opened>=6 && tick>=nextUse && FlightClient.click(screen,"slate.starbridge.field.mark_ship")) nextUse=tick+15;return;}
            if(step==Step.SHIP){
                ship=view.ship();legCornerX=ship.getX();legCornerZ=ship.getZ();nextLeg();
                if (Files.isRegularFile(Path.of("lunar-continue-field-search.fixture"))) {
                    var checkpoint = com.google.gson.JsonParser.parseString(Files.readString(Path.of("lunar-search-checkpoint.json"))).getAsJsonObject();
                    if (checkpoint.has("visitedPits")) {
                        for (var old : checkpoint.getAsJsonArray("visitedPits")) rejected.add(BlockPos.of(old.getAsLong()));
                    }
                    Path earlierRims = Path.of("walked-rims.json");
                    if (Files.isRegularFile(earlierRims)) {
                        for (var location : com.google.gson.JsonParser.parseString(Files.readString(earlierRims)).getAsJsonArray()) {
                            var xyz=location.getAsJsonArray(); var old=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());
                            if (!rejected.contains(old)) rejected.add(old);
                        }
                    }
                    String previousGoal = checkpoint.get("target").getAsString();
                    while (!goal.toString().equals(previousGoal) && leg < 600) nextLeg();
                    if (!goal.toString().equals(previousGoal)) throw new IllegalStateException("Missing previous player's survey course");
                    FlightClient.log("Continue the last recorded surface survey course " + previousGoal);
                }
                if (Files.isRegularFile(Path.of("lunar-return-from-field.fixture"))) {
                    step = Step.SITE;
                    FlightClient.log("Mark a surface rest stop and follow the saved ship marker back to the deck");
                } else {
                    FlightClient.snap(game,"field-ship-position"); step=Step.SURVEY;
                }
                opened=tick; return;
            }
            if(step==Step.SITE && view.site()==null){if(tick>=nextUse && FlightClient.click(screen,"slate.starbridge.field.mark_site"))nextUse=tick+15;return;}
            if(step==Step.SITE) {
                site=view.site(); ship=view.ship(); FlightClient.click(screen,"slate.starbridge.field.ship");
                step=Step.BEARING; opened=tick; return;
            }
            if(step==Step.BEARING) {
                if (tick-opened < 12) return;
                if (field(screen,"focus") != FieldNotes.Marker.SHIP) { FlightClient.click(screen,"slate.starbridge.field.ship"); return; }
                FlightClient.snap(game,"field-site-and-ship");
                var feet=player.blockPosition();
                double dx=ship.getX()-feet.getX(), dz=ship.getZ()-feet.getZ();
                double length=Math.hypot(dx,dz);
                // On-map and off-map markers both preserve bearing. Recheck the same terminal further on.
                int span=(int)Math.min(32,Math.ceil(length));
                goal = length < 2 ? ship : feet.offset((int)Math.round(dx*span/length),0,(int)Math.round(dz*span/length));
                FlightClient.log("Follow selected ship marker from " + feet.toShortString() + ", horizontal distance " + Math.round(length));
                FlightClient.click(screen,"gui.done");step=Step.BACK;opened=0;lastProgress=tick;return;
            }
            if (step==Step.DETAIL) {
                int stride = (int) field(screen, "stride");
                var zoomIn = FlightClient.find(screen, "slate.starbridge.field.zoom_in");
                var zoomOut = FlightClient.find(screen, "slate.starbridge.field.zoom_out");
                if (zoomIn == null || zoomOut == null || zoomIn.active != (stride > 1) || zoomOut.active != (stride < 8))
                    throw new IllegalStateException("The visible zoom controls do not match the map span");
                if (!photographedCloseMap && (observedSpans.isEmpty() || observedSpans.get(observedSpans.size() - 1) != 32 * stride))
                    observedSpans.add(32 * stride);
                if (!checkedWideMap) {
                    if (stride < 8) { FlightClient.click(screen, "slate.starbridge.field.zoom_out"); return; }
                    checkedWideMap = true;
                }
                if (stride > 1) {
                    FlightClient.click(screen, "slate.starbridge.field.zoom_in");
                    opened = tick;
                    return;
                }
                if (!photographedCloseMap && tick - opened == 20) SurveyRoute.hoverWalkingCell(game, screen);
                if (tick - opened < 26) return;
                walkingRoute=SurveyRoute.across(screen,localCourse,difficultSteps);
                if (!photographedCloseMap) {
                    FlightClient.snap(game, "field-one-block-scale");
                    LunarTrail.record(Path.of("field-zoom-check.json"), Map.of(
                            "spans", observedSpans, "zoomInAtClosest", zoomIn.active, "zoomOutAtClosest", zoomOut.active,
                            "feet", player.position().toString(), "health", player.getHealth(), "walkableSteps", walkingRoute.size(),
                            "surfaceHeightAtCursor", SurveyRoute.hoverWalkingCell(game, screen),
                            "boundaries", SurveyRoute.screenshotBoundaries(game, screen)));
                    photographedCloseMap = true;
                    FlightClient.log("Inspected the 32-block field chart before choosing close walking steps");
                }
                routeStepSince=tick; step=Step.WALK; opened=0;
                FlightClient.click(screen,"gui.done"); return;
            }
            if (!sawGuide && tick - opened > 5 && FlightClient.click(screen, "slate.starbridge.field.clues.title")) {
                guideOpened = tick;
                return;
            }
            if (wideSearch && (int) field(screen, "stride") < 8) {
                if (FlightClient.click(screen, "slate.starbridge.field.zoom_out")) opened = tick;
                return;
            }
            if(tick-opened<43)return;
            pit=findPit(screen,player.blockPosition());
            ++samples;scanOrigin=player.blockPosition();lastSample=tick;
            if(pit!=null){FlightClient.snap(game,"field-depression-"+samples);FlightClient.log("Survey contour suggests an open depression at "+pit);rimAttempt=0;step=Step.RIM;}
            else {
                var coarse = SurveyRoute.across(screen,goal,difficultSteps);
                localCourse=goal;
                contourWalk = false;
                if (wideSearch && photographedCloseMap) {
                    // Follow a flat stretch of the visible map on foot; recheck the ground from the eye at every step.
                    int height = player.getBlockY();
                    for (var next : coarse) {
                        if (Math.abs(next.getY() - height) > 2) break;
                        height = next.getY();
                        localCourse = next;
                        double distance = horizontal(player.position(), next);
                        if (distance >= 20) contourWalk = true;
                        if (distance >= 44) break;
                    }
                    if (contourWalk) {
                        walkingRoute.clear();
                        step = Step.WALK;
                        lastProgress = tick;
                        FlightClient.click(screen, "gui.done");
                        opened = 0;
                        return;
                    }
                }
                localCourse = goal;
                for (var next:coarse) { localCourse=next; if (horizontal(player.position(),next)>=18) break; }
                step=Step.DETAIL; opened=tick;return;
            }
            FlightClient.click(screen,"gui.done");opened=0;lastProgress=tick;return;
        }
        if(game.screen!=null){game.screen.onClose();return;}
        game.options.keyShift.setDown(false);
        if(step==Step.WALK){
            double remaining = horizontal(player.position(), goal);
            if (lastLeg != leg || remaining + 3 < leastRemaining) {
                lastLeg = leg; leastRemaining = remaining; routeStalled = tick;
            }
            if (remaining < 2 || remaining < 40 && tick-routeStalled > 360) {
                if (remaining >= 2) FlightClient.log("Turn the survey transect early at a difficult slope, " + Math.round(remaining) + " blocks before the corner");
                nextLeg(); leastRemaining = Double.POSITIVE_INFINITY; step=Step.SURVEY;return;
            }
            if(tick-lastSample>45 && horizontal(player.position(),scanOrigin)>51){step=Step.SURVEY;return;}
            if (contourWalk) {
                if (horizontal(player.position(), localCourse) < 2 || tick - lastProgress > 260) {
                    contourWalk = false;
                    step = Step.SURVEY;
                } else walk(game, localCourse, tick);
                return;
            }
            while(!walkingRoute.isEmpty() && horizontal(player.position(),walkingRoute.peek())<.57 && Math.abs(player.getY()-walkingRoute.peek().getY()) < 1.2) {
                walkingRoute.remove();routeStepSince=tick;
                if(walkingRoute.isEmpty()){step=Step.SURVEY;return;}
            }
            if(!walkingRoute.isEmpty() && tick-routeStepSince>70) {
                var obstacle=walkingRoute.peek();difficultSteps.add(BlockPos.asLong(obstacle.getX(),0,obstacle.getZ()));
                if(difficultSteps.size()>150)difficultSteps.clear();step=Step.SURVEY;return;
            }
            if (walkingRoute.isEmpty()) walk(game,localCourse==null ? goal : localCourse,tick);
            else {
                var next=walkingRoute.peek();
                if (ascent.tick(game,next,tick)) return;
                double dx=next.getX()+.5-player.getX(), dz=next.getZ()+.5-player.getZ();
                player.setYRot((float)Math.toDegrees(Math.atan2(-dx,dz)));player.setXRot(8);
                game.options.keyShift.setDown(false);game.options.keySprint.setDown(false);
                game.options.keyUp.setDown(true);game.options.keyJump.setDown(player.onGround() && (next.getY()>player.getY()+.2 || player.horizontalCollision));
            }
            return;
        }
        if(step==Step.RIM){
            var centre=Vec3.atCenterOf(pit);
            double dx=player.getX()-centre.x,dz=player.getZ()-centre.z;
            double h=Math.sqrt(dx*dx+dz*dz);
            if(h < 20 && tick%5 == 0){
                var hit=visibleMasonry(game,pit);
                if(hit!=null){stop(game);sighted=hit;sightSince=tick;player.lookAt(Anchor.EYES,hit.getLocation());return;}
            }
            if (rimAttempt == 0 && tick - lastProgress > 650) {
                FlightClient.log("Could not reach the candidate safely from this side of the ravine: " + pit.toShortString());
                rejected.add(pit); pit=null; step=Step.SURVEY; opened=0; return;
            }
            // Inspect different accessible lips of the same depression. Interior rock is never inspected.
            if(h>9 && rimAttempt==0){walk(game,pit,tick);return;}
            if(rimAttempt==0){rimAttempt=1;lastProgress=tick;}
            int[] xs={6,0,-6,0},zs={0,6,0,-6};int side=(rimAttempt-1)%4;
            var rim=pit.offset(xs[side],0,zs[side]);
            if(horizontal(player.position(),rim)<1.6 || tick-lastProgress>160){rimAttempt++;lastProgress=tick;}
            if(rimAttempt>4){FlightClient.log("Inspected the visible slopes without seeing original masonry at " + pit.toShortString());rejected.add(pit);pit=null;step=Step.SURVEY;opened=0;return;}
            walk(game,rim,tick);return;
        }
        if(step==Step.BACK){
            double planar=horizontal(player.position(),ship);
            if (planar < 6 || boardingNow) {
                boardingNow = true;
                boarding.tick(game,tick,ship);
                return;
            }
            if (horizontal(player.position(),goal) < 2.0) { step=Step.BEARING; opened=0; return; }
            walk(game,goal,tick);
        }
    }

    private void confirmSight(Minecraft game,int tick) throws Exception {
        stop(game);
        game.player.lookAt(Anchor.EYES,sighted.getLocation());
        if (tick-sightSince<8) return;
        var eye=game.player.getEyePosition();
        var aimed=game.level.clip(new ClipContext(eye,eye.add(game.player.getViewVector(1).scale(64)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,game.player));
        if (aimed.getType()!=HitResult.Type.BLOCK || !aimed.getBlockPos().equals(sighted.getBlockPos())) {sighted=null;return;}
        var frame=Path.of("screenshots/field-visible-dungeon-brick.png");
        if (!Files.isRegularFile(frame)) {if ((tick-sightSince)%8==0) FlightClient.snap(game,"field-visible-dungeon-brick"); return;}
        if (game.screen instanceof SurfaceSurveyScreen notebook) {
            FlightClient.click(notebook, "slate.starbridge.field.clues.title");
            return;
        }
        if (!(game.screen instanceof dev.starbridge.client.survey.SurfaceCluesScreen guide)) {
            openNotebook(game,tick);
            return;
        }
        game.options.keyShift.setDown(false);
        Object face = field(guide,"observed");
        if (face == null) { FlightClient.click(guide,"slate.starbridge.field.sight.inspect"); return; }
        if (!"brick".equals(field(face,"description")) || !aimed.getBlockPos().equals(field(face,"surface"))) {
            guide.onClose(); sighted=null; return;
        }
        var compared=Path.of("screenshots/field-compared-dungeon-brick.png");
        if (!Files.isRegularFile(compared)) {if (tick%8==0) FlightClient.snap(game,"field-compared-dungeon-brick");return;}
        var state=game.level.getBlockState(aimed.getBlockPos());
        LunarTrail.record(Path.of("observed-entrance.json"),Map.of("mapCell",pit.toShortString(),"viewerFeet",game.player.position().toString(),
                "firstVisibleFace",aimed.getBlockPos().toShortString(),"state",state.toString(),"sightDistance",eye.distanceTo(aimed.getLocation()),
                "survey",samples,"cameraYaw",game.player.getYRot(),"cameraPitch",game.player.getXRot(),"capturedFacingTheMasonry",true,"identifiedInFieldGuide",true));
        sighted=null; step=Step.SITE; opened=0; nextUse=tick+10;
    }

    private void openNotebook(Minecraft game,int tick) throws Exception {
        if(game.screen!=null){if(game.screen instanceof dev.starbridge.client.FirstFlightScreen){FlightClient.click(game.screen,"slate.starbridge.field.title");}
            else game.screen.onClose();return;}
        game.player.getInventory().selected=0;game.options.keyShift.setDown(true);
        if(game.player.isShiftKeyDown() && tick>=nextUse){game.gameMode.useItem(game.player,InteractionHand.MAIN_HAND);nextUse=tick+18;}
    }
    private static Object field(Object obj,String name)throws Exception{var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private BlockPos findPit(SurfaceSurveyScreen screen,BlockPos feet)throws Exception {
        Object terrain=field(screen,"terrain");int stride=(int)field(screen,"stride"),cx=(int)field(screen,"centerX"),cz=(int)field(screen,"centerZ");
        var drop = terrain.getClass().getDeclaredMethod("rimDrop", int.class, int.class);
        var steep = terrain.getClass().getDeclaredMethod("steep", int.class, int.class);
        var elevation = terrain.getClass().getDeclaredMethod("at", int.class, int.class);
        drop.setAccessible(true); steep.setAccessible(true); elevation.setAccessible(true);
        BlockPos best = null; double score = 0;
        for (int dx=-15; dx<=15; dx++) for (int dz=-15; dz<=15; dz++) {
            int x=cx+dx, z=cz+dz, depth=(int)drop.invoke(terrain,x,z);
            boolean sharp=(boolean)steep.invoke(terrain,x,z);
            if (depth < (sharp ? 12 : 18)) continue;
            var candidate = new BlockPos(x*stride+stride/2,feet.getY(),z*stride+stride/2);
            if (horizontal(Vec3.atCenterOf(ship),candidate)<10 || rejected.stream().anyMatch(old->horizontal(Vec3.atCenterOf(old),candidate)<10)) continue;
            int[] rims = new int[8]; int samples = 0;
            for (int ox=-2;ox<=2;ox+=2) for (int oz=-2;oz<=2;oz+=2) if (ox!=0 || oz!=0) rims[samples++]=(int)elevation.invoke(terrain,x+ox,z+oz);
            Arrays.sort(rims);
            if (rims[1]==Integer.MIN_VALUE) continue;
            int bankSpread=rims[6]-rims[1];
            if (bankSpread>14 && depth<30) continue;
            double rank=depth+(sharp?18:0)-Math.min(35,bankSpread)*1.2-.03*horizontal(Vec3.atCenterOf(feet),candidate);
            if (rank>score) {score=rank;best=candidate;}
        }
        return best;
    }

    private static BlockHitResult visibleMasonry(Minecraft game,BlockPos target){
        var eye=game.player.getEyePosition();
        for(int dx=-4;dx<=4;dx+=2)for(int dz=-4;dz<=4;dz+=2)for(int depth=0;depth<=12;depth++){
            var end=new Vec3(target.getX()+dx+.5,eye.y+1-depth*4,target.getZ()+dz+.5);
            if(eye.distanceToSqr(end)>48*48)continue;
            var hit=game.level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,game.player));
            if(hit.getType()!=HitResult.Type.BLOCK)continue;
            var state=game.level.getBlockState(hit.getBlockPos());String visible=state.toString().toLowerCase(Locale.ROOT);
            if(visible.contains("galacticraftcore") && visible.contains("moonblock") && visible.matches(".*(?:meta|metadata)=14(?:[,\\]].*)"))return hit;
        }
        return null;
    }
    private void nextLeg(){int n=leg/2+1; int[] dx={1,0,-1,0},dz={0,1,0,-1};legCornerX+=dx[leg%4]*n*82;legCornerZ+=dz[leg%4]*n*82;leg++;goal=new BlockPos(legCornerX,ship.getY(),legCornerZ);}
    private static double horizontal(Vec3 v,BlockPos p){return Math.hypot(v.x-p.getX()-.5,v.z-p.getZ()-.5);}
    private void walk(Minecraft game,BlockPos destination,int tick) throws Exception {
        var p=game.player;double x=destination.getX()+.5-p.getX(),z=destination.getZ()+.5-p.getZ();double d=Math.hypot(x,z);
        if ((step == Step.WALK || step == Step.RIM) && ascent.tick(game,destination,tick)) return;
        if (motionAnchor == null || ((p.getX()-motionAnchor.x)*(p.getX()-motionAnchor.x) + (p.getZ()-motionAnchor.z)*(p.getZ()-motionAnchor.z)) > 1) {
            motionAnchor = p.position(); lastMotionTick = tick; escapeTurn = 0;
        }
        int stuck = tick - lastMotionTick;
        if (stuck > 22) escapeTurn = 1 + (stuck - 23) / 16;
        int[] turn = {0, 90, -90, 180, -45, 45, 135, -135};
        float yaw = (float)Math.toDegrees(Math.atan2(-x,z)) + turn[escapeTurn % turn.length];
        if (step == Step.RIM || step == Step.WALK) {
            yaw = ground.course(game, yaw, tick);
            if (Float.isNaN(yaw)) { stop(game); return; }
            if (step == Step.WALK && ground.canSprint() && ledge.tick(game,destination,d,tick)) return;
        }
        p.setYRot(yaw); p.setXRot(8);
        game.options.keyDown.setDown(false); game.options.keyLeft.setDown(false); game.options.keyRight.setDown(false);
        game.options.keyUp.setDown(true); game.options.keySprint.setDown(!wideSearch && d > 5 && stuck < 22 && ground.canSprint());
        game.options.keyJump.setDown(p.onGround() && (p.horizontalCollision || stuck > 13));
    }
    private static void stop(Minecraft game){game.options.keyUp.setDown(false);game.options.keySprint.setDown(false);game.options.keyJump.setDown(false);}
}
