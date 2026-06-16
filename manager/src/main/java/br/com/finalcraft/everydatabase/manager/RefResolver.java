package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a key into its entity, with caching. Implemented by {@link CachingManager} and looked up
 * by a {@link Ref} through the {@link Refs} registry (keyed by the entity type).
 *
 * <p>The contract is expressed at the <b>cell</b> level: {@link #peekCell}/{@link #resolveCell}
 * return the live {@link CacheEntry} for a key. A {@code Ref} memoizes that cell so subsequent
 * reads are lock-free, and since the cell updates in place, the memoized holder always sees the
 * latest value. The value-level {@link #peek}/{@link #resolve} are conveniences derived from the
 * cell primitives.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public interface RefResolver<K, V> {

    /** The manager's default freshness policy (used when a reference declares no override). */
    CachePolicy defaultPolicy();

    /**
     * The live cache cell for {@code key} if it is currently cached and fresh under {@code policy},
     * else {@code null}. Synchronous, cache-only - never does I/O.
     */
    CacheEntry<V> peekCell(K key, CachePolicy policy);

    /**
     * The live cache cell for {@code key}, loading and caching on a miss/stale. Completes with
     * {@code null} when the entity does not exist.
     */
    CompletableFuture<CacheEntry<V>> resolveCell(K key, CachePolicy policy);

    // ------------------------------------------------------------------
    //  Value-level conveniences, derived from the cell primitives
    // ------------------------------------------------------------------

    /** Cache-only read with the manager's default policy. */
    default Optional<V> peek(K key) {
        return peek(key, defaultPolicy());
    }

    /** Cache-only read with the given freshness policy. */
    default Optional<V> peek(K key, CachePolicy policy) {
        CacheEntry<V> cell = peekCell(key, policy);
        return cell == null ? Optional.empty() : Optional.of(cell.getValue());
    }

    /** Cache-or-load read with the manager's default policy. */
    default CompletableFuture<Optional<V>> resolve(K key) {
        return resolve(key, defaultPolicy());
    }

    /** Cache-or-load read with the given freshness policy. */
    default CompletableFuture<Optional<V>> resolve(K key, CachePolicy policy) {
        return resolveCell(key, policy)
                .thenApply(cell -> cell == null ? Optional.<V>empty() : Optional.of(cell.getValue()));
    }
}
