package br.com.finalcraft.everydatabase.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of a batch write-back ({@link CachingManager#saveAllAndCache} /
 * {@link CachingManager#flushDirty}): the per-entity <b>failures</b> only. A fully successful flush
 * returns an {@linkplain #isEmpty() empty} report; saved entities are not listed. Inspect
 * {@link #conflictedKeys()} (optimistic-lock conflicts, whose cells were evicted) and
 * {@link #erroredKeys()} (other failures, left cached for a retry) for the ones needing attention.
 *
 * @param <K> the key type
 */
public final class BatchSaveReport<K> {

    private static final BatchSaveReport<?> EMPTY = new BatchSaveReport<>(Collections.emptyList());

    private final List<KeyOutcome<K>> failures;

    private BatchSaveReport(List<KeyOutcome<K>> failures) {
        this.failures = Collections.unmodifiableList(failures);
    }

    @SuppressWarnings("unchecked")
    public static <K> BatchSaveReport<K> empty() {
        return (BatchSaveReport<K>) EMPTY;
    }

    /** Builds a report from per-entity outcomes, keeping only the failures (non-{@code SAVED}). */
    static <K> BatchSaveReport<K> of(List<KeyOutcome<K>> outcomes) {
        List<KeyOutcome<K>> failures = new ArrayList<>();
        for (KeyOutcome<K> outcome : outcomes) {
            if (outcome.status() != KeyOutcome.Status.SAVED) {
                failures.add(outcome);
            }
        }
        return failures.isEmpty() ? BatchSaveReport.<K>empty() : new BatchSaveReport<>(failures);
    }

    /** The failures (optimistic-lock conflicts and other errors); empty on a fully successful flush. */
    public List<KeyOutcome<K>> failures() {
        return failures;
    }

    public boolean isEmpty() {
        return failures.isEmpty();
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    /** Keys that hit an optimistic-lock {@link KeyOutcome.Status#CONFLICT} (their cells were evicted). */
    public List<K> conflictedKeys() {
        return keysWithStatus(KeyOutcome.Status.CONFLICT);
    }

    /** Keys that failed with a non-conflict {@link KeyOutcome.Status#ERROR} (their cells were kept). */
    public List<K> erroredKeys() {
        return keysWithStatus(KeyOutcome.Status.ERROR);
    }

    private List<K> keysWithStatus(KeyOutcome.Status status) {
        List<K> keys = new ArrayList<>();
        for (KeyOutcome<K> outcome : failures) {
            if (outcome.status() == status) {
                keys.add(outcome.key());
            }
        }
        return keys;
    }

    @Override
    public String toString() {
        return "BatchSaveReport{failures=" + failures + "}";
    }
}
