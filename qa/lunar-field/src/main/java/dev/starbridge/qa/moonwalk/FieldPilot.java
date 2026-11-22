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
import net.minecraft.world.entity.monster.Monster;
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
        DESCEND,
        ROOM,
        DONE
    }

    private static Stage stage = Stage.WORLD;
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
    private static BlockPos regionalStep;
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
            if (stage != Stage.WORLD
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
                if (tick % 100 == 0) {
                    trail.add(
                            Map.of(
                                    "tick",
                                    tick,
                                    "feet",
                                    point(current),
                                    "health",
                                    mc.player.getHealth(),
                                    "state",
                                    stage.name()));
                    progress();
                }
                if (!mc.player.isAlive() || mc.player.getHealth() < 6 || tick - started >= 14500) {
                    finish(mc, "field checkpoint");
                    return;
                }
                if ((stage == Stage.WALK || stage == Stage.ROOM)
                        && (eating || mc.player.getFoodData().getFoodLevel() <= 15)) {
                    if (!eating) {
                        stop(mc);
                        mc.player.getInventory().selected = 2;
                        mc.options.keyUse.setDown(true);
                        KeyMapping.click(mc.options.keyUse.getKey());
                        eating = true;
                    }
                    if (mc.player.getFoodData().getFoodLevel() > 15
                            || mc.player.getMainHandItem().isEmpty()) {
                        mc.options.keyUse.setDown(false);
                        mc.gameMode.releaseUsingItem(mc.player);
                        eating = false;
                        mc.player.getInventory().selected = 0;
                    }
                    return;
                }
            }
            switch (stage) {
                case WORLD -> start(mc);
                case READY -> {
                    if (mc.player == null
                            || mc.level == null
                            || mc.screen != null
                            || !mc.player.getMainHandItem().is(VoyageModule.SLATE.get())
                            || !mc.level.dimension().location().getNamespace().equals("retrofit"))
                        return;
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
                        go(Stage.DESCEND);
                    } else if (tick - entered > 95)
                        finish(mc, "no slow-falling effect for descent");
                }
                case DESCEND -> descend(mc);
                case ROOM -> inside(mc);
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
        mc.createWorldOpenFlows()
                .createFreshLevel(
                        "FieldWalk",
                        settings,
                        new WorldOptions(FieldStart.SEED, true, false),
                        WorldPresets::createNormalWorldDimensions);
        go(Stage.READY);
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
        if (selected == null && !measured) selected = new FieldChart.Cell(18, 18, 0, 0, 0);
        if (selected != null) {
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
        var location = new BlockPos(x, mc.player.getBlockY(), z);
        visited.add(location);
        // A shallow hollow is recorded on the chart. Aim the walking party at deeper enclosed cuts.
        if (depth >= 10 && !shape.equals("UNSURVEYED")) {
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
            abandonApproach(mc);
            return;
        }
        go(Stage.BACK);
    }

    private static void walk(Minecraft mc) {
        if (mc.screen != null) return;
        if (candidate != null && horizontal(mc, goal) < 2.6 && horizontal(mc, candidate) < 10) {
            stop(mc);
            reachedRims++;
            rims.add(Map.of("tick", tick, "feet", point(mc.player.position()),
                    "surfaceCenter", List.of(candidate.getX(), candidate.getZ()),
                    "rimPoint", List.of(goal.getX(), goal.getZ())));
            turn = 0;
            go(Stage.TURN);
            return;
        }
        if (candidate == null && (horizontal(mc, goal) < 8 || tick - lastMapTick > 450)) {
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
            FieldStart.require(
                    kind.equals("brick") && brick.equals(FieldChart.field(observed, "surface")),
                    "Original exposed brick confirmed by terminal");
            if (!images.contains("identified-brick")) return;
            sawMasonry = true;
            go(Stage.DRINK);
        } else {
            if (kind.equals("rock") && !images.contains("natural-face")) return;
            naturalSight = kind.equals("rock");
            verifyNatural = false;
            mc.screen.onClose();
            if (mc.screen instanceof SurfaceSurveyScreen map) FieldChart.click(map, "gui.done");
            go(Stage.TURN);
        }
    }

    private static void descend(Minecraft mc) throws Exception {
        if (Files.isRegularFile(Path.of("field-native-interaction.json"))) {
            stop(mc);
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
        p.setYRot(
                (float)
                        Math.toDegrees(
                                Math.atan2(
                                        -(candidate.getX() + .5 - p.getX()),
                                        candidate.getZ() + .5 - p.getZ())));
        mc.options.keyUp.setDown(horizontal(mc, candidate) > 1.25);
        mc.options.keyJump.setDown(false);
        if (tick - entered > 380)
            finish(mc, "Visible masonry recorded; no reachable face during descent");
    }

    private static void inside(Minecraft mc) throws Exception {
        var p = mc.player;
        p.getInventory().selected = 1;
        if (mc.crosshairPickEntity instanceof Monster m
                && m.isAlive()
                && p.hasLineOfSight(m)
                && p.distanceTo(m) <= 3.2
                && p.getAttackStrengthScale(.5f) > .9f) {
            mc.gameMode.attack(p, m);
            p.swing(InteractionHand.MAIN_HAND);
        }
        if (tick % 9 == 0) {
            p.setXRot(5);
            var hit = crosshair(mc, 3);
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getLocation().distanceTo(p.getEyePosition()) < 2.2)
                p.setYRot(p.getYRot() + 45);
        }
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
        if (Files.isRegularFile(Path.of("field-key.json")) || tick - entered > 600)
            finish(mc, "Original dungeon interaction recorded");
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
        FieldStart.write("field-progress.json", progress);
    }

    private static void finish(Minecraft mc, String why) throws Exception {
        stop(mc);
        progress();
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
