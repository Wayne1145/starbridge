package dev.starbridge.testing;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.block.ShipUpgrade;
import dev.starbridge.content.ContentModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class StarbridgeGameTests {
    @GameTest(template = "empty")
    public static void corePersistsOwnerAndFuel(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var state = Starbridge.SHIP_CORE.get().defaultBlockState();
        helper.getLevel().setBlockAndUpdate(pos, state);
        var core = (ShipCoreBlockEntity) helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(core != null, "Flight core must create a block entity");
        UUID owner = UUID.randomUUID();
        core.setOwner(owner);
        ItemStack fuel = new ItemStack(Items.COAL, 2);
        helper.assertTrue(core.addFuel(fuel, false), "Coal should charge the vector drive");
        helper.assertTrue(fuel.getCount() == 1, "Charging must consume exactly one coal");
        core.pairComputer(42);
        core.setAgentAllowed(true);
        var saved = core.saveWithFullMetadata();
        var loaded = new ShipCoreBlockEntity(pos, state);
        loaded.load(saved);
        helper.assertTrue(owner.equals(loaded.owner()), "Owner must survive NBT serialization");
        helper.assertTrue(core.id().equals(loaded.id()), "Core ID must survive NBT serialization");
        helper.assertTrue(loaded.energy() == core.energy() && loaded.energy() > 0, "Energy must survive NBT serialization");
        helper.assertTrue(loaded.pairedComputer() == 42 && loaded.saveWithFullMetadata().getBoolean("AgentAllowed"), "Control permissions must persist");
        helper.assertTrue(!loaded.agentAllowed(), "An unloaded deserialized core must never expose agent control");
        helper.assertTrue("IDLE".equals(loaded.status().get("mode")), "Reloading must not start the drive");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void upgradesPersistAndScaleTheCore(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var state = Starbridge.SHIP_CORE.get().defaultBlockState();
        helper.getLevel().setBlockAndUpdate(pos, state);
        var core = (ShipCoreBlockEntity) helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(core != null, "Flight core must create a block entity");
        core.setOwner(UUID.randomUUID());
        int base = core.capacity();
        helper.assertTrue(core.installUpgrade(ShipUpgrade.CAPACITOR), "First capacitor must install");
        helper.assertTrue(core.capacity() == base + ShipUpgrade.CAPACITY_PER_MODULE, "Capacitor must add storage");
        helper.assertTrue(core.installUpgrade(ShipUpgrade.THRUSTER), "Thruster must install");
        helper.assertTrue(core.maxManualAcceleration() > 3.0, "Thruster must raise manual thrust");
        for (int i = 0; i < 5; i++) core.installUpgrade(ShipUpgrade.CAPACITOR);
        helper.assertTrue(core.upgradeLevel(ShipUpgrade.CAPACITOR) == ShipUpgrade.CAPACITOR.maxLevel(), "Capacitor level must cap");
        int added = core.charge(dev.starbridge.content.StarFuelCellItem.ENERGY);
        helper.assertTrue(added == dev.starbridge.content.StarFuelCellItem.ENERGY, "A fuel cell must deliver its full charge into free storage");
        ItemStack coal = new ItemStack(Items.COAL_BLOCK, 4);
        helper.assertTrue(core.addFuel(coal, false, true) > 0 && coal.isEmpty(), "Sneak-charging must take the whole stack when it fits");
        var loaded = new ShipCoreBlockEntity(pos, state);
        loaded.load(core.saveWithFullMetadata());
        helper.assertTrue(loaded.upgradeLevel(ShipUpgrade.THRUSTER) == 1 && loaded.capacity() == core.capacity(), "Upgrades must persist");
        helper.assertTrue(loaded.energy() == core.energy(), "Upgraded storage must restore its full energy");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void expeditionDimensionLoads(GameTestHelper helper) {
        var moon = helper.getLevel().getServer().getLevel(ContentModule.MOON);
        helper.assertTrue(moon != null, "Survey moon datapack must create a server dimension");
        helper.assertTrue(ContentModule.OXYGEN_MASK.isPresent() && ContentModule.SURVEY_CORE.isPresent(), "Expedition equipment must be registered");
        helper.succeed();
    }
}
