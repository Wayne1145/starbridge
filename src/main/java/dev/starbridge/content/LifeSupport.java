package dev.starbridge.content;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Breathable air granted by a powered ship core to players inside its hull. Grants are short and
 * must be renewed by the core every second, so an unpowered or departed ship stops supplying air.
 * Server thread only.
 */
public final class LifeSupport {
    public static final int GRANT_TICKS = 40;
    private static final Map<UUID, Long> UNTIL = new HashMap<>();

    private LifeSupport() {}

    public static void grant(Player player, long gameTime) {
        UNTIL.put(player.getUUID(), gameTime + GRANT_TICKS);
    }

    public static boolean active(Player player, long gameTime) {
        Long until = UNTIL.get(player.getUUID());
        if (until == null) return false;
        if (gameTime > until) {
            UNTIL.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public static void clear() { UNTIL.clear(); }
}
