package dev.starbridge.compat.legacy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The exact classes and members Starbridge calls in Retrofit and the original 1.7.10 mods.
 *
 * <p>Each compat class declares its references here as constants instead of looking members up by
 * string at every call. References resolve once per class loader and are cached. {@link #verify}
 * checks every declared reference up front, so an incompatible upstream update is reported by
 * name in the install check instead of silently disabling a feature.</p>
 *
 * <p>{@link Realm#HOST} types live in the modern 1.20.1 game (Retrofit's bridge classes);
 * {@link Realm#LEGACY} types live on Retrofit's 1.7.10 game class loader.</p>
 */
public final class LegacyContract {
    /** Where a type is loaded from. */
    public enum Realm { HOST, LEGACY }
    public enum Kind { TYPE, METHOD, FIELD, DECLARED_FIELD, CONSTRUCTOR }

    /**
     * One required class or member. {@code module} matches an install-check entry id, e.g.
     * {@code galacticraft}, {@code advancedrocketry}, {@code libvulpes} or {@code retrofit}.
     * Parameter types are class names in the owner's realm, primitive names, or modern classes.
     */
    public record Ref(String module, Realm realm, Kind kind, String owner, String name, List<Object> params) {
        public Ref {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(realm, "realm");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owner, "owner");
            params = List.copyOf(params);
        }

        /** Human-readable signature, e.g. {@code zmaster587…TileRocketBuilder#getRocketPadBounds(World,int,int,int)}. */
        public String describe() {
            StringBuilder text = new StringBuilder(owner);
            if (kind == Kind.TYPE) return text.toString();
            text.append('#').append(kind == Kind.CONSTRUCTOR ? "<init>" : name);
            if (kind == Kind.METHOD || kind == Kind.CONSTRUCTOR) {
                text.append('(');
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) text.append(',');
                    Object type = params.get(i);
                    String full = type instanceof Class<?> clazz ? clazz.getName() : type.toString();
                    text.append(full.substring(full.lastIndexOf('.') + 1));
                }
                text.append(')');
            }
            return text.toString();
        }
    }

    public record Missing(Ref ref, String reason) {
        public String describe() { return ref.module() + ": " + ref.describe() + " (" + reason + ")"; }
    }

    /** Supplies the class loader for each realm; the legacy loader may be null before Retrofit starts. */
    @FunctionalInterface
    public interface Loaders { ClassLoader of(Realm realm) throws ReflectiveOperationException; }

    private static final Set<Ref> DECLARED = new LinkedHashSet<>();
    private static final Map<Key, Object> RESOLVED = new ConcurrentHashMap<>();
    private record Key(Ref ref, ClassLoader loader) {}

    private static volatile Loaders loaders = LegacyContract::defaultLoader;

    private LegacyContract() {}

    // ------------------------------------------------------------------ declaring references

    public static Ref type(String module, Realm realm, String owner) {
        return declare(new Ref(module, realm, Kind.TYPE, owner, null, List.of()));
    }

    public static Ref method(String module, Realm realm, String owner, String name, Object... params) {
        return declare(new Ref(module, realm, Kind.METHOD, owner, name, List.of(params)));
    }

    public static Ref field(String module, Realm realm, String owner, String name) {
        return declare(new Ref(module, realm, Kind.FIELD, owner, name, List.of()));
    }

    /** A non-public field, made accessible when resolved. */
    public static Ref declaredField(String module, Realm realm, String owner, String name) {
        return declare(new Ref(module, realm, Kind.DECLARED_FIELD, owner, name, List.of()));
    }

    public static Ref constructor(String module, Realm realm, String owner, Object... params) {
        return declare(new Ref(module, realm, Kind.CONSTRUCTOR, owner, null, List.of(params)));
    }

    private static Ref declare(Ref ref) {
        synchronized (DECLARED) { DECLARED.add(ref); }
        return ref;
    }

    public static List<Ref> declared() {
        synchronized (DECLARED) { return List.copyOf(DECLARED); }
    }

    // ------------------------------------------------------------------ resolving (cached)

    public static Class<?> type(Ref ref) throws ReflectiveOperationException {
        return (Class<?>) resolve(ref.kind() == Kind.TYPE ? ref : new Ref(ref.module(), ref.realm(), Kind.TYPE, ref.owner(), null, List.of()));
    }

    public static Method method(Ref ref) throws ReflectiveOperationException { return (Method) resolve(expect(ref, Kind.METHOD)); }

    public static Field field(Ref ref) throws ReflectiveOperationException {
        if (ref.kind() != Kind.FIELD && ref.kind() != Kind.DECLARED_FIELD) throw new IllegalArgumentException(ref + " is not a field");
        return (Field) resolve(ref);
    }

    public static Constructor<?> constructor(Ref ref) throws ReflectiveOperationException {
        return (Constructor<?>) resolve(expect(ref, Kind.CONSTRUCTOR));
    }

    /** Invokes a method reference; {@code target} is null for static methods. */
    public static Object invoke(Ref ref, Object target, Object... args) throws ReflectiveOperationException {
        return method(ref).invoke(target, args);
    }

    /** Reads a field reference; {@code target} is null for static fields. */
    public static Object get(Ref ref, Object target) throws ReflectiveOperationException {
        return field(ref).get(target);
    }

    public static Object create(Ref ref, Object... args) throws ReflectiveOperationException {
        return constructor(ref).newInstance(args);
    }

    private static Ref expect(Ref ref, Kind kind) {
        if (ref.kind() != kind) throw new IllegalArgumentException(ref + " is not a " + kind);
        return ref;
    }

    private static Object resolve(Ref ref) throws ReflectiveOperationException {
        ClassLoader loader = loaders.of(ref.realm());
        if (loader == null) throw new ClassNotFoundException(ref.owner() + " (Retrofit legacy runtime not started)");
        Key key = new Key(ref, loader);
        Object cached = RESOLVED.get(key);
        if (cached != null) return cached;
        Object resolved = lookup(ref, loader);
        RESOLVED.put(key, resolved);
        return resolved;
    }

    private static Object lookup(Ref ref, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(ref.owner(), false, loader);
        return switch (ref.kind()) {
            case TYPE -> owner;
            case METHOD -> owner.getMethod(ref.name(), parameterTypes(ref, loader));
            case FIELD -> owner.getField(ref.name());
            case DECLARED_FIELD -> {
                Field field = owner.getDeclaredField(ref.name());
                field.setAccessible(true);
                yield field;
            }
            case CONSTRUCTOR -> owner.getConstructor(parameterTypes(ref, loader));
        };
    }

    private static Class<?>[] parameterTypes(Ref ref, ClassLoader loader) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[ref.params().size()];
        for (int i = 0; i < types.length; i++) {
            Object type = ref.params().get(i);
            types[i] = type instanceof Class<?> clazz ? clazz : named(type.toString(), loader);
        }
        return types;
    }

    private static Class<?> named(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "boolean" -> boolean.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> Class.forName(name, false, loader);
        };
    }

    // ------------------------------------------------------------------ verification

    /** Checks every declared reference against the running game. Empty when everything resolves. */
    public static List<Missing> verify() { return verify(declared()); }

    public static List<Missing> verify(Collection<Ref> refs) {
        var missing = new ArrayList<Missing>();
        for (Ref ref : refs) {
            try {
                resolve(ref);
            } catch (ClassNotFoundException | NoClassDefFoundError absent) {
                missing.add(new Missing(ref, "class not found"));
            } catch (NoSuchMethodException | NoSuchFieldException changed) {
                missing.add(new Missing(ref, "member not found"));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                missing.add(new Missing(ref, failure.getClass().getSimpleName()));
            }
        }
        return List.copyOf(missing);
    }

    /** Missing references grouped for one install-check module. */
    public static List<Missing> missingFor(List<Missing> report, String module) {
        return report.stream().filter(m -> m.ref().module().equals(module)).toList();
    }

    /** The underlying cause of a reflective call failure, for logs and status text. */
    public static Throwable cause(Throwable failure) {
        return failure instanceof InvocationTargetException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure;
    }

    // ------------------------------------------------------------------ loaders

    private static ClassLoader defaultLoader(Realm realm) throws ReflectiveOperationException {
        return realm == Realm.HOST ? LegacyContract.class.getClassLoader() : retrofitGameLoader();
    }

    /** Retrofit's 1.7.10 game class loader, or null before its runtime has started. */
    public static ClassLoader retrofitGameLoader() throws ReflectiveOperationException {
        Method runtimeAccessor = retrofitRuntime;
        if (runtimeAccessor == null) {
            Class<?> retrofit = Class.forName(RetrofitApi.MOD, false, LegacyContract.class.getClassLoader());
            runtimeAccessor = retrofitRuntime = retrofit.getMethod("runtime");
        }
        Object runtime = runtimeAccessor.invoke(null);
        if (runtime == null) return null;
        Method loaderAccessor = retrofitGameLoader;
        if (loaderAccessor == null || loaderAccessor.getDeclaringClass() != runtime.getClass())
            loaderAccessor = retrofitGameLoader = runtime.getClass().getMethod("gameLoader");
        return (ClassLoader) loaderAccessor.invoke(runtime);
    }

    private static volatile Method retrofitRuntime;
    private static volatile Method retrofitGameLoader;

    /** Test seam: replace the realm class loaders and clear the cache. */
    static void useLoaders(Loaders replacement) {
        loaders = Objects.requireNonNull(replacement);
        RESOLVED.clear();
    }

    static void reset() { useLoaders(LegacyContract::defaultLoader); }

    /** Names of Retrofit's own entry classes. */
    static final class RetrofitApi {
        static final String MOD = "retrofit.forge.RetrofitMod";
        private RetrofitApi() {}
    }
}
