package dev.starbridge.client.orbit;

import com.mojang.blaze3d.platform.InputConstants;

import dev.starbridge.Starbridge;
import dev.starbridge.orbit.OrbitWire;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

/** This class is reached on the physical client only. Controls belong to the helm screen. */
@Mod.EventBusSubscriber(
        modid = Starbridge.ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class OrbitClient {
    private static final IKeyConflictContext HELM =
            new IKeyConflictContext() {
                public boolean isActive() {
                    return Minecraft.getInstance().screen instanceof OrbitScreen;
                }

                public boolean conflicts(IKeyConflictContext other) {
                    return other == this;
                }
            };

    enum Binding {
        FORWARD("forward", GLFW.GLFW_KEY_W),
        REVERSE("reverse", GLFW.GLFW_KEY_S),
        LEFT("left", GLFW.GLFW_KEY_A),
        RIGHT("right", GLFW.GLFW_KEY_D),
        UP("up", GLFW.GLFW_KEY_UP),
        DOWN("down", GLFW.GLFW_KEY_DOWN),
        BRAKE("brake", GLFW.GLFW_KEY_X),
        CRUISE("cruise", GLFW.GLFW_KEY_C),
        MANUAL("manual", GLFW.GLFW_KEY_V),
        APPROACH("approach", GLFW.GLFW_KEY_L);
        final KeyMapping mapping;

        Binding(String name, int code) {
            mapping =
                    new KeyMapping(
                            "key.starbridge.orbit." + name,
                            HELM,
                            InputConstants.Type.KEYSYM,
                            code,
                            "key.categories.starbridge.orbit");
        }
    }

    private OrbitClient() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (var binding : Binding.values()) event.register(binding.mapping);
    }

    @SubscribeEvent
    public static void resources(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener)
                        manager ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (Minecraft.getInstance().screen
                                                            instanceof OrbitScreen screen)
                                                        screen.reloadModels();
                                                }));
    }

    public static void frame(OrbitWire.Frame packet) {
        var game = Minecraft.getInstance();
        if (game.player == null) return;
        if (game.screen instanceof OrbitScreen screen && screen.ship().equals(packet.core())) {
            if (packet.closed()) screen.closedByServer();
            else screen.update(packet);
        } else if (packet.open() && !packet.closed()) game.setScreen(new OrbitScreen(packet));
        if (packet.closed() && !packet.notice().isBlank())
            game.player.displayClientMessage(Component.translatable(packet.notice()), true);
    }

    public static void hull(OrbitWire.Hull packet) {
        if (Minecraft.getInstance().screen instanceof OrbitScreen screen) screen.hull(packet);
    }
}
