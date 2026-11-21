package dev.starbridge.compat.unbound;

import com.mojang.authlib.GameProfile;
import dev.starbridge.Starbridge;
import dev.starbridge.content.ContentModule;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.gametest.GameTestHolder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tests only register with the real optional mod installed; they run exclusively in a disposable GameTest server. */
@GameTestHolder(Starbridge.ID)
public final class UnboundGameTests {
    @GameTestGenerator
    public static List<TestFunction> registered() {
        if (!UnboundCompat.enabled()) return List.of();
        return List.of(new TestFunction("starbridge_unbound", "starbridge:unbound_journal_routes", "starbridge:empty",
                        200, 0, true, UnboundGameTests::journal),
                new TestFunction("starbridge_unbound", "starbridge:unbound_cabin_air", "starbridge:empty",
                        240, 0, true, UnboundGameTests::air));
    }

    private static ServerLevel moon(GameTestHelper helper) {
        if (!(helper.getLevel().getServer() instanceof GameTestServer server)) throw new IllegalStateException("Disposable test server required");
        ServerLevel moon = server.getLevel(UnboundCompat.destination("moon"));
        helper.assertTrue(moon != null, "Unbound must load its actual Moon dimension");
        return moon;
    }

    private static FakePlayer crew(ServerLevel level, String name) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
    }

    private static void journal(GameTestHelper helper) {
        ServerLevel moon = moon(helper);
        try {
            Class<?> journal = Class.forName("me.moltea.unbound.api.journal.Journal");
            Method grant = journal.getMethod("grant", ServerPlayer.class, String.class);
            Method has = journal.getMethod("has", ServerPlayer.class, String.class);
            FakePlayer pilot = crew(moon, "MoonSurveyor");
            FakePlayer outsider = crew(helper.getLevel(), "GroundControl");
            helper.assertFalse(UnboundCompat.discovered(pilot, "moon"), "A new player must not start with the Moon discovery");
            helper.assertFalse((boolean) grant.invoke(null, pilot, "body/moon"), "Unbound must enforce its own observation prerequisites");
            for (ServerPlayer person : List.of(pilot, outsider)) {
                for (String key : List.of("journal", "optics", "refractor", "first_light", "refinery", "lunar_rocket")) {
                    helper.assertTrue((boolean) has.invoke(null, person, key) || (boolean) grant.invoke(null, person, key),
                            "Expected Unbound journal prerequisite " + key);
                }
            }
            helper.assertTrue((boolean) grant.invoke(null, pilot, "body/moon"), "Could not grant observer's native discovery");
            helper.assertTrue(UnboundCompat.discovered(pilot, "moon"), "A real Unbound Moon discovery must unlock its chart");
            helper.assertFalse(UnboundCompat.discovered(pilot, "mars"), "Observing the Moon must leave Mars locked");
            helper.assertFalse(UnboundCompat.discovered(outsider, "moon"), "One pilot must not discover the Moon for the other");
            UnboundCompat.arrived(outsider, "moon");
            helper.assertFalse((boolean) has.invoke(null, outsider, "world/moon"), "An Earthbound crew member received a lunar landing");
            UnboundCompat.arrived(pilot, "moon");
            helper.assertTrue((boolean) has.invoke(null, pilot, "world/moon"), "The real Unbound journal must remember a lunar landing");
            UnboundCompat.arrived(pilot, "moon");
            helper.assertFalse((boolean) grant.invoke(null, pilot, "world/moon"), "Landing should already be present after replay");
            helper.succeed();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unbound 0.16.1 public Journal contract failed", e);
        }
    }

    private record Cell(BlockPos pos, BlockState previous) {}

    private static void air(GameTestHelper helper) {
        ServerLevel level = moon(helper);
        BlockPos center = new BlockPos(152, level.getMaxBuildHeight() - 12, 152);
        // Unconnected test players do not load chunks. Use a temporary ticket like an arriving real player.
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(center), 3, center);
        level.getChunkAt(center);
        List<Cell> cells = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 2, 1))) {
            helper.assertTrue(level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null, "Expected an unused test berth");
            cells.add(new Cell(pos.immutable(), level.getBlockState(pos)));
        }
        FakePlayer pilot = crew(level, "CabinEngineer");
        pilot.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        BlockPos vent = center.west();
        try {
            for (Cell cell : cells) {
                if (!cell.pos.equals(center) && !cell.pos.equals(center.above())) level.setBlockAndUpdate(cell.pos, Blocks.IRON_BLOCK.defaultBlockState());
            }
            level.setBlockAndUpdate(vent, VoyageModule.VENT.get().defaultBlockState());
            pilot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ContentModule.OXYGEN_CANISTER.get()));
            level.getBlockState(vent).use(level, pilot, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(vent), Direction.EAST, vent, false));
            helper.assertTrue(pilot.getMainHandItem().isEmpty(), "Charging the vent must consume exactly one survival cartridge");
            helper.runAfterDelay(2, () -> sealed(helper, level, pilot, vent, cells));
        } catch (RuntimeException | Error e) {
            cleanup(level, pilot, cells);
            throw e;
        }
    }

    private static void sealed(GameTestHelper helper, ServerLevel level, FakePlayer pilot, BlockPos vent, List<Cell> cells) {
        try {
            helper.assertFalse(airAt(level, pilot.getEyePosition()), "Native moon vacuum should initially be unbreathable");
            CabinAirBridge.tick(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, pilot));
            int remaining = level.getBlockEntity(vent).saveWithoutMetadata().getInt("Oxygen");
            helper.assertTrue(airAt(level, pilot.getEyePosition()), "The pressurized cabin must provide real Unbound air, remaining " + remaining
                    + ", chunk ticking " + level.isPositionEntityTicking(vent) + ", eyes " + pilot.getEyePosition());
            helper.assertFalse(airAt(level, pilot.getEyePosition().add(3, 0, 0)), "Air must not leak outside the hull");
            helper.assertTrue(remaining == 119, "The vent must consume one person-second, remaining " + remaining);
            CabinAirBridge.tick(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, pilot));
            helper.assertTrue(level.getBlockEntity(vent).saveWithoutMetadata().getInt("Oxygen") == remaining, "Duplicate checks in one tick spent oxygen twice");
            level.setBlockAndUpdate(vent.east(2).above(), Blocks.AIR.defaultBlockState());
            helper.runAfterDelay(22, () -> {
                try {
                    CabinAirBridge.tick(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, pilot));
                    helper.assertFalse(airAt(level, pilot.getEyePosition()), "A breached room retained its native oxygen zone");
                    helper.succeed();
                } finally { cleanup(level, pilot, cells); }
            });
        } catch (RuntimeException | Error e) {
            cleanup(level, pilot, cells);
            throw e;
        }
    }

    private static boolean airAt(ServerLevel level, Vec3 eyes) {
        try {
            return (boolean) Class.forName("me.moltea.unbound.api.life.Environment").getMethod("breathable", ServerLevel.class, Vec3.class)
                    .invoke(null, level, eyes);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unbound Environment API unavailable", e);
        }
    }

    private static void cleanup(ServerLevel level, FakePlayer pilot, List<Cell> cells) {
        CabinAirBridge.logout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(pilot));
        for (Cell cell : cells) level.setBlockAndUpdate(cell.pos, cell.previous);
    }
}
