package dev.starbridge;

import dev.starbridge.block.ShipCoreBlock;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.command.StarbridgeCommands;
import dev.starbridge.content.ContentModule;
import dev.starbridge.benchmark.BenchmarkModule;
import dev.starbridge.network.PilotModule;
import dev.starbridge.assembly.AssemblyModule;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.server.AgentHttpServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mod(Starbridge.ID)
public final class Starbridge {
    public static final String ID = "starbridge";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, ID);
    public static final RegistryObject<Block> SHIP_CORE = BLOCKS.register("ship_core", ShipCoreBlock::new);
    public static final RegistryObject<Item> SHIP_CORE_ITEM = ITEMS.register("ship_core", () -> new BlockItem(SHIP_CORE.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<ShipCoreBlockEntity>> SHIP_CORE_ENTITY = BLOCK_ENTITIES.register("ship_core", () -> BlockEntityType.Builder.of(ShipCoreBlockEntity::new, SHIP_CORE.get()).build(null));
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.starbridge"))
            .icon(() -> new ItemStack(SHIP_CORE_ITEM.get()))
            .displayItems((parameters, output) -> {
                output.accept(SHIP_CORE_ITEM.get());
                output.accept(ContentModule.OXYGEN_MASK.get());
                output.accept(ContentModule.OXYGEN_CANISTER.get());
                output.accept(ContentModule.SURVEY_CORE.get());
                output.accept(ContentModule.HULL_MARKER.get());
                output.accept(ContentModule.STARLIT_ORE_ITEM.get());
                output.accept(ContentModule.STARLIT_CRYSTAL.get());
                output.accept(ContentModule.STAR_FUEL_CELL.get());
                output.accept(ContentModule.STAR_CHART.get());
                output.accept(ContentModule.CAPACITOR_MODULE.get());
                output.accept(ContentModule.THRUSTER_MODULE.get());
                output.accept(ContentModule.EFFICIENCY_MODULE.get());
            }).build());

    public Starbridge() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
        ContentModule.register(bus);
        BenchmarkModule.register();
        PilotModule.register(bus);
        AssemblyModule.register();
        registerDevelopmentHarness();
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> StarbridgeCommands.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> ShipCoreBlockEntity.startPhysics());
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            AgentHttpServer.stop();
            ShipCoreBlockEntity.shutdownPhysics();
            CoreRegistry.clear();
            dev.starbridge.content.LifeSupport.clear();
        });
        if (ModList.get().isLoaded("computercraft")) {
            dev.starbridge.compat.computer.ComputerIntegration.register(bus);
        }
    }

    /** Test harnesses live in the separate gametest source set and are absent from release jars. */
    private static void registerDevelopmentHarness() {
        try {
            Class.forName("dev.starbridge.testing.DedicatedFlightSmoke").getMethod("register").invoke(null);
        } catch (ClassNotFoundException releaseBuild) {
            // Normal for a published jar.
        } catch (ReflectiveOperationException failure) {
            LOGGER.warn("Development test harness could not start", failure);
        }
    }
}
