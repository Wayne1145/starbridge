package dev.starbridge.client.survey;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

/** The first outlined block face along the player's actual line of sight when comparing a cliff with the guide. */
record ObservedRock(BlockPos surface, ItemStack sample, String description, int distance) {
    static ObservedRock atCrosshair(Minecraft game) {
        var player = game.player;
        if (player == null || game.level == null) return empty();
        var eye = player.getEyePosition();
        var aim = eye.add(player.getViewVector(1).scale(48));
        var hit = game.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !game.level.hasChunkAt(hit.getBlockPos())) return empty();
        var position = hit.getBlockPos();
        var state = game.level.getBlockState(position);
        var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        var item = new ItemStack(state.getBlock().asItem());
        String kind = "other";
        if (id != null && id.getNamespace().equals("galacticraftcore") && id.getPath().equals("tile.moonblock")) {
            for (var entry : state.getValues().entrySet()) {
                if (entry.getKey().getName().equals("meta") && entry.getValue() instanceof Integer value) {
                    if (!item.isEmpty()) item.getOrCreateTag().putInt("LegacyMeta", value);
                    kind = switch (value) {
                        case 14 -> "brick";
                        case 0, 1, 2, 3, 4, 5 -> "rock";
                        default -> "other";
                    };
                    break;
                }
            }
        }
        double range = eye.distanceTo(hit.getLocation());
        return new ObservedRock(position, item, kind, range < 1 ? 0 : (int) Math.round(range));
    }
    private static ObservedRock empty() { return new ObservedRock(null, ItemStack.EMPTY, "empty", 0); }
    Component title() { return Component.translatable("slate.starbridge.field.sight." + description); }
}
