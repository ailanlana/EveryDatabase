package br.com.finalcraft.everydatabase.manager;

import java.util.UUID;

/** Test entity: a player whose guild reference declares a per-reference no-cache override. */
public class PlayerNoCache {

    public UUID uuid;

    @RefPolicy(noCache = true)
    public Ref<UUID, Guild> guild;

    public PlayerNoCache() {
    }

    public PlayerNoCache(UUID uuid, Ref<UUID, Guild> guild) {
        this.uuid = uuid;
        this.guild = guild;
    }
}
