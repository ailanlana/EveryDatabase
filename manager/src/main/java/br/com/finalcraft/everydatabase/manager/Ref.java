package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A typed, lazily-resolved reference to an entity stored in another collection.
 *
 * <p>On disk a {@code Ref} serializes as <b>just its key</b> (via the Jackson
 * {@code RefModule}) - identical to storing the raw key, no embedded entity. In Java you hold
 * a typed handle: the target type {@code V} is recovered from the field's generic declaration
 * at deserialization time, so the JSON stays clean.
 *
 * <p>Resolution is always explicit and goes through the entity type's {@link RefResolver}
 * (its manager) found in the {@link Refs} registry - so caching, TTL and the identity map all
 * live in the manager, and the {@code Ref} stays a thin pointer:
 * <ul>
 *   <li>{@link #peek()} - synchronous, cache-only (no I/O). The hot-loop path.</li>
 *   <li>{@link #resolve()} - asynchronous: cache hit, or load-and-cache on miss.</li>
 * </ul>
 *
 * <p>An optional per-reference {@link CachePolicy} override (typically declared with
 * {@code @RefPolicy}) changes only this reference's freshness verdict; the cached value stays
 * shared.
 *
 * @param <K> the key type
 * @param <V> the referenced entity type
 */
public final class Ref<K, V> {

    private final K key;
    private final Class<V> type;
    /** Nullable: when null, the manager's default policy is used. */
    private final CachePolicy policyOverride;
    /** Memoized live cache cell (runtime only; never serialized). Enables lock-free reads. */
    private transient volatile CacheEntry<V> cell;
    /** Memoized effective policy: the override, or the manager default once the resolver is known. */
    private transient volatile CachePolicy effectivePolicy;

    private Ref(K key, Class<V> type, CachePolicy policyOverride) {
        this.type = Objects.requireNonNull(type, "type");
        this.key = key;
        this.policyOverride = policyOverride;
    }

    // ------------------------------------------------------------------
    //  Factories
    // ------------------------------------------------------------------

    /** A reference to {@code key} of type {@code type}, using the manager's default policy. */
    public static <K, V> Ref<K, V> of(K key, Class<V> type) {
        return new Ref<>(key, type, null);
    }

    /** A reference with a per-reference freshness override. */
    public static <K, V> Ref<K, V> of(K key, Class<V> type, CachePolicy policyOverride) {
        return new Ref<>(key, type, policyOverride);
    }

    /** An empty reference (no target). {@link #peek()}/{@link #resolve()} yield empty. */
    public static <K, V> Ref<K, V> empty(Class<V> type) {
        return new Ref<>(null, type, null);
    }

    // ------------------------------------------------------------------
    //  Derivations (Ref is immutable)
    // ------------------------------------------------------------------

    /** Returns a copy carrying the given per-reference policy override. */
    public Ref<K, V> withPolicy(CachePolicy policyOverride) {
        return new Ref<>(key, type, policyOverride);
    }

    /** Convenience: {@link #withPolicy(CachePolicy)} with {@link CachePolicy#ttl(Duration)}. */
    public Ref<K, V> withTtl(Duration ttl) {
        return withPolicy(CachePolicy.ttl(ttl));
    }

    // ------------------------------------------------------------------
    //  Accessors
    // ------------------------------------------------------------------

    public K key() {
        return key;
    }

    public Class<V> type() {
        return type;
    }

    public Optional<CachePolicy> policyOverride() {
        return Optional.ofNullable(policyOverride);
    }

    /** {@code true} when this reference points at something (its key is non-null). */
    public boolean isPresent() {
        return key != null;
    }

    // ------------------------------------------------------------------
    //  Resolution
    // ------------------------------------------------------------------

    /**
     * Synchronous, cache-only resolution. Empty when this reference is empty, or when the
     * value is not currently cached/fresh under the effective policy. Never does I/O.
     */
    public Optional<V> peek() {
        if (key == null) {
            return Optional.empty();
        }
        CacheEntry<V> memo = cell;
        CachePolicy eff = effectivePolicy;
        if (memo != null && !memo.isEvicted() && !memo.isDeleted() && eff != null && eff.isFresh(memo)) {
            return Optional.of(memo.getValue());           // lock-free fast path
        }
        RefResolver<K, V> resolver = resolver();
        CacheEntry<V> got = resolver.peekCell(key, effectivePolicy(resolver));
        this.cell = got;
        return got == null ? Optional.empty() : Optional.of(got.getValue());
    }

    /**
     * Asynchronous resolution: serves a fresh cache hit, otherwise loads from the backend and
     * caches the result. Empty when this reference is empty or the target no longer exists.
     */
    public CompletableFuture<Optional<V>> resolve() {
        if (key == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CacheEntry<V> memo = cell;
        CachePolicy eff = effectivePolicy;
        if (memo != null && !memo.isEvicted() && !memo.isDeleted() && eff != null && eff.isFresh(memo)) {
            return CompletableFuture.completedFuture(Optional.of(memo.getValue())); // lock-free fast path
        }
        RefResolver<K, V> resolver = Refs.resolver(type);
        if (resolver == null) {
            // Async contract: surface a wiring error through the future, not a synchronous throw.
            return failedFuture(noResolver());
        }
        return resolver.resolveCell(key, effectivePolicy(resolver)).thenApply(got -> {
            this.cell = got;
            return got == null ? Optional.<V>empty() : Optional.of(got.getValue());
        });
    }

    /**
     * Blocking convenience: the resolved value, or {@code null} when absent. Fast on a cache
     * hit; <b>may block on a cold miss</b> - avoid for cold data on a latency-sensitive thread,
     * prefer {@link #resolve()} there.
     */
    public V join() {
        return resolve().join().orElse(null);
    }

    private RefResolver<K, V> resolver() {
        RefResolver<K, V> resolver = Refs.resolver(type);
        if (resolver == null) {
            throw noResolver();
        }
        return resolver;
    }

    /** The effective freshness policy for this reference, memoized once the resolver is known. */
    private CachePolicy effectivePolicy(RefResolver<K, V> resolver) {
        CachePolicy eff = effectivePolicy;
        if (eff == null) {
            eff = policyOverride != null ? policyOverride : resolver.defaultPolicy();
            effectivePolicy = eff;
        }
        return eff;
    }

    private IllegalStateException noResolver() {
        return new IllegalStateException("No RefResolver registered for " + type.getName()
                + " - create its manager (CachingManager) before resolving this Ref.");
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable ex) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    // ------------------------------------------------------------------
    //  Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Ref)) {
            return false;
        }
        Ref<?, ?> that = (Ref<?, ?>) o;
        return Objects.equals(key, that.key)
                && type.equals(that.type)
                && Objects.equals(policyOverride, that.policyOverride);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type, policyOverride);
    }

    @Override
    public String toString() {
        return "Ref{" + type.getSimpleName() + ":" + key
                + (policyOverride != null ? " " + policyOverride : "") + "}";
    }
}
