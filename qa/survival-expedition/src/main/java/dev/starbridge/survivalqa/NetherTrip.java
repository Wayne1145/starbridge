package dev.starbridge.survivalqa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Remember the vanilla portal that received the captain, then return through it. */
final class NetherTrip {
    private static final Path RETURN = Path.of("expedition-nether-exit.txt");

    static boolean remember(Minecraft game) throws IOException {
        if (!game.level.dimension().equals(Level.NETHER)) return false;
        BlockPos feet = game.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var cell : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 3, 4))) {
            if (!game.level.getBlockState(cell).is(Blocks.NETHER_PORTAL)
                    || !game.level.getBlockState(cell.below()).is(Blocks.OBSIDIAN)) continue;
            double distance = cell.distSqr(feet);
            if (distance < bestDistance) {
                best = cell.immutable();
                bestDistance = distance;
            }
        }
        if (best == null) return false;
        Files.writeString(RETURN, best.getX() + " " + best.getY() + " " + best.getZ() + "\n");
        Expedition.log("Located the Nether return cell at " + best.toShortString());
        return true;
    }

    static boolean returnHome(Minecraft game) throws IOException {
        if (game.level.dimension().equals(Level.OVERWORLD)) return true;
        if (!game.level.dimension().equals(Level.NETHER) || !Files.isRegularFile(RETURN)) return false;
        String[] cell = Files.readString(RETURN).trim().split(" ");
        var gate = new BlockPos(Integer.parseInt(cell[0]), Integer.parseInt(cell[1]), Integer.parseInt(cell[2]));
        PlaceAt.stand(game, gate);
        return false; // Vanilla handles portal contact, cooldown and the Overworld landing.
    }

    private NetherTrip() { }
}
