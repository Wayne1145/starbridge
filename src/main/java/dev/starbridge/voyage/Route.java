package dev.starbridge.voyage;

import java.util.Arrays;
import java.util.Locale;

/** Published destinations and fixed drive costs. The same fare is used by the helm and remote controls. */
public enum Route {
    HOME(0), MOON(12_000), MARS(32_000), BELT(40_000), RELAY(68_000);

    private final int legCost;
    Route(int legCost) { this.legCost = legCost; }
    public String key() { return name().toLowerCase(Locale.ROOT); }
    public static Route named(String key) {
        return Arrays.stream(values()).filter(r -> r.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown expedition destination: " + key));
    }
    public int fareTo(Route target) {
        if (target == this) throw new IllegalArgumentException("Already at this destination");
        return Math.max(legCost, target.legCost);
    }
    public int returnReserve() { return legCost; }
    public void checkFuel(Route target, int energy) {
        if (energy < fareTo(target) + target.returnReserve()) {
            throw new IllegalStateException("Insufficient drive energy (fare " + fareTo(target)
                    + " FE, return reserve " + target.returnReserve() + " FE)");
        }
    }
}
