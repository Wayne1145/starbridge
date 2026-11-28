package dev.starbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.Starbridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Starbridge.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SurfaceControls {
    private static final IKeyConflictContext HELM = new IKeyConflictContext() {
        @Override public boolean isActive() { return Minecraft.getInstance().screen instanceof PilotScreen; }
        @Override public boolean conflicts(IKeyConflictContext other) { return other == this; }
    };

    enum Binding {
        FORWARD("forward", GLFW.GLFW_KEY_W), BACK("back", GLFW.GLFW_KEY_S),
        LEFT("left", GLFW.GLFW_KEY_A), RIGHT("right", GLFW.GLFW_KEY_D),
        UP("up", GLFW.GLFW_KEY_SPACE), DOWN("down", GLFW.GLFW_KEY_LEFT_SHIFT),
        HOLD("hold", GLFW.GLFW_KEY_H), BRAKE("brake", GLFW.GLFW_KEY_X),
        LOOK("look", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        final KeyMapping mapping;
        Binding(String name, int key) { this(name, InputConstants.Type.KEYSYM, key); }
        Binding(String name, InputConstants.Type type, int key) {
            mapping = new KeyMapping("key.starbridge.surface." + name, HELM, type, key, "key.categories.starbridge.surface");
        }
        Component label() { return mapping.getTranslatedKeyMessage(); }
    }

    private SurfaceControls() {}
    @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) {
        for (Binding control : Binding.values()) event.register(control.mapping);
    }
}
