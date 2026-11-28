package dev.starbridge.orbit;

/**
 * What a ship is built from, counted block by block in its cabin. Bigger hulls cost more to move;
 * original Advanced Rocketry motors make them faster, fuel tanks make them cheaper and let the
 * core store more drive energy. Pure numbers, no game state.
 */
public record ShipRating(int hullBlocks, int engines, int tanks, int seats, boolean guidance) {
    /** Hull blocks one engine moves at the reference thrust-to-weight ratio of 1. */
    public static final int BLOCKS_PER_ENGINE = 40;
    /** Hull blocks billed at the base fare; heavier hulls pay proportionally more. */
    public static final int BASE_HULL = 400;
    public static final double MAX_MASS_FACTOR = 8;
    public static final double EFFICIENCY_PER_TANK = 0.05, MAX_EFFICIENCY = 0.40;
    public static final double MIN_PERFORMANCE = 0.5, MAX_PERFORMANCE = 2.0;
    /** Thrust-to-weight ratio below which the helm warns that the engines limit the ship. */
    public static final double LOW_THRUST = 0.5;
    public static final int CAPACITY_PER_TANK = 250_000, BASE_CAPACITY = 2_000_000, MAX_CAPACITY = 20_000_000;

    public ShipRating {
        if (hullBlocks < 1 || engines < 0 || tanks < 0 || seats < 0)
            throw new IllegalArgumentException("Invalid ship rating");
    }

    /** Fare multiplier from hull size: 1 up to {@value #BASE_HULL} blocks, capped at {@value #MAX_MASS_FACTOR}. */
    public double massFactor() {
        return Math.max(1, Math.min(MAX_MASS_FACTOR, hullBlocks / (double) BASE_HULL));
    }

    public double thrustToWeight() {
        return engines * (double) BLOCKS_PER_ENGINE / hullBlocks;
    }

    /** Orbital acceleration and top-speed multiplier; square root so each extra engine helps less. */
    public double performance() {
        if (engines == 0) return MIN_PERFORMANCE;
        return Math.max(MIN_PERFORMANCE, Math.min(MAX_PERFORMANCE, Math.sqrt(thrustToWeight())));
    }

    public double efficiency() {
        return Math.min(MAX_EFFICIENCY, tanks * EFFICIENCY_PER_TANK);
    }

    /** Multiplier on every drive cost: heavier ships pay more, fuel tanks discount it. */
    public double costFactor() {
        return massFactor() * (1 - efficiency());
    }

    /** Drive energy the ship's core can hold. */
    public int capacity() {
        return (int) Math.min(MAX_CAPACITY, BASE_CAPACITY + (long) tanks * CAPACITY_PER_TANK);
    }

    public boolean underpowered() { return thrustToWeight() < LOW_THRUST; }
}
