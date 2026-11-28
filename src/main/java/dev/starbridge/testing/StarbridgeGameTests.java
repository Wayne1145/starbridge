package dev.starbridge.testing;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
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
    public static void ironBlocksAreHarvestableWithAPickaxe(GameTestHelper helper) {
        // Both blocks copy iron-block properties, which require the correct tool for drops.
        for (var block : java.util.List.of(Starbridge.SHIP_CORE.get(), dev.starbridge.voyage.VoyageModule.VENT.get())) {
            var state = block.defaultBlockState();
            String name = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block).toString();
            helper.assertTrue(state.requiresCorrectToolForDrops(), name + " should still require a tool");
            helper.assertTrue(new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(state), name + " must drop when mined with a stone pickaxe");
            helper.assertTrue(!new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(state), name + " needs at least a stone pickaxe");
        }
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
