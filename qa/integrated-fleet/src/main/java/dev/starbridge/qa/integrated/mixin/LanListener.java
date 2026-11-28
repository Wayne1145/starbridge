package dev.starbridge.qa.integrated.mixin;

import net.minecraft.server.network.ServerConnectionListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(ServerConnectionListener.class)
abstract class LanListener {
    @ModifyVariable(
            method = {
                "m_9711_(Ljava/net/InetAddress;I)V",
                "startTcpServerListener(Ljava/net/InetAddress;I)V"
            },
            at = @At("HEAD"),
            argsOnly = true,
            remap = false,
            require = 1)
    private InetAddress integrated$onlyQaLoopback(InetAddress original)
            throws UnknownHostException {
        return Files.isRegularFile(Path.of("integrated-lan-host.fixture"))
                ? InetAddress.getByAddress(new byte[] {127, 0, 0, 1})
                : original;
    }
}
