package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.manager.testdata.GuildBattleData;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nested references: each entity type resolves through its own manager and its own policy. */
class NestedRefTest {

    private RefRegistry registry;
    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        storage = Storages.createInMemory();
        storage.init().join();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    @Test
    void guild_resolves_its_nested_battle_data_through_a_separate_manager() {
        EntityDescriptor<UUID, Guild> guildDesc = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))                  // Guild has a Ref field
                .build();

        EntityDescriptor<UUID, GuildBattleData> battleDesc = EntityDescriptor.builder(UUID.class, GuildBattleData.class)
                .collection("guild_battle")
                .keyExtractor(GuildBattleData::getId)
                .codec(new JacksonJsonCodec<>(GuildBattleData.class))
                .build();

        // Two managers, two policies: guilds resident, battle data on a 3-minute TTL.
        CachingManager<UUID, Guild> guilds = new CachingManager<>(guildDesc, storage, CachePolicy.always(), registry);
        CachingManager<UUID, GuildBattleData> battle =
                new CachingManager<>(battleDesc, storage, CachePolicy.ttl(Duration.ofMinutes(3)), registry);

        UUID gid = UUID.randomUUID();
        UUID bid = UUID.randomUUID();
        battle.saveAndCache(new GuildBattleData(bid, 42)).join();
        guilds.saveAndCache(new Guild(gid, "Knights", Ref.of(bid, GuildBattleData.class, null))).join();

        // Force a load from the backend: the write-through cache holds the original instance whose
        // ref was built programmatically (no override), whereas the @RefPolicy(ttlSeconds = 180)
        // on Guild.battleData is recovered only on deserialization.
        guilds.evict(gid);

        // Load the guild, then resolve the nested ref through GuildBattleData's own manager.
        Guild loaded = guilds.resolve(gid).join().orElseThrow(AssertionError::new);
        assertTrue(loaded.getBattleData().isPresent());
        assertTrue(loaded.getBattleData().policyOverride().isPresent());

        GuildBattleData bd = loaded.getBattleData().resolve().join().orElseThrow(AssertionError::new);
        assertEquals(42, bd.getTotalBattles());
    }
}
