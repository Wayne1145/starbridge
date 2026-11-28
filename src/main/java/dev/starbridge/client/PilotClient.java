package dev.starbridge.client;

import dev.starbridge.network.PilotModule;
import dev.starbridge.text.Lang;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Loaded only from the client branch of the packet handler. */
@OnlyIn(Dist.CLIENT)
public final class PilotClient {
    private PilotClient() {}

    public static void receive(PilotModule.PilotState packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (packet.kind() == PilotModule.UpdateKind.OPEN) {
            if (minecraft.screen instanceof PilotScreen screen && screen.coreId().equals(packet.coreId())) screen.update(packet);
            else minecraft.setScreen(new PilotScreen(packet));
        } else if (minecraft.screen instanceof PilotScreen screen && screen.coreId().equals(packet.coreId())) {
            if (packet.kind() == PilotModule.UpdateKind.CLOSE) screen.closedByServer(packet.notice());
            else screen.update(packet);
        } else if (packet.kind() == PilotModule.UpdateKind.CLOSE && minecraft.player != null) {
            minecraft.player.displayClientMessage(Lang.keyOrText(packet.notice()), false);
        }
    }
}
