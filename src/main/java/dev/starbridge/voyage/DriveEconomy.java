package dev.starbridge.voyage;

import dev.starbridge.orbit.OrbitalFlight;
import dev.starbridge.orbit.ShipRating;

/**
 * Every drive-energy price in one place, so balance can be tuned with a single edit.
 *
 * <p>Target feel: a starter ship's Moon round trip costs about four lumps of coal; a hundred-block
 * cargo hull needs a stack or real Advanced Rocketry power. Fares are paid on landing; the return
 * reserve is held back so a ship can always fly home.</p>
 */
public final class DriveEconomy {
    /** FE per tick of furnace burn time when fuel is fed into a core (a coal lump gives 32,000 FE). */
    public static final int FE_PER_BURN_TICK = 20;
    /** Multiplier on the published celestial fares. */
    public static final int FARE_MULTIPLIER = 5;

    private DriveEconomy() {}

    /** FE charged on landing for a leg whose published fare is {@code baseFare}. */
    public static int fare(int baseFare, ShipRating ship) { return fare(baseFare, ship.costFactor()); }

    public static int fare(int baseFare, double costFactor) {
        return scale((long) baseFare * FARE_MULTIPLIER, costFactor);
    }

    /** FE the core must keep for the way home from a destination with published fare {@code baseFare}. */
    public static int reserve(int baseFare, ShipRating ship) { return fare(baseFare, ship); }

    public static int reserve(int baseFare, double costFactor) { return fare(baseFare, costFactor); }

    /** One powered acceleration and braking run in orbit, at this ship's per-step cost. */
    public static int cruiseAllowance(ShipRating ship) { return cruiseAllowance(ship.costFactor()); }

    public static int cruiseAllowance(double costFactor) {
        return scale(OrbitalFlight.CRUISE_ALLOWANCE, costFactor);
    }

    /** Energy that stays locked during the flight: fare, return reserve and one braking run. */
    public static int protectedEnergy(int fareBase, int reserveBase, boolean returningHome, ShipRating ship) {
        return protectedEnergy(fareBase, reserveBase, returningHome, ship.costFactor());
    }

    public static int protectedEnergy(int fareBase, int reserveBase, boolean returningHome, double costFactor) {
        long total = (long) fare(fareBase, costFactor) + reserve(reserveBase, costFactor) + (returningHome ? 0 : cruiseAllowance(costFactor));
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Energy needed to launch: everything protected plus one more powered run to get underway. */
    public static int launchEnergy(int fareBase, int reserveBase, boolean returningHome, ShipRating ship) {
        return launchEnergy(fareBase, reserveBase, returningHome, ship.costFactor());
    }

    public static int launchEnergy(int fareBase, int reserveBase, boolean returningHome, double costFactor) {
        return (int) Math.min(Integer.MAX_VALUE, (long) protectedEnergy(fareBase, reserveBase, returningHome, costFactor) + cruiseAllowance(costFactor));
    }

    /** FE for one orbital step whose unscaled cost is {@code baseStep}. */
    public static int step(int baseStep, ShipRating ship) { return step(baseStep, ship.costFactor()); }

    public static int step(int baseStep, double costFactor) {
        return baseStep <= 0 ? 0 : scale(baseStep, costFactor);
    }

    /** FE a furnace fuel item adds, before the core's per-item cap. */
    public static long fuelEnergy(int burnTicks) {
        return (long) Math.max(0, burnTicks) * FE_PER_BURN_TICK;
    }

    private static int scale(long base, double factor) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(base * factor));
    }
}
