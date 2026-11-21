package dev.starbridge.campaign;

import com.mojang.authlib.GameProfile;
import dev.starbridge.Starbridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class CampaignGameTests {
    private CampaignGameTests() {}

    @GameTest(template = "empty", batch = "starbridge_campaign", timeoutTicks = 1300)
    public static void crewCanSalvageCargoCommissionRelayAndCompleteRescue(GameTestHelper h) {
        var server = h.getLevel().getServer();
        h.assertTrue(server instanceof GameTestServer, "Dedicated GameTest fixture required");
        ServerLevel mars = server.getLevel(CampaignModule.MARS);
        ServerLevel belt = server.getLevel(CampaignModule.BELT);
        ServerLevel relay = server.getLevel(CampaignModule.RELAY);
        h.assertTrue(mars != null && belt != null && relay != null, "All three registered worlds must load");
        ServerPlayer archaeologist = fake(mars, "MarsSurveyor");
        BlockPos archive = CampaignSites.prepare(mars, "mars");
        h.assertTrue(archive != null && mars.getBlockState(archive.above()).is(Blocks.RED_SAND), "Archive engraving must be buried at the surface");
        mars.removeBlock(archive.above(), false);
        extract(archaeologist, archive);
        h.assertTrue(archaeologist.getMainHandItem().is(CampaignModule.RELIC.get()), "Tube recovers an archive rubbing");
        ServerPlayer salvager = fake(belt, "BeltSalvager");
        BlockPos wreck = CampaignSites.prepare(belt, "belt");
        h.assertTrue(wreck != null, "Belt wreck must generate");
        extract(salvager, wreck);
        h.assertTrue(salvager.getMainHandItem().is(CampaignModule.FLIGHT_RECORDER.get()), "Wreck yields a recorder");
        extract(salvager, wreck.east(4));
        h.assertTrue(salvager.getMainHandItem().is(CampaignModule.CORE_TUBE.get()), "No rescue capsule without a rescue order");
        ServerPlayer builder = fake(relay, "RelayBuilder");
        BlockPos anchor = CampaignSites.prepare(relay, "relay");
        h.assertTrue(anchor != null && relay.getBlockState(anchor).isAir(), "Installable relay foundation must exist");
        relay.setBlockAndUpdate(anchor, CampaignModule.TERMINAL.get().defaultBlockState());
        deliver(builder, anchor, new ItemStack(CampaignModule.ARRAY.get(), 3));
        deliver(builder, anchor, new ItemStack(Items.COPPER_INGOT, 64));
        h.assertTrue(builder.getMainHandItem().getCount() == 32, "Only outstanding materials may be consumed");
        deliver(builder, anchor, new ItemStack(Items.REDSTONE_BLOCK, 8));
        CampaignData data = CampaignData.get(relay);
        h.assertTrue(data.online(), "Relay must commission after all three deliveries");
        deliver(builder, anchor, new ItemStack(Items.BREAD, 16));
        h.assertTrue(builder.getMainHandItem().is(Items.EMERALD) && builder.getMainHandItem().getCount() == 4, "Supply run pays four emeralds");
        deliver(builder, anchor, archaeologist.getMainHandItem().copy());
        h.assertTrue(data.contract() == CampaignData.Contract.RESCUE, "Survey delivery must open the rescue mission");
        extract(salvager, wreck.east(4));
        h.assertTrue(salvager.getMainHandItem().is(CampaignModule.SURVIVOR.get()), "Can recover the occupied pod during a rescue mission");
        deliver(builder, anchor, salvager.getMainHandItem().copy());
        h.assertTrue(builder.getMainHandItem().is(Items.EMERALD) && builder.getMainHandItem().getCount() == 6 && data.contract() == CampaignData.Contract.SUPPLY,
                "Rescue cargo closes the mission and dispatch begins a new cycle");
        h.succeed();
    }
    private static ServerPlayer fake(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), name));
    }
    private static void extract(ServerPlayer player, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CampaignModule.CORE_TUBE.get()));
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit(pos)));
    }
    private static void deliver(ServerPlayer player, BlockPos pos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.serverLevel().getBlockState(pos).use(player.serverLevel(), player, InteractionHand.MAIN_HAND, hit(pos));
    }
    private static BlockHitResult hit(BlockPos pos) { return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false); }
}
