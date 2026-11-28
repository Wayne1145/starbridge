package dev.starbridge.compat.galacticraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Navigation identity stays stable when an old dimension ID is moved by the pack. */
public record CelestialPort(String key, String title, String provider, int tier, int fare, ResourceKey<Level> dimension) {
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+(?:[.][a-z0-9_]+)*");
    public static final String GC = "Galacticraft";
    public static final String EP = "ExtraPlanets";
    public static final String GS = "GalaxySpace";
    public static final CelestialPort HOME = new CelestialPort("home", "Earth", "Minecraft", 0, 0, Level.OVERWORLD);
    public static final Set<String> CLASSIC = Set.of("home", "moon", "mars", "belt");

    public CelestialPort {
        if (!validKey(key) || title == null || title.isBlank() || provider == null || tier < 0 || tier > 100 || fare < 0)
            throw new IllegalArgumentException("Invalid celestial port");
    }
    public static boolean validKey(String key) { return key != null && key.length() <= 96 && KEY.matcher(key).matches(); }
    public static String owner(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "galacticraftcore", "galacticraftmars", "galacticraftplanets" -> GC;
            case "extraplanets" -> EP;
            case "galaxyspace" -> GS;
            default -> "";
        };
    }
    public static String key(String provider, String raw) {
        String body = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (body.isEmpty()) throw new IllegalArgumentException("Unusable celestial name");
        if (GC.equals(provider)) {
            if (body.equals("asteroids")) return "belt";
            if (body.equals("moon") || body.equals("mars")) return body;
        }
        String key = provider.toLowerCase(Locale.ROOT) + "." + body;
        if (!validKey(key)) throw new IllegalArgumentException("Unusable celestial name");
        return key;
    }
    static CelestialPort legacy(String name, String title, String provider, int tier, int realId) {
        String key = key(provider, name);
        return new CelestialPort(key, title, provider, tier, cost(key, tier), ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation("retrofit", "legacy_dim_" + realId)));
    }
    public static CelestialPort missing(String key) {
        int tier = switch (key) { case "moon" -> 1; case "mars" -> 2; case "belt" -> 3; default -> throw new IllegalArgumentException(key); };
        return new CelestialPort(key, key, GC, tier, cost(key,tier), null);
    }
    private static int cost(String key, int tier) {
        return switch (key) { case "moon" -> 12_000; case "mars" -> 32_000; case "belt" -> 40_000; default -> 12_000 + 8_000 * Math.max(0, tier - 1); };
    }
    public int fareTo(CelestialPort to) {
        if (key.equals(to.key)) throw new IllegalArgumentException("Already at this destination");
        return Math.max(fare, to.fare);
    }
    public int requiredEnergy(CelestialPort to) { return fareTo(to) + to.fare; }
}
