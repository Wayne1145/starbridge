package dev.starbridge.block;

import java.util.Locale;

/** Core modules installed by right-clicking the core. Levels are saved on the core and kept when it is mined. */
public enum ShipUpgrade {
    /** +2,000,000 FE storage per module. */
    CAPACITOR(4),
    /** +50% thrust and cruise speed per module. */
    THRUSTER(2),
    /** −20% drive energy per module. */
    EFFICIENCY(2);

    public static final int BASE_CAPACITY = 2_000_000;
    public static final int CAPACITY_PER_MODULE = 2_000_000;

    private final int maxLevel;

    ShipUpgrade(int maxLevel) { this.maxLevel = maxLevel; }

    public int maxLevel() { return maxLevel; }
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static int capacity(int capacitorLevel) { return BASE_CAPACITY + CAPACITY_PER_MODULE * clamp(capacitorLevel, CAPACITOR); }
    public static double thrustFactor(int thrusterLevel) { return 1.0 + 0.5 * clamp(thrusterLevel, THRUSTER); }
    public static double energyFactor(int efficiencyLevel) { return 1.0 - 0.2 * clamp(efficiencyLevel, EFFICIENCY); }

    private static int clamp(int level, ShipUpgrade type) { return Math.max(0, Math.min(type.maxLevel, level)); }
}
