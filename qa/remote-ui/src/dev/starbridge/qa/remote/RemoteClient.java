package dev.starbridge.qa.remote;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Capture provenance from the installed Forge container and let the supervisor request a clean stop. */
@Mod.EventBusSubscriber(modid = "starbridgehabitatqa", value = Dist.CLIENT)
public final class RemoteClient {
    private static boolean reported;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                || !Files.isRegularFile(Path.of("remote-ui.fixture"))) return;
        var mc = Minecraft.getInstance();
        if (Files.isRegularFile(Path.of("remote-ui.stop"))) { mc.stop(); return; }
        if (reported || mc.getOverlay() != null || mc.screen == null) return;
        JsonObject loaded = new JsonObject();
        for (String id : new String[]{"starbridge", "starbridgehabitatqa"}) {
            Path file = ModList.get().getModFileById(id).getFile().getFilePath();
            if (!Files.isSameFile(file, Path.of("mods").resolve(file.getFileName().toString()))) {
                throw new IllegalStateException("Expected an installed mod JAR for " + id);
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            loaded.addProperty(id, hash);
        }
        loaded.addProperty("language", mc.getLanguageManager().getSelected());
        Files.writeString(Path.of("remote-ui-loaded.json"), loaded.toString());
        reported = true;
    }
}
