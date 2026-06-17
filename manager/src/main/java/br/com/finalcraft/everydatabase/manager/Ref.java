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
 * (its manager) found in a {@link RefRegistry} - so caching, TTL and the identity map all live
 * in the manager, and the {@code Ref} stays a thin pointer:
 * <ul>
 *   <li>{@link #peek()} - synchronous, cache-only (no I/O). The hot-loop path.</li>
 *   <li>{@link #resolve()} - asynchronous: cache hit, or load-and-cache on miss.</li>
 * </ul>
 *
 * <h3>Binding</h3>
 * A {@code Ref} resolves only against the {@link RefRegistry} it is <b>bound</b> to. The binding
 * is set where the reference enters Java:
 * <ul>
 *   <li>deserialized from an entity - the ref-aware codec ({@link RefRegistry#codec(Class)}) binds
 *       its registry to every {@code Ref} it reads;</li>
 *   <li>built programmatically to resolve - use {@link RefRegistry#ref(Object, Class)} (or
 *       {@code Ref.of(key, type, registry)}).</li>
 * </ul>
 * Passing {@code null} as the registry ({@code Ref.of(key, type, null)}) makes an <b>unbound</b>
 * reference: perfectly fine to build and store (only the key is serialized), but calling
 * {@link #peek()}/{@link #resolve()} on it fails fast with a clear message rather than guessing a
 * resolver - there is no global registry to fall back to. The registry is always an explicit
 * argument, so that choice is visible at the call site.
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
    /** The registry this reference resolves against; {@code null} when unbound. */
    private final RefRegistry registry;
    /** Nullable: when null, the manager's default policy is used. */
    private final CachePolicy policyOverride;
    /** Memoized live cache cell (runtime only; never serialized). Enables lock-free reads. */
    private transient volatile CacheEntry<V> cell;
    /** Memoized effective policy: the override, or the manager default once the resolver is known. */
    private transient volatile CachePolicy effectivePolicy;

    private Ref(K key, Class<V> type, RefRegistry registry, CachePolicy policyOverride) {
        this.type = Objects.requireNonNull(type, "type");
        this.key = key;
        this.registry = registry;
        this.policyOverride = policyOverride;
    }

    // ------------------------------------------------------------------
    //  Factories
    // ------------------------------------------------------------------

    /**
     * A reference to {@code key} of type {@code type}, bound to {@code registry} (resolution goes
     * there), using the manager's default policy.
     *
     * <p>The registry is a required, explicit argument. For a bound reference prefer
     * {@link RefRegistry#ref(Object, Class)}. Pass {@code null} <b>deliberately</b> for an
     * <b>unbound</b> reference - one you only intend to store (it serializes as just the key); calling
     * {@link #peek()}/{@link #resolve()} on an unbound reference fails fast. Requiring the explicit
     * {@code null} keeps that choice, and its consequence, visible at the call site (there is no
     * convenience overload that hides it).
     */
    public static <K, V> Ref<K, V> of(K key, Class<V> type, RefRegistry registry) {
        return new Ref<>(key, type, registry, null);
    }

    /** An unbound empty reference (no target). {@link #peek()}/{@link #resolve()} yield empty. */
    public static <K, V> Ref<K, V> empty(Class<V> type) {
        return new Ref<>(null, type, null, null);
    }

    /** An empty reference bound to {@code registry}. */
    public static <K, V> Ref<K, V> empty(Class<V> type, RefRegistry registry) {
        return new Ref<>(null, type, registry, null);
    }

    // ------------------------------------------------------------------
    //  Derivations (Ref is immutable)
    // ------------------------------------------------------------------

    /** Returns a copy bound to the given registry. */
    public Ref<K, V> withRegistry(RefRegistry registry) {
        return new Ref<>(key, type, registry, policyOverride);
    }

    /** Returns a copy carrying the given per-reference policy override. */
    public Ref<K, V> withPolicy(CachePolicy policyOverride) {
        return new Ref<>(key, type, registry, policyOverride);
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

    /** The registry this reference resolves against, if bound. */
    public Optional<RefRegistry> registry() {
        return Optional.ofNullable(registry);
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
        if (registry == null) {
            // Async contract: surface a wiring error through the future, not a synchronous throw.
            return failedFuture(unbound());
        }
        RefResolver<K, V> resolver = registry.resolver(type);
        if (resolver == null) {
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
        if (registry == null) {
            throw unbound();
        }
        RefResolver<K, V> resolver = registry.resolver(type);
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

    private IllegalStateException unbound() {
        return new IllegalStateException("Ref to " + type.getName() + " is not bound to a RefRegistry"
                + " - read it through a RefRegistry.codec(...) codec, or build it with"
                + " RefRegistry.ref(key, type) / Ref.of(key, type, registry).");
    }

    private IllegalStateException noResolver() {
        return new IllegalStateException("No RefResolver registered for " + type.getName()
                + " in this RefRegistry - create its manager (CachingManager) before resolving this Ref.");
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
