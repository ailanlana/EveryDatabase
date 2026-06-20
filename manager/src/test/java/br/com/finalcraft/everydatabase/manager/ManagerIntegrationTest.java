package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.manager.testdata.Squad;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Multi-manager scenarios: a reference resolves across managers, and a list of refs batches. */
class ManagerIntegrationTest {

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

    private EntityDescriptor<UUID, Guild> guildDescriptor() {
        return EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }

    @Test
    void a_player_resolves_its_guild_ref_end_to_end_through_two_managers() {
        EntityDescriptor<UUID, Guild> guildDesc = guildDescriptor();
        EntityDescriptor<UUID, Player> playerDesc = EntityDescriptor.builder(UUID.class, Player.class)
                .collection("players")
                .keyExtractor(Player::getUuid)
                .codec(registry.codec(Player.class))
                .build();

        CachingManager<UUID, Guild> guilds = new CachingManager<>(guildDesc, storage, CachePolicy.always(), registry);
        CachingManager<UUID, Player> players = new CachingManager<>(playerDesc, storage, CachePolicy.always(), registry);

        UUID guildId = UUID.randomUUID();
        guilds.saveAndCache(new Guild(guildId, "Knights")).join();

        UUID playerId = UUID.randomUUID();
        players.saveAndCache(new Player(playerId, Ref.of(guildId, Guild.class, null))).join();

        // Force a fresh deserialization of the player (serialize -> store -> decode -> resolve).
        players.evict(playerId);
        Player loaded = players.resolve(playerId).join().orElseThrow(AssertionError::new);

        // The player's guild Ref resolves through the Guild manager - a different type, its own cache.
        Guild guild = loaded.getGuild().resolve().join().orElseThrow(AssertionError::new);
        assertEquals("Knights", guild.getName());
    }

    @Test
    void a_squad_of_refs_resolves_all_members_in_one_batch() {
        CachingManager<UUID, Guild> guilds = new CachingManager<>(guildDescriptor(), storage, CachePolicy.always(), registry);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        guilds.saveAndCache(new Guild(a, "A")).join();
        guilds.saveAndCache(new Guild(b, "B")).join();
        guilds.saveAndCache(new Guild(c, "C")).join();

        Squad squad = new Squad(UUID.randomUUID(), Arrays.asList(
                Ref.of(a, Guild.class, null), Ref.of(b, Guild.class, null), Ref.of(c, Guild.class, null)));

        // Batch-resolve the members' keys via the guild manager (the in-loop N+1 antidote).
        List<UUID> keys = squad.getMembers().stream().map(Ref::key).collect(Collectors.toList());
        List<Guild> members = guilds.getAll(keys).join();

        Set<String> names = members.stream().map(Guild::getName).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("A", "B", "C")), names);
    }
}
