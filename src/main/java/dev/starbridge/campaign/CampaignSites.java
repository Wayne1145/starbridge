package dev.starbridge.campaign;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small hand-authored, deterministic sites. Candidate plans may write only into air. */
final class CampaignSites {
    private CampaignSites() {}

    static BlockPos prepare(ServerLevel level, String route) {
        if (!(route.equals("mars") || route.equals("belt") || route.equals("relay")) || !CampaignModule.matches(level, route)) return null;
        CampaignData data = CampaignData.get(level);
        String key = level.dimension().location().toString();
        BlockPos saved = data.site(key);
        if (saved != null) return saved;
        for (int i = 0; i < 9; i++) {
            int x = (i % 3) * 16;
            int z = 32 + (i / 3) * 16;
            int y = 112;
            if (route.equals("mars")) {
                // Platforms stand directly above the highest surface in their footprint.
                y = level.getMinBuildHeight();
                for (int dx = -5; dx <= 5; dx++) for (int dz = -3; dz <= 3; dz++) {
                    level.getChunk((x + dx) >> 4, (z + dz) >> 4);
                    y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
                }
                y++;
            }
            BlockPos anchor = new BlockPos(x, y, z);
            Map<BlockPos, Block> plan = plan(route, anchor);
            if (plan.keySet().stream().anyMatch(pos -> !level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getBlockState(pos).isAir())) continue;
            for (var cell : plan.entrySet()) level.setBlock(cell.getKey(), cell.getValue().defaultBlockState(), Block.UPDATE_CLIENTS);
            data.setSite(key, anchor);
            return anchor;
        }
        return null;
    }

    private static Map<BlockPos, Block> plan(String route, BlockPos anchor) {
        Map<BlockPos, Block> cells = new LinkedHashMap<>();
        boolean mars = route.equals("mars"), belt = route.equals("belt");
        for (int x = -5; x <= 5; x++) for (int z = -3; z <= 3; z++) {
            if (Math.abs(x) == 5 && Math.abs(z) == 3) continue;
            Block floor = mars ? Blocks.CUT_RED_SANDSTONE : belt ? Blocks.TUFF : Blocks.SMOOTH_STONE;
            if (belt && (x*x + z*z) % 7 == 1) floor = Blocks.DEEPSLATE_IRON_ORE;
            cells.put(anchor.offset(x, -1, z), floor);
            if (belt && Math.abs(x) + Math.abs(z) < 7) cells.put(anchor.offset(x, -2, z), Blocks.BASALT);
        }
        if (mars) {
            cells.put(anchor, Blocks.CHISELED_RED_SANDSTONE);
            cells.put(anchor.above(), Blocks.RED_SAND); // uncover the engraving with a shovel
            for (int x : new int[]{-4, 4}) for (int z : new int[]{-2, 2}) {
                cells.put(anchor.offset(x, 0, z), Blocks.RED_SANDSTONE);
                cells.put(anchor.offset(x, 1, z), Blocks.RED_SANDSTONE_WALL);
            }
        } else if (belt) {
            cells.put(anchor, Blocks.WAXED_CUT_COPPER);
            cells.put(anchor.east(4), Blocks.BLUE_ICE);
            cells.put(anchor.west(3), Blocks.BLAST_FURNACE);
            cells.put(anchor.offset(-3, 1, 0), Blocks.LIGHTNING_ROD);
            cells.put(anchor.offset(0, 0, 2), Blocks.CUT_COPPER_SLAB);
        } else {
            for (int x : new int[]{-4,4}) cells.put(anchor.offset(x, 0, -2), Blocks.SEA_LANTERN);
            cells.put(anchor.below(), Blocks.CHISELED_STONE_BRICKS);
            // Players must bring and install the terminal at the marked center.
        }
        return cells;
    }
}
