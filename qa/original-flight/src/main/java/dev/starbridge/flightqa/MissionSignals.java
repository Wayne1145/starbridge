package dev.starbridge.flightqa;

import com.google.gson.*;

import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The disposable QA server and two GUI clients run on the same test host. */
final class MissionSignals {
    static final boolean JOINED = Files.isRegularFile(Path.of("starbridge-joined.fixture"));
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final BlockPos SPAWNER = new BlockPos(396, 27, 193), REWARD = new BlockPos(369, 25, 190);
    static final BlockPos CHEST_FROM_CORE = new BlockPos(2, -1, -1),
            FURNACE_FROM_CORE = new BlockPos(0, 0, -2);
    static final String PILOT = "OrbitCaptain", ENGINEER = "OrbitEngineer";
    static final String MOON = "retrofit:legacy_dim_-28";

    static void write(Path target, Object value) throws java.io.IOException {
        Path draft = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(draft, JSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(
                draft, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void tell(String player, String action, Object... args) throws java.io.IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("action", action);
        for (int i = 0; i < args.length; i += 2) data.put((String) args[i], args[i + 1]);
        write(Path.of("..", player, "mission.json"), data);
    }

    static JsonObject read() throws java.io.IOException {
        Path path = Path.of("mission.json");
        return Files.exists(path)
                ? JsonParser.parseString(Files.readString(path)).getAsJsonObject()
                : null;
    }

    static String id(net.minecraft.world.item.ItemStack item) {
        return net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(item.getItem())
                .toString();
    }
}
