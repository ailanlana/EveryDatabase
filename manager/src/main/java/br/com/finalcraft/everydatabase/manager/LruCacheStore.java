package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Thread-safe entry store for a {@link CachingManager}.
 *
 * <p>When {@code maxSize > 0} it is a bounded LRU (access-order {@link LinkedHashMap} that
 * evicts the least-recently-used entry past the bound); when {@code 0} it is unbounded.
 *
 * <p>A single lock guards every operation. Access-order {@code LinkedHashMap.get} structurally
 * reorders, so reads must hold the same lock as writes - the cost is negligible at cache sizes,
 * and it keeps the store correct without a third-party dependency. Swap in Caffeine here if a
 * deployment needs lock-striped concurrency.
 *
 * <p>Note: in bounded mode <em>any</em> consultation via {@link #get} counts as an LRU access and
 * promotes the key to most-recently-used, even when the caller then judges the entry stale and
 * serves nothing - so a hot read loop over non-fresh keys can pin never-served entries.
 * Acceptable for the small hot sets this layer targets.
 *
 * <p>The compound operations ({@link #installIfAbsent}, {@link #replaceIfSame}, {@link #markStale})
 * exist so the manager keeps the identity map stable under concurrency: publishing a freshly
 * loaded value is one atomic step, not a racy get-then-put.
 *
 * @param <K> the key type
 * @param <V> the cached value type
 */
final class LruCacheStore<K, V> {

    private final Object lock = new Object();
    private final Map<K, CacheEntry<V>> map;

    LruCacheStore(int maxSize) {
        if (maxSize > 0) {
            final int bound = maxSize;
            this.map = new LinkedHashMap<K, CacheEntry<V>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                    return size() > bound;
                }
            };
        } else {
            this.map = new HashMap<>();
        }
    }

    CacheEntry<V> get(K key) {
        synchronized (lock) {
            return map.get(key);
        }
    }

    void put(K key, CacheEntry<V> entry) {
        synchronized (lock) {
            map.put(key, entry);
        }
    }

    void remove(K key) {
        synchronized (lock) {
            map.remove(key);
        }
    }

    /**
     * Atomically installs {@code candidate} only if no mapping exists, and returns the entry now
     * held (the existing one if present, else {@code candidate}). Lets concurrent cold misses
     * converge on a single canonical instance.
     */
    CacheEntry<V> installIfAbsent(K key, CacheEntry<V> candidate) {
        synchronized (lock) {
            CacheEntry<V> existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            map.put(key, candidate);
            return candidate;
        }
    }

    /**
     * Atomically replaces the mapping only if it is still {@code expected} (by identity), and
     * returns whether it did. Lets a stale reload overwrite exactly the entry it judged stale,
     * without clobbering a concurrent authoritative write.
     */
    boolean replaceIfSame(K key, CacheEntry<V> expected, CacheEntry<V> candidate) {
        synchronized (lock) {
            if (map.get(key) == expected) {
                map.put(key, candidate);
                return true;
            }
            return false;
        }
    }

    /** Atomically marks the current entry (if any) stale, under the lock (no detached-entry race). */
    void markStale(K key) {
        synchronized (lock) {
            CacheEntry<V> entry = map.get(key);
            if (entry != null) {
                entry.markStale();
            }
        }
    }

    /** Removes every entry matching {@code shouldEvict}; returns how many were removed. */
    int purge(Predicate<CacheEntry<V>> shouldEvict) {
        synchronized (lock) {
            int removed = 0;
            Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (shouldEvict.test(it.next().getValue())) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }

    void clear() {
        synchronized (lock) {
            map.clear();
        }
    }

    int size() {
        synchronized (lock) {
            return map.size();
        }
    }

    /** Snapshot of the current entries (for bulk invalidation). */
    List<CacheEntry<V>> valuesSnapshot() {
        synchronized (lock) {
            return new ArrayList<>(map.values());
        }
    }
}
