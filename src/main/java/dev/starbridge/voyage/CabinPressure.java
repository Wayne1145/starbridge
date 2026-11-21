package dev.starbridge.voyage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import dev.starbridge.content.ContentModule;
import dev.starbridge.compat.vs.VsShipBackend;
import java.util.*;

/** Each cartridge supplies 120 person-seconds. Air escapes through unloaded chunks and open doors. */
public final class CabinPressure {
    private static final int MAX_CELLS = 768, RANGE = 16, CAPACITY = 1_920;
    private static final Map<ServerLevel, Set<Vent>> LOADED = new IdentityHashMap<>();
    private static final VsShipBackend VS = new VsShipBackend();
    private CabinPressure() {}
    public static void clear() { LOADED.clear(); }

    public static boolean breathable(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (Vent vent : LOADED.getOrDefault(level, Set.of())) {
            if (vent.isRemoved()) continue;
            Vec3 eye = player.getEyePosition();
            if (vent.shipyard()) {
                if (vent.carrier == null || level.getGameTime() - vent.locatedAt >= 20) {
                    vent.locatedAt = level.getGameTime();
                    vent.carrier = VS.inspect(level, vent.getBlockPos()).map(VsShipBackend.Snapshot::shipId).orElse(null);
                }
                if (vent.carrier == null) continue;
                var local = dev.starbridge.transit.HullTransit.toShipLocal(level, vent.carrier, eye);
                if (local.isEmpty()) continue;
                eye = local.get();
            }
            BlockPos start = BlockPos.containing(eye);
            if (vent.getBlockPos().distManhattan(start) > RANGE || !vent.admits(start)) continue;
            if (vent.supply(player.getUUID(), level.getGameTime())) return true;
        }
        return false;
    }

    public static final class Block extends BaseEntityBlock {
        Block() { super(Properties.copy(Blocks.IRON_BLOCK).strength(3)); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Vent(pos, state); }
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof Vent vent) {
                var stack = player.getItemInHand(hand);
                if (stack.is(ContentModule.OXYGEN_CANISTER.get()) && vent.charge <= CAPACITY - 120) {
                    vent.charge += 120;
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    vent.setChanged();
                }
                player.displayClientMessage(Component.translatable("message.starbridge.vent_charge", vent.charge), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    public static final class Vent extends BlockEntity {
        private final Map<UUID, Long> lastServed = new HashMap<>();
        private final Map<BlockPos, Boolean> rooms = new HashMap<>();
        private long checkedAt = Long.MIN_VALUE, locatedAt;
        private int charge;
        private Long carrier;
        private Boolean inShipyard;
        public Vent(BlockPos pos, BlockState state) { super(VoyageModule.VENT_ENTITY.get(), pos, state); }
        @Override public void onLoad() {
            super.onLoad();
            if (level instanceof ServerLevel server) LOADED.computeIfAbsent(server, k -> new LinkedHashSet<>()).add(this);
        }
        @Override public void setRemoved() { unregister(); super.setRemoved(); }
        @Override public void onChunkUnloaded() { unregister(); super.onChunkUnloaded(); }
        private void unregister() {
            Set<Vent> vents = LOADED.get(level);
            if (vents != null) { vents.remove(this); if (vents.isEmpty()) LOADED.remove(level); }
        }
        boolean shipyard() {
            if (inShipyard == null) inShipyard = net.minecraftforge.fml.ModList.get().isLoaded("valkyrienskies")
                    && VS.isShipyardChunk(level, worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
            return inShipyard;
        }
        boolean admits(BlockPos start) {
            if (charge <= 0 && lastServed.isEmpty()) return false;
            long second = level.getGameTime() / 20;
            if (checkedAt != second) { rooms.clear(); checkedAt = second; }
            Boolean result = rooms.get(start);
            if (result != null) return result;
            Set<BlockPos> cells = new HashSet<>();
            boolean enclosed = sealed((ServerLevel) level, start, worldPosition, cells);
            for (BlockPos pos : cells) rooms.put(pos, enclosed);
            return enclosed;
        }
        boolean supply(UUID person, long tick) {
            var last = lastServed.get(person);
            if (last != null && tick >= last && tick - last < 20) return true;
            lastServed.entrySet().removeIf(entry -> tick < entry.getValue() || tick - entry.getValue() >= 20);
            if (charge == 0) return false;
            charge--; lastServed.put(person, tick); setChanged(); return true;
        }
        @Override public void load(CompoundTag tag) { super.load(tag); charge = Math.max(0, Math.min(CAPACITY, tag.getInt("Oxygen"))); }
        @Override protected void saveAdditional(CompoundTag tag) { super.saveAdditional(tag); tag.putInt("Oxygen", charge); }
    }

    static boolean sealed(ServerLevel level, BlockPos start, BlockPos vent, Set<BlockPos> visited) {
        boolean reached = false;
        var pending = new ArrayDeque<BlockPos>();
        if (!level.hasChunkAt(start) || airtight(level, start)) return false;
        visited.add(start); pending.add(start);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.removeFirst();
            for (Direction face : Direction.values()) {
                BlockPos next = pos.relative(face);
                if (next.equals(vent)) { reached = true; continue; }
                if (visited.contains(next)) continue;
                if (next.getY() < level.getMinBuildHeight() || next.getY() >= level.getMaxBuildHeight() || !level.hasChunkAt(next)) return false;
                if (airtight(level, next)) continue;
                if (next.distManhattan(vent) > RANGE || visited.size() >= MAX_CELLS) return false;
                visited.add(next); pending.addLast(next);
            }
        }
        return reached;
    }
    private static boolean airtight(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock) return !state.getValue(BlockStateProperties.OPEN);
        return state.isCollisionShapeFullBlock(level, pos);
    }
}
