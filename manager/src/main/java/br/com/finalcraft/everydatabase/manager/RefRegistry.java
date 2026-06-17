package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A self-contained <b>world of references</b>: it maps each entity type to its
 * {@link RefResolver} (its manager) and is the single object you wire your refs through.
 *
 * <p>There is <b>no global registry</b> on purpose. Each context (a plugin, a subsystem, a
 * test) creates its own {@code RefRegistry}, and a {@link Ref} resolves only against the
 * registry it was bound to. Two independent registries can therefore each register a manager for
 * the <b>same</b> entity type — backed by different storages, with different policies — and they
 * never collide. (A static, process-wide registry would silently re-introduce exactly that
 * collision: two managers for {@code Player}, last-writer-wins.)
 *
 * <h3>Wire a context through the registry</h3>
 * The registry <b>vends</b> the two things a ref-aware setup needs, so you cannot half-wire it:
 * <ul>
 *   <li>{@link #codec(Class)} — a ref-aware Jackson codec <b>bound to this registry</b>; every
 *       {@link Ref} it deserializes resolves here.</li>
 *   <li>{@link #manager(EntityDescriptor, Storage, CachePolicy)} — a {@link CachingManager}
 *       created and <b>registered in this registry</b> in one call.</li>
 * </ul>
 *
 * <pre>{@code
 * RefRegistry world = new RefRegistry();
 *
 * EntityDescriptor<UUID, Guild> GUILDS = EntityDescriptor.builder(UUID.class, Guild.class)
 *         .collection("guilds")
 *         .keyExtractor(Guild::getId)
 *         .codec(world.codec(Guild.class))     // bound to 'world'
 *         .build();
 *
 * CachingManager<UUID, Guild> guilds = world.manager(GUILDS, guildStorage, CachePolicy.always());
 * }</pre>
 *
 * <p>The map is a {@link ConcurrentHashMap}; registration and lookup are thread-safe.
 */
public final class RefRegistry {

    private final Map<Class<?>, RefResolver<?, ?>> resolvers = new ConcurrentHashMap<>();

    public RefRegistry() {
    }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    /**
     * Registers (or replaces) the resolver for {@code type}. {@link CachingManager} calls this
     * from its constructor, so {@link #manager(EntityDescriptor, Storage, CachePolicy)} and a
     * {@code CachingManager} built with this registry both self-register here.
     */
    public void register(Class<?> type, RefResolver<?, ?> resolver) {
        resolvers.put(type, resolver);
    }

    /** Removes the resolver for {@code type}, if any. */
    public void unregister(Class<?> type) {
        resolvers.remove(type);
    }

    /** Returns {@code true} when a resolver is registered for {@code type}. */
    public boolean isRegistered(Class<?> type) {
        return resolvers.containsKey(type);
    }

    /**
     * Returns the resolver for {@code type}, or {@code null} when none is registered.
     * The cast is safe: registration always pairs a {@code Class<V>} with a matching
     * {@code RefResolver<?, V>}.
     */
    @SuppressWarnings("unchecked")
    public <K, V> RefResolver<K, V> resolver(Class<V> type) {
        return (RefResolver<K, V>) resolvers.get(type);
    }

    /** Clears every registration. Handy in tests, or to tear a context down. */
    public void clear() {
        resolvers.clear();
    }

    // ------------------------------------------------------------------
    //  Factories (bound to this registry)
    // ------------------------------------------------------------------

    /**
     * A ref-aware compact JSON codec for {@code type}, <b>bound to this registry</b>: every
     * {@link Ref} it deserializes resolves through this registry's managers. Use it as the codec
     * on any descriptor whose entity contains {@code Ref} fields.
     */
    public <T> JacksonJsonCodec<T> codec(Class<T> type) {
        return RefCodecs.json(type, this);
    }

    /**
     * Creates a {@link CachingManager} for {@code descriptor} on {@code storage} and registers it
     * in this registry, in one call. Convenience for the non-subclass case; a domain-named
     * subclass passes this registry to {@code super(...)} instead.
     */
    public <K, V> CachingManager<K, V> manager(EntityDescriptor<K, V> descriptor, Storage storage, CacheOptions options) {
        return new CachingManager<>(descriptor, storage, options, this);
    }

    /** {@link #manager(EntityDescriptor, Storage, CacheOptions)} with an unbounded cache. */
    public <K, V> CachingManager<K, V> manager(EntityDescriptor<K, V> descriptor, Storage storage, CachePolicy policy) {
        return manager(descriptor, storage, CacheOptions.of(policy));
    }

    /**
     * A {@link Ref} bound to this registry — for resolving a key you hold programmatically (the
     * common case is a {@code Ref} deserialized from an entity, which the {@link #codec(Class)}
     * binds automatically). Resolving an <b>unbound</b> {@code Ref.of(key, type)} fails fast.
     */
    public <K, V> Ref<K, V> ref(K key, Class<V> type) {
        return Ref.of(key, type, this);
    }

    /** A bound {@link Ref} carrying a per-reference freshness override. */
    public <K, V> Ref<K, V> ref(K key, Class<V> type, CachePolicy policyOverride) {
        return Ref.of(key, type, this).withPolicy(policyOverride);
    }
}
