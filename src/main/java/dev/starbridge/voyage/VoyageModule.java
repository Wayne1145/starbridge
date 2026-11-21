package dev.starbridge.voyage;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.command.StarbridgeCommands;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class VoyageModule {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Starbridge.ID);
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Starbridge.ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Starbridge.ID);
    private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, Starbridge.ID);
    public static final RegistryObject<net.minecraft.world.inventory.MenuType<VentMenu>> VENT_MENU = MENUS.register("oxygen_vent",
            () -> new net.minecraft.world.inventory.MenuType<>(VentMenu::new, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));
    public static final RegistryObject<Item> SLATE = ITEMS.register("navigation_slate", NavigationSlate::new);
    public static final RegistryObject<Block> VENT = BLOCKS.register("oxygen_vent", CabinPressure.Block::new);
    public static final RegistryObject<Item> VENT_ITEM = ITEMS.register("oxygen_vent", () -> new BlockItem(VENT.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<CabinPressure.Vent>> VENT_ENTITY = ENTITIES.register("oxygen_vent",
            () -> BlockEntityType.Builder.of(CabinPressure.Vent::new, VENT.get()).build(null));
    private VoyageModule() {}
    public static void register(IEventBus bus) {
        ITEMS.register(bus); BLOCKS.register(bus); ENTITIES.register(bus); MENUS.register(bus);
        VoyageNetwork.register();
        dev.starbridge.transit.HullTransit.register(Voyages::arrived);
        MinecraftForge.EVENT_BUS.addListener(MoonObservation::observe);
        MinecraftForge.EVENT_BUS.addListener((LivingEntityUseItemEvent.Stop event) -> {
            if (event.getEntity() instanceof ServerPlayer player && event.getItem().is(net.minecraft.world.item.Items.SPYGLASS))
                player.getPersistentData().remove("starbridge:telescope_focus");
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent ignored) -> { CabinPressure.clear(); dev.starbridge.compat.galacticraft.OriginalStarMap.clear(); });
        MinecraftForge.EVENT_BUS.addListener(VoyageModule::commands);
    }
    private static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("starbridge").then(Commands.literal("chart").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ShipCoreBlockEntity core = StarbridgeCommands.selected(ctx.getSource());
            VoyageNetwork.open(player, core); return 1;
        }));
        var sail = Commands.literal("voyage");
        for (Route route : Route.values()) sail.then(Commands.literal(route.key()).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ShipCoreBlockEntity core = StarbridgeCommands.selected(ctx.getSource());
            try { Voyages.depart(player, core, route.key(), false); return 1; }
            catch (IllegalArgumentException | IllegalStateException rejected) { ctx.getSource().sendFailure(Component.literal(rejected.getMessage())); return 0; }
        }));
        event.getDispatcher().register(root.then(sail));
    }
}
