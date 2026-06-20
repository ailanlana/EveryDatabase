package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.*;
import br.com.finalcraft.everydatabase.manager.testdata.DirtyFlagAccount;
import br.com.finalcraft.everydatabase.manager.testdata.DirtyableAccount;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Dirty tracking on the caching manager: dirty-wins, seedIfAbsent, flushDirty, batch failures, both opt-in forms. */
class DirtyTrackingCachingManagerTest {

    private RefRegistry registry;
    private InMemoryStorage storage;
    private EntityDescriptor<UUID, DirtyableAccount> descriptor;
    private EntityDescriptor<UUID, DirtyFlagAccount> dfDescriptor;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        storage = Storages.createInMemory();
        storage.init().join();
        descriptor = EntityDescriptor.builder(UUID.class, DirtyableAccount.class)
                .collection("accounts")
                .keyExtractor(DirtyableAccount::getId)
                .codec(registry.codec(DirtyableAccount.class))
                .build();
        dfDescriptor = EntityDescriptor.builder(UUID.class, DirtyFlagAccount.class)
                .collection("df_accounts")
                .keyExtractor(DirtyFlagAccount::getId)
                .codec(registry.codec(DirtyFlagAccount.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    private CachingManager<UUID, DirtyableAccount> manager() {
        return new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);
    }

    private CachingManager<UUID, DirtyFlagAccount> dfManager() {
        return new CachingManager<>(dfDescriptor, storage, CachePolicy.always(), registry);
    }

    // ------------------------------------------------------------------
    //  IDirtyable interface form
    // ------------------------------------------------------------------

    @Test
    void seedIfAbsent_caches_a_default_without_writing_to_the_backend() {
        CachingManager<UUID, DirtyableAccount> mgr = manager();
        UUID id = UUID.randomUUID();

        DirtyableAccount seeded = mgr.seedIfAbsent(id, new DirtyableAccount(id, 0));

        assertSame(seeded, mgr.peek(id).get(), "the seeded instance becomes the cached one");
        assertFalse(mgr.repository().find(id).join().isPresent(), "seed performs no I/O");
        assertSame(seeded, mgr.seedIfAbsent(id, new DirtyableAccount(id, 999)), "a second seed does not clobber");
    }

    @Test
    void a_dirty_cell_is_served_and_never_reloaded_until_flushed() {
        CachingManager<UUID, DirtyableAccount> mgr = manager();
        UUID id = UUID.randomUUID();
        mgr.repository().save(new DirtyableAccount(id, 0)).join();   // the backend has balance 0

        DirtyableAccount account = mgr.resolve(id).join().get();     // cached, clean
        account.deposit(50);                                         // local change -> dirty

        mgr.invalidate(id);   // would normally force a reload on the next read...
        // ...but dirty wins: the same instance with the unsaved change is still served.
        assertSame(account, mgr.peek(id).get());
        assertEquals(50, mgr.peek(id).get().getBalance());
    }

    @Test
    void flushDirty_persists_dirty_cells_and_clears_the_flag() {
        CachingManager<UUID, DirtyableAccount> mgr = manager();
        UUID id = UUID.randomUUID();
        DirtyableAccount account = mgr.seedIfAbsent(id, new DirtyableAccount(id, 0));
        account.deposit(120);
        assertTrue(account.isDirty());

        BatchSaveReport<UUID> report = mgr.flushDirty().join();

        assertFalse(report.hasFailures());
        assertFalse(account.isDirty(), "the flush clears the dirty flag");
        assertEquals(120, mgr.repository().find(id).join().get().getBalance(), "persisted to the backend");
        assertTrue(mgr.flushDirty().join().isEmpty(), "a second flush has nothing to do");
    }

    @Test
    void saveAllAndCache_writes_through_and_updates_the_cells() {
        CachingManager<UUID, DirtyableAccount> mgr = manager();
        DirtyableAccount a = new DirtyableAccount(UUID.randomUUID(), 10);
        DirtyableAccount b = new DirtyableAccount(UUID.randomUUID(), 20);

        BatchSaveReport<UUID> report = mgr.saveAllAndCache(Arrays.asList(a, b)).join();

        assertFalse(report.hasFailures());
        assertSame(a, mgr.peek(a.getId()).get(), "the saved instance is cached write-through");
        assertEquals(20, mgr.repository().find(b.getId()).join().get().getBalance());
    }

    @Test
    void cachedValues_snapshots_the_live_cached_set() {
        CachingManager<UUID, DirtyableAccount> mgr = manager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        mgr.seedIfAbsent(a, new DirtyableAccount(a, 1));
        mgr.seedIfAbsent(b, new DirtyableAccount(b, 2));
        assertEquals(2, mgr.cachedValues().size());

        mgr.deleteAndEvict(a).join();   // tombstone -> excluded from the live snapshot
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (DirtyableAccount account : mgr.cachedValues()) {
            ids.add(account.getId());
        }
        assertEquals(Collections.singletonList(b), ids);
    }

    @Test
    void flushDirty_evicts_on_conflict_and_re_marks_dirty_on_a_transient_error() {
        ScriptedRepository<UUID, DirtyableAccount> repo = new ScriptedRepository<>(DirtyableAccount::getId);
        CachingManager<UUID, DirtyableAccount> mgr =
                new CachingManager<>(repo, DirtyableAccount.class, DirtyableAccount::getId, CacheOptions.of(CachePolicy.always()));

        UUID okId = UUID.randomUUID();
        UUID errId = UUID.randomUUID();
        UUID conflictId = UUID.randomUUID();
        DirtyableAccount ok = mgr.seedIfAbsent(okId, new DirtyableAccount(okId, 1));
        DirtyableAccount err = mgr.seedIfAbsent(errId, new DirtyableAccount(errId, 2));
        DirtyableAccount conflict = mgr.seedIfAbsent(conflictId, new DirtyableAccount(conflictId, 3));
        ok.deposit(10);
        err.deposit(10);
        conflict.deposit(10);

        repo.failSave(errId, () -> new RuntimeException("disk full"));
        repo.failSave(conflictId, () -> new OptimisticLockException(DirtyableAccount.class, conflictId, 1L, 2L));

        BatchSaveReport<UUID> report = mgr.flushDirty().join();

        assertTrue(report.hasFailures());
        assertEquals(Collections.singletonList(conflictId), report.conflictedKeys());
        assertEquals(Collections.singletonList(errId), report.erroredKeys());

        assertFalse(ok.isDirty(), "the saved entity is clean");
        assertEquals(11, repo.find(okId).join().get().getBalance());

        assertTrue(err.isDirty(), "a transient error re-marks the entity dirty for a retry");
        assertTrue(mgr.peek(errId).isPresent(), "...and keeps its cell");

        assertFalse(mgr.peek(conflictId).isPresent(), "a conflict evicts the stale cell (reload on next read)");
    }

    // ------------------------------------------------------------------
    //  @DirtyFlag annotation form (no IDirtyable interface)
    // ------------------------------------------------------------------

    @Test
    void df_a_dirty_cell_is_served_and_never_reloaded_until_flushed() {
        CachingManager<UUID, DirtyFlagAccount> mgr = dfManager();
        UUID id = UUID.randomUUID();
        mgr.repository().save(new DirtyFlagAccount(id, 0)).join();   // the backend has balance 0

        DirtyFlagAccount account = mgr.resolve(id).join().get();     // cached, clean
        account.deposit(50);                                         // local change sets the @DirtyFlag field

        mgr.invalidate(id);   // would normally force a reload on the next read...
        // ...but dirty wins through the annotation accessor: the unsaved change is still served.
        assertSame(account, mgr.peek(id).get());
        assertEquals(50, mgr.peek(id).get().getBalance());
    }

    @Test
    void df_flushDirty_persists_dirty_cells_and_clears_the_field() {
        CachingManager<UUID, DirtyFlagAccount> mgr = dfManager();
        UUID id = UUID.randomUUID();
        DirtyFlagAccount account = mgr.seedIfAbsent(id, new DirtyFlagAccount(id, 0));
        account.deposit(120);

        BatchSaveReport<UUID> report = mgr.flushDirty().join();

        assertFalse(report.hasFailures());
        assertEquals(120, mgr.repository().find(id).join().get().getBalance(), "persisted to the backend");
        assertTrue(mgr.flushDirty().join().isEmpty(), "the flush cleared the @DirtyFlag field - nothing left to do");
    }

    // ------------------------------------------------------------------
    //  DirtyAccessor resolution (interface vs annotation vs neither vs both)
    // ------------------------------------------------------------------

    public static class BothFormsAccount implements IDirtyable {
        private UUID id = UUID.randomUUID();
        @DirtyFlag
        private boolean dirty;

        @Override public boolean isDirty() { return dirty; }
        @Override public void markClean()  { this.dirty = false; }
        @Override public void markDirty()  { this.dirty = true; }
    }

    @Test
    void dirtyAccessor_rejects_an_entity_with_both_idirtyable_and_dirtyflag() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DirtyAccessor.forType(BothFormsAccount.class));
        assertTrue(ex.getMessage().contains("use one, not both"),
                "message must explain the mutual exclusion, got: " + ex.getMessage());
    }

    public static class PlainAccount {
        private UUID id = UUID.randomUUID();
    }

    @Test
    void dirtyAccessor_is_null_for_a_plain_entity() {
        assertNull(DirtyAccessor.forType(PlainAccount.class),
                "a type that is neither IDirtyable nor @DirtyFlag-annotated is not dirty-trackable");
    }
}
