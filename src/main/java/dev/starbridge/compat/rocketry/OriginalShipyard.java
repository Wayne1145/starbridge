package dev.starbridge.compat.rocketry;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Optional;

/**
 * Entry through the actual Advanced Rocketry assembler. Other legacy machines keep their own UIs.
 */
public final class OriginalShipyard {
    private static final String CABIN = "starbridge:original_cabin";
    private static boolean registered;

    private OriginalShipyard() {}

    public static void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        ShipyardWire.register();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, OriginalShipyard::use);
        RocketTransfer.register();
        OriginalShipyardFixture.register();
    }

    private static void use(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || event.getUseBlock() == Event.Result.DENY
                || event.getEntity().isSecondaryUseActive()
                || !OriginalRocketApi.isConsole(event.getLevel().getBlockState(event.getPos())))
            return;
        if (event.getEntity() instanceof ServerPlayer player
                && !ShipyardWire.permitted(player, event.getPos())) return;
        event.setCancellationResult(
                InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player)
            ShipyardWire.open(player, event.getPos());
    }

    static void bind(ShipCoreBlockEntity core, BlockPos min, BlockPos max) {
        var tag = new CompoundTag();
        tag.putLong("Origin", core.getBlockPos().asLong());
        tag.putString("World", core.getLevel().dimension().location().toString());
        tag.putLong("Min", min.asLong());
        tag.putLong("Max", max.asLong());
        core.getPersistentData().put(CABIN, tag);
        core.setChanged();
        dev.starbridge.orbit.OrbitModule.bindCabin(core, min, max);
    }

    public static boolean isOriginal(ShipCoreBlockEntity core) {
        return core.originalRocket() != null;
    }

    public static int tier(ShipCoreBlockEntity core) {
        return core.navigationTier();
    }

    public static Optional<StorageBounds> cabinBounds(ShipCoreBlockEntity core) {
        if (!isOriginal(core) || !(core.getLevel() instanceof ServerLevel level))
            return Optional.empty();
        var tag = core.getPersistentData().getCompound(CABIN);
        if (!tag.contains("Min", Tag.TAG_LONG)
                || !tag.contains("Max", Tag.TAG_LONG)
                || !tag.contains("Origin", Tag.TAG_LONG)
                || tag.getLong("Origin") != core.getBlockPos().asLong()
                || !level.dimension().location().toString().equals(tag.getString("World")))
            return Optional.empty();
        BlockPos min = BlockPos.of(tag.getLong("Min")), max = BlockPos.of(tag.getLong("Max"));
        return Optional.of(
                new StorageBounds(
                        min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
    }
}
