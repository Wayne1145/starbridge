package dev.starbridge.compat.computer;

import dan200.computercraft.api.ForgeComputerCraftAPI;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.starbridge.block.ShipCoreBlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** This class is only linked when CC:Tweaked is installed. */
public final class ComputerIntegration {
    private ComputerIntegration() {}
    public static void register(IEventBus bus) {
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() ->
                ForgeComputerCraftAPI.registerPeripheralProvider((level, pos, side) -> {
                    if (level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core) {
                        LazyOptional<IPeripheral> peripheral = LazyOptional.of(() -> new ShipPeripheral(core));
                        core.getCapability(ForgeCapabilities.ENERGY).addListener(ignored -> peripheral.invalidate());
                        return peripheral;
                    }
                    return LazyOptional.empty();
                })));
    }
}
