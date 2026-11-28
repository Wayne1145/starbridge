package dev.starbridge.qa.wide;

import com.google.gson.Gson;
import dev.starbridge.orbit.OrbitWire;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Record the clearance and speed the pilot actually receives. Never change the landing selection. */
@Mod.EventBusSubscriber(modid = "starbridgewideqa", value = Dist.CLIENT)
public final class LandingObservation {
    private static final boolean ENABLED = Files.isRegularFile(Path.of("wide-fleet-host.fixture"));
    private static final Gson JSON = new Gson();
    private static int ticks;
    private static String previous = "";

    private static Object get(Object screen, String name) throws ReflectiveOperationException {
        Field field = screen.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(screen);
    }

    @SubscribeEvent
    public static void observe(TickEvent.ClientTickEvent event) throws ReflectiveOperationException, IOException {
        if (!ENABLED || event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        Minecraft game = Minecraft.getInstance();
        var screen = game.screen;
        if (screen == null || !screen.getClass().getSimpleName().equals("OrbitScreen") || !(boolean)get(screen, "approach")) return;
        var frame = (OrbitWire.Frame)get(screen, "frame");
        var site = (OrbitWire.Site)get(screen, "landingSite");
        var button = (Button)get(screen, "landButton");
        String status = site == null ? "NO_SURVEY" : site.clearance().name();
        String key = frame.target() + "/" + status + "/" + button.active;
        if (key.equals(previous) && ticks % 100 != 0) return;
        var row = new LinkedHashMap<String, Object>();
        row.put("tick", ticks);
        row.put("target", frame.target());
        row.put("clearance", status);
        row.put("distance", frame.body().subtract(frame.position()).length());
        row.put("speed", frame.velocity().length());
        row.put("energy", frame.fuel());
        row.put("autopilot", frame.cruise());
        row.put("button", button.getMessage().getString());
        row.put("canLand", button.active);
        row.put("stale", get(screen, "stale"));
        if (site != null) {
            row.put("markX",site.markX());
            row.put("markZ",site.markZ());
        }
        Files.writeString(Path.of("wide-landing-ui.jsonl"), JSON.toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!key.equals(previous)) {
            String name = "wide-landing-" + frame.target() + "-" + status + "-" + button.active + ".png";
            Screenshot.grab(game.gameDirectory, name, game.getMainRenderTarget(), message -> {});
        }
        previous = key;
    }
}
