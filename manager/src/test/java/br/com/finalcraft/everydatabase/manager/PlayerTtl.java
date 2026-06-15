package br.com.finalcraft.everydatabase.manager;

import java.util.UUID;

/** Test entity: a player whose guild reference declares a per-reference 180s TTL override. */
public class PlayerTtl {

    public UUID uuid;

    @RefPolicy(ttlSeconds = 180)
    public Ref<UUID, Guild> guild;

    public PlayerTtl() {
    }

    public PlayerTtl(UUID uuid, Ref<UUID, Guild> guild) {
        this.uuid = uuid;
        this.guild = guild;
    }
}
