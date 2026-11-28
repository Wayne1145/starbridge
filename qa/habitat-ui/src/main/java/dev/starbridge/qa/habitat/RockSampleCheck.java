package dev.starbridge.qa.habitat;

import com.google.gson.GsonBuilder;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.survey.SurfaceCluesScreen;
import dev.starbridge.client.survey.SurfaceSurveyScreen;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Look at an original block and then at the same material behind a nearer natural face in a separate test world. */
final class RockSampleCheck {
    static final Path MARKER = Path.of("starbridge-rock-samples.fixture");
    private static final BlockPos FEET = new BlockPos(62, 160, 62);
    private static final BlockPos EXPOSED = FEET.south(3).above();
    private static final BlockPos SHALE = FEET.east(3).above(), HIDDEN = FEET.east(5).above();
    private static final List<Map<String, Object>> readings = new ArrayList<>();
    private static int sample, step, next;
    private static boolean prepared;

    static void prepare(ServerLevel level, ServerPlayer player) {
        if (prepared) return;
        if (player.hasPermissions(2) || player.isCreative()) throw new IllegalStateException("Expected an ordinary survival player");
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("galacticraftcore", "tile.moonblock"));
        if (block == null || !(block.getStateDefinition().getProperty("meta") instanceof IntegerProperty meta))
            throw new IllegalStateException("Galacticraft Moon material unavailable");
        for (var pos : BlockPos.betweenClosed(FEET.offset(-3, -1, -3), FEET.offset(6, -1, 4)))
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        for (var pos : BlockPos.betweenClosed(FEET.offset(-1, 0, -1), FEET.offset(6, 4, 4)))
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var brick = block.defaultBlockState().setValue(meta, 14);
        level.setBlockAndUpdate(EXPOSED, brick);
        level.setBlockAndUpdate(HIDDEN, brick);
        level.setBlockAndUpdate(SHALE, block.defaultBlockState().setValue(meta, 5));
        player.teleportTo(level, FEET.getX() + .5, FEET.getY(), FEET.getZ() + .5, 0, 0);
        player.getInventory().setItem(0, new ItemStack(VoyageModule.SLATE.get()));
        player.getInventory().selected = 0;
        prepared = true;
    }

    static void tick(Minecraft game, int tick) throws Exception {
        if (tick < next || sample >= 3 || !game.player.getMainHandItem().is(VoyageModule.SLATE.get())) return;
        var expected = sample == 0 ? EXPOSED : sample == 1 ? SHALE : null;
        var expectedKind = sample == 0 ? "brick" : sample == 1 ? "rock" : "empty";
        var page = game.screen;
        switch (step) {
            case 0 -> {
                if (page != null || !game.player.onGround() || !game.player.blockPosition().equals(FEET)) return;
                if (expected == null) game.player.setXRot(-89);
                else game.player.lookAt(Anchor.EYES, Vec3.atCenterOf(expected));
                game.options.keyShift.setDown(true);
                step = 1;
            }
            case 1 -> {
                if (page == null && game.player.isShiftKeyDown()) game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
                if (page instanceof FirstFlightScreen) HabitatClient.click(page, "slate.starbridge.field.title");
                if (page instanceof SurfaceSurveyScreen && HabitatClient.click(page, "slate.starbridge.field.clues.title")) step = 2;
            }
            case 2 -> {
                if (page instanceof SurfaceCluesScreen && HabitatClient.click(page, "slate.starbridge.field.sight.inspect")) step = 3;
            }
            case 3 -> {
                if (!(page instanceof SurfaceCluesScreen)) return;
                var observed = field(page, "observed");
                if (observed == null) throw new IllegalStateException("The compare pane did not open");
                var surface = (BlockPos) field(observed, "surface");
                var item = (ItemStack) field(observed, "sample");
                int meta = item.hasTag() ? item.getTag().getInt("LegacyMeta") : -1;
                if (!expectedKind.equals(field(observed, "description")) || !java.util.Objects.equals(surface, expected)
                        || meta != (sample == 0 ? 14 : sample == 1 ? 5 : -1))
                    throw new IllegalStateException("Unexpected first visible face " + surface + " / " + field(observed, "description"));
                var row = new LinkedHashMap<String, Object>();
                row.put("sight", expectedKind); row.put("firstFace", surface == null ? null : surface.toShortString());
                row.put("distance", field(observed, "distance")); row.put("legacyVariant", meta);
                row.put("originalName", item.isEmpty() ? null : item.getHoverName().getString());
                if (sample == 1) row.put("brickBehindFirstFace", HIDDEN.toShortString());
                readings.add(row);
                HabitatClient.snap(game, "field-sample-" + expectedKind);
                step = 4;
            }
            case 4 -> {
                game.options.keyShift.setDown(false);
                if (page instanceof SurfaceCluesScreen) { HabitatClient.click(page, "slate.starbridge.field.clues.back"); return; }
                if (page instanceof SurfaceSurveyScreen) { HabitatClient.click(page, "gui.done"); return; }
                if (page != null) { page.onClose(); return; }
                if (++sample == 3) {
                    Files.writeString(Path.of("rock-sample-result.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(Map.of(
                            "observed", readings, "world", game.level.dimension().location().toString(), "originalBlocksPreparedInSeparateWorld", true)));
                    return;
                }
                step = 0;
            }
        }
        next = tick + 9;
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var member = target.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(target);
    }
}
