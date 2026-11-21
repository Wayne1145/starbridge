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
    private enum Step { SHIP, SURVEY, WALK, RIM, SITE, BEARING, BACK, DONE }
    private Step step=Step.SHIP;
    private int opened, nextUse, leg, legCornerX, legCornerZ, samples, lastSample, rimAttempt;
    private int lastProgress, lastMotionTick, escapeTurn;
    private Vec3 motionAnchor;
    private BlockPos ship, goal, scanOrigin, pit, site;
    private final List<BlockPos> rejected=new ArrayList<>();
    private final ArrayList<String> path=new ArrayList<>();


    void tick(Minecraft game,int tick) throws Exception {
        var player=game.player;
        if(step==Step.DONE || !game.level.dimension().location().toString().equals(LunarTrail.MOON)) return;
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
            LunarTrail.record(Path.of("walking-input-live.json"), input);
            if (samples <= 2 && tick % 240 == 0) FlightClient.snap(game, "field-walking-deck");
        }
        if(tick%100==0){
            path.add(tick+" "+step+" "+player.blockPosition().toShortString());
            LunarTrail.record(Path.of("lunar-walk-live.json"),Map.of("phase",step.name(),"feet",player.position().toString(),"samples",samples,
                    "rejected",rejected.size(),"target",String.valueOf(goal),"pit",String.valueOf(pit),"sampledPath",path));
        }
        if(step==Step.SHIP || step==Step.SURVEY || step==Step.SITE || step==Step.BEARING){
            stop(game);
            if(!(game.screen instanceof SurfaceSurveyScreen screen)) {openNotebook(game,tick);return;}
            game.options.keyShift.setDown(false);
            if(opened==0) opened=tick;
            var view=(FieldNotes.View) field(screen,"view");
            if(step==Step.SHIP && view.ship()==null){if(tick-opened>=6 && tick>=nextUse && FlightClient.click(screen,"slate.starbridge.field.mark_ship")) nextUse=tick+15;return;}
            if(step==Step.SHIP){
                ship=view.ship();legCornerX=ship.getX();legCornerZ=ship.getZ();nextLeg();
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
            if(tick-opened<35)return;
            pit=findPit(screen,player.blockPosition());
            ++samples;scanOrigin=player.blockPosition();lastSample=tick;
            if(pit!=null){FlightClient.snap(game,"field-depression-"+samples);FlightClient.log("Survey contour suggests an open depression at "+pit);rimAttempt=0;step=Step.RIM;}
            else step=Step.WALK;
            FlightClient.click(screen,"gui.done");opened=0;lastProgress=tick;return;
        }
        if(game.screen!=null){game.screen.onClose();return;}
        game.options.keyShift.setDown(false);
        if(step==Step.WALK){
            if(horizontal(player.position(),goal)<2.0){nextLeg();}
            if(tick-lastSample>45 && horizontal(player.position(),scanOrigin)>43){step=Step.SURVEY;return;}
            walk(game,goal,tick);return;
        }
        if(step==Step.RIM){
            var centre=Vec3.atCenterOf(pit);
            double dx=player.getX()-centre.x,dz=player.getZ()-centre.z;
            double h=Math.sqrt(dx*dx+dz*dz);
            if(h<14 && tick%3==0){
                var hit=visibleMasonry(game,pit);
                if(hit!=null){stop(game);player.lookAt(Anchor.EYES,hit.getLocation());
                    var state=game.level.getBlockState(hit.getBlockPos());
                    LunarTrail.record(Path.of("observed-entrance.json"),Map.of("mapCell",pit.toShortString(),"viewerFeet",player.position().toString(),"firstVisibleFace",hit.getBlockPos().toShortString(),"state",state.toString(),"sightDistance",player.getEyePosition().distanceTo(hit.getLocation()),"survey",samples));
                    FlightClient.snap(game,"field-visible-dungeon-brick");step=Step.SITE;opened=0;nextUse=tick+7;return;}
            }
            // Inspect different accessible lips of the same depression. Interior rock is never inspected.
            if(h>9 && rimAttempt==0){walk(game,pit,tick);return;}
            if(rimAttempt==0){rimAttempt=1;lastProgress=tick;}
            int[] xs={6,0,-6,0},zs={0,6,0,-6};int side=(rimAttempt-1)%4;
            var rim=pit.offset(xs[side],0,zs[side]);
            if(horizontal(player.position(),rim)<1.6 || tick-lastProgress>160){rimAttempt++;lastProgress=tick;}
            if(rimAttempt>4){rejected.add(pit);pit=null;step=Step.SURVEY;opened=0;return;}
            walk(game,rim,tick);return;
        }
        if(step==Step.BACK){
            double planar=horizontal(player.position(),ship);
            if(planar<1.2 && Math.abs(player.getY()-ship.getY())<1.4){stop(game);FlightClient.snap(game,"field-back-on-deck");
                Files.writeString(Path.of("trail-on-deck"),player.position().toString());step=Step.DONE;return;}
            if (planar > 3 && horizontal(player.position(),goal) < 2.0) { step=Step.BEARING; opened=0; return; }
            if (planar < 4 && player.getY() < ship.getY()-1.5 && player.onGround() && tick % 12 == 0) {
                FlightClient.snap(game,"field-boarding");
            }
            walk(game,planar < 3 ? ship : goal,tick);
            if(planar<5 && player.getY()<ship.getY()+.1 && player.onGround())game.options.keyJump.setDown(true);
        }
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
        var at=terrain.getClass().getDeclaredMethod("at",int.class,int.class);at.setAccessible(true);
        var depression=terrain.getClass().getDeclaredMethod("depression",int.class,int.class);depression.setAccessible(true);
        BlockPos best=null;double score=0;
        for(int dx=-14;dx<=14;dx++)for(int dz=-14;dz<=14;dz++){
            int x=cx+dx,z=cz+dz;
            if(!(boolean)depression.invoke(terrain,x,z))continue;
            int floor=(int)at.invoke(terrain,x,z);var candidate=new BlockPos(x*stride,floor,z*stride);
            if(horizontal(Vec3.atCenterOf(ship),candidate)<10 || rejected.stream().anyMatch(old->horizontal(Vec3.atCenterOf(old),candidate)<10))continue;
            int[] banks={(int)at.invoke(terrain,x-2,z),(int)at.invoke(terrain,x+2,z),(int)at.invoke(terrain,x,z-2),(int)at.invoke(terrain,x,z+2)};
            Arrays.sort(banks);int drop=banks[1]-floor;if(drop<11)continue;
            double rank=drop-.016*horizontal(Vec3.atCenterOf(feet),candidate);if(rank>score){score=rank;best=candidate;}
        }
        return best;
    }

    private static BlockHitResult visibleMasonry(Minecraft game,BlockPos target){
        var eye=game.player.getEyePosition();
        for(int dx=-4;dx<=4;dx+=2)for(int dz=-4;dz<=4;dz+=2)for(int depth=0;depth<=5;depth++){
            var end=new Vec3(target.getX()+dx+.5,target.getY()+depth*4-.5,target.getZ()+dz+.5);
            if(eye.distanceToSqr(end)>64*64)continue;
            var hit=game.level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,game.player));
            if(hit.getType()!=HitResult.Type.BLOCK)continue;
            var state=game.level.getBlockState(hit.getBlockPos());String visible=state.toString().toLowerCase(Locale.ROOT);
            if(visible.contains("galacticraftcore") && visible.contains("moonblock") && visible.matches(".*(?:meta|metadata)=14(?:[,\\]].*)"))return hit;
        }
        return null;
    }
    private void nextLeg(){int n=leg/2+1; int[] dx={1,0,-1,0},dz={0,1,0,-1};legCornerX+=dx[leg%4]*n*82;legCornerZ+=dz[leg%4]*n*82;leg++;goal=new BlockPos(legCornerX,ship.getY(),legCornerZ);}
    private static double horizontal(Vec3 v,BlockPos p){return Math.hypot(v.x-p.getX()-.5,v.z-p.getZ()-.5);}
    private void walk(Minecraft game,BlockPos destination,int tick){
        var p=game.player;double x=destination.getX()+.5-p.getX(),z=destination.getZ()+.5-p.getZ();double d=Math.hypot(x,z);
        if (motionAnchor == null || ((p.getX()-motionAnchor.x)*(p.getX()-motionAnchor.x) + (p.getZ()-motionAnchor.z)*(p.getZ()-motionAnchor.z)) > 1) {
            motionAnchor = p.position(); lastMotionTick = tick; escapeTurn = 0;
        }
        int stuck = tick - lastMotionTick;
        if (stuck > 22) escapeTurn = 1 + (stuck - 23) / 16;
        int[] turn = {0, 90, -90, 180, -45, 45, 135, -135};
        float yaw = (float)Math.toDegrees(Math.atan2(-x,z)) + turn[escapeTurn % turn.length];
        p.setYRot(yaw); p.setXRot(8);
        game.options.keyDown.setDown(false); game.options.keyLeft.setDown(false); game.options.keyRight.setDown(false);
        game.options.keyUp.setDown(true); game.options.keySprint.setDown(d > 5 && stuck < 22);
        game.options.keyJump.setDown(p.onGround() && (p.horizontalCollision || stuck > 13));
    }
    private static void stop(Minecraft game){game.options.keyUp.setDown(false);game.options.keySprint.setDown(false);game.options.keyJump.setDown(false);}
}
