package dev.starbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.Starbridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Gives Starbridge's non-container panels the same close binding as a Minecraft inventory. */
@Mod.EventBusSubscriber(modid = Starbridge.ID, value = Dist.CLIENT)
public final class FlightScreenKeys {
    private FlightScreenKeys() {}

    @SubscribeEvent
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (close(event.getScreen(), InputConstants.getKey(event.getKeyCode(), event.getScanCode())))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void mousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (close(event.getScreen(), InputConstants.Type.MOUSE.getOrCreate(event.getButton())))
            event.setCanceled(true);
    }

    private static boolean close(Screen screen, InputConstants.Key key) {
        if (screen instanceof AbstractContainerScreen<?> || !screen.shouldCloseOnEsc()) return false;
        String packageName = screen.getClass().getPackageName();
        if (!packageName.equals("dev.starbridge.client") && !packageName.startsWith("dev.starbridge.client.")) return false;
        if (!Minecraft.getInstance().options.keyInventory.isActiveAndMatches(key)) return false;
        screen.onClose();
        return true;
    }
}
