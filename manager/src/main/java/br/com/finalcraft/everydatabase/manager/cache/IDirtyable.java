package br.com.finalcraft.everydatabase.manager.cache;

/**
 * One of two opt-in ways to make an entity <b>dirty-trackable</b> for the manager's write-back flow:
 * mutate the cached instance in memory, flag it dirty, and flush it later in a batch instead of
 * writing through on every change. (The other way is the {@link DirtyFlag @DirtyFlag} field
 * annotation - pick one, never both.)
 *
 * <p>A {@code CachingManager} treats a dirty-trackable cached value specially:
 * <ul>
 *   <li><b>Dirty wins.</b> While {@link #isDirty()} is {@code true} the cell is always served and
 *       never reloaded by a freshness policy, so an unflushed local change is never silently
 *       overwritten by a backend reload - a TTL policy stays safe even on a mutable entity.</li>
 *   <li><b>Batch flush.</b> {@code flushDirty()} collects the dirty cells, calls {@link #markClean()}
 *       on each before persisting it, and on a failed persist calls {@link #markDirty()} so the
 *       entity is retried on the next flush.</li>
 * </ul>
 *
 * <p>A value that is neither an {@code IDirtyable} nor carries a {@code @DirtyFlag} field keeps the
 * plain read-through / write-through behavior. The three methods front a single boolean "dirty" flag
 * on the entity; that flag must not be persisted (mark it transient / ignored by the codec).
 */
public interface IDirtyable {

    /** Whether the entity has in-memory changes not yet persisted. */
    boolean isDirty();

    /** Clears the dirty flag. Called by the manager right before a flush persists the entity. */
    void markClean();

    /** (Re)sets the dirty flag. Called by the manager when a flush fails, so the entity is retried. */
    void markDirty();
}
