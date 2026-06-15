package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A cache-backed façade in front of a single {@link Repository}: it owns an in-memory
 * identity map of its entities (one instance per key) and resolves {@link Ref}s to that type.
 *
 * <p>Construct one per entity type at startup; it self-registers in {@link Refs} (keyed by the
 * descriptor's entity type), so every {@code Ref<?, ThatType>} resolves through it. Subclass it
 * for a domain-named manager ({@code class GuildManager extends CachingManager<UUID, Guild>}),
 * or instantiate it directly.
 *
 * <h3>Freshness vs capacity</h3>
 * Freshness is a {@link CachePolicy} (default from {@link CacheOptions}, overridable per
 * reference); capacity is the LRU bound from {@link CacheOptions#maxSize()}. The two are
 * separate by design - see {@code CacheOptions}.
 *
 * <h3>Invalidation</h3>
 * {@link #save(Object)} is write-through (the cache entry is replaced with the just-saved value)
 * and auto-evicts on an {@link OptimisticLockException} (a stale cached write means the cache is
 * behind the backend). Cross-process writes are invisible here - bound their staleness with a
 * TTL policy and/or wire an external signal to {@link #invalidate(Object)}/{@link #evict(Object)}.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public class CachingManager<K, V> implements RefResolver<K, V> {

    protected final Repository<K, V> repository;
    protected final Class<V> type;
    protected final Function<V, K> keyOf;
    protected final CacheOptions options;
    private final LruCacheStore<K, V> store;

    /** Creates a manager with the given options and registers it in {@link Refs}. */
    public CachingManager(EntityDescriptor<K, V> descriptor, Storage storage, CacheOptions options) {
        this.repository = storage.repository(descriptor);
        this.type       = descriptor.type();
        this.keyOf      = descriptor.keyExtractor();
        this.options    = options;
        this.store      = new LruCacheStore<>(options.maxSize());
        Refs.register(type, this);
    }

    /** Convenience: unbounded cache with the given default policy. */
    public CachingManager(EntityDescriptor<K, V> descriptor, Storage storage, CachePolicy policy) {
        this(descriptor, storage, CacheOptions.of(policy));
    }

    // ------------------------------------------------------------------
    //  RefResolver
    // ------------------------------------------------------------------

    @Override
    public Optional<V> peek(K key) {
        return peek(key, options.policy());
    }

    @Override
    public Optional<V> peek(K key, CachePolicy policy) {
        CacheEntry<V> entry = store.get(key);
        return (entry != null && policy.isFresh(entry)) ? Optional.of(entry.getValue()) : Optional.empty();
    }

    @Override
    public CompletableFuture<Optional<V>> resolve(K key) {
        return resolve(key, options.policy());
    }

    @Override
    public CompletableFuture<Optional<V>> resolve(K key, CachePolicy policy) {
        CacheEntry<V> observed = store.get(key);
        if (observed != null && policy.isFresh(observed)) {
            return CompletableFuture.completedFuture(Optional.of(observed.getValue()));
        }
        final CacheEntry<V> previous = observed; // null (absent) or a stale entry
        return repository.find(key).thenApply(opt -> {
            if (!opt.isPresent()) {
                return opt;
            }
            V value = opt.get();
            if (!policy.cacheable()) {
                return Optional.of(value); // true bypass: never touch the shared cache
            }
            return Optional.of(publishLoaded(key, previous, value));
        });
    }

    /**
     * Publishes a freshly loaded value while keeping the identity map stable under concurrency:
     * the first instance published for a key wins (concurrent cold misses converge), and an
     * authoritative {@link #save} is never clobbered by a slower reload.
     */
    private V publishLoaded(K key, CacheEntry<V> previous, V value) {
        CacheEntry<V> candidate = new CacheEntry<>(value);
        if (previous == null) {
            // Cold miss: install only if still absent; otherwise keep whoever won the race.
            return store.installIfAbsent(key, candidate).getValue();
        }
        // Stale reload: replace exactly the entry we judged stale; if it changed underneath us
        // (a concurrent save or another reload), keep the current canonical instance.
        if (store.replaceIfSame(key, previous, candidate)) {
            return value;
        }
        CacheEntry<V> current = store.get(key);
        return current != null ? current.getValue() : value;
    }

    // ------------------------------------------------------------------
    //  Bulk
    // ------------------------------------------------------------------

    /**
     * Batched multi-get with partial cache hits: fresh entries are served from memory, only the
     * misses go to the backend in a single {@link Repository#findMany}. The order of the result
     * is not significant. This is the in-loop antidote to N+1 when you have the keys up front.
     */
    public CompletableFuture<List<V>> getAll(Collection<K> keys) {
        CachePolicy policy = options.policy();
        Map<K, V> hits = new LinkedHashMap<>();
        List<K> misses = new ArrayList<>();
        for (K key : keys) {
            CacheEntry<V> entry = store.get(key);
            if (entry != null && policy.isFresh(entry)) {
                hits.put(key, entry.getValue());
            } else {
                misses.add(key);
            }
        }
        if (misses.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>(hits.values()));
        }
        final boolean cacheable = policy.cacheable();
        return repository.findMany(misses).thenApply(loaded -> {
            List<V> result = new ArrayList<>(hits.values());
            for (V value : loaded) {
                if (cacheable) {
                    // installIfAbsent keeps identity stable (a concurrently cached instance wins).
                    result.add(store.installIfAbsent(keyOf.apply(value), new CacheEntry<>(value)).getValue());
                } else {
                    result.add(value);
                }
            }
            return result;
        });
    }

    /**
     * Loads the collection into the cache (the "in-memory mirror" of a small hot set). Entries are
     * installed only where absent, so a concurrently-saved value is never clobbered and held
     * instances stay stable; for a hard refresh, {@link #clearCache()} first.
     *
     * <p>With a bounded {@link CacheOptions#maxSize()}, a collection larger than the bound is
     * truncated by LRU eviction - only {@code maxSize} entries survive. It is a full mirror only
     * when the cache is unbounded or {@code maxSize} covers the collection.
     */
    public CompletableFuture<Void> preloadAll() {
        return repository.all().thenAccept(stream ->
                stream.forEach(value -> store.installIfAbsent(keyOf.apply(value), new CacheEntry<>(value))));
    }

    // ------------------------------------------------------------------
    //  Writes (write-through) + invalidation
    // ------------------------------------------------------------------

    /**
     * Persists {@code value} <b>and</b> updates the cache write-through - the cache-aware
     * counterpart of {@code repository().save(value)} (which persists without touching the cache
     * and would leave a stale entry behind). Prefer this from manager-mediated code.
     *
     * <p>On an {@link OptimisticLockException} the (stale) cached entry is evicted so the next read
     * reloads the current backend state; the original exception still propagates.
     */
    public CompletableFuture<Void> saveAndCache(V value) {
        final K key = keyOf.apply(value);
        return repository.save(value).whenComplete((ignored, ex) -> {
            if (ex == null) {
                store.put(key, new CacheEntry<>(value));
            } else if (isOptimisticLock(ex)) {
                store.remove(key);
            }
        });
    }

    /** Marks a cached entry stale (atomically, under the store lock): the next read reloads it. */
    public void invalidate(K key) {
        store.markStale(key);
    }

    /** Removes a cached entry outright. */
    public void evict(K key) {
        store.remove(key);
    }

    /** Marks every cached entry stale (e.g. after a bulk external change). */
    public void invalidateAll() {
        for (CacheEntry<V> entry : store.valuesSnapshot()) {
            entry.markStale();
        }
    }

    /** Empties the cache. */
    public void clearCache() {
        store.clear();
    }

    /**
     * Evicts every entry the default policy no longer considers fresh; returns the number removed.
     *
     * <p>TTL governs freshness, not memory: a cached entry is held by a <b>strong</b> reference, so
     * an expired-but-untouched entry is not GC-eligible until it is overwritten or evicted. Bound
     * memory with {@link CacheOptions#maxSize()} (evicted entries become collectable), and/or call
     * this periodically to proactively release stale entries.
     */
    public int purgeExpired() {
        return store.purge(entry -> !options.policy().isFresh(entry));
    }

    // ------------------------------------------------------------------
    //  Accessors
    // ------------------------------------------------------------------

    /** The underlying repository (uncached) - use for queries, deletes, or cache-bypassing reads. */
    public Repository<K, V> repository() {
        return repository;
    }

    public Class<V> type() {
        return type;
    }

    /** Current number of cached entries. */
    public int cachedSize() {
        return store.size();
    }

    /** Unwraps {@link CompletableFuture}/reflection wrappers to detect an optimistic-lock failure. */
    static boolean isOptimisticLock(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof OptimisticLockException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
