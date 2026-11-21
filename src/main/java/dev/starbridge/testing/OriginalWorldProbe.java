package dev.starbridge.testing;

import com.google.gson.GsonBuilder;
import dev.starbridge.Starbridge;
import dev.starbridge.compat.galacticraft.OriginalNavigationTech;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dedicated fixture reads the same registry and real item wrappers as the ship's chart and calibration control. */
public final class OriginalWorldProbe {
    private OriginalWorldProbe() {}
    public static void register() {
        if (Boolean.getBoolean("starbridge.originalWorldProbe")) MinecraftForge.EVENT_BUS.addListener(OriginalWorldProbe::sample);
    }
    private static void sample(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !(event.getServer() instanceof DedicatedServer server) || server.getTickCount()!=45
                || !Files.isRegularFile(Path.of("starbridge-original.fixture"))) return;
        Map<String,Object> report = new LinkedHashMap<>();
        try {
            var ports = OriginalStarMap.ports(server);
            for(var port:ports.values()) report.put(port.key(),Map.of("provider",port.provider(),"tier",port.tier(),"world",port.dimension()==null?"":port.dimension().location().toString()));
            List<Object> technology=new ArrayList<>();
            for (var id:ForgeRegistries.ITEMS.getKeys()) {
                if(!id.getPath().contains("schematic")) continue;
                for(int meta=0;meta<=2;meta++) {
                    var stack = new ItemStack(ForgeRegistries.ITEMS.getValue(id));
                    if(meta>0)stack.getOrCreateTag().putInt("LegacyMeta",meta);
                    int tier=OriginalNavigationTech.tier(stack).orElse(0);
                    if(tier>0)technology.add(Map.of("item",id.toString(),"meta",meta,"tier",tier));
                }
            }
            report.put("calibration",technology);
            report.put("originalMoonRecognized",ports.get("moon").dimension()!=null);
            report.put("worldCount",server.levelKeys().size());
            Files.writeString(Path.of("starbridge-original.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch(Exception e){Starbridge.LOGGER.error("Could not record original world fixture",e);}
        finally { server.halt(false); }
    }
}
