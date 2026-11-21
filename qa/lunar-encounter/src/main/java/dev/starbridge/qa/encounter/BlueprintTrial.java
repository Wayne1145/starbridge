package dev.starbridge.qa.encounter;

import com.google.gson.GsonBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

/**
 * Follow up in a copy of the player's already unlocked original chest. Never grants a blueprint or
 * page.
 */
@Mod.EventBusSubscriber(modid = "starbridgelunarqa")
public final class BlueprintTrial {
    static final BlockPos BENCH = new BlockPos(0, 157, 0);
    static final Map<String, Object> result = new LinkedHashMap<>();
    static Set<Integer> baseline;
    static Integer expected;
    static int start;
    static boolean done;

    static ResourceLocation originalId() {
        return new ResourceLocation(
                "extraplanets",
                EncounterSite.SELECTED.name().equals("venus")
                        ? "item.schematicvenusrover"
                        : "schematictier4");
    }

    private static Set<Integer> pages(Object stats, ClassLoader loader) throws Exception {
        var pageType = loader.loadClass("micdoodle8.mods.galacticraft.api.recipe.ISchematicPage");
        Set<Integer> ids = new TreeSet<>();
        for (Object page :
                (Collection<?>) stats.getClass().getField("unlockedSchematics").get(stats))
            if (page != null) ids.add((int) pageType.getMethod("getPageID").invoke(page));
        return ids;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (done
                || event.phase != TickEvent.Phase.END
                || !Files.isRegularFile(Path.of("starbridge-blueprint.fixture"))) return;
        var server = event.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        var p = server.getPlayerList().getPlayers().get(0);
        int tick = server.getTickCount();
        if (start == 0) start = tick;
        if (tick - start < 40 || tick % 5 != 0) return;
        try {
            Object runtime =
                    Class.forName("retrofit.forge.RetrofitMod").getMethod("runtime").invoke(null);
            var l = (ClassLoader) runtime.getClass().getMethod("gameLoader").invoke(runtime);
            Class<?> players = Class.forName("retrofit.forge.world.LegacyPlayers");
            var mirror = players.getMethod("mirror", Player.class, boolean.class);
            mirror.setAccessible(true);
            Object legacy = mirror.invoke(null, p, false);
            Object stats =
                    l.loadClass("micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats")
                            .getMethod(
                                    "get",
                                    l.loadClass("net.minecraft.entity.player.EntityPlayerMP"))
                            .invoke(null, legacy);
            Set<Integer> unlocked = pages(stats, l);
            if (baseline == null) {
                baseline = unlocked;
                result.put("originalPagesBefore", baseline);
                result.put("nonOp", !server.getPlayerList().isOp(p.getGameProfile()));
                result.put("gamemode", p.gameMode.getGameModeForPlayer().getName());
                result.put("expectedItem", originalId().toString());
            }
            var stack =
                    p.getInventory().items.stream()
                            .filter(
                                    s ->
                                            originalId()
                                                    .equals(
                                                            ForgeRegistries.ITEMS.getKey(
                                                                    s.getItem())))
                            .findAny()
                            .orElse(ItemStack.EMPTY);
            if (expected == null && !stack.isEmpty()) {
                var toLegacy =
                        Class.forName("retrofit.forge.world.LegacyStacks")
                                .getMethod("toLegacy", ItemStack.class);
                toLegacy.setAccessible(true);
                var rec =
                        l.loadClass("micdoodle8.mods.galacticraft.api.recipe.SchematicRegistry")
                                .getMethod(
                                        "getMatchingRecipeForItemStack",
                                        l.loadClass("net.minecraft.item.ItemStack"))
                                .invoke(null, toLegacy.invoke(null, stack));
                result.put("proofMeta", stack.hasTag() ? stack.getTag().getInt("LegacyMeta") : 0);
                result.put("obtainedFromChest", true);
                if (rec == null)
                    throw new IllegalStateException(
                            "Original registry has no schematic page for the original item");
                expected =
                        (int)
                                l.loadClass(
                                                "micdoodle8.mods.galacticraft.api.recipe.ISchematicPage")
                                        .getMethod("getPageID")
                                        .invoke(rec);
                result.put("originalRecipePage", rec.getClass().getName());
                result.put("expectedPageID", expected);
                var earth = server.overworld();
                var work =
                        ForgeRegistries.BLOCKS.getKeys().stream()
                                .filter(
                                        id ->
                                                id.getNamespace().equals("galacticraftcore")
                                                        && id.getPath().contains("rocketworkbench"))
                                .findFirst()
                                .orElseThrow();
                result.put("nativeWorkbench", work.toString());
                for (int x = -2; x <= 2; x++)
                    for (int z = -2; z <= 3; z++)
                        earth.setBlockAndUpdate(
                                BENCH.offset(x, -1, z), Blocks.IRON_BLOCK.defaultBlockState());
                earth.setBlockAndUpdate(
                        BENCH, ForgeRegistries.BLOCKS.getValue(work).defaultBlockState());
                p.closeContainer();
                p.teleportTo(earth, .5, 157, 2.5, 180, 16);
            }
            result.put("actualPages", unlocked);
            result.put("elapsedTicks", tick - start);
            result.put("menu", p.containerMenu.getClass().getName());
            if (expected != null && !baseline.contains(expected) && unlocked.contains(expected)) {
                result.put("pass", true);
                done = true;
            } else if (tick - start > 1250 || !p.isAlive()) {
                result.put("pass", false);
                done = true;
            }
        } catch (Exception e) {
            result.put("error", e.toString() + " / " + e.getCause());
            result.put("pass", false);
            done = true;
        }
        if (done || tick % 35 == 0)
            Files.writeString(
                    Path.of(done ? "blueprint-result.json" : "blueprint-live.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
