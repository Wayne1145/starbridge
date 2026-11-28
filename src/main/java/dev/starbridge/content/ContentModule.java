package dev.starbridge.content;

import dev.starbridge.block.ShipUpgrade;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The original exploration content. No Galacticraft code or art is included. */
public final class ContentModule {
    public static final ResourceKey<Level> MOON = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation("starbridge", "survey_moon"));
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "starbridge");
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "starbridge");

    public static final RegistryObject<Item> OXYGEN_MASK = ITEMS.register("oxygen_mask", () ->
            new ArmorItem(ArmorMaterials.IRON, ArmorItem.Type.HELMET, new Item.Properties()));
    public static final RegistryObject<Item> OXYGEN_CANISTER = ITEMS.register("oxygen_canister", OxygenCanisterItem::new);
    public static final RegistryObject<Item> SURVEY_CORE = ITEMS.register("survey_core", () ->
            new TooltipItem("survey_core", true, new Item.Properties().stacksTo(16).rarity(Rarity.RARE)));

    public static final RegistryObject<Block> STARLIT_ORE = BLOCKS.register("starlit_ore", () ->
            new DropExperienceBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_DIAMOND_ORE).strength(3.5F, 3)
                    .lightLevel(state -> 4).requiresCorrectToolForDrops(), UniformInt.of(3, 7)));
    public static final RegistryObject<Item> STARLIT_ORE_ITEM = ITEMS.register("starlit_ore", () ->
            new BlockItem(STARLIT_ORE.get(), new Item.Properties()));
    public static final RegistryObject<Item> STARLIT_CRYSTAL = ITEMS.register("starlit_crystal", () ->
            new TooltipItem("starlit_crystal", false, new Item.Properties().rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> STAR_FUEL_CELL = ITEMS.register("star_fuel_cell", () ->
            new StarFuelCellItem(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> STAR_CHART = ITEMS.register("star_chart", () ->
            new TooltipItem("star_chart", false, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> HULL_MARKER = ITEMS.register("hull_marker", () ->
            new HullMarkerItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> CAPACITOR_MODULE = ITEMS.register("capacitor_module", () ->
            new UpgradeModuleItem(ShipUpgrade.CAPACITOR, new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> THRUSTER_MODULE = ITEMS.register("thruster_module", () ->
            new UpgradeModuleItem(ShipUpgrade.THRUSTER, new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> EFFICIENCY_MODULE = ITEMS.register("efficiency_module", () ->
            new UpgradeModuleItem(ShipUpgrade.EFFICIENCY, new Item.Properties().stacksTo(16).rarity(Rarity.RARE)));

    private ContentModule() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MinecraftForge.EVENT_BUS.register(new ExpeditionEvents());
    }
}
