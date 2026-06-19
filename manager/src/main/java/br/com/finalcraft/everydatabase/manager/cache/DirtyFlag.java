package br.com.finalcraft.everydatabase.manager.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the boolean dirty-flag field of an entity, opting it into the manager's <b>write-back</b>
 * flow without implementing {@link IDirtyable}.
 *
 * <p>Annotated entities get dirty tracking wired automatically: the {@code CachingManager} reads the
 * field (is-dirty), clears it (mark-clean) and re-sets it (mark-dirty) by reflection - no interface to
 * implement:
 *
 * <pre>{@code
 * public class Account {
 *     private UUID id;
 *     private long balance;
 *
 *     @DirtyFlag
 *     @JsonIgnore
 *     private transient boolean dirty;   // never persisted; set true by your own mutations
 *
 *     public void deposit(long n) {
 *         balance += n;
 *         this.dirty = true;             // the entity flags itself dirty
 *     }
 * }
 * }</pre>
 *
 * <p>Unlike {@link br.com.finalcraft.everydatabase.versioned.OptimisticLock @OptimisticLock} - whose
 * version field is fully managed by the backend and must never be touched by hand - the dirty flag is
 * the <b>entity's</b> responsibility to set {@code true} on its own mutations. The manager only ever
 * reads it and clears/re-sets it around a flush.
 *
 * <h3>Field requirements (validated when the manager resolves the entity type)</h3>
 * <ul>
 *   <li>Type must be {@code boolean} or {@code Boolean}; anything else throws
 *       {@link IllegalArgumentException}. A {@code Boolean} field that is still {@code null} reads as
 *       {@code false} (not dirty).</li>
 *   <li>Must not be {@code static} or {@code final} (the manager clears and re-sets it around a
 *       flush) - {@link IllegalArgumentException} otherwise.</li>
 *   <li>At most one field per entity (including inherited fields) may carry the annotation; two or
 *       more throw {@link IllegalStateException}.</li>
 *   <li>Mutually exclusive with implementing {@link IDirtyable}: combining the annotation with the
 *       interface throws {@link IllegalStateException}. Use one mechanism, not both.</li>
 * </ul>
 *
 * <h3>Semantics</h3>
 * Identical to implementing {@link IDirtyable}: while the flag is set the cell is always served and
 * never reloaded over (an unflushed change is never lost to a backend reload), and {@code flushDirty()}
 * clears the flag before persisting and re-sets it if the persist fails. The flag is in-memory
 * bookkeeping only - it must be {@code transient} / ignored by the codec so it is never serialized.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DirtyFlag {
}
