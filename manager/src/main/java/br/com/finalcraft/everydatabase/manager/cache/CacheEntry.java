package br.com.finalcraft.everydatabase.manager.cache;

import java.time.Instant;

/**
 * A single cached value plus the metadata a {@link CachePolicy} needs to judge freshness.
 *
 * <p>One entry holds exactly one entity instance (the identity-map property of a
 * {@code CachingManager}). The same entry may be evaluated by different policies - the
 * manager's default and any per-reference override - against its single {@link #loadedAt}
 * timestamp.
 *
 * @param <S> the cached value type
 */
public final class CacheEntry<S> {

    private final S value;
    private final Instant loadedAt;
    private volatile boolean stale = false;

    public CacheEntry(S value) {
        this(value, Instant.now());
    }

    public CacheEntry(S value, Instant loadedAt) {
        this.value = value;
        this.loadedAt = loadedAt;
    }

    public S getValue() {
        return value;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    /** Manual invalidation: the next read that consults a policy reloads from the backend. */
    public void markStale() {
        this.stale = true;
    }

    public boolean isStale() {
        return stale;
    }
}
