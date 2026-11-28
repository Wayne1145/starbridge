package dev.starbridge.voyage;

import dev.starbridge.Starbridge;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.HashSet;
import java.util.UUID;

@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class CabinGameTests {
    private CabinGameTests() {}
    @GameTest(template="empty", batch="starbridge_cabin")
    public static void sealedCabinReservesAirUntilHatchOpens(GameTestHelper helper) {
        var level = helper.getLevel();
        // Keep this 3x3x3 cabin inside the test structure footprint.
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 1));
        for (var face : net.minecraft.core.Direction.values()) level.setBlockAndUpdate(start.relative(face), Blocks.GLASS.defaultBlockState());
        BlockPos ventPos = start.below();
        level.setBlockAndUpdate(ventPos, VoyageModule.VENT.get().defaultBlockState());
        var vent = (CabinPressure.Vent) level.getBlockEntity(ventPos);
        helper.assertTrue(vent != null, "Cabin vent block entity must load");
        CompoundTag nbt = vent.saveWithFullMetadata(); nbt.putInt("Oxygen", 2); vent.load(nbt);
        BlockPos hatch = start.above();
        level.setBlockAndUpdate(hatch, Blocks.IRON_TRAPDOOR.defaultBlockState());
        helper.assertTrue(CabinPressure.sealed(level, start, ventPos, new HashSet<>()), "Closed hatch must seal the cabin");
        UUID captain = UUID.randomUUID(), crew = UUID.randomUUID();
        helper.assertTrue(vent.supply(captain, 0) && vent.supply(captain, 19), "One passenger can breathe through the full paid second");
        helper.assertTrue(vent.supply(crew, 19) && !vent.supply(captain, 20), "Two people consume two person-seconds");
        level.setBlockAndUpdate(hatch, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true));
        helper.assertTrue(!CabinPressure.sealed(level, start, ventPos, new HashSet<>()), "An open exterior hatch vents the cabin");
        helper.succeed();
    }
    @GameTest(template="empty", batch="starbridge_cabin")
    public static void ventDrawsOxygenFromEverySupportedSource(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos ventPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(ventPos, VoyageModule.VENT.get().defaultBlockState());
        var vent = (CabinPressure.Vent) level.getBlockEntity(ventPos);
        helper.assertTrue(vent != null, "Cabin vent block entity must load");
        var player = helper.makeMockPlayer();
        var canisters = new net.minecraft.world.item.ItemStack(dev.starbridge.content.ContentModule.OXYGEN_CANISTER.get(), 2);
        helper.assertTrue(CabinPressure.oxygenSource(canisters), "A canister is an oxygen source");
        helper.assertTrue(vent.refill(player, canisters) && vent.charge() == CabinPressure.PER_CARTRIDGE && canisters.getCount() == 1,
                "A canister adds one cartridge and is used up");
        var stone = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
        helper.assertTrue(!CabinPressure.oxygenSource(stone) && !vent.refill(player, stone), "Unrelated items are refused");
        // Original tanks keep their oxygen as item damage, under Retrofit's LegacyMeta when present.
        var legacy = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
        legacy.getOrCreateTag().putInt("LegacyMeta", 30);
        helper.assertTrue(CabinPressure.tankDamage(legacy) == 30, "Legacy damage is read from LegacyMeta");
        CabinPressure.setTankDamage(legacy, 75);
        helper.assertTrue(legacy.getTag().getInt("LegacyMeta") == 75, "Legacy damage is written back to LegacyMeta");
        helper.succeed();
    }

    @GameTest(template="empty", batch="starbridge_cabin")
    public static void chartsAndBerthsSurviveSaveAndMidnightMoonIsVisible(GameTestHelper helper) {
        UUID crew = UUID.randomUUID(), ship = UUID.randomUUID();
        var log = new VoyageLog(); log.chartMoon(crew); var berth = new Vec3(-8.5, 91, 33.5); log.rememberHome(ship, berth);
        var loaded = VoyageLog.load(log.save(new CompoundTag()));
        helper.assertTrue(loaded.charted(crew) && berth.equals(loaded.home(ship)) && loaded.home(crew) == null, "Personal charts and ship berths must not be interchanged");
        helper.assertTrue(MoonObservation.aligns(Math.PI, 0, 1) && !MoonObservation.aligns(Math.PI, 0, -1)
                && !MoonObservation.aligns(0, 0, -1), "Only a visible moon along the sightline counts");
        helper.succeed();
    }
}
