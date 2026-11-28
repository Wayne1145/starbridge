package dev.starbridge.voyage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
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
    private static final int MAX_CELLS = 768, RANGE = 16;
    public static final int CAPACITY = 1_920, PER_CARTRIDGE = 120;
    private static final Map<ServerLevel, Set<Vent>> LOADED = new IdentityHashMap<>();
    private static final VsShipBackend VS = new VsShipBackend();
    private CabinPressure() {}
    public static void clear() { LOADED.clear(); }

    /** Full Galacticraft oxygen tanks: item id to oxygen they hold when full (their max damage). */
    public static final java.util.Map<net.minecraft.resources.ResourceLocation, Integer> OXYGEN_TANKS = java.util.Map.of(
            new net.minecraft.resources.ResourceLocation("galacticraftcore", "item.oxygentanklightfull"), 90,
            new net.minecraft.resources.ResourceLocation("galacticraftcore", "item.oxygentankmedfull"), 180,
            new net.minecraft.resources.ResourceLocation("galacticraftcore", "item.oxygentankheavyfull"), 270);
    /** An Advanced Rocketry liquid-oxygen bucket; the empty bucket is returned. */
    public static final net.minecraft.resources.ResourceLocation LIQUID_OXYGEN = new net.minecraft.resources.ResourceLocation("advancedrocketry", "item.bucketoxygen");
    static final int LIQUID_OXYGEN_CHARGE = 240;

    /** Items a cabin vent can draw oxygen from. */
    public static boolean oxygenSource(ItemStack stack) {
        if (stack.is(ContentModule.OXYGEN_CANISTER.get())) return true;
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        Integer tank = OXYGEN_TANKS.get(id);
        return LIQUID_OXYGEN.equals(id) || (tank != null && tankDamage(stack) < tank);
    }

    /** Retrofit keeps 1.7 item damage as {@code LegacyMeta}; fall back to modern damage. */
    static int tankDamage(ItemStack stack) {
        var tag = stack.getTag();
        return tag != null && tag.contains("LegacyMeta") ? tag.getInt("LegacyMeta") : stack.getDamageValue();
    }

    static void setTankDamage(ItemStack stack, int damage) {
        var tag = stack.getTag();
        if (tag != null && tag.contains("LegacyMeta")) tag.putInt("LegacyMeta", damage);
        else stack.setDamageValue(damage);
    }

    public static boolean breathable(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (Vent vent : LOADED.getOrDefault(level, Set.of())) {
            if (vent.isRemoved() || !vent.canSupply() || !vent.encloses(player)) continue;
            if (vent.supply(player.getUUID(), level.getGameTime())) return true;
        }
        return false;
    }

    public static final class Block extends BaseEntityBlock {
        Block() { super(Properties.copy(Blocks.IRON_BLOCK).strength(3)); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Vent(pos, state); }
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof Vent vent) {
                vent.refill(player, player.getItemInHand(hand));
                serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new VentMenu(id, inventory, vent),
                        Component.translatable("vent.starbridge.title")));
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
        int charge() { return charge; }
        private boolean canSupply() { return charge > 0 || !lastServed.isEmpty(); }

        boolean refill(Player player, ItemStack stack) {
            boolean creative = player.getAbilities().instabuild;
            if (stack.is(ContentModule.OXYGEN_CANISTER.get())) {
                if (charge > CAPACITY - PER_CARTRIDGE) return false;
                charge += PER_CARTRIDGE;
                if (!creative) stack.shrink(1);
                setChanged();
                return true;
            }
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            Integer tank = OXYGEN_TANKS.get(id);
            if (tank != null) {
                // Original 1.7 tanks keep their remaining oxygen as item damage; draw only what fits.
                int used = tankDamage(stack), take = Math.min(tank - used, CAPACITY - charge);
                if (take <= 0) return false;
                charge += take;
                if (!creative) setTankDamage(stack, used + take);
                setChanged();
                return true;
            }
            if (LIQUID_OXYGEN.equals(id)) {
                if (charge > CAPACITY - LIQUID_OXYGEN_CHARGE) return false;
                charge += LIQUID_OXYGEN_CHARGE;
                if (!creative) {
                    stack.shrink(1);
                    player.getInventory().placeItemBackInInventory(new ItemStack(net.minecraft.world.item.Items.BUCKET));
                }
                setChanged();
                return true;
            }
            return false;
        }

        Optional<Vec3> eyeInCabin(ServerPlayer player) {
            if (player.level() != level || isRemoved()) return Optional.empty();
            Vec3 eye = player.getEyePosition();
            if (!shipyard()) return Optional.of(eye);
            if (carrier == null || level.getGameTime() - locatedAt >= 20) {
                locatedAt = level.getGameTime();
                carrier = VS.inspect(level, getBlockPos()).map(VsShipBackend.Snapshot::shipId).orElse(null);
            }
            return carrier == null ? Optional.empty() : dev.starbridge.transit.HullTransit.toShipLocal(player.serverLevel(), carrier, eye);
        }

        boolean encloses(ServerPlayer player) {
            var eye = eyeInCabin(player);
            if (eye.isEmpty()) return false;
            BlockPos start = BlockPos.containing(eye.get());
            return worldPosition.distManhattan(start) <= RANGE && admits(start);
        }

        boolean admits(BlockPos start) {
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
