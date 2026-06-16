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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
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
 * <h3>Cells &amp; handles</h3>
 * Each key maps to a stable {@link CacheEntry} <b>cell</b>; writes and reloads update that cell
 * <b>in place</b> (swap the value, guarded by a monotonic stamp). A {@link Ref} memoizes the cell,
 * so once resolved its reads are lock-free and it always observes the latest value. On eviction the
 * cell is flagged so a holder re-resolves on next access.
 *
 * <h3>Invalidation</h3>
 * {@link #saveAndCache(Object)} is write-through (the cell is updated in place with the saved value)
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
    /** Monotonic source for publication stamps (orders writes/reloads so none regress a newer one). */
    private final AtomicLong stampGen = new AtomicLong();

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

    public Repository<K, V> getRepository() {
        return repository;
    }

    // ------------------------------------------------------------------
    //  RefResolver
    // ------------------------------------------------------------------

    @Override
    public CachePolicy defaultPolicy() {
        return options.policy();
    }

    @Override
    public CacheEntry<V> peekCell(K key, CachePolicy policy) {
        CacheEntry<V> cell = store.get(key);
        return serveable(cell, policy) ? cell : null;
    }

    @Override
    public CompletableFuture<CacheEntry<V>> resolveCell(K key, CachePolicy policy) {
        CacheEntry<V> existing = store.get(key);
        if (serveable(existing, policy)) {
            return CompletableFuture.completedFuture(existing);
        }
        if (!policy.cacheable()) {
            // True bypass: load without caching; hand back a throwaway, unshared cell.
            return repository.find(key)
                    .thenApply(opt -> opt.isPresent() ? new CacheEntry<>(opt.get()) : null);
        }
        // A cold miss has no cell yet; anything else (stale, or a tombstone) is a reload.
        final boolean coldMiss = (existing == null);
        final long stamp = stampGen.incrementAndGet();
        return repository.find(key).thenApply(opt -> {
            if (!opt.isPresent()) {
                return null;
            }
            V value = opt.get();
            CacheEntry<V> cell = coldMiss
                    ? store.installColdMiss(key, value, stamp)   // keep-first, but loses to a newer delete
                    : updateInPlace(key, value, stamp);          // stamp-guarded (resurrects an older tombstone)
            return cell.isDeleted() ? null : cell;               // a newer delete won -> treat as absent
        });
    }

    /** A cell is serveable when it is present, live (not a tombstone), not evicted, and fresh. */
    private static boolean serveable(CacheEntry<?> cell, CachePolicy policy) {
        return cell != null && !cell.isEvicted() && !cell.isDeleted() && policy.isFresh(cell);
    }

    /**
     * Authoritative / stale-reload publish: update the key's cell <b>in place</b> (swap value),
     * guarded by a monotonic stamp so a slower writer never regresses a newer one. Creates the cell
     * if absent. Returns the live cell - the same instance any memoized holder already points at.
     */
    private CacheEntry<V> updateInPlace(K key, V value, long stamp) {
        CacheEntry<V> cell = store.installIfAbsent(key, new CacheEntry<>(value));
        cell.publish(value, stamp);
        return cell;
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
            if (serveable(entry, policy)) {
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
                    // Update the cell in place (refreshes a stale entry; creates one if absent).
                    result.add(updateInPlace(keyOf.apply(value), value, stampGen.incrementAndGet()).getValue());
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
        final long stamp = stampGen.incrementAndGet();
        return repository.save(value).whenComplete((ignored, ex) -> {
            if (ex == null) {
                if (options.policy().cacheable()) {
                    updateInPlace(key, value, stamp);   // write-through: update the cell in place
                }
            } else if (isOptimisticLock(ex)) {
                store.remove(key);
            }
        });
    }

    /**
     * Deletes the entity from the backend <b>and</b> evicts it from the cache - the delete
     * counterpart of {@link #saveAndCache}. The eviction always runs (whether or not the key was
     * cached), so a deleted entity is never left dangling in the cache. Prefer this over
     * {@code repository().delete(key)}, which removes from the backend but leaves a stale cache entry.
     *
     * <p>On success the cache slot becomes a stamp-ordered <b>tombstone</b> rather than just being
     * removed: this blocks a concurrent in-flight reload (one that read the entity before the delete)
     * from re-installing the now-deleted entity. The tombstone is cleared by a later re-save (which
     * resurrects it), by {@link #purgeExpired()}, or by LRU eviction. A failed delete just
     * invalidates (the entity may still exist, so the next read reloads it).
     *
     * @return whether the entity existed in the backend
     */
    public CompletableFuture<Boolean> deleteAndEvict(K key) {
        final long stamp = stampGen.incrementAndGet();
        return repository.delete(key).whenComplete((existed, ex) -> {
            if (ex == null) {
                store.tombstone(key, stamp);
            } else {
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
        return store.purge(entry -> entry.isDeleted() || !options.policy().isFresh(entry));
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

    /** Current number of <b>live</b> cached entries (tombstones from deletes are not counted). */
    public int cachedSize() {
        return store.liveCount();
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
