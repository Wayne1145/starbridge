package dev.starbridge.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The original exploration slice. No Galacticraft code or art is included. */
public final class ContentModule {
    public static final ResourceKey<Level> MOON = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation("starbridge", "survey_moon"));
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "starbridge");
    public static final RegistryObject<Item> OXYGEN_MASK = ITEMS.register("oxygen_mask", () ->
            new ArmorItem(ArmorMaterials.IRON, ArmorItem.Type.HELMET, new Item.Properties()));
    public static final RegistryObject<Item> OXYGEN_CANISTER = ITEMS.register("oxygen_canister", OxygenCanisterItem::new);
    public static final RegistryObject<Item> SURVEY_CORE = ITEMS.register("survey_core", () ->
            new Item(new Item.Properties().stacksTo(16).rarity(Rarity.RARE)));

    private ContentModule() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        MinecraftForge.EVENT_BUS.register(new ExpeditionEvents());
    }
}
