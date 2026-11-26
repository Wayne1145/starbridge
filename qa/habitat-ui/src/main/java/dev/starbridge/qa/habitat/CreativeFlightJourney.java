package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A disposable creative flight. Assembler blocks are prepared before the client begins. */
@Mod.EventBusSubscriber(modid = "starbridgehabitatqa")
public final class CreativeFlightJourney {
    static final Path MARKER = Path.of("starbridge-creative-flight.fixture");
    private static final Path RESULT = Path.of("creative-flight-client.json");
    private static final BlockPos ASSEMBLER = new BlockPos(19, 191, 17);
    private static final BlockPos BRIDGE = new BlockPos(18, 192, 20);
    private static final BlockPos GC_PAD = new BlockPos(39, 190, 19);
    private static final String MOON = "retrofit:legacy_dim_-28";
    private static final String FUEL = "advancedrocketry:item.bucketrocketfuel";
    private static final String GC_ROCKET = "galacticraftcore:item.spaceship";
    private static final Map<String, Object> observations = new LinkedHashMap<>();
    private static volatile UUID bridgeId;
    private static volatile boolean cabin, landed, gcPrepared;
    private static int phase, waitUntil, phaseChanged, firstCabinFrame, firstLandedFrame;
    private static boolean arFlew, gcFlew, inventoryClosed;

    private CreativeFlightJourney() {}

