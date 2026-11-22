package dev.starbridge.qa.moonwalk;

import dev.starbridge.client.survey.SurfaceCluesScreen;
import dev.starbridge.client.survey.SurfaceProfileScreen;
import dev.starbridge.client.survey.SurfaceSurveyScreen;
import dev.starbridge.flightqa.SurveyRoute;
import dev.starbridge.survey.FieldNotes;
import dev.starbridge.voyage.VoyageModule;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgelunarfieldqa", value = Dist.CLIENT)
public final class FieldPilot {
    private enum Stage {
        WORLD,
        READY,
        USE,
        MAP,
        PROFILE,
        ROUTE,
        BACK,
        WALK,
        TURN,
        IDENTIFY,
        DRINK,
        SHAFT_LOOK,
        DESCEND,
        ROOM,
        RETRACE,
        CLIMB,
        RIM_EXIT,
        CARGO,
        FLY_HOME,
        DONE
    }

    private static Stage stage = Stage.WORLD;
    private static String lastLoadScreen = "";
    private static int tick,
            entered,
            started,
            checks,
            scans,
            ring,
            leg,
            turn,
            lastMapTick,
            routePlans,
            stepSince,
            goalSince,
            reachedRims;
    private static int spiralX, spiralZ;
    private static boolean markClicked, measured, capturedProfile, sawMasonry, naturalSight, eating;
    private static double walked, horizontalWalked, furthestFromShip;
    private static Vec3 lastPosition;
    private static boolean reopenForRoute;
    private static BlockPos regionalStep, descentRim, shaftFloor;
    private static Vec3 descentStep;
    private static int descentStepTick;
    private static boolean mappedBrick, goingHome;
    private static int lastRimExitAttempt = -400;
    private static int homeStep, timelineOffset;
    private static final ArrayDeque<Vec3> returnTrail = new ArrayDeque<>();
    private static final ArrayDeque<Vec3> knownRoomTrail = new ArrayDeque<>();
    private static int progressOnKnownTrail, knownTrailStart, mealGoal;
    private static Deque<BlockPos> route = new ArrayDeque<>();
    private static final Set<Long> blockedSteps = new HashSet<>();
    private static BlockPos goal, candidate, ship, waypoint, brick;
    private static boolean seekingBrick, verifyNatural;
    private static final List<BlockPos> visited = new ArrayList<>();
    private static final List<Map<String, Object>> readings = new ArrayList<>(),
            trail = new ArrayList<>();
    private static final Set<String> images = new LinkedHashSet<>();
    private static final List<Map<String, Object>> rims = new ArrayList<>();
    private static final String PREFIX = "slate.starbridge.field.";

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) throws Exception {
        if (!FieldStart.ON || e.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (Files.isRegularFile(Path.of("lunar-field.stop"))) {
            stop(mc);
            mc.stop();
            return;
        }
        if (stage == Stage.DONE || mc.getOverlay() != null) return;
        mc.setWindowActive(true);
        tick++;
        try {
            if (stage != Stage.FLY_HOME && stage != Stage.WORLD
                    && stage != Stage.READY
                    && mc.player != null
                    && mc.level != null) {
                var current = mc.player.position();
                if (lastPosition != null) {
                    double step = current.distanceTo(lastPosition);
                    FieldStart.require(step < 12, "Unexpected relocation during foot survey");
                    walked += step;
                    horizontalWalked += Math.hypot(current.x - lastPosition.x, current.z - lastPosition.z);
                    furthestFromShip = Math.max(furthestFromShip, horizontal(mc, ship));
                }
                lastPosition = current;
                if ((stage == Stage.DESCEND || stage == Stage.ROOM) &&
                        (returnTrail.isEmpty() || returnTrail.peekLast().distanceTo(current) > 1.05))
                    returnTrail.addLast(current);
                if (tick % 100 == 0) {
                    trail.add(
                            Map.of(
                                    "tick",
                                    tick + timelineOffset,
                                    "feet",
                                    point(current),
                                    "health",
                                    mc.player.getHealth(),
                                    "state",
                                    stage.name()));
                    progress();
                }
                if (mc.player.isAlive() && stage == Stage.ROOM && tick - started < 29000
                        && FieldCavern.recoverHealth(mc, tick)) return;
                if (!mc.player.isAlive() || tick - started >= 29000) {
                    finish(mc, "field checkpoint");
                    return;
                }
                int hunger = mc.player.getFoodData().getFoodLevel();
                boolean returning = stage == Stage.RETRACE || stage == Stage.CLIMB || goingHome;
                boolean recoveryMeal = (stage == Stage.ROOM || returning) && mc.player.getHealth() < 12 && hunger < 19;
                if ((stage == Stage.WALK || stage == Stage.ROOM || returning) && mc.screen == null
                        && (eating || ((hunger <= 15 || recoveryMeal)
                                && mc.player.getInventory().getItem(2).isEdible()))) {
                    if (!eating) {
                        stop(mc);
                        mealGoal = recoveryMeal ? 19 : 16;
                        mc.player.getInventory().selected = 2;
                        mc.options.keyUse.setDown(true);
                        KeyMapping.click(mc.options.keyUse.getKey());
                        eating = true;
                    }
                    if (hunger >= mealGoal || mc.player.getMainHandItem().isEmpty()) {
                        mc.options.keyUse.setDown(false);
                        mc.gameMode.releaseUsingItem(mc.player);
                        eating = false;
                        mc.player.getInventory().selected = 0;
                    }
                    return;
                }
                if (mc.player.getHealth() < 6) {
                    if (returning && tick - started < 300 && hunger >= 18) {
                        stop(mc);
                        return;
                    }
                    finish(mc, "field checkpoint");
                    return;
                }
            }
            switch (stage) {
                case WORLD -> start(mc);
                case READY -> {
                    if (FieldStart.resuming() && mc.player == null) {
                        inspectLoadScreen(mc);
                        return;
                    }
                    if (FieldStart.resuming()) mc.player.getInventory().selected = 0;
                    if (mc.player == null
                            || mc.level == null
                            || mc.screen != null
                            || !mc.player.getMainHandItem().is(VoyageModule.SLATE.get())
                            || !mc.level.dimension().location().getNamespace().equals("retrofit"))
                        return;
                    if (FieldStart.resuming()) {
                        if (Files.isRegularFile(Path.of("field-rejoined.json"))) resume(mc);
                        return;
                    }
                    ship = mc.player.blockPosition();
                    spiralX = ship.getX();
                    spiralZ = ship.getZ();
                    started = tick;
                    go(Stage.USE);
                }
                case USE -> {
                    if (mc.screen instanceof SurfaceSurveyScreen) {
                        mc.options.keyShift.setDown(false);
                        go(reopenForRoute ? Stage.ROUTE : Stage.MAP);
                        reopenForRoute = false;
                        return;
                    }
                    if (mc.screen != null) return;
                    mc.options.keyShift.setDown(true);
                    if (tick - entered >= 7 && (tick - entered) % 16 == 7)
                        KeyMapping.click(mc.options.keyUse.getKey());
                }
                case MAP -> chart(mc);
                case PROFILE -> profile(mc);
                case ROUTE -> planRoute(mc);
                case BACK -> {
                    if (!(mc.screen instanceof SurfaceSurveyScreen map)) return;
                    if ((tick - entered) > 3 && FieldChart.click(map, "gui.done")) {
                        stepSince = tick;
                        go(Stage.WALK);
                    }
                }
                case WALK -> walk(mc);
                case TURN -> examine(mc);
                case IDENTIFY -> identify(mc);
                case DRINK -> {
                    if (mc.screen != null) {
                        mc.screen.onClose();
                        return;
                    }
                    if (tick - entered == 4) {
                        mc.player.getInventory().selected = 3;
                        mc.options.keyUse.setDown(true);
                        KeyMapping.click(mc.options.keyUse.getKey());
                    }
                    if (mc.player.hasEffect(MobEffects.SLOW_FALLING)) {
                        mc.options.keyUse.setDown(false);
                        mc.gameMode.releaseUsingItem(mc.player);
                        mc.player.getInventory().selected = 0;
                        go(sawMasonry ? Stage.DESCEND : Stage.SHAFT_LOOK);
                    } else if (tick - entered > 95)
                        finish(mc, "no slow-falling effect for descent");
                }
                case SHAFT_LOOK -> enterOpening(mc);
                case DESCEND -> descend(mc);
                case ROOM -> inside(mc);
                case RETRACE -> retrace(mc);
                case CLIMB -> climb(mc);
                case RIM_EXIT -> {
                    if (mc.screen == null && !FieldCavern.leaveShaft(mc, tick, ship)) {
                        lastRimExitAttempt = tick;
                        mc.player.getInventory().selected = 0;
                        consultRoute(mc);
                    }
                }
                case CARGO -> {
                    if (Files.isRegularFile(Path.of("field-cargo.json"))) {
                        if (mc.screen != null) mc.screen.onClose();
                        stop(mc);go(Stage.FLY_HOME);
                    } else if (Files.isRegularFile(Path.of("field-boarded.json"))) TreasureHands.work(mc,tick,true);
                }
                case FLY_HOME -> flyHome(mc);
                default -> {}
            }
        } catch (Exception | AssertionError failure) {
            stop(mc);
            FieldStart.write(
                    "field-failed.json",
                    Map.of("source", "client", "stage", stage.name(), "error", failure.toString()));
            go(Stage.DONE);
        }
    }

    private static void inspectLoadScreen(Minecraft mc) throws Exception {
        var screen = mc.screen;
        if (screen == null) return;
        String key = screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents title
                ? title.getKey() : "";
        String name = screen.getClass().getSimpleName();
        if (!(name + "/" + key).equals(lastLoadScreen)) {
            lastLoadScreen = name + "/" + key;
            FieldStart.write("field-load-screen.json", Map.of("class", name, "titleKey", key, "tick", tick));
        }
        // The source world is already archived. Continue only the known experimental-world prompt.
        boolean experimental = key.equals("selectWorld.warning.experimental.title") || key.equals("selectWorld.backupQuestion.experimental");
        if (!experimental) return;
        if (screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen) {
            FieldChart.click(screen, "selectWorld.backupJoinSkipButton");
        } else if (screen instanceof net.minecraft.client.gui.screens.ConfirmScreen) {
            if (!FieldChart.click(screen, "gui.proceed")) FieldChart.click(screen, "gui.yes");
        }
    }

    private static void start(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof TitleScreen)) return;
        var files = new LinkedHashMap<String, String>();
        for (String id : List.of("starbridge", "retrofit", "starbridgelunarfieldqa")) {
            var file = ModList.get().getModFileById(id).getFile().getFilePath();
            FieldStart.require(
                    Files.isSameFile(file, Path.of("mods").resolve(file.getFileName().toString())),
                    "Installed Forge JAR " + id);
            files.put(
                    id,
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(file))));
        }
        FieldStart.require(
                "zh_cn".equals(mc.getLanguageManager().getSelected()), "Chinese language");
        FieldStart.write("field-loaded.json", files);
        var settings =
                new LevelSettings(
                        "FieldWalk",
                        GameType.SURVIVAL,
                        false,
                        Difficulty.NORMAL,
                        false,
                        new GameRules(),
                        WorldDataConfiguration.DEFAULT);
        if (FieldStart.resuming()) {
            mc.createWorldOpenFlows().loadLevel(mc.screen, "FieldWalk");
            go(Stage.READY);
            return;
        }
        mc.createWorldOpenFlows()
                .createFreshLevel(
                        "FieldWalk",
                        settings,
                        new WorldOptions(FieldStart.SEED, true, false),
                        WorldPresets::createNormalWorldDimensions);
        go(Stage.READY);
    }

    private static BlockPos pos(com.google.gson.JsonArray value) {
        return new BlockPos(value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt());
    }
    private static Vec3 vec(com.google.gson.JsonArray value) {
        return new Vec3(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble());
    }
    private static void restoreRows(com.google.gson.JsonObject source,String field,List<Map<String,Object>> destination) {
        var type = new com.google.gson.reflect.TypeToken<List<Map<String,Object>>>() {}.getType();
        destination.addAll(new com.google.gson.Gson().fromJson(source.get(field),type));
    }
    private static void resume(Minecraft mc) throws Exception {
        var p = mc.player;
        var slate = p.getMainHandItem().getTag();
        FieldStart.require(slate != null,"Restored slate and ship mark");
        var notes = slate.getCompound("StarbridgeFieldNotes").getCompound(p.level().dimension().location().toString());
        FieldStart.require(notes.contains("SHIP",4),"Ship mark on the returning player's terminal");
        ship = BlockPos.of(notes.getLong("SHIP"));
        var old = FieldStart.read("previous-field-progress");
        var outcome = FieldStart.read("previous-field-result");
        restoreRows(old,"profiles",readings);
        restoreRows(old,"trail",trail);
        restoreRows(old,"rimInspections",rims);
        if (!trail.isEmpty()) timelineOffset = ((Number)trail.get(trail.size()-1).get("tick")).intValue();
        for (var row : old.getAsJsonArray("profiles")) {
            var center = row.getAsJsonObject().getAsJsonArray("center");
            visited.add(new BlockPos(center.get(0).getAsInt(),0,center.get(1).getAsInt()));
        }
        for (var name : old.getAsJsonArray("photos")) images.add(name.getAsString());
        markClicked = old.get("markedShip").getAsBoolean();
        measured = !readings.isEmpty();
        reachedRims = rims.size();
        walked = old.get("walked").getAsDouble();
        horizontalWalked = old.get("horizontalWalked").getAsDouble();
        furthestFromShip = old.get("furthestFromShip").getAsDouble();
        routePlans = old.get("walkingPlans").getAsInt();
        checks = old.get("visibleFaceChecks").getAsInt();
        scans = old.get("mapVisits").getAsInt();
        naturalSight = outcome.get("naturalRockFace").getAsBoolean();
        sawMasonry = outcome.get("masonry").getAsBoolean();
        spiralX = ship.getX(); spiralZ = ship.getZ();
        // Recover the next unvisited leg from the expedition's walking record, never from terrain outside the map.
        for (int i=0;i<64;i++) {
            explore(mc);
            int x=waypoint.getX(),z=waypoint.getZ();
            boolean walkedHere = false;
            for (var entry : old.getAsJsonArray("trail")) {
                var at = vec(entry.getAsJsonObject().getAsJsonArray("feet"));
                if (Math.hypot(at.x-x-.5,at.z-z-.5)<15) {walkedHere=true;break;}
            }
            if (!walkedHere) break;
            waypoint=null;
        }
        started = tick;
        boolean suspendedDescent = false;
        if (Files.isRegularFile(Path.of("previous-field-course.json"))) {
            var course=FieldStart.read("previous-field-course");
            suspendedDescent = course.get("stage").getAsString().equals(Stage.DESCEND.name());
            if (course.has("rim")) descentRim=pos(course.getAsJsonArray("rim"));
            if (course.has("shaftFloor")) shaftFloor=pos(course.getAsJsonArray("shaftFloor"));
            for (var entry:course.getAsJsonArray("undergroundTrail")) returnTrail.add(vec(entry.getAsJsonArray()));
            FieldCavern.restoreVisits(course.getAsJsonArray("roomVisits"), returnTrail);
            if (course.has("spiral")) {
                var spiral=course.getAsJsonArray("spiral");leg=spiral.get(0).getAsInt();spiralX=spiral.get(1).getAsInt();spiralZ=spiral.get(2).getAsInt();
                waypoint=course.has("waypoint")?pos(course.getAsJsonArray("waypoint")):null;
            }
            goingHome=course.get("goingHome").getAsBoolean();
        }
        if (Files.isRegularFile(Path.of("field-native-interaction.json"))) {
            if (descentRim == null && old.getAsJsonArray("rimInspections").size()>0) {
                var visits=old.getAsJsonArray("rimInspections");descentRim=BlockPos.containing(vec(visits.get(visits.size()-1).getAsJsonObject().getAsJsonArray("feet")));
            }
            if (shaftFloor == null) {
                var face = FieldStart.read("field-native-interaction").get("face").getAsString().split(",");
                FieldStart.require(face.length == 3,"Saved interaction face");
                shaftFloor = new BlockPos(Integer.parseInt(face[0].trim()),Integer.parseInt(face[1].trim()),Integer.parseInt(face[2].trim())).above();
            }
            if (Files.isRegularFile(Path.of("field-reward.json")) && goingHome) {
                goal=ship; goalSince=tick; consultRoute(mc);
            } else if (Files.isRegularFile(Path.of("field-reward.json"))) beginReturn(mc.player.position());
            else {
                if (FieldVessel.keys(mc.player) > 0) revisitDeepestRooms(mc.player.position());
                go(Stage.ROOM);
            }
        } else if (suspendedDescent && sawMasonry && Files.isRegularFile(Path.of("field-identified-face.json"))) {
            brick = pos(FieldStart.read("field-identified-face").getAsJsonArray("observedFace"));
            go(Stage.DESCEND);
        } else go(Stage.USE);
    }

    private static void chart(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof SurfaceSurveyScreen map)) return;
        var view = (FieldNotes.View) FieldChart.field(map, "view");
        if (view.ship() == null) {
            if (!markClicked) markClicked = FieldChart.click(map, PREFIX + "mark_ship");
            return;
        }
        if (seekingBrick && view.site() == null && !FieldChart.click(map, PREFIX + "mark_site"))
            return;
        if (seekingBrick || verifyNatural) {
            FieldChart.click(map, PREFIX + "profile.title");
            go(Stage.PROFILE);
            return;
        }
        if ((int) FieldChart.field(map, "stride") < 8) {
            FieldChart.click(map, PREFIX + "zoom_out");
            entered = tick;
            return;
        }
        if (tick - entered < 50) return;
        scans++;
        lastMapTick = tick;
        var selected = FieldChart.best(map, mc.player.blockPosition(), visited);
        // After the first comparative profiles, complete the search transect until the displayed
        // surface texture changes. Carrying a marked map avoids revisiting every natural fissure.
        if (selected != null && !selected.surfaceBrick() && measured && (naturalSight || readings.size() >= 3))
            selected = null;
        if (selected == null && !measured) selected = new FieldChart.Cell(18, 18, 0, 0, 0, false);
        if (selected != null) {
            mappedBrick = selected.surfaceBrick();
            if (mappedBrick && !images.contains("surface-masonry")) {
                if (!FieldChart.surfaceFrame(mc, map, selected)) return;
                images.add("surface-masonry");
            }
            FieldChart.open(map, selected);
            go(Stage.PROFILE);
            return;
        }
        candidate = null;
        explore(mc);
        newRoute();
    }

    private static void profile(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof SurfaceProfileScreen screen) || tick - entered < 4) return;
        if (seekingBrick || verifyNatural) {
            if (FieldChart.click(screen, PREFIX + "sight.inspect")) go(Stage.IDENTIFY);
            return;
        }
        if (!capturedProfile) return;
        var p = FieldChart.field(screen, "profile");
        int x = (int) FieldChart.field(p, "x"), z = (int) FieldChart.field(p, "z");
        int depth = (int) FieldChart.field(p, "depth"),
                ew = (int) FieldChart.field(p, "eastWidth"),
                ns = (int) FieldChart.field(p, "northWidth");
        String shape = FieldChart.field(p, "shape").toString();
        int[] a = (int[]) FieldChart.field(p, "eastWest"),
                b = (int[]) FieldChart.field(p, "northSouth");
        readings.add(
                Map.of(
                        "center",
                        List.of(x, z),
                        "depth",
                        depth,
                        "width",
                        List.of(ew, ns),
                        "shape",
                        shape,
                        "eastWest",
                        a,
                        "northSouth",
                        b,
                        "from",
                        point(mc.player.position())));
        var location = new BlockPos(x, mappedBrick ? a[12] : mc.player.getBlockY(), z);
        visited.add(location);
        // A shallow hollow is recorded on the chart. Aim the walking party at deeper enclosed cuts.
        if (mappedBrick || depth >= 10 && !shape.equals("UNSURVEYED")) {
            candidate = location;
            ring = 0;
            goal = rim(mc, 0);
        } else {
            candidate = null;
            explore(mc);
        }
        measured = true;
        FieldChart.click(screen, PREFIX + "clues.back");
        newRoute();
    }

    private static void explore(Minecraft mc) {
        if (waypoint == null || horizontal(mc, waypoint) < 9) {
            int[] dx = {1, 0, -1, 0}, dz = {0, 1, 0, -1};
            int length = (leg / 2 + 1) * 92;
            spiralX += dx[leg % 4] * length;
            spiralZ += dz[leg % 4] * length;
            leg++;
            waypoint = new BlockPos(spiralX, mc.player.getBlockY(), spiralZ);
        }
        goal = waypoint;
    }

    private static BlockPos rim(Minecraft mc, int n) {
        double angle =
                Math.atan2(mc.player.getZ() - candidate.getZ(), mc.player.getX() - candidate.getX())
                        + n * Math.PI / 2;
        return candidate.offset(
                (int) Math.round(6 * Math.cos(angle)), 0, (int) Math.round(6 * Math.sin(angle)));
    }

    private static void newRoute() {
        goalSince = tick;
        regionalStep = null;
        route.clear();
        go(Stage.ROUTE);
    }

    private static void consultRoute(Minecraft mc) {
        stop(mc);
        regionalStep = null;
        reopenForRoute = true;
        go(Stage.USE);
    }

    private static void abandonApproach(Minecraft mc) {
        stop(mc);
        if (goingHome) {
            // Keep the ship marker. Retry a new short route from the latest physical position.
            goal = ship;
            goalSince = tick;
            consultRoute(mc);
            if (mc.screen instanceof SurfaceSurveyScreen) { reopenForRoute = false; go(Stage.ROUTE); }
            return;
        }
        candidate = null;
        waypoint = null;
        route.clear();
        regionalStep = null;
        // When already reading the notebook, go straight back to choosing a surface mark.
        go(mc.screen instanceof SurfaceSurveyScreen ? Stage.MAP : Stage.USE);
    }

    private static void planRoute(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof SurfaceSurveyScreen map)) return;
        if (tick - goalSince > 2400) {
            abandonApproach(mc);
            return;
        }
        int stride = (int) FieldChart.field(map, "stride");
        int wanted = regionalStep == null ? 4 : 1;
        if (stride != wanted) {
            FieldChart.click(map, PREFIX + (stride > wanted ? "zoom_in" : "zoom_out"));
            entered = tick;
            return;
        }
        // The 33x33 visible terrain grid fills over several frames after every zoom.
        if (tick - entered < (stride == 1 ? 20 : 40)) return;
        if (regionalStep == null) {
            regionalStep = goal;
            for (var step : SurveyRoute.across(map, goal, Set.of())) {
                regionalStep = step;
                if (horizontal(mc, step) >= 15) break;
            }
            return;
        }
        route = SurveyRoute.across(map, regionalStep, blockedSteps);
        if (route.isEmpty()) route = SurveyRoute.across(map, goal, blockedSteps);
        routePlans++;
        if (route.isEmpty() && horizontal(mc, goal) > 2.6) {
            if (goingHome && tick - lastRimExitAttempt >= 400
                    && mc.level.getBlockState(mc.player.blockPosition().below()).is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                    && FieldChart.click(map, "gui.done")) {
                go(Stage.RIM_EXIT);
                return;
            }
            abandonApproach(mc);
            return;
        }
        go(Stage.BACK);
    }

    private static void walk(Minecraft mc) {
        if (mc.screen != null) return;
        if (goingHome && horizontal(mc, ship) < 1.55 && Math.abs(mc.player.getY() - ship.getY()) < 2) {
            stop(mc);
            mc.player.getInventory().selected = 0;
            go(Stage.CARGO);
            return;
        }
        if (candidate != null && horizontal(mc, goal) < 2.6 && horizontal(mc, candidate) < 10) {
            stop(mc);
            reachedRims++;
            rims.add(Map.of("tick", tick + timelineOffset, "feet", point(mc.player.position()),
                    "surfaceCenter", List.of(candidate.getX(), candidate.getZ()),
                    "rimPoint", List.of(goal.getX(), goal.getZ())));
            turn = 0;
            go(Stage.TURN);
            return;
        }
        if (!goingHome && candidate == null && (horizontal(mc, goal) < 8 || tick - lastMapTick > 450)) {
            stop(mc);
            go(Stage.USE);
            return;
        }
        if (tick - goalSince > 2400) {
            abandonApproach(mc);
            return;
        }
        while (!route.isEmpty() && horizontal(mc, route.peek()) < .58
                && Math.abs(mc.player.getY() - route.peek().getY()) < 1.2) {
            route.remove();
            stepSince = tick;
        }
        if (route.isEmpty()) {
            consultRoute(mc);
            return;
        }
        BlockPos next = route.peek();
        if (tick - stepSince > 50) {
            blockedSteps.add(BlockPos.asLong(next.getX(), 0, next.getZ()));
            consultRoute(mc);
            return;
        }
        double dx = next.getX() + .5 - mc.player.getX(), dz = next.getZ() + .5 - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot(8f);
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(mc.player.onGround()
                && (next.getY() > mc.player.getY() + .2 || mc.player.horizontalCollision));
    }

    private static void examine(Minecraft mc) throws Exception {
        if (candidate == null) {
            go(Stage.USE);
            return;
        }
        int age = tick - entered;
        double yaw =
                Math.toDegrees(
                        Math.atan2(
                                -(candidate.getX() + .5 - mc.player.getX()),
                                candidate.getZ() + .5 - mc.player.getZ()));
        int[] pans = {0, -11, 11, -23, 23, -37, 37};
        int[] tilts = {48, 61, 74, 35, 83, 20};
        if (turn >= pans.length * tilts.length) {
            if (mappedBrick && !sawMasonry && horizontal(mc,candidate) < 10) {
                stop(mc);
                descentRim = mc.player.blockPosition();
                go(Stage.DRINK);
                return;
            }
            if (++ring < 3) {
                goal = rim(mc, 1);
                goalSince = tick;
                consultRoute(mc);
            } else {
                candidate = null;
                go(Stage.USE);
            }
            return;
        }
        if (age % 3 == 1) {
            mc.player.setYRot((float) yaw + pans[turn % pans.length]);
            mc.player.setXRot(tilts[turn / pans.length]);
            return;
        }
        if (age % 3 != 2) return;
        var h = crosshair(mc, 48);
        turn++;
        checks++;
        if (h.getType() != HitResult.Type.BLOCK) return;
        if (isBrick(mc, h)) {
            stop(mc);
            brick = h.getBlockPos().immutable();
            seekingBrick = true;
            mc.player.lookAt(Anchor.EYES, h.getLocation());
            snap(mc, "exposed-brick");
            go(Stage.USE);
        } else if (!naturalSight && !verifyNatural) {
            verifyNatural = true;
            go(Stage.USE);
        }
    }

    private static void identify(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof SurfaceCluesScreen) || tick - entered < 6) return;
        var observed = FieldChart.field(mc.screen, "observed");
        if (observed == null) return;
        String kind = (String) FieldChart.field(observed, "description");
        if (seekingBrick) {
            if (!kind.equals("brick")) {
                seekingBrick = false;
                enterSurvey(mc);
                return;
            }
            var visible = (BlockPos) FieldChart.field(observed, "surface");
            if (!images.contains("identified-brick")) return;
            // A distant sight line can move onto another brick before the terminal opens.
            // Continue towards the exact face the terminal measured from the player's eyes.
            FieldStart.write("field-identified-face.json", Map.of(
                    "firstFace", List.of(brick.getX(), brick.getY(), brick.getZ()),
                    "observedFace", List.of(visible.getX(), visible.getY(), visible.getZ()),
                    "feet", point(mc.player.position()), "range", FieldChart.field(observed, "distance")));
            brick = visible;
            sawMasonry = true;
            if (descentRim == null) descentRim = mc.player.blockPosition();
            go(Stage.DRINK);
        } else {
            if (kind.equals("rock") && !images.contains("natural-face")) return;
            naturalSight = kind.equals("rock");
            verifyNatural = false;
            enterSurvey(mc);
        }
    }

    private static void enterSurvey(Minecraft mc) {
        mc.screen.onClose();
        if (mc.screen instanceof SurfaceSurveyScreen map) FieldChart.click(map, "gui.done");
        go(Stage.TURN);
    }

    private static void enterOpening(Minecraft mc) throws Exception {
        var player = mc.player;
        if (!player.hasEffect(MobEffects.SLOW_FALLING)) {
            finish(mc, "Protected descent ended before the visible face was confirmed");
            return;
        }
        // The measured point and floor height come from the actual selected profile. First-person
        // line of sight still has to see original masonry and the server later requires hand reach.
        var measured = Vec3.atCenterOf(candidate.below());
        player.lookAt(Anchor.EYES, measured);
        var hit = ray(mc, player.getEyePosition(), measured);
        if (hit.getType()==HitResult.Type.BLOCK && isBrick(mc,hit)) {
            stop(mc);
            brick=hit.getBlockPos().immutable();
            seekingBrick=true;
            player.lookAt(Anchor.EYES,hit.getLocation());
            snap(mc,"exposed-brick");
            go(Stage.USE);
            return;
        }
        double gap=horizontal(mc,candidate);
        mc.options.keySprint.setDown(false);
        mc.options.keyUp.setDown(gap>.28);
        mc.options.keyJump.setDown(player.onGround() && gap>.7 && player.horizontalCollision);
        if (tick-entered>500) finish(mc,"Measured deep opening approached under slow falling; no visible native brick");
    }

    private static void descend(Minecraft mc) throws Exception {
        if (Files.isRegularFile(Path.of("field-native-interaction.json"))) {
            stop(mc);
            shaftFloor = mc.player.blockPosition();
            returnTrail.clear();
            returnTrail.add(mc.player.position());
            go(Stage.ROOM);
            return;
        }
        var p = mc.player;
        p.lookAt(Anchor.EYES, Vec3.atCenterOf(brick));
        var hit = crosshair(mc, 4.4);
        if (hit.getType() == HitResult.Type.BLOCK && isBrick(mc, hit)) {
            stop(mc);
            p.getInventory().selected = 1;
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }
        p.getInventory().selected = 1;
        if (descentStep == null || (p.onGround() && tick - descentStepTick > 30)) {
            descentStep = lowerOpening(mc);
            descentStepTick = tick;
        }
        if (descentStep == null) {
            mc.options.keyUp.setDown(false);
        } else {
            double dx = descentStep.x - p.getX(), dz = descentStep.z - p.getZ();
            p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.options.keyUp.setDown(Math.hypot(dx, dz) > .22);
        }
        mc.options.keyJump.setDown(false);
        if (tick - entered > 450)
            finish(mc, "Visible masonry recorded; no reachable face during descent");
    }

    private static Vec3 lowerOpening(Minecraft mc) {
        var player = mc.player;
        var feet = player.position();
        var eye = player.getEyePosition();
        int safeDepth = player.hasEffect(MobEffects.SLOW_FALLING) ? 26 : 4;
        Vec3 best = null;
        double score = 0;
        var origin = player.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                var edge = new Vec3(origin.getX() + dx + .5, feet.y + .05, origin.getZ() + dz + .5);
                if (ray(mc, eye, edge).getType() != HitResult.Type.MISS) continue;
                var floor = ray(mc, edge, edge.add(0, -safeDepth, 0));
                if (floor.getType() != HitResult.Type.BLOCK) continue;
                double drop = feet.y - floor.getLocation().y;
                if (drop < .75) continue;
                double priority = drop - Math.hypot(edge.x - brick.getX() - .5, edge.z - brick.getZ() - .5);
                if (priority <= score) continue;
                best = edge;
                score = priority;
            }
        }
        return best;
    }

    private static void inside(Minecraft mc) throws Exception {
        if (Files.isRegularFile(Path.of("field-reward.json"))) {
            stop(mc);
            if (mc.screen != null) mc.screen.onClose();
            beginReturn(mc.player.position());
        } else if (tick - entered > 5200) finish(mc, "Original dungeon interaction recorded; room walk checkpoint");
        else {
            boolean canOpenTreasure = FieldVessel.keys(mc.player) > 0
                    || Files.isRegularFile(Path.of("field-chest-open.json"));
            if (!canOpenTreasure || !TreasureHands.work(mc, tick, false)) {
                // Reach the recorded side of a floor edge before advancing to the next footprint.
                while (!knownRoomTrail.isEmpty() && knownRoomTrail.peekFirst().distanceTo(mc.player.position()) < .35) {
                    knownRoomTrail.removeFirst();
                    progressOnKnownTrail = tick;
                }
                if (!knownRoomTrail.isEmpty() && FieldCavern.bridging()) progressOnKnownTrail = tick;
                if (!knownRoomTrail.isEmpty() && tick - progressOnKnownTrail > 40
                        && !FieldCavern.canFollowRemembered(mc, knownRoomTrail.peekFirst())) {
                    recoverVisibleTrail(mc);
                }
                if (!knownRoomTrail.isEmpty() && tick - progressOnKnownTrail > 180) {
                    FieldStart.write("field-trail-blocked.json", Map.of(
                            "feet", point(mc.player.position()), "next", point(knownRoomTrail.peekFirst()),
                            "remaining", knownRoomTrail.size(), "elapsedTicks", tick - started));
                    knownRoomTrail.clear();
                }
                FieldCavern.move(mc, tick, knownRoomTrail.peekFirst());
            }
        }
    }

    private static void recoverVisibleTrail(Minecraft mc) {
        int skipped = 0;
        for (Vec3 point : knownRoomTrail) {
            if (skipped > 4) break;
            if (FieldCavern.canFollowRemembered(mc, point)) {
                for (int i = 0; i < skipped; i++) knownRoomTrail.removeFirst();
                progressOnKnownTrail = tick;
                return;
            }
            skipped++;
        }
    }

    private static void revisitDeepestRooms(Vec3 feet) {
        if (shaftFloor == null || returnTrail.isEmpty()) return;
        knownRoomTrail.addAll(RoomTrail.towardDeepest(returnTrail, feet, Vec3.atBottomCenterOf(shaftFloor)));
        knownTrailStart = knownRoomTrail.size();
        progressOnKnownTrail = tick;
    }

    private static void beginReturn(Vec3 feet) {
        knownRoomTrail.clear();
        knownRoomTrail.addAll(RoomTrail.towardEntrance(returnTrail, feet, Vec3.atBottomCenterOf(shaftFloor)));
        knownTrailStart = knownRoomTrail.size();
        progressOnKnownTrail = tick;
        go(Stage.RETRACE);
    }

    private static void retrace(Minecraft mc) {
        var feet = mc.player.position();
        var shaft = new Vec3(shaftFloor.getX() + .5, feet.y, shaftFloor.getZ() + .5);
        if (feet.distanceTo(shaft) < .45 && mc.player.onGround()) {
            stop(mc);
            go(Stage.CLIMB);
            return;
        }
        while (!knownRoomTrail.isEmpty() && knownRoomTrail.peekFirst().distanceTo(feet) < .35) {
            knownRoomTrail.removeFirst();
            progressOnKnownTrail = tick;
        }
        if (FieldCavern.bridging()) progressOnKnownTrail = tick;
        if (!knownRoomTrail.isEmpty() && tick - progressOnKnownTrail > 30
                && !FieldCavern.canFollowRemembered(mc, knownRoomTrail.peekFirst())) recoverVisibleTrail(mc);
        if (tick - progressOnKnownTrail > 160) {
            knownRoomTrail.clear();
            knownRoomTrail.addAll(RoomTrail.towardEntrance(returnTrail, feet, Vec3.atBottomCenterOf(shaftFloor)));
            progressOnKnownTrail = tick;
        }
        Vec3 target = knownRoomTrail.peekFirst();
        if (FieldCavern.canFollowRemembered(mc, shaft)) {
            target = shaft;
        } else if (feet.distanceToSqr(shaft) < 24 * 24
                && (target == null || knownRoomTrail.size() <= 2 && tick - progressOnKnownTrail > 30)) {
            // A collapsed floor can split the remembered route just before the shaft.
            // The cave walker checks footing and can bridge only to a visible firm landing.
            var direction = net.minecraft.core.Direction.getNearest(shaft.x - feet.x, 0, shaft.z - feet.z);
            target = Vec3.atBottomCenterOf(mc.player.blockPosition().relative(direction));
        }
        FieldCavern.move(mc, tick, target);
    }

    private static void climb(Minecraft mc) {
        var p = mc.player;
        if (p.getY() >= descentRim.getY() && p.onGround()) {
            stop(mc);
            goingHome = true;
            candidate = null;
            goal = ship;
            goalSince = tick;
            p.getInventory().selected = 0;
            consultRoute(mc);
            return;
        }
        p.getInventory().selected = 6;
        p.setXRot(90);
        mc.options.keyJump.setDown(true);
        if (tick % 2 != 0) return;
        var support = crosshair(mc, 4.3);
        if (support.getType() == HitResult.Type.BLOCK && support.getDirection() == net.minecraft.core.Direction.UP
                && p.getY() - support.getLocation().y >= 1.04 && p.getY() - support.getLocation().y < 2.5)
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, support);
    }

    private static void flyHome(Minecraft mc) throws Exception {
        if (Files.isRegularFile(Path.of("field-return.json"))) { finish(mc,"Key and same ship returned home"); return; }
        if (tick - entered > 2000) { finish(mc,"Homeward voyage checkpoint"); return; }
        if (mc.player == null || tick % 4 != 0) return;
        if (homeStep == 0 && mc.screen == null) {
            mc.options.keyShift.setDown(false);
            mc.player.getInventory().selected = 0;
            mc.player.setXRot(-70);
            KeyMapping.click(mc.options.keyUse.getKey());
            return;
        }
        if (mc.screen == null) return;
        String[] keys = {"route.starbridge.home","nav.starbridge.cruise","orbit.starbridge.landing","orbit.starbridge.touchdown"};
        if (homeStep < keys.length && FieldChart.click(mc.screen, keys[homeStep])) homeStep++;
    }

    private static boolean isBrick(Minecraft mc, BlockHitResult h) {
        var state = mc.level.getBlockState(h.getBlockPos());
        var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null
                && id.toString().equals("galacticraftcore:tile.moonblock")
                && state.getValues().entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey().getName().equals("meta")
                                                && e.getValue().equals(14));
    }

    private static BlockHitResult ray(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(
                new ClipContext(
                        from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    private static BlockHitResult crosshair(Minecraft mc, double distance) {
        var eye = mc.player.getEyePosition();
        return ray(mc, eye, eye.add(mc.player.getViewVector(1).scale(distance)));
    }

    private static double horizontal(Minecraft mc, BlockPos b) {
        return Math.hypot(mc.player.getX() - b.getX() - .5, mc.player.getZ() - b.getZ() - .5);
    }

    private static List<Double> point(Vec3 v) {
        return List.of(v.x, v.y, v.z);
    }

    private static void go(Stage s) {
        stage = s;
        entered = tick;
        if (s == Stage.PROFILE) capturedProfile = false;
    }

    private static void stop(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyUse.setDown(false);
    }

    private static void snap(Minecraft mc, String name) throws Exception {
        if (images.contains(name)) return;
        try (var im = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var folder = Path.of("screenshots");
            Files.createDirectories(folder);
            im.writeToFile(folder.resolve(name + ".png"));
        }
        images.add(name);
    }

    @SubscribeEvent
    public static void rendered(ScreenEvent.Render.Post e) throws Exception {
        if (!FieldStart.ON) return;
        var mc = Minecraft.getInstance();
        if (stage == Stage.PROFILE
                && !capturedProfile
                && e.getScreen() instanceof SurfaceProfileScreen sc
                && !seekingBrick
                && !verifyNatural) {
            e.getGuiGraphics().flush();
            var p = FieldChart.field(sc, "profile");
            String name =
                    "profile-" + FieldChart.field(p, "shape").toString().toLowerCase(Locale.ROOT);
            snap(mc, name);
            capturedProfile = true;
        }
        if (stage == Stage.IDENTIFY && e.getScreen() instanceof SurfaceCluesScreen) {
            var face = FieldChart.field(e.getScreen(), "observed");
            if (face == null) return;
            var kind = FieldChart.field(face, "description");
            e.getGuiGraphics().flush();
            if ("brick".equals(kind) && seekingBrick) snap(mc, "identified-brick");
            if ("rock".equals(kind) && verifyNatural) snap(mc, "natural-face");
        }
    }

    private static void progress() throws Exception {
        var progress = new LinkedHashMap<String, Object>();
        progress.put("stage", stage.name());
        progress.put("previousTimelineTicks", timelineOffset);
        progress.put("markedShip", markClicked);
        progress.put("walked", walked);
        progress.put("horizontalWalked", horizontalWalked);
        progress.put("furthestFromShip", furthestFromShip);
        progress.put("closeRims", reachedRims);
        progress.put("rimInspections", rims);
        progress.put("walkingPlans", routePlans);
        progress.put("blockedSteps", blockedSteps.size());
        progress.put("mapVisits", scans);
        progress.put("visibleFaceChecks", checks);
        progress.put("profiles", readings);
        progress.put("trail", trail);
        progress.put("photos", images);
        progress.put("sawMasonry", sawMasonry);
        progress.put("knownTrailStart", knownTrailStart);
        progress.put("knownTrailRemaining", knownRoomTrail.size());
        FieldStart.write("field-progress.json", progress);
    }

    private static void finish(Minecraft mc, String why) throws Exception {
        stop(mc);
        progress();
        var course=new LinkedHashMap<String,Object>();
        course.put("stage",stage.name());
        course.put("spiral",List.of(leg,spiralX,spiralZ));
        course.put("goingHome",goingHome);
        if (waypoint != null) course.put("waypoint",List.of(waypoint.getX(),waypoint.getY(),waypoint.getZ()));
        if (descentRim != null) course.put("rim",List.of(descentRim.getX(),descentRim.getY(),descentRim.getZ()));
        if (shaftFloor != null) course.put("shaftFloor",List.of(shaftFloor.getX(),shaftFloor.getY(),shaftFloor.getZ()));
        course.put("undergroundTrail",returnTrail.stream().map(FieldPilot::point).toList());
        course.put("roomVisits", FieldCavern.saveVisits());
        FieldStart.write("field-course.json",course);
        FieldStart.write(
                "field-result.json",
                Map.of(
                        "reason",
                        why,
                        "elapsedTicks",
                        tick - started,
                        "walked",
                        walked,
                        "graphicalProfiles",
                        readings.size(),
                        "marker",
                        markClicked,
                        "naturalRockFace",
                        naturalSight,
                        "masonry",
                        sawMasonry,
                        "nativeInteraction",
                        Files.isRegularFile(Path.of("field-native-interaction.json")),
                        "boss",
                        Files.isRegularFile(Path.of("field-boss.json")),
                        "key",
                        Files.isRegularFile(Path.of("field-key.json"))));
        go(Stage.DONE);
    }
}
