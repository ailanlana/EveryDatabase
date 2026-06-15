package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a key into its entity, with caching. Implemented by {@link CachingManager} and
 * looked up by a {@link Ref} through the {@link Refs} registry (keyed by the entity type).
 *
 * <p>Two access modes:
 * <ul>
 *   <li>{@link #peek(Object)} - synchronous, cache-only, never does I/O. Empty when the value
 *       is absent or not currently cached/fresh. The hot-loop path.</li>
 *   <li>{@link #resolve(Object)} - asynchronous: serves a fresh cache hit, otherwise loads from
 *       the backend and caches the result.</li>
 * </ul>
 *
 * <p>The {@code (... , CachePolicy)} overloads let a single reference override the manager's
 * default freshness policy (see {@code @RefPolicy}). The override only changes the
 * <em>freshness verdict</em> against the shared {@link br.com.finalcraft.everydatabase.manager.cache.CacheEntry};
 * the cached value/instance stays shared, so a stricter reference may trigger a reload that
 * refreshes the entry for every consumer - never staler, possibly fresher.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public interface RefResolver<K, V> {

    /** Cache-only, synchronous read using the manager's default policy. */
    Optional<V> peek(K key);

    /** Cache-only, synchronous read using the given freshness policy. */
    Optional<V> peek(K key, CachePolicy policy);

    /** Cache-or-load read using the manager's default policy. */
    CompletableFuture<Optional<V>> resolve(K key);

    /** Cache-or-load read using the given freshness policy. */
    CompletableFuture<Optional<V>> resolve(K key, CachePolicy policy);
}