    @SubscribeEvent
    public static void server(TickEvent.ServerTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || !Files.isRegularFile(MARKER)) return;
        var server = event.getServer();
        if (server.isDedicatedServer() || server.getPlayerList().getPlayers().isEmpty()) return;
        var pilot = server.getPlayerList().getPlayers().get(0);
        if (!pilot.isCreative()) throw new IllegalStateException("Expected creative mode");
        var earth = server.overworld();
        if (bridgeId == null) {
            var setup = Class.forName("dev.starbridge.compat.rocketry.OriginalShipyardFixture")
                    .getDeclaredMethod("setup", net.minecraft.server.level.ServerLevel.class, UUID.class);
            setup.setAccessible(true);
            setup.invoke(null, earth, pilot.getUUID());
            var core = (ShipCoreBlockEntity) earth.getBlockEntity(BRIDGE);
            bridgeId = core.id();
            core.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new).receiveEnergy(200000, false);
            for (int x = 16; x <= 22; x++) for (int z = 14; z <= 17; z++)
                earth.setBlockAndUpdate(new BlockPos(x, 190, z), Blocks.IRON_BLOCK.defaultBlockState());
            pilot.teleportTo(earth, 19.5, 191, 16.5, 0, 7);
            return;
        }
        CoreRegistry.find(bridgeId).ifPresent(core -> {
            if (core.originalRocket() == null) return;
            if (pilot.serverLevel() == earth) cabin = OrbitModule.aboard(pilot, core);
            else if (pilot.serverLevel().dimension().location().toString().equals(MOON)) {
                landed = dev.starbridge.transit.HullTransit.isAboard(pilot, core)
                        && !dev.starbridge.transit.HullTransit.inProgress(server, core.id());
            }
        });
        if (landed && !gcPrepared && Files.isRegularFile(Path.of("begin-galacticraft"))) {
            // The original rocket item expects the assembled center, with its own TileEntityLandingPad.
            var pad = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("galacticraftcore:tile.landingpadfull")).orElseThrow();
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 2; z++) {
                var pos = GC_PAD.offset(x, 0, z);
                earth.setBlockAndUpdate(pos.below(), Blocks.IRON_BLOCK.defaultBlockState());
            }
            earth.setBlockAndUpdate(GC_PAD, pad.defaultBlockState());
            pilot.teleportTo(earth, 39.5, 190, 16.5, 0, 12);
            gcPrepared = true;
        }
    }

    static void client(Minecraft mc, int ticks) throws Exception {
        if (Files.isRegularFile(RESULT) || bridgeId == null || ticks < waitUntil) return;
        var p = mc.player;
        var sc = mc.screen;
        if (phaseChanged == 0) phaseChanged = ticks;
        if (ticks - phaseChanged > 2100) throw new IllegalStateException("Flight stalled in phase " + phase + ": " + sc);
        switch (phase) {
            case 0 -> {
                if (take(mc, FUEL, 0, 0)) next(ticks, 5);
            }
            case 1 -> {
                if (take(mc, "libvulpes:tile.creativepowerbattery", 0, 2)) next(ticks, 5);
            }
            case 2 -> {
                if (take(mc, "advancedrocketry:rocketmotor", 0, 3)) next(ticks, 5);
            }
            case 3 -> {
                if (sc == null) use(mc, ASSEMBLER);
                else if (sc.getClass().getSimpleName().equals("ShipyardScreen")) {
                    var mapping = mc.options.keyInventory;
                    var old = mapping.getKey();
                    int key = GLFW.GLFW_KEY_I;
                    mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(key));
                    var pressed = new net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre(sc, key, 0, 0);
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(pressed);
                    mapping.setKey(old);
                    if (mc.screen != null || !pressed.isCanceled()) throw new IllegalStateException("Rebound inventory key did not close the dock");
                    inventoryClosed = true;
                    next(ticks, 5);
                }
            }
            case 4 -> {
                if (sc == null) use(mc, ASSEMBLER);
                else if (HabitatClient.click(sc, "shipyard.starbridge.scan")) next(ticks, 10);
            }
            case 5 -> { if (sc != null && HabitatClient.click(sc, "shipyard.starbridge.assemble")) next(ticks, 10); }
            case 6 -> {
                p.getInventory().selected = 0;
                if (sc != null && HabitatClient.click(sc, "shipyard.starbridge.refuel")) next(ticks, 15);
            }
            case 7 -> {
                if (p.getInventory().selected != 1) {
                    p.getInventory().selected = 1;
                    return;
                }
                if (sc != null && HabitatClient.click(sc, "shipyard.starbridge.board")) next(ticks, 15);
            }
            case 8 -> { if (sc != null && HabitatClient.click(sc, "shipyard.starbridge.launch")) next(ticks, 6); }
            case 9 -> {
                if (p.isPassenger() && p.getY() > 193) arFlew = true;
                if (cabin && p.getX() < -23000 && sc instanceof dev.starbridge.client.ChartScreen) {
                    if (!arFlew) throw new IllegalStateException("Native ascent was not observed");
                    if (firstCabinFrame == 0) firstCabinFrame = ticks;
                    if (ticks - firstCabinFrame < 10) return;
                    HabitatClient.snap(mc, "01-ar-fixed-cabin");
                    observations.put("arAscentAndCabin", true);
                    next(ticks, 20);
                }
            }
            case 10 -> {
                if (sc != null && HabitatClient.click(sc, "route.starbridge.moon")
                        && HabitatClient.click(sc, "nav.starbridge.cruise")) next(ticks, 175);
            }
            case 11 -> { if (sc != null && HabitatClient.click(sc, "orbit.starbridge.landing")) next(ticks, 30); }
            case 12 -> { if (sc != null && HabitatClient.click(sc, "orbit.starbridge.touchdown")) next(ticks, 8); }
            case 13 -> {
                if (landed && sc == null && mc.level.dimension().location().toString().equals(MOON)) {
                    if (firstLandedFrame == 0) {
                        firstLandedFrame = ticks;
                        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                        p.setYRot(135);
                        p.setXRot(15);
                        return;
                    }
                    if (ticks - firstLandedFrame < 13) return;
                    HabitatClient.snap(mc, "02-ar-ship-moon");
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    observations.put("arShipLandedOn", MOON);
                    next(ticks, 20);
                }
            }
            case 14 -> {
                Files.writeString(Path.of("begin-galacticraft"), "Moon ship stage completed");
                if (gcPrepared && mc.level.dimension() == Level.OVERWORLD) next(ticks, 40);
            }
            case 15 -> { if (take(mc, GC_ROCKET, 4, 4)) next(ticks, 8); }
            case 16 -> {
                if (p.getInventory().selected != 4) {
                    p.getInventory().selected = 4;
                    return;
                }
                HabitatClient.log("Used pre-fueled rocket on original pad " + mc.level.getBlockState(GC_PAD));
                use(mc, GC_PAD);
                next(ticks, 15);
            }
            case 17 -> {
                for (var e : mc.level.entitiesForRendering()) {
                    var id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
                    if (id.getNamespace().equals("galacticraftcore") && e.distanceToSqr(p) < 49
                            && (id.getPath().contains("rocket") || id.getPath().contains("spaceship"))) {
                        HabitatClient.log("Boarding original entity " + id);
                        mc.gameMode.interact(p, e, InteractionHand.MAIN_HAND);
                        next(ticks, 10);
                        break;
                    }
                }
            }
            case 18 -> {
                if (p.isPassenger()) {
                    KeyMapping.set(mc.options.keyJump.getKey(), true);
                    next(ticks, 6);
                }
            }
            case 19 -> {
                KeyMapping.set(mc.options.keyJump.getKey(), false);
                if (p.isPassenger() && p.getY() > 197) gcFlew = true;
                if (sc != null && sc.getClass().getName().startsWith("retrofit.")) {
                    HabitatClient.snap(mc, "03-galacticraft-destination");
                    selectMoon(sc);
                    next(ticks, 20);
                }
            }
            case 20 -> {
                if (gcFlew && sc == null && mc.level.dimension().location().toString().equals(MOON)
                        && p.onGround() && p.getY() < 180) {
                    HabitatClient.snap(mc, "03-galacticraft-moon");
                    observations.put("gcRocketLandedOn", MOON);
                    observations.put("creative", p.isCreative());
                    observations.put("inventoryClose", inventoryClosed);
                    observations.put("pass", true);
                    Files.writeString(RESULT, new Gson().toJson(observations));
                }
            }
            default -> throw new IllegalStateException("Unknown flight phase");
        }
        if (phase % 100 == 0 || ticks % 60 == 0)
            Files.writeString(Path.of("creative-flight-live.json"), new Gson().toJson(Map.of("phase", phase,
                    "screen", String.valueOf(sc), "dimension", mc.level.dimension().location().toString(), "observations", observations)));
    }

    private static void next(int ticks, int wait) throws Exception {
        phase++;
        phaseChanged = ticks;
        waitUntil = ticks + wait;
        HabitatClient.log("Creative flight reached phase " + phase);
    }

    private static void use(Minecraft mc, BlockPos pos) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, .5, 0), Direction.UP, pos, false));
    }

    private static boolean take(Minecraft mc, String item, int meta, int hotbar) throws Exception {
        if (mc.screen == null) {
            KeyMapping.click(mc.options.keyInventory.getKey());
            return false;
        }
        if (!(mc.screen instanceof CreativeModeInventoryScreen creative)) return false;
        var id = ResourceLocation.parse(item);
        var desired = net.minecraft.world.item.CreativeModeTabs.tabs().stream()
                .flatMap(tab -> tab.getDisplayItems().stream())
                .filter(s -> is(s, id, meta)).findFirst().orElseThrow(() -> new IllegalStateException("Not in creative: " + item));
        if (is(mc.player.getInventory().getItem(hotbar), id, meta)) {
            creative.onClose();
            HabitatClient.log("Took " + item + " from creative inventory");
            return true;
        }
        var chat = mc.options.keyChat.getKey().getValue();
        creative.keyPressed(chat, 0, 0);
        creative.keyReleased(chat, 0, 0);
        creative.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
        for (char character : desired.getHoverName().getString().toCharArray()) creative.charTyped(character, 0);
        var slot = creative.getMenu().slots.stream().limit(45).filter(s -> is(s.getItem(), id, meta))
                .findFirst().orElseThrow(() -> new IllegalStateException("Creative search missed " + desired.getHoverName().getString()));
        click(creative, slot.x, slot.y);
        click(creative, 9 + hotbar * 18, 112);
        return false;
    }

    private static void click(CreativeModeInventoryScreen screen, int x, int y) {
        double sx = screen.getGuiLeft() + x + 7, sy = screen.getGuiTop() + y + 7;
        screen.mouseClicked(sx, sy, 0);
        screen.mouseReleased(sx, sy, 0);
    }

    private static boolean is(ItemStack stack, ResourceLocation id, int meta) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)
                && (stack.getTag() == null ? 0 : stack.getTag().getInt("LegacyMeta")) == meta;
    }

    private static void selectMoon(net.minecraft.client.gui.screens.Screen screen) throws Exception {
        Object legacy = null;
        for (var f : screen.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object value = f.get(screen);
            if (value != null && value.getClass().getName().endsWith(".GuiCelestialSelection")) legacy = value;
        }
        if (legacy == null) throw new IllegalStateException("Native destination screen unavailable: " + screen.getClass());
        var type = legacy.getClass();
        Object moon = type.getClassLoader().loadClass("micdoodle8.mods.galacticraft.core.GalacticraftCore").getField("moonMoon").get(null);
        var selected = type.getDeclaredField("selectedBody");
        selected.setAccessible(true);
        selected.set(legacy, moon);
        var launch = type.getDeclaredMethod("teleportToSelectedBody");
        launch.setAccessible(true);
        if (!Boolean.TRUE.equals(launch.invoke(legacy))) throw new IllegalStateException("Original Galacticraft destination rejected the Moon");
    }
}
