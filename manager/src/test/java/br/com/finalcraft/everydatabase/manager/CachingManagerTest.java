package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cache-backed manager: resolve/peek, write-through, batching, invalidation, per-ref policy. */
class CachingManagerTest {

    private InMemoryStorage storage;
    private EntityDescriptor<UUID, Guild> guildDescriptor;

    @BeforeEach
    void setUp() {
        Refs.clear();
        storage = Storages.createInMemory();
        storage.init().join();
        guildDescriptor = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(g -> g.id)
                .codec(RefCodecs.json(Guild.class))   // Guild carries a Ref field (battleData)
                .build();
    }

    @AfterEach
    void tearDown() {
        Refs.clear();
    }

    private CachingManager<UUID, Guild> manager(CacheOptions options) {
        return new CachingManager<>(guildDescriptor, storage, options);
    }

    private CachingManager<UUID, Guild> manager(CachePolicy policy) {
        return new CachingManager<>(guildDescriptor, storage, policy);
    }

    /** Seeds the backend directly (bypassing the manager's cache). */
    private UUID seedGuild(CachingManager<UUID, Guild> mgr, String name) {
        UUID id = UUID.randomUUID();
        mgr.repository().save(new Guild(id, name)).join();
        return id;
    }

    @Test
    void resolve_loads_from_backend_then_serves_from_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Knights");

        assertFalse(mgr.peek(id).isPresent(), "not cached before first resolve");

        Optional<Guild> resolved = mgr.resolve(id).join();
        assertTrue(resolved.isPresent());
        assertEquals("Knights", resolved.get().name);
        assertEquals(1, mgr.cachedSize());

        // The identity map: peek returns the same cached instance the resolve produced.
        assertSame(resolved.get(), mgr.peek(id).get());
    }

    @Test
    void save_is_write_through() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        Guild guild = new Guild(UUID.randomUUID(), "Wolves");

        mgr.saveAndCache(guild).join();

        // Cached without any read, and the cached instance is exactly what we saved.
        assertSame(guild, mgr.peek(guild.id).get());
        // ...and it is actually in the backend.
        assertTrue(mgr.repository().find(guild.id).join().isPresent());
    }

    @Test
    void getAll_serves_hits_from_cache_and_batches_the_misses() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID a = seedGuild(mgr, "A");
        UUID b = seedGuild(mgr, "B");
        UUID c = seedGuild(mgr, "C");
        mgr.resolve(a).join(); // pre-cache one

        List<Guild> all = mgr.getAll(Arrays.asList(a, b, c)).join();

        Set<UUID> ids = all.stream().map(g -> g.id).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(a, b, c)), ids);
        assertEquals(3, mgr.cachedSize());
    }

    @Test
    void preloadAll_mirrors_the_whole_collection() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID a = seedGuild(mgr, "A");
        UUID b = seedGuild(mgr, "B");

        mgr.preloadAll().join();

        assertEquals(2, mgr.cachedSize());
        assertTrue(mgr.peek(a).isPresent());
        assertTrue(mgr.peek(b).isPresent());
    }

    @Test
    void invalidate_forces_a_reload_on_next_read() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Old");
        mgr.resolve(id).join();

        mgr.invalidate(id);
        assertFalse(mgr.peek(id).isPresent(), "stale entry is not served");

        // A fresh resolve reloads from the backend.
        assertTrue(mgr.resolve(id).join().isPresent());
        assertTrue(mgr.peek(id).isPresent());
    }

    @Test
    void evict_removes_the_entry() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Gone");
        mgr.resolve(id).join();
        assertEquals(1, mgr.cachedSize());

        mgr.evict(id);
        assertEquals(0, mgr.cachedSize());
        assertFalse(mgr.peek(id).isPresent());
    }

    @Test
    void purgeExpired_drops_entries_the_policy_no_longer_considers_fresh() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Stale");
        mgr.resolve(id).join();
        assertEquals(1, mgr.cachedSize());

        mgr.invalidate(id);                 // marks stale -> no longer fresh
        int purged = mgr.purgeExpired();

        assertEquals(1, purged);
        assertEquals(0, mgr.cachedSize(), "stale entries are released so the GC can reclaim them");
    }

    @Test
    void per_reference_policy_override_can_bypass_the_shared_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Hot");
        mgr.resolve(id).join(); // now cached

        // Default policy serves it...
        assertTrue(mgr.peek(id).isPresent());
        // ...but a no-cache override never serves from cache (forces a fresh load).
        assertFalse(mgr.peek(id, CachePolicy.noCache()).isPresent());
    }

    @Test
    void maxSize_bounds_the_cache() {
        CachingManager<UUID, Guild> mgr = manager(
                CacheOptions.builder().policy(CachePolicy.always()).maxSize(2).build());
        UUID a = seedGuild(mgr, "A");
        UUID b = seedGuild(mgr, "B");
        UUID c = seedGuild(mgr, "C");

        mgr.resolve(a).join();
        mgr.resolve(b).join();
        mgr.resolve(c).join(); // overflows the bound of 2

        assertEquals(2, mgr.cachedSize());
    }

    @Test
    void isOptimisticLock_unwraps_nested_causes() {
        OptimisticLockException ole = new OptimisticLockException(Guild.class, UUID.randomUUID(), 1L, 2L);
        assertTrue(CachingManager.isOptimisticLock(ole));
        assertTrue(CachingManager.isOptimisticLock(new CompletionException(ole)));
        assertFalse(CachingManager.isOptimisticLock(new RuntimeException("unrelated")));
    }

    @Test
    void noCache_override_does_not_populate_the_shared_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Hot");

        Optional<Guild> resolved = mgr.resolve(id, CachePolicy.noCache()).join();

        assertTrue(resolved.isPresent());
        assertEquals(0, mgr.cachedSize(), "a noCache resolve is a true bypass - it must not populate the cache");
        assertFalse(mgr.peek(id).isPresent());
    }

    @Test
    void concurrent_cold_misses_converge_on_one_instance() throws Exception {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "One");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Guild>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mgr.resolve(id).join().get();
                }));
            }
            start.countDown();

            Set<Guild> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<Guild> f : futures) {
                distinct.add(f.get());
            }
            assertEquals(1, distinct.size(), "all concurrent resolves must converge on one instance");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ref_resolve_without_a_registered_manager_returns_a_failed_future() {
        // No manager is registered for String in this test's cleared registry.
        Ref<UUID, String> ref = Ref.of(UUID.randomUUID(), String.class);

        CompletableFuture<Optional<String>> future = ref.resolve();

        assertTrue(future.isCompletedExceptionally(), "wiring errors must surface via the future, not a sync throw");
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }
}
