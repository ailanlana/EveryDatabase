package br.com.finalcraft.everydatabase.manager;

/**
 * The outcome of persisting one entity within a batch write-back
 * ({@link CachingManager#saveAllAndCache} / {@link CachingManager#flushDirty}).
 *
 * @param <K> the key type
 */
public final class KeyOutcome<K> {

    /** Whether the entity was saved, hit an optimistic-lock conflict, or failed with another error. */
    public enum Status { SAVED, CONFLICT, ERROR }

    private final K key;
    private final Status status;
    private final Throwable error;

    public KeyOutcome(K key, Status status, Throwable error) {
        this.key = key;
        this.status = status;
        this.error = error;
    }

    public K key() {
        return key;
    }

    public Status status() {
        return status;
    }

    /**
     * The failure cause for {@link Status#CONFLICT} / {@link Status#ERROR};
     * {@code null} for {@link Status#SAVED}.
     */
    public Throwable error() {
        return error;
    }

    @Override
    public String toString() {
        return "KeyOutcome{" + key + " -> " + status + (error != null ? " (" + error + ")" : "") + "}";
    }
}
