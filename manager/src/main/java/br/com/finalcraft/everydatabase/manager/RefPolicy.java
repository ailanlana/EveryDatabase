package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

/**
 * Per-reference freshness override, declared right on the {@link Ref} field. Read by the
 * Jackson deserializer at deserialization time and baked into the {@link Ref}, so the override
 * lives where the relationship is declared - no extra wiring.
 *
 * <p>The override only changes the freshness verdict for this reference; the cached value is
 * still shared with every other reference to the same entity (one instance, one timestamp).
 *
 * <pre>{@code
 * public class Guild {
 *     private UUID id;
 *
 *     // battle history: tolerate up to 3 minutes of staleness, regardless of the manager default
 *     @RefPolicy(ttlSeconds = 180)
 *     private Ref<UUID, GuildBattleData> battleData;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RefPolicy {

    /**
     * TTL for this reference:
     * <ul>
     *   <li>{@code > 0} - {@link CachePolicy#ttl(Duration)} of that many seconds</li>
     *   <li>{@code 0}   - {@link CachePolicy#always()} (cache until invalidated)</li>
     *   <li>{@code < 0} - inherit the manager's default policy (the default)</li>
     * </ul>
     * Ignored when {@link #noCache()} is {@code true}.
     */
    long ttlSeconds() default -1;

    /** When {@code true}, bypass the cache for this reference entirely. Overrides {@link #ttlSeconds()}. */
    boolean noCache() default false;
}
