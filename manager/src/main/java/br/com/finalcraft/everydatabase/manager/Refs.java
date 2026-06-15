package br.com.finalcraft.everydatabase.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry mapping an entity type to its {@link RefResolver} (its manager).
 *
 * <p>A {@link Ref} carries only a key and the target type; it finds its resolver here at
 * resolution time. Managers self-register on construction ({@code CachingManager}'s
 * constructor calls {@link #register}), so wiring is just "create the managers at startup".
 *
 * <p>This is a static service-locator - idiomatic for a single-server plugin where managers
 * are effectively singletons. For multiple isolated contexts (or hermetic tests), inject the
 * resolver into the codec via Jackson {@code InjectableValues} instead; see
 * {@code specs/SPEC_refs_and_managers.md}.
 */
public final class Refs {

    private static final Map<Class<?>, RefResolver<?, ?>> RESOLVERS = new ConcurrentHashMap<>();

    private Refs() {
    }

    /** Registers (or replaces) the resolver for {@code type}. */
    public static void register(Class<?> type, RefResolver<?, ?> resolver) {
        RESOLVERS.put(type, resolver);
    }

    /** Removes the resolver for {@code type}, if any. */
    public static void unregister(Class<?> type) {
        RESOLVERS.remove(type);
    }

    /** Returns {@code true} when a resolver is registered for {@code type}. */
    public static boolean isRegistered(Class<?> type) {
        return RESOLVERS.containsKey(type);
    }

    /**
     * Returns the resolver for {@code type}, or {@code null} when none is registered.
     * The cast is safe: registration always pairs a {@code Class<V>} with a matching
     * {@code RefResolver<?, V>}.
     */
    @SuppressWarnings("unchecked")
    public static <K, V> RefResolver<K, V> resolver(Class<V> type) {
        return (RefResolver<K, V>) RESOLVERS.get(type);
    }

    /** Clears every registration. Intended for tests. */
    public static void clear() {
        RESOLVERS.clear();
    }
}
