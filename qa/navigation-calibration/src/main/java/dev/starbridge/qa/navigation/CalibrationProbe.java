package dev.starbridge.qa.navigation;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.OriginalNavigationTech;
import dev.starbridge.compat.galacticraft.OriginalNavigationTech.Sample;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.voyage.NavigationCalibration;
import dev.starbridge.voyage.NavigationCalibration.Choice;
import dev.starbridge.voyage.NavigationCalibration.Result;
import dev.starbridge.voyage.Voyages;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Mod("starbridgenavigationqa")
@Mod.EventBusSubscriber(modid = "starbridgenavigationqa")
public final class CalibrationProbe {
    private static final Map<String, Object> result = new LinkedHashMap<>();
    private static final BlockPos HELM = new BlockPos(12, 128, 12);
    private static final UUID OWNER = UUID.fromString("3efa2a73-7eb7-42fc-97a4-4993aee77420");
    private static final UUID VISITOR = UUID.fromString("92bdbeec-294a-4f59-8ed0-4a0076c4c83e");

    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException(name);
    }

    private static Choice choose(Sample sample, int slot) {
        return new Choice(sample.item(), sample.metadata(), slot);
    }

    private static String condition(ShipCoreBlockEntity core, String target) {
        var routes = (Map<?, ?>) Voyages.chart(core).get("routes");
        return ((Map<?, ?>) routes.get(target)).get("condition").toString();
    }

    private static Sample sample(List<Sample> all, String namespace, int tier, boolean rocket) {
        return all.stream()
                .filter(
                        s ->
                                s.item().getNamespace().equals(namespace)
                                        && s.tier() == tier
                                        && s.rocket() == rocket)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        namespace + " tier " + tier + " rocket " + rocket));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) throws Exception {
        if (e.phase != TickEvent.Phase.END
                || !(e.getServer() instanceof DedicatedServer server)
                || server.getTickCount() != 65
                || !Files.isRegularFile(Path.of("navigation-calibration.fixture"))) return;
        try {
            var level = server.overworld();
            level.getChunkAt(HELM);
            if (Files.exists(Path.of("navigation-reopen.fixture"))) {
                var core = (ShipCoreBlockEntity) level.getBlockEntity(HELM);
                check(
                        core != null && OWNER.equals(core.owner()) && core.navigationTier() == 10,
                        "Persisted owner and T10 navigation");
                result.put("tier", core.navigationTier());
                result.put("owner", core.owner().toString());
                result.put("core", core.id().toString());
            } else {
                var ports = OriginalStarMap.ports(server);
                var examples = OriginalNavigationTech.examples();
                result.put(
                        "catalogue",
                        examples.stream()
                                .map(
                                        s ->
                                                Map.of(
                                                        "item",
                                                        s.item().toString(),
                                                        "metadata",
                                                        s.metadata(),
                                                        "tier",
                                                        s.tier(),
                                                        "rocket",
                                                        s.rocket()))
                                .toList());
                var owner = FakePlayerFactory.get(level, new GameProfile(OWNER, "NavOwner"));
                var visitor = FakePlayerFactory.get(level, new GameProfile(VISITOR, "NavVisitor"));
                check(
                        !owner.hasPermissions(2) && !visitor.hasPermissions(2),
                        "Both test players must be non-OP");
                level.setBlockAndUpdate(HELM, Starbridge.SHIP_CORE.get().defaultBlockState());
                var core = (ShipCoreBlockEntity) level.getBlockEntity(HELM);
                core.setOwner(OWNER);
                core.bindOriginalRocket(UUID.randomUUID());
                core.register();
                owner.setPos(13, 128, 12);
                visitor.setPos(13, 128, 12);
                var gc = sample(examples, "galacticraftcore", 2, false);
                var mars = sample(examples, "galacticraftmars", 3, false);
                var ep = sample(examples, "extraplanets", 4, false);
                var gs = sample(examples, "galaxyspace", 4, true);
                var high = sample(examples, "extraplanets", 10, false);
                var preserved = core.saveWithoutMetadata();
                preserved.putInt("NavigationTier", 7);
                var older = new ShipCoreBlockEntity(HELM, core.getBlockState());
                older.load(preserved);
                check(
                        older.navigationTier() == 7
                                && OWNER.equals(older.owner())
                                && core.id().equals(older.id()),
                        "Existing tier and owner");
                preserved.remove("NavigationTier");
                older.load(preserved);
                check(older.navigationTier() == 1, "Pre-tier core defaults to level one");
                result.put("oldSaveWithoutTier", 1);
                result.put("oldSaveWithTier", 7);
                for (String id :
                        List.of(
                                "galaxyspace:item.itemschematics",
                                "extraplanets:item.schematicvenusrover")) {
                    var item =
                            BuiltInRegistries.ITEM
                                    .getOptional(new ResourceLocation(id))
                                    .orElseThrow();
                    for (int metadata : List.of(0, 5)) {
                        var piece = new ItemStack(item);
                        piece.getOrCreateTag().putInt("LegacyMeta", metadata);
                        check(
                                OriginalNavigationTech.inspect(piece).isEmpty(),
                                "Unrelated original item: " + id);
                    }
                }
                check(
                        gc.metadata() == 1
                                && mars.metadata() == 0
                                && ep.metadata() == 0
                                && high.metadata() == 0,
                        "Original schematic variants");
                for (Sample s : examples)
                    check(
                            OriginalNavigationTech.inspect(s.stack()).orElseThrow().equals(s),
                            "Offered item must really calibrate: " + s);
                var real = gc.stack();
                real.setHoverName(Component.literal("Lunar treasure"));
                real.setCount(2);
                owner.getInventory().setItem(5, real);
                visitor.getInventory().setItem(5, real.copy());
                var choice = choose(gc, 5);
                var expected = real.save(new net.minecraft.nbt.CompoundTag());
                check(
                        NavigationCalibration.apply(owner, core, "mars", choice) == Result.DENIED,
                        "Unbound owner");
                owner.getPersistentData().putUUID("starbridge:selected_core", core.id());
                visitor.getPersistentData().putUUID("starbridge:selected_core", core.id());
                check(
                        NavigationCalibration.apply(visitor, core, "mars", choice) == Result.DENIED,
                        "Other player blocked");
                check(condition(core, "mars").equals("TECHNOLOGY"), "Mars starts locked");
                owner.setPos(224, 128, 250);
                check(
                        NavigationCalibration.apply(owner, core, "mars", choice) == Result.RETURN,
                        "Absent captain");
                owner.setPos(13, 128, 12);
                check(
                        NavigationCalibration.apply(owner, core, "missing", choice)
                                == Result.UNKNOWN,
                        "No invented planet");
                check(
                        NavigationCalibration.apply(owner, core, "mars", choose(gc, 41))
                                == Result.CHANGED,
                        "Invalid slot");
                var decoy = gc.stack();
                decoy.getOrCreateTag().putInt("LegacyMeta", 0);
                owner.getInventory().setItem(5, decoy);
                check(
                        OriginalNavigationTech.inspect(decoy).isEmpty()
                                && NavigationCalibration.apply(owner, core, "mars", choice)
                                        == Result.CHANGED,
                        "Sibling schematic rejected");
                var paper = new ItemStack(Items.PAPER);
                paper.setHoverName(real.getHoverName());
                owner.getInventory().setItem(5, paper);
                check(
                        NavigationCalibration.apply(owner, core, "mars", choice) == Result.CHANGED
                                && paper.getCount() == 1,
                        "Lookalike and stale selection rejected");
                owner.getInventory().setItem(5, real);
                var listed = NavigationCalibration.entries(owner);
                check(
                        listed.stream().anyMatch(v -> v.slot() == 5 && v.sample().equals(gc)),
                        "Server offered owner's slot");
                check(
                        NavigationCalibration.apply(owner, core, "mars", choice) == Result.LEARNED,
                        "Acquired schematic");
                check(
                        NavigationCalibration.apply(owner, core, "mars", choice) == Result.KNOWN
                                && core.navigationTier() == 2,
                        "Repeat calibration");
                check(
                        real.save(new net.minecraft.nbt.CompoundTag()).equals(expected),
                        "Original item count, name and tags remain intact");
                check(condition(core, "mars").equals("FUEL"), "Mars now requires only fuel");
                var venus = "galaxyspace.venus";
                check(ports.get(venus).tier() == 4, "Actual Venus provider and requirement");
                check(
                        NavigationCalibration.apply(owner, core, venus, choice) == Result.CHANGED,
                        "Tier 2 is insufficient for Venus");
                var realGs = gs.stack();
                realGs.getOrCreateTag().putInt("LegacyMeta", 3);
                owner.getInventory().setItem(40, realGs);
                var gsChosen = OriginalNavigationTech.inspect(realGs).orElseThrow();
                check(
                        NavigationCalibration.entries(owner).stream()
                                .anyMatch(v -> v.slot() == 40 && v.sample().equals(gsChosen)),
                        "Rocket with cargo bays in the off hand");
                var secondPos = HELM.east(3);
                level.setBlockAndUpdate(secondPos, Starbridge.SHIP_CORE.get().defaultBlockState());
                var second = (ShipCoreBlockEntity) level.getBlockEntity(secondPos);
                second.setOwner(VISITOR);
                second.register();
                visitor.getPersistentData().putUUID("starbridge:selected_core", second.id());
                visitor.getInventory().setItem(40, realGs.copy());
                check(
                        NavigationCalibration.apply(visitor, second, venus, choose(gsChosen, 40))
                                        == Result.LEARNED
                                && second.navigationTier() == 4
                                && visitor.getInventory().getItem(40).getCount() == 1
                                && core.navigationTier() == 2,
                        "An owner may calibrate only their own helm with a loaded rocket");
                result.put("secondHelm", second.id().toString());
                result.put("rocketOwnerTier", second.navigationTier());
                var absurd = realGs.copy();
                absurd.getOrCreateTag().putInt("LegacyMeta", 127);
                check(
                        OriginalNavigationTech.inspect(absurd).isEmpty(),
                        "Nonexistent rocket variant");
                owner.getInventory().setItem(8, ep.stack());
                check(
                        NavigationCalibration.apply(owner, core, venus, choose(ep, 8))
                                == Result.LEARNED,
                        "ExtraPlanets original schematic opens GalaxySpace Venus");
                String max =
                        ports.values().stream()
                                .filter(port -> port.tier() == 10 && port.dimension() != null)
                                .findFirst()
                                .orElseThrow()
                                .key();
                owner.getInventory().setItem(3, high.stack());
                check(
                        NavigationCalibration.apply(owner, core, max, choose(high, 3))
                                == Result.LEARNED,
                        "Tier 10 schematic");
                check(
                        core.navigationTier() == 10
                                && condition(core, max).equals("FUEL")
                                && owner.getInventory().getItem(3).getCount() == 1,
                        "Tier 10 route and item");
                result.put("marsBefore", "TECHNOLOGY");
                result.put("marsAfter", "FUEL");
                result.put("venus", venus);
                result.put("highestRoute", max);
                result.put("core", core.id().toString());
                result.put(
                        "acceptedIndependentItems",
                        List.of(
                                gc.item().toString(),
                                ep.item().toString(),
                                high.item().toString()));
                result.put("owner", OWNER.toString());
                result.put("visitor", VISITOR.toString());
                result.put("originalStackUnchanged", true);
                result.put("testedRocketVariant", gsChosen.metadata());
            }
            result.put("pass", true);
        } catch (Throwable error) {
            result.put("pass", false);
            result.put("error", error.toString());
            Starbridge.LOGGER.error("Navigation calibration fixture failed", error);
        } finally {
            var path =
                    Path.of(
                            Files.exists(Path.of("navigation-reopen.fixture"))
                                    ? "reopen-result.json"
                                    : "calibration-result.json");
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(result));
            server.halt(false);
        }
    }
}
