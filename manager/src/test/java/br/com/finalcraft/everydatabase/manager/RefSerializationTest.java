package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Ref serializes as its key; the target type is recovered from the field on read. */
class RefSerializationTest {

    private final ObjectMapper mapper = RefCodecs.newMapper();

    @Test
    void ref_serializes_as_its_key_not_an_embedded_object() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID guildId = UUID.randomUUID();
        Player player = new Player(playerId, Ref.of(guildId, Guild.class));

        String json = mapper.writeValueAsString(player);

        // On disk the guild is just the UUID string, exactly like storing the raw key.
        assertTrue(json.contains("\"guild\":\"" + guildId + "\""),
                "expected guild to serialize as the key string, got: " + json);
        assertFalse(json.contains("\"name\""), "no embedded guild object expected: " + json);
    }

    @Test
    void ref_deserializes_with_key_and_type_recovered_from_the_field() throws Exception {
        UUID guildId = UUID.randomUUID();
        Player player = new Player(UUID.randomUUID(), Ref.of(guildId, Guild.class));

        Player back = mapper.readValue(mapper.writeValueAsString(player), Player.class);

        assertNotNull(back.guild);
        assertTrue(back.guild.isPresent());
        assertEquals(guildId, back.guild.key());
        assertEquals(Guild.class, back.guild.type());
        assertFalse(back.guild.policyOverride().isPresent(), "no override declared");
    }

    @Test
    void null_reference_round_trips_to_an_empty_ref() throws Exception {
        Player player = new Player(UUID.randomUUID(), null);

        Player back = mapper.readValue(mapper.writeValueAsString(player), Player.class);

        assertNotNull(back.guild, "a JSON null becomes an empty Ref, never a bare null");
        assertFalse(back.guild.isPresent());
    }

    @Test
    void refPolicy_noCache_annotation_is_baked_into_the_ref() throws Exception {
        UUID guildId = UUID.randomUUID();
        PlayerNoCache player = new PlayerNoCache(UUID.randomUUID(), Ref.of(guildId, Guild.class));

        PlayerNoCache back = mapper.readValue(mapper.writeValueAsString(player), PlayerNoCache.class);

        assertTrue(back.guild.policyOverride().isPresent());
        CachePolicy override = back.guild.policyOverride().get();
        // noCache semantics: nothing is ever fresh.
        assertFalse(override.isFresh(new CacheEntry<>(new Guild(guildId, "x"))));
    }

    @Test
    void refPolicy_ttl_annotation_is_baked_into_the_ref() throws Exception {
        UUID guildId = UUID.randomUUID();
        PlayerTtl player = new PlayerTtl(UUID.randomUUID(), Ref.of(guildId, Guild.class));

        PlayerTtl back = mapper.readValue(mapper.writeValueAsString(player), PlayerTtl.class);

        assertTrue(back.guild.policyOverride().isPresent());
        CachePolicy override = back.guild.policyOverride().get();
        Guild g = new Guild(guildId, "x");
        assertTrue(override.isFresh(new CacheEntry<>(g, Instant.now())), "within 180s is fresh");
        assertFalse(override.isFresh(new CacheEntry<>(g, Instant.now().minusSeconds(600))), "older than 180s is stale");
    }

    @Test
    void list_of_refs_round_trips_recovering_element_types() throws Exception {
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        Squad squad = new Squad(UUID.randomUUID(),
                Arrays.asList(Ref.of(g1, Guild.class), Ref.of(g2, Guild.class)));

        Squad back = mapper.readValue(mapper.writeValueAsString(squad), Squad.class);

        assertEquals(2, back.members.size());
        assertTrue(back.members.get(0).isPresent());
        assertEquals(g1, back.members.get(0).key());
        assertEquals(Guild.class, back.members.get(0).type());
        assertEquals(g2, back.members.get(1).key());
    }
}
