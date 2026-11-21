package dev.starbridge.compat.retrofit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Compare the settings a multiplayer world actually shares. Keep original config files untouched. */
public final class SharedRetrofitSettings {
    private static final String SPLASH = "config/splash.properties";
    private static final String ROCKETRY = "config/advRocketry/advancedRocketry.cfg";
    private static final Set<String> HUD = Set.of(
            "B:lockUI", "I:atmBarModeX", "I:atmBarModeY", "I:atmBarX", "I:atmBarY",
            "I:hydrogenBarModeX", "I:hydrogenBarModeY", "I:hydrogenBarX", "I:hydrogenBarY",
            "I:oxygenBarModeX", "I:oxygenBarModeY", "I:oxygenBarX", "I:oxygenBarY",
            "I:suitPanelModeX", "I:suitPanelModeY", "I:suitPanelX", "I:suitPanelY");

    private SharedRetrofitSettings() {}

    public static Map<String,String> compare(Map<String,String> upstream, Path legacyRoot) {
        if (!upstream.containsKey(SPLASH) && !upstream.containsKey(ROCKETRY)) return upstream;
        var shared = new HashMap<>(upstream);
        // LegacyHost always disables Forge's splash. The client then writes a wall-clock timestamp to it.
        shared.remove(SPLASH);
        if (!shared.containsKey(ROCKETRY)) return shared;
        Path config = legacyRoot.resolve(ROCKETRY);
        try {
            // Same cap as Retrofit's file offer. A read failure retains the unmodified upstream digest.
            if (Files.size(config) <= (1 << 20)) shared.put(ROCKETRY, rocketryHash(Files.readString(config)));
        } catch (IOException ignored) { }
        return shared;
    }

    static String rocketryHash(String file) {
        var canonical = new StringBuilder();
        boolean clientCategory = false, list = false;
        int depth = 0;
        for (String line : file.split("\\R", -1)) {
            String trimmed = line.strip();
            if (list) {
                canonical.append(line).append('\n');
                if (trimmed.equals(">")) list = false;
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.endsWith("{")) {
                if (depth++ == 0) clientCategory = trimmed.equals("client {");
            } else if (trimmed.equals("}")) {
                if (--depth == 0) clientCategory = false;
            } else if (trimmed.endsWith("<")) list = true;
            else if (clientCategory && depth == 1) {
                int separator = trimmed.indexOf('=');
                if (separator > 0 && HUD.contains(trimmed.substring(0,separator))) continue;
            }
            canonical.append(line).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
