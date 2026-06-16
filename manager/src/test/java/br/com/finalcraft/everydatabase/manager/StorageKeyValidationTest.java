package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Clan;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import br.com.finalcraft.everydatabase.modules.sql.h2.H2SqlStorage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cross-backend key contract: an oversized key is rejected at save time with a clear error. */
class StorageKeyValidationTest {

    private static String repeat(int length) {
        return String.join("", Collections.nCopies(length, "x"));
    }

    private static EntityDescriptor<String, Clan> clanDescriptor() {
        return EntityDescriptor.builder(String.class, Clan.class)
                .collection("clans")
                .keyExtractor(c -> c.getTag())
                .codec(RefCodecs.json(Clan.class))
                .build();
    }

    private static void assertRejected(CompletableFuture<Void> save) {
        assertTrue(save.isCompletedExceptionally(), "oversized key must be rejected, not stored");
        CompletionException ex = assertThrows(CompletionException.class, save::join);
        assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "expected IllegalArgumentException, got " + ex.getCause());
        assertTrue(ex.getCause().getMessage().contains(String.valueOf(StorageKeys.MAX_KEY_LENGTH)),
                ex.getCause().getMessage());
    }

    @Test
    void sql_rejects_an_oversized_key_and_accepts_one_at_the_limit() {
        H2SqlStorage h2 = Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:keylimit_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", ""));
        h2.init().join();
        try {
            Repository<String, Clan> repo = h2.repository(clanDescriptor());

            assertRejected(repo.save(new Clan(repeat(StorageKeys.MAX_KEY_LENGTH + 1), "Too Long")));
            // A key exactly at the limit fits the storage_key column and is accepted.
            repo.save(new Clan(repeat(StorageKeys.MAX_KEY_LENGTH), "At The Limit")).join();
        } finally {
            h2.close().join();
        }
    }

    @Test
    void inmemory_enforces_the_same_limit_so_the_contract_is_uniform() {
        InMemoryStorage mem = Storages.createInMemory();
        mem.init().join();
        Repository<String, Clan> repo = mem.repository(clanDescriptor());

        assertRejected(repo.save(new Clan(repeat(StorageKeys.MAX_KEY_LENGTH + 1), "Too Long")));
    }
}
