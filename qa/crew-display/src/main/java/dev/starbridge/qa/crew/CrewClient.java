package dev.starbridge.qa.crew;

import com.mojang.blaze3d.platform.NativeImage;
import dev.starbridge.orbit.CrewWatch;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgecrewqa", value = Dist.CLIENT)
public final class CrewClient {
    private static int tick, step;
    private static boolean online, failed;
    private static double initialDistance = -1;
    private static UUID session;
    private static final List<String> inputs = new ArrayList<>();

    private static String role(Minecraft mc) {
        return mc.getUser().getName();
    }

    private static void trace(String action) throws Exception {
        inputs.add(action);
        Files.writeString(
                Path.of("crew-inputs.log"),
                action + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String stateName(Screen sc) {
        return sc == null ? "world" : sc.getClass().getSimpleName();
    }

    private static boolean has(String who, String file) {
        return Files.isRegularFile(Path.of("..", who, file));
    }

    private static boolean press(Screen sc, String key) throws Exception {
        for (var item : sc.children())
            if (item instanceof Button b
                    && b.active
                    && b.visible
                    && b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals(key)) {
                tap(sc, b.getX() + b.getWidth() / 2d, b.getY() + b.getHeight() / 2d);
                trace("button " + key);
                return true;
            }
        return false;
    }

    private static void tap(Screen sc, double x, double y) {
        CrewFixture.check(sc.mouseClicked(x, y, 0), "Visible widget accepted click");
        sc.mouseReleased(x, y, 0);
    }

    private static boolean use(Minecraft mc, BlockPos pos) throws Exception {
        if (!mc.player.getMainHandItem().isEmpty())
            throw new IllegalStateException("Expected empty main hand at the helm or furnace");
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        // Apply the eye direction before evaluating the bound use key on the next tick.
        mc.player.yRotO = mc.player.getYRot();
        mc.player.xRotO = mc.player.getXRot();
        mc.gameRenderer.pick(1f);
        if (!(mc.hitResult instanceof BlockHitResult block) || !block.getBlockPos().equals(pos))
            return false;
        KeyMapping.click(mc.options.keyUse.getKey());
        trace(
                "use "
                        + (pos.equals(CrewFixture.CORE) ? "helm" : "furnace")
                        + " "
                        + mc.options.keyUse.getKey().getName());
        return true;
    }

    private static Object read(Object o, String key) throws Exception {
        var f = o.getClass().getDeclaredField(key);
        f.setAccessible(true);
        return f.get(o);
    }

    private static int slot(FurnaceScreen gui, net.minecraft.world.item.Item item) {
        for (int i = 3; i < gui.getMenu().slots.size(); i++)
            if (gui.getMenu().getSlot(i).getItem().is(item)) return i;
        return -1;
    }

    private static void slotClick(FurnaceScreen gui, int index) {
        var slot = gui.getMenu().getSlot(index);
        tap(gui, gui.getGuiLeft() + slot.x + 8, gui.getGuiTop() + slot.y + 8);
    }

    private static void transfer(FurnaceScreen gui, int from, int to, String name)
            throws Exception {
        slotClick(gui, from);
        slotClick(gui, to);
        trace("transfer " + name + " " + from + " " + to);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) throws Exception {
        if (!CrewFixture.ENABLED || e.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        mc.setWindowActive(true);
        if (Files.isRegularFile(Path.of("crew.stop"))) {
            mc.stop();
            return;
        }
        if (failed
                || ++tick % 4 != 0
                || mc.getOverlay() != null
                || mc.player == null
                || mc.level == null
                || mc.gameMode == null) return;
        try {
            if (!online) {
                if (!mc.level
                                .getBlockState(CrewFixture.CORE)
                                .is(dev.starbridge.Starbridge.SHIP_CORE.get())
                        || mc.player.distanceToSqr(Vec3.atCenterOf(CrewFixture.CORE)) > 16) return;
                CrewFixture.check(
                        !mc.player.hasPermissions(2)
                                && !mc.player.isCreative()
                                && !mc.player.isSpectator()
                                && "zh_cn".equals(mc.getLanguageManager().getSelected()),
                        "Chinese non-OP survival client");
                CrewFixture.write(
                        "crew-online.json",
                        Map.of("name", role(mc), "uuid", mc.player.getUUID(), "language", "zh_cn"));
                online = true;
            }
            var sc = mc.screen;
            if (CrewFixture.CAPTAIN.equals(role(mc))) {
                if (!stateName(sc).equals("OrbitScreen")) return;
                if (step == 0
                        && has(CrewFixture.ENGINEER, "crew-auto.json")
                        && press(sc, "orbit.starbridge.take")) step = 1;
                else if (step == 1
                        && has(CrewFixture.ENGINEER, "crew-manual.json")
                        && press(sc, "orbit.starbridge.cruise")) step = 2;
                else if (step == 2 && has(CrewFixture.ENGINEER, "crew-resumed.json")) {
                    var frame = (dev.starbridge.orbit.OrbitWire.Frame) read(sc, "frame");
                    CrewFixture.check(
                            frame.cruise() && frame.target().equals("moon"),
                            "Captain retains cruise after both changes");
                    CrewFixture.write(
                            "crew-controls.json",
                            Map.of(
                                    "inputs",
                                    inputs,
                                    "ship",
                                    frame.core(),
                                    "target",
                                    frame.target(),
                                    "cruise",
                                    frame.cruise()));
                    step = 3;
                }
                return;
            }
            switch (step) {
                case 0 -> {
                    if (sc == null
                            && has(CrewFixture.CAPTAIN, "crew-helm-open.json")
                            && use(mc, CrewFixture.CORE)) step = 1;
                }
                case 4 -> {
                    if (stateName(sc).equals("CrewFlightScreen") && press(sc, "gui.done")) step = 5;
                }
                case 5 -> {
                    if (sc == null && use(mc, CrewFixture.OVEN)) step = 6;
                }
                case 6 -> {
                    if (sc instanceof FurnaceScreen oven) {
                        int source = slot(oven, Items.RAW_IRON);
                        if (source >= 0) {
                            transfer(oven, source, 0, "raw_iron");
                            step = 7;
                        }
                    }
                }
                case 7 -> {
                    if (sc instanceof FurnaceScreen oven && oven.getMenu().getCarried().isEmpty()) {
                        int source = slot(oven, Items.COAL);
                        if (source >= 0) {
                            transfer(oven, source, 1, "coal");
                            step = 8;
                        }
                    }
                }
                case 8 -> {
                    if (sc instanceof FurnaceScreen oven
                            && oven.getMenu().getSlot(2).getItem().is(Items.IRON_INGOT)
                            && Files.exists(Path.of("crew-furnace-frame.json"))) {
                        int dest = 3;
                        while (!oven.getMenu().getSlot(dest).getItem().isEmpty()) dest++;
                        transfer(oven, 2, dest, "iron_ingot");
                        step = 9;
                    }
                }
                case 9 -> {
                    if (sc instanceof FurnaceScreen oven
                            && slot(oven, Items.IRON_INGOT) >= 0
                            && oven.getMenu().getCarried().isEmpty()) {
                        oven.onClose();
                        CrewFixture.write(
                                "crew-machine.json",
                                Map.of("inputs", inputs, "ingots", 1, "closed", true));
                        step = 10;
                    }
                }
            }
        } catch (Exception | AssertionError err) {
            fail(err);
        }
    }

    private static int rgb(NativeImage image, Screen sc, int x, int y) {
        int px =
                image.getPixelRGBA(
                        (int) ((x + .5) * image.getWidth() / sc.width),
                        (int) ((y + .5) * image.getHeight() / sc.height));
        return (px & 255) << 16 | px & 0xff00 | (px >>> 16) & 255;
    }

    @SubscribeEvent
    public static void paint(ScreenEvent.Render.Post e) throws Exception {
        if (!CrewFixture.ENABLED || !online || failed) return;
        var mc = Minecraft.getInstance();
        var sc = e.getScreen();
        try {
            if (CrewFixture.CAPTAIN.equals(role(mc))) {
                if (stateName(sc).equals("OrbitScreen")
                        && !Files.exists(Path.of("crew-helm-open.json")))
                    CrewFixture.write("crew-helm-open.json", Map.of("screen", "OrbitScreen"));
                return;
            }
            if (step == 8
                    && sc instanceof FurnaceScreen oven
                    && oven.getMenu().getSlot(2).getItem().is(Items.IRON_INGOT)
                    && !Files.exists(Path.of("crew-furnace-frame.json"))) {
                e.getGuiGraphics().flush();
                shot(mc, "crew-furnace");
                CrewFixture.write(
                        "crew-furnace-frame.json",
                        Map.of("item", "minecraft:iron_ingot", "slot", 2));
            }
            if (step < 1 || step > 3 || !stateName(sc).equals("CrewFlightScreen")) return;
            var r = (CrewWatch.Reading) read(sc, "state");
            if (step == 1 && initialDistance < 0) {
                initialDistance = r.remaining();
                return;
            }
            if (r.motion() != (step == 2 ? CrewWatch.Motion.MANUAL : CrewWatch.Motion.CRUISE)
                    || step == 1 && (r.speed() <= 0 || initialDistance - r.remaining() < 1)) return;
            if (session == null) session = r.session();
            CrewFixture.check(
                    r.session().equals(session)
                            && r.captain().equals(CrewFixture.CAPTAIN)
                            && r.from().equals("home")
                            && r.to().equals("moon")
                            && r.reserve() > 0
                            && r.energy() > r.reserve(),
                    "Live reading for the original captain and owner");
            String name = step == 1 ? "crew-auto" : step == 2 ? "crew-manual" : "crew-resumed";
            int left = (int) read(sc, "left"),
                    top = (int) read(sc, "top"),
                    w = (int) read(sc, "panelWidth"),
                    x = left + 13,
                    right = left + w - 12;
            int mark =
                    x
                            + (int)
                                    Math.round(
                                            (right - x)
                                                    * Math.max(
                                                            0,
                                                            Math.min(
                                                                    1,
                                                                    1
                                                                            - r.remaining()
                                                                                    / Math.max(
                                                                                            1,
                                                                                            r
                                                                                                    .span()))));
            e.getGuiGraphics().flush();
            try (var img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                CrewFixture.check(
                        rgb(img, sc, mark, top + 44) == 0xffc982
                                && rgb(img, sc, x + 1, top + 109) == 0xffc982
                                && rgb(img, sc, right - 1, top + 109) == 0x80dfca,
                        "Visible course marker and both energy regions");
                var dir = Path.of("screenshots");
                Files.createDirectories(dir);
                img.writeToFile(dir.resolve(name + ".png"));
                var out = new LinkedHashMap<String, Object>();
                out.put("reading", r);
                out.put("title", sc.getTitle().getString());
                out.put(
                        "modeLabel",
                        Component.translatable(
                                        "orbit.starbridge.crew.motion."
                                                + r.motion().name().toLowerCase(Locale.ROOT))
                                .getString());
                out.put(
                        "ports",
                        List.of(
                                Component.translatable("route.starbridge." + r.from()).getString(),
                                Component.translatable("route.starbridge." + r.to()).getString()));
                out.put("panel", List.of(left, top, w));
                out.put("gui", List.of(sc.width, sc.height));
                out.put("framebuffer", List.of(img.getWidth(), img.getHeight()));
                CrewFixture.write(name + ".json", out);
            }
            step++;
        } catch (Exception | AssertionError err) {
            fail(err);
        }
    }

    private static void shot(Minecraft mc, String name) throws Exception {
        var dir = Path.of("screenshots");
        Files.createDirectories(dir);
        try (var img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            img.writeToFile(dir.resolve(name + ".png"));
        }
    }

    private static void fail(Throwable e) throws Exception {
        failed = true;
        CrewFixture.write(
                "crew-error.json", Map.of("step", step, "error", e.toString(), "inputs", inputs));
    }
}
