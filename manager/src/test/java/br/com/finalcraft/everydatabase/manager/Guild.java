package br.com.finalcraft.everydatabase.manager;

import java.util.UUID;

/** Test entity: a guild that itself holds a nested, per-ref-TTL reference to its battle data. */
public class Guild {

    public UUID id;
    public String name;

    @RefPolicy(ttlSeconds = 180)
    public Ref<UUID, GuildBattleData> battleData;

    public Guild() {
    }

    public Guild(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public Guild(UUID id, String name, Ref<UUID, GuildBattleData> battleData) {
        this.id = id;
        this.name = name;
        this.battleData = battleData;
    }
}
