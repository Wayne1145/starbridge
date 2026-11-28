package dev.starbridge.qa.wide;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Capture the actual cruise and container views of the two clients in this voyage. */
@Mod.EventBusSubscriber(modid = "starbridgewideqa", value = Dist.CLIENT)
public final class VoyagePhotos {
    private static final boolean HELM = Files.isRegularFile(Path.of("wide-fleet-host.fixture"));
    private static final boolean CREW = Files.isRegularFile(Path.of("wide-fleet-guest.fixture"));
    private static int orbitFrames, barrelFrames, furnaceFrames;

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (!HELM && !CREW)) return;
        Minecraft game = Minecraft.getInstance();
        if (game.screen == null) return;

        String name;
        if (HELM && game.screen.getClass().getSimpleName().equals("OrbitScreen") && orbitFrames < 35 && ++orbitFrames == 35) {
            name = "wide-hull-cruise.png";
        } else if (CREW && game.screen instanceof ContainerScreen && barrelFrames < 2 && ++barrelFrames == 2) {
            name = "wide-barrel.png";
        } else if (CREW && game.screen instanceof BlastFurnaceScreen && furnaceFrames < 8 && ++furnaceFrames == 8) {
            name = "wide-furnace.png";
        } else {
            return;
        }
        Screenshot.grab(game.gameDirectory, name, game.getMainRenderTarget(), message -> {});
    }
}
