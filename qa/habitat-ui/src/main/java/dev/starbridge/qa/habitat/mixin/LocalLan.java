package dev.starbridge.qa.habitat.mixin;

import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(ServerConnectionListener.class)
public abstract class LocalLan {
    @ModifyVariable(method={"m_9711_(Ljava/net/InetAddress;I)V","startTcpServerListener(Ljava/net/InetAddress;I)V"},at=@At("HEAD"),argsOnly=true,remap=false,require=1)
    private InetAddress habitat$loopback(InetAddress requested) {
        if (!Files.isRegularFile(Path.of("starbridge-habitat.host"))) return requested;
        try { return InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }
        catch (java.net.UnknownHostException impossible) { throw new AssertionError(impossible); }
    }
}
