package dev.starbridge.compat.rocketry;

import dev.starbridge.compat.legacy.LegacyApis;
import dev.starbridge.compat.legacy.LegacyContract;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static dev.starbridge.compat.legacy.LegacyApis.*;
import static dev.starbridge.compat.legacy.LegacyContract.invoke;

/**
 * Calls only named, public entry points of Retrofit 0.6.2 and Advanced Rocketry MC1.7. Fixed entry
 * points are declared in {@link LegacyApis}; {@link #call} and {@link #field} remain for members of
 * runtime-chosen classes and cache their lookups per class.
 */
final class OriginalRocketApi {
    static final String BUILDER = LegacyApis.ROCKET_BUILDER;
    static final String ROCKET = LegacyApis.ROCKET;
    private static final String HOST = "retrofit.forge.world.";
    static final ResourceLocation ASSEMBLER =
            new ResourceLocation("advancedrocketry", "rocketbuilder");
    static final ResourceLocation CARRIER = new ResourceLocation("advancedrocketry", "rocket");
    private static final Map<Member, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<Member, Field> FIELDS = new ConcurrentHashMap<>();
    private record Member(Class<?> owner, String name) {}

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
        Member key = new Member(owner.getClass(), name);
        Method method = METHODS.get(key);
        if (method == null) {
            method = owner.getClass().getMethod(name);
            METHODS.put(key, method);
        }
        return method.invoke(owner);
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Member key = new Member(owner.getClass(), name);
        Field field = FIELDS.get(key);
        if (field == null) {
            field = owner.getClass().getField(name);
            FIELDS.put(key, field);
        }
        return field.get(owner);
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
        Object world = invoke(LEGACY_WORLD, null, player.serverLevel());
        return invoke(PAD_BOUNDS, builder, world, console.getX(), console.getY(), console.getZ());
    }

    /**
     * Same server-side entry as the upstream machine GUI. The caller checks distance, permissions
     * and block identity.
     */
    static void press(Object tile, ServerPlayer player, boolean assemble)
            throws ReflectiveOperationException {
        Object mirror = invoke(PLAYER_MIRROR, null, player, true);
        if (mirror == null)
            throw new IllegalStateException("No legacy player for this crew member");
        invoke(BUILDER_NETWORK, tile, mirror, LegacyContract.get(SIDE_SERVER, null),
                (byte) (assemble ? 1 : 0), LegacyContract.create(NEW_NBT));
    }

    static void passengerAction(Object rocket, ServerPlayer player, String action)
            throws ReflectiveOperationException {
        Object mirror = invoke(PLAYER_MIRROR, null, player, true);
        Class<?> types = LegacyContract.type(ROCKET_PACKET_TYPE);
        int code = ((Enum<?>) types.getField(action).get(null)).ordinal();
        invoke(ROCKET_NETWORK, rocket, mirror, LegacyContract.get(SIDE_SERVER, null),
                (byte) code, LegacyContract.create(NEW_NBT));
        invoke(PLAYER_WRITE_BACK, null, player, mirror);
        invoke(PLAYER_SYNC_RIDING, null, player, mirror);
    }

    static void planLocalOrbit(Object original, ServerPlayer pilot)
            throws ReflectiveOperationException {
        Object computer = call(LegacyContract.get(ROCKET_STORAGE, original), "getGuidanceComputer");
        if (computer == null) throw new IllegalStateException("navigation");
        // Respect any original navigation chip. A blank computer can be programmed for a local
        // orbit departure.
        if (computer.getClass().getMethod("func_70301_a", int.class).invoke(computer, 0) != null)
            return;
        if (pilot.level().dimension() != net.minecraft.world.level.Level.OVERWORLD)
            throw new IllegalStateException("navigation");
        Object position = LegacyContract.create(NEW_VECTOR,
                (float) pilot.getX(), (float) pilot.getY(), (float) pilot.getZ());
        computer.getClass()
                .getMethod("setFallbackDestination", int.class, position.getClass())
                .invoke(computer, 0, position);
    }

    static String error(Throwable exception) {
        Throwable cause = LegacyContract.cause(exception);
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
