package dev.starbridge.qa.fleet;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "starbridgefleetqa", value = Dist.CLIENT)
public final class CloseHub {
    @SubscribeEvent
    public static void stop(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && Files.isRegularFile(Path.of("linux-fleet.stop")))
            Minecraft.getInstance().stop();
    }
}
