package dev.starbridge.compat.rocketry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Calls only named, public entry points of Retrofit 0.6.2 and Advanced Rocketry MC1.7. */
final class OriginalRocketApi {
    static final String BUILDER = "zmaster587.advancedRocketry.tile.TileRocketBuilder";
    static final String ROCKET = "zmaster587.advancedRocketry.entity.EntityRocket";
    private static final String HOST = "retrofit.forge.world.";
    static final ResourceLocation ASSEMBLER =
            new ResourceLocation("advancedrocketry", "rocketbuilder");
    static final ResourceLocation CARRIER = new ResourceLocation("advancedrocketry", "rocket");

    private OriginalRocketApi() {}

    static boolean isConsole(net.minecraft.world.level.block.state.BlockState state) {
        return ASSEMBLER.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    static Optional<Object> builder(BlockEntity wrapped) {
        if (wrapped == null || !isConsole(wrapped.getBlockState())) return Optional.empty();
        try {
            Object tile = call(wrapped, "legacyTile");
            return tile != null && tile.getClass().getName().equals(BUILDER)
                    ? Optional.of(tile)
                    : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    static Object rocket(Entity hosted) throws ReflectiveOperationException {
        if (!CARRIER.equals(ForgeRegistries.ENTITY_TYPES.getKey(hosted.getType()))) return null;
        Object core = call(hosted, "core");
        Object original = call(core, "legacy");
        return original != null && original.getClass().getName().equals(ROCKET) ? original : null;
    }

    static Object call(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    static int number(Object object, String method) throws ReflectiveOperationException {
        return ((Number) call(object, method)).intValue();
    }

    static Object host(String className, String methodName, Class<?>[] args, Object... values)
            throws ReflectiveOperationException {
        return Class.forName(HOST + className).getMethod(methodName, args).invoke(null, values);
    }

    static Object bounds(Object builder, ServerPlayer player, BlockPos console)
            throws ReflectiveOperationException {
        ClassLoader realm = builder.getClass().getClassLoader();
        Object world =
                host(
                        "LegacyContent",
                        "legacyWorld",
                        new Class<?>[] {net.minecraft.world.level.Level.class},
                        player.serverLevel());
        return builder.getClass()
                .getMethod(
                        "getRocketPadBounds",
                        realm.loadClass("net.minecraft.world.World"),
                        int.class,
                        int.class,
                        int.class)
                .invoke(builder, world, console.getX(), console.getY(), console.getZ());
    }
    /**
     * Same server-side entry as the upstream machine GUI. The caller checks distance, permissions
     * and block identity.
     */
    static void press(Object tile, ServerPlayer player, boolean assemble)
            throws ReflectiveOperationException {
        Object mirror =
                host(
                        "LegacyPlayers",
                        "mirror",
                        new Class<?>[] {Player.class, boolean.class},
                        player,
                        true);
        if (mirror == null)
            throw new IllegalStateException("No legacy player for this crew member");
        ClassLoader realm = tile.getClass().getClassLoader();
        Class<?> side = realm.loadClass("cpw.mods.fml.relauncher.Side");
        Class<?> tag = realm.loadClass("net.minecraft.nbt.NBTTagCompound");
        Method packet =
                tile.getClass()
                        .getMethod(
                                "useNetworkData",
                                realm.loadClass("net.minecraft.entity.player.EntityPlayer"),
                                side,
                                byte.class,
                                tag);
        packet.invoke(
                tile,
                mirror,
                side.getField("SERVER").get(null),
                (byte) (assemble ? 1 : 0),
                tag.getConstructor().newInstance());
    }

    static void passengerAction(Object rocket, ServerPlayer player, String action)
            throws ReflectiveOperationException {
        Object mirror =
                host(
                        "LegacyPlayers",
                        "mirror",
                        new Class<?>[] {Player.class, boolean.class},
                        player,
                        true);
        ClassLoader realm = rocket.getClass().getClassLoader();
        Class<?> types = realm.loadClass(ROCKET + "$PacketType"),
                side = realm.loadClass("cpw.mods.fml.relauncher.Side"),
                nbt = realm.loadClass("net.minecraft.nbt.NBTTagCompound");
        int code = ((Enum<?>) types.getField(action).get(null)).ordinal();
        rocket.getClass()
                .getMethod(
                        "useNetworkData",
                        realm.loadClass("net.minecraft.entity.player.EntityPlayer"),
                        side,
                        byte.class,
                        nbt)
                .invoke(
                        rocket,
                        mirror,
                        side.getField("SERVER").get(null),
                        (byte) code,
                        nbt.getConstructor().newInstance());
        host(
                "LegacyPlayers",
                "writeBack",
                new Class<?>[] {Player.class, Object.class},
                player,
                mirror);
        host(
                "LegacyPlayers",
                "syncRiding",
                new Class<?>[] {Player.class, Object.class},
                player,
                mirror);
    }

    static void planLocalOrbit(Object original, ServerPlayer pilot)
            throws ReflectiveOperationException {
        Object computer = call(field(original, "storage"), "getGuidanceComputer");
        if (computer == null) throw new IllegalStateException("navigation");
        // Respect any original navigation chip. A blank computer can be programmed for a local
        // orbit departure.
        if (computer.getClass().getMethod("func_70301_a", int.class).invoke(computer, 0) != null)
            return;
        if (pilot.level().dimension() != net.minecraft.world.level.Level.OVERWORLD)
            throw new IllegalStateException("navigation");
        Class<?> vec =
                original.getClass()
                        .getClassLoader()
                        .loadClass("zmaster587.libVulpes.util.Vector3F");
        Object position =
                vec.getConstructor(Object.class, Object.class, Object.class)
                        .newInstance(
                                (float) pilot.getX(), (float) pilot.getY(), (float) pilot.getZ());
        computer.getClass()
                .getMethod("setFallbackDestination", int.class, vec)
                .invoke(computer, 0, position);
    }

    static String error(Throwable exception) {
        Throwable cause =
                exception instanceof InvocationTargetException wrapped && wrapped.getCause() != null
                        ? wrapped.getCause()
                        : exception;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
