package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Opens Galacticraft 3's own player container, as its original inventory tab does. */
public final class OriginalOxygenEquipment {
    private static final ResourceLocation MASK = new ResourceLocation("galacticraftcore", "item.oxygenmask");
    private static final int GUI_EXTENDED_INVENTORY = 5;

    public enum Result { OPENED, MISSING, UNAVAILABLE, BUSY }

    private OriginalOxygenEquipment() {}

    public static Result open(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return Result.UNAVAILABLE;
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return Result.BUSY;
        if (!ModList.get().isLoaded("retrofit") || !ForgeRegistries.ITEMS.containsKey(MASK)) return Result.MISSING;

        try {
            Object mirror = Class.forName("retrofit.forge.world.LegacyPlayers").getMethod("mirror", Player.class, boolean.class)
                    .invoke(null, player, true);
            if (mirror == null) return Result.UNAVAILABLE;
            ClassLoader realm = mirror.getClass().getClassLoader();
            Object mod = realm.loadClass("micdoodle8.mods.galacticraft.core.GalacticraftCore").getField("instance").get(null);
            Object world = Class.forName("retrofit.forge.world.LegacyContent").getMethod("legacyWorld", Level.class)
                    .invoke(null, player.serverLevel());
            if (mod == null || world == null) return Result.UNAVAILABLE;
            Class<?> hostMenu = Class.forName("retrofit.forge.world.LegacyMenu");
            var unwrap = hostMenu.getMethod("legacyContainer");
            Class<?> equipment = realm.loadClass("micdoodle8.mods.galacticraft.core.inventory.ContainerExtendedInventory");
            mirror.getClass().getMethod("openGui", Object.class, int.class, realm.loadClass("net.minecraft.world.World"),
                            int.class, int.class, int.class)
                    .invoke(mirror, mod, GUI_EXTENDED_INVENTORY, world, 0, 0, 0);
            return hostMenu.isInstance(player.containerMenu) && equipment.isInstance(unwrap.invoke(player.containerMenu))
                    ? Result.OPENED : Result.UNAVAILABLE;
        } catch (ReflectiveOperationException | LinkageError failure) {
            Starbridge.LOGGER.debug("Could not open the player's original Galacticraft oxygen equipment", failure);
            return Result.UNAVAILABLE;
        }
    }
}
