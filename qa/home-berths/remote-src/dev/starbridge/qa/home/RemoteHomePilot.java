package dev.starbridge.qa.home;

import dev.starbridge.orbit.LandingMap;
import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "starbridgeportqa", value = Dist.CLIENT)
public final class RemoteHomePilot {
    private enum Step { WORLD, TERMINAL, HOME, CRUISE, RADAR, INSTRUMENT, PORT, ALTERNATE, RECENTER, RESELECT, ARM, REFUSAL, RETRY, ARRIVAL, DONE }
    private static Step step = Step.WORLD;
    private static boolean captured, reported;
    private static int ticks;
    private static final Map<String, Object> frames = new LinkedHashMap<>();
    private static final List<Map<String,Object>> mapClicks = new java.util.ArrayList<>();
    private static float landingBar;
    private static float lastCapture = -1;
    private static int landingBarX, landingBarY;
    private static final List<Map<String, Object>> landingBars = new java.util.ArrayList<>();
    private static final Path STOP = Path.of("remote-home.stop");

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) throws Exception {
        if (!RemoteHomeWorld.ENABLED || e.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (Files.exists(STOP)) { mc.stop(); return; }
        if (step == Step.DONE || mc.getOverlay() != null) return;
        mc.setWindowActive(true);
        if (++ticks % 2 != 0) return;
        try {
            var screen = mc.screen;
            switch (step) {
                case WORLD -> {
                    if (!(screen instanceof TitleScreen)) return;
                    var mods = new LinkedHashMap<String,String>();
                    for (String id : List.of("starbridge", "starbridgeportqa", "retrofit", "valkyrienskies", "kotlinforforge")) {
                        var file = ModList.get().getModFileById(id).getFile().getFilePath();
                        // KFF's container can be a nested runtime component. Its outer distribution is checked by the installer.
                        if (!id.equals("kotlinforforge")) {
                            RemoteHomeWorld.require(Files.isSameFile(file, Path.of("mods").resolve(file.getFileName().toString())), "Installed mod file " + id);
                            mods.put(id, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                        }
                    }
                    RemoteHomeWorld.require("zh_cn".equals(mc.getLanguageManager().getSelected()), "Chinese client");
                    RemoteHomeWorld.write("home-loaded.json", mods);
                    var settings = new LevelSettings("HomePort", GameType.SURVIVAL, false, Difficulty.NORMAL, false, new GameRules(), WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("HomePort", settings, new WorldOptions(576618501L, true, false), WorldPresets::createNormalWorldDimensions);
                    next(Step.TERMINAL);
                }
                case TERMINAL -> {
                    if (screen != null || mc.player == null || !mc.player.getMainHandItem().is(VoyageModule.SLATE.get())
                            || mc.level == null || !mc.level.dimension().location().getNamespace().equals("retrofit")) return;
                    KeyMapping.click(mc.options.keyUse.getKey());
                    log("use bound key on paired slate"); next(Step.HOME);
                }
                case HOME -> { if (is(screen,"ChartScreen") && click(screen,"route.starbridge.home")) next(Step.CRUISE); }
                case CRUISE -> { if (is(screen,"ChartScreen") && click(screen,"nav.starbridge.cruise")) next(Step.RADAR); }
                case RADAR -> { if (is(screen,"OrbitScreen") && RadarPilot.drive(screen)) next(Step.INSTRUMENT); }
                case INSTRUMENT -> { if (is(screen,"OrbitScreen") && click(screen,"orbit.starbridge.landing")) next(Step.PORT); }
                case PORT, RECENTER -> {
                    if (!captured) return;
                    pick(screen, false); next(step == Step.PORT ? Step.ALTERNATE : Step.RESELECT);
                }
                case ALTERNATE -> { if (captured) { pick(screen, true); next(Step.RECENTER); } }
                case RESELECT -> { if (captured) { RemoteHomeWorld.requestLate = true; next(Step.ARM); } }
                case ARM -> {
                    if (!RemoteHomeWorld.latePresent) return;
                    RemoteHomeWorld.require(click(screen,"orbit.starbridge.touchdown"), "Pilot confirms the last visibly clear map before the next survey");
                    next(Step.REFUSAL);
                }
                case REFUSAL -> { if (captured) { RemoteHomeWorld.removeLate = true; next(Step.RETRY); } }
                case RETRY -> { if (RemoteHomeWorld.lateRemoved && captured && click(screen,"orbit.starbridge.touchdown")) next(Step.ARRIVAL); }
                case ARRIVAL -> {
                    if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || mc.player == null || !mc.player.isAlive()
                            || is(screen,"OrbitScreen") || !Files.isRegularFile(Path.of("home-server.json"))) return;
                    RemoteHomeWorld.require(!RemoteHomeWorld.WIDE || landingBars.size() >= 2, "Visible progress bar advanced before landing");
                    RemoteHomeWorld.write("home-client.json", Map.of("pass", true, "language", "zh_cn", "frames", frames, "mapClicks", mapClicks,
                            "boundUse", mc.options.keyUse.getKey().getName(), "landingBars", landingBars));
                    next(Step.DONE);
                }
                default -> { }
            }
        } catch (Exception | AssertionError error) { fail(error); }
    }

    private static void log(String value) throws Exception {
        Files.writeString(Path.of("home-actions.txt"), value + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void next(Step value) { step = value; captured = false; }
    private static boolean is(Screen screen, String name) { return screen != null && screen.getClass().getSimpleName().equals(name); }
    static Button button(Screen screen, String key) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.visible && b.getMessage().getContents() instanceof TranslatableContents t && key.equals(t.getKey())).findFirst().orElse(null);
    }
    private static boolean click(Screen screen, String key) throws Exception {
        var b = button(screen, key);
        if (b == null || !b.active) return false;
        at(screen, b.getX() + b.getWidth() / 2d, b.getY() + b.getHeight() / 2d);
        log(key); return true;
    }
    static void at(Screen screen, double x, double y) {
        RemoteHomeWorld.require(screen.mouseClicked(x, y, 0), "Map or button accepted player click");
        screen.mouseReleased(x, y, 0);
    }
    private static int side(Screen sc) { return Math.max(52, Math.min(180, Math.min(sc.width / 2 - 28, sc.height - 93))); }
    private static int left(Screen sc) { return Math.max(12, (sc.width / 2 - side(sc)) / 2); }
    private static int top(Screen sc) { return (sc.height - 35 - side(sc)) / 2; }
    private static void pick(Screen sc, boolean center) throws Exception {
        int n = side(sc);
        var target = Minecraft.getInstance().getMainRenderTarget();
        // Aim inside the white square, off its exact center, to exercise clicking the marker itself.
        double x = center ? LandingMap.pixel(0,n) + 2 : (RemoteHomeWorld.AIM_X + 512) * (n - 1) / 1024d;
        double z = center ? LandingMap.pixel(0,n) - 2 : (RemoteHomeWorld.AIM_Z + 512) * (n - 1) / 1024d;
        int px = (int)Math.round((left(sc) + x) * target.width / sc.width);
        int py = (int)Math.round((top(sc) + z) * target.height / sc.height);
        double inputX = (double)px * sc.width / target.width;
        double inputY = (double)py * sc.height / target.height;
        if (!center) {
            var point = new RemoteHomeWorld.Offset(LandingMap.coordinate(inputX - left(sc), n), LandingMap.coordinate(inputY - top(sc), n));
            RemoteHomeWorld.require(RemoteHomeWorld.chosen == null || RemoteHomeWorld.chosen.equals(point), "Same alternate framebuffer pixel");
            RemoteHomeWorld.chosen = point;
        }
        at(sc, inputX, inputY);
        mapClicks.add(Map.of("pixel",List.of(px,py),"framebuffer",List.of(target.width,target.height),"gui",List.of(sc.width,sc.height),
                "map",List.of(left(sc),top(sc),n)));
        log(center ? "click recorded port square" : "click alternate port on map");
    }

    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post e) throws Exception {
        if (!RemoteHomeWorld.ENABLED || captured || !is(e.getScreen(),"OrbitScreen")) return;
        if (step == Step.RADAR) {
            try { RadarPilot.rendered(e.getScreen(), e.getGuiGraphics()); }
            catch (Exception | AssertionError error) { fail(error); }
            return;
        }
        boolean center = step == Step.PORT || step == Step.RECENTER;
        boolean clear = step == Step.ALTERNATE || step == Step.RESELECT || step == Step.RETRY;
        if (!center && !clear && step != Step.REFUSAL || step == Step.RETRY && !RemoteHomeWorld.lateRemoved) return;
        try {
            Screen sc = e.getScreen();
            var field = sc.getClass().getDeclaredField("landingSite");
            field.setAccessible(true);
            var site = (OrbitWire.Site)field.get(sc);
            var b = button(sc, "orbit.starbridge.touchdown");
            int x = center ? 0 : RemoteHomeWorld.chosen.x(), z = center ? 0 : RemoteHomeWorld.chosen.z();
            if (site == null || b == null || site.x() != x || site.z() != z || b.active != clear
                    || site.clearance() != (clear ? OrbitWire.Clearance.CLEAR : OrbitWire.Clearance.OCCUPIED)) return;
            int cx = (int)Math.floor(RemoteHomeWorld.PORT.x) + x, cz = (int)Math.floor(RemoteHomeWorld.PORT.z) + z;
            RemoteHomeWorld.require(site.centerX() == cx && site.centerZ() == cz && site.floor() == (int)Math.floor(RemoteHomeWorld.PORT.y), "Absolute berth and home altitude");
            RemoteHomeWorld.require(clear || site.markX() == cx && site.markZ() == cz, "Obstruction coordinates");
            RemoteHomeWorld.require(site.width() == RemoteHomeWorld.RIGHT - RemoteHomeWorld.LEFT + 1
                    && site.depth() == RemoteHomeWorld.TAIL + 2 && site.height() == 2, "Real ship footprint");
            for (byte cell : site.cells()) RemoteHomeWorld.require(cell != 0, "Sampled terrain cell");
            var label = Component.translatable("orbit.starbridge.site_" + (clear ? "clear" : "occupied")).getString();
            RemoteHomeWorld.require(label.equals(clear ? "泊位净空" : "泊位被占用"), "Localized clearance");
            e.getGuiGraphics().flush();
            String file = step.name().toLowerCase(java.util.Locale.ROOT);
            var mc = Minecraft.getInstance();
            int mx = left(sc) + LandingMap.pixel(x,side(sc)), mz = top(sc) + LandingMap.pixel(z,side(sc));
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int color = clear ? 0x83e0cb : 0xffc882;
                for (int dir : new int[]{-1,1}) {
                    int d = clear ? 5 : 3;
                    checkColor(image, sc, mx - d, mz + dir*d, color);
                    checkColor(image, sc, mx + d, mz + dir*d, color);
                }
                checkColor(image, sc, mx, mz, clear ? 0x345368 : color);
                var dir = Path.of("screenshots"); Files.createDirectories(dir);
                image.writeToFile(dir.resolve(file + ".png"));
            }
            frames.put(file, Map.of("offset", List.of(x,z), "center", List.of(cx,cz), "clearance", site.clearance().name(),
                    "buttonActive", b.active, "symbol", clear ? "open green brackets" : "orange cross", "label", label));
            captured = true;
        } catch (Exception | AssertionError error) { fail(error); }
    }

    @SubscribeEvent
    public static void preparing(CustomizeGuiOverlayEvent.BossEventProgress event) {
        if (!RemoteHomeWorld.ENABLED || !RemoteHomeWorld.WIDE || step != Step.ARRIVAL) return;
        if (!(event.getBossEvent().getName().getContents() instanceof TranslatableContents title)
                || !title.getKey().equals("nav.starbridge.approach.loading")) return;
        RemoteHomeWorld.require(event.getBossEvent().getName().getString().equals("正在准备整艘船着陆"),
                "Localized landing progress title");
        landingBar = event.getBossEvent().getProgress();
        landingBarX = event.getX();
        landingBarY = event.getY();
    }

    @SubscribeEvent
    public static void landingGauge(RenderGuiOverlayEvent.Post event) throws Exception {
        if (!RemoteHomeWorld.ENABLED || !RemoteHomeWorld.WIDE || step != Step.ARRIVAL
                || !event.getOverlay().id().equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id())) return;
        float amount = landingBar;
        landingBar = 0;
        if (amount <= .05f || (lastCapture >= 0 && amount - lastCapture < .24f) || landingBars.size() >= 2) return;
        var mc = Minecraft.getInstance();
        event.getGuiGraphics().flush();
        String name = "landing-progress-" + (landingBars.size() + 1);
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var dir = Path.of("screenshots");
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name + ".png"));
        }
        landingBars.add(Map.of("frame", name, "progress", amount, "guiX", landingBarX, "guiY", landingBarY,
                "size", List.of(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight())));
        lastCapture = amount;
    }

    private static void checkColor(com.mojang.blaze3d.platform.NativeImage img, Screen sc, int x, int y, int rgb) {
        int raw = img.getPixelRGBA((int)((x + .5) * img.getWidth() / sc.width), (int)((y + .5) * img.getHeight() / sc.height));
        int actual = (raw & 255) << 16 | raw & 0xff00 | raw >>> 16 & 255;
        RemoteHomeWorld.require(rgb == actual, "Rendered map symbol pixel " + Integer.toHexString(actual) + ", expected " + Integer.toHexString(rgb));
    }
    private static void fail(Throwable failure) throws Exception {
        if (!reported) RemoteHomeWorld.write("home-client.json", Map.of("pass",false,"step",step.name(),"error",failure.toString()));
        reported = true; next(Step.DONE);
    }
}
