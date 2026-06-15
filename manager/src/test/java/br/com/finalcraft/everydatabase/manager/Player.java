package br.com.finalcraft.everydatabase.manager;

import java.util.UUID;

/** Test entity: a player whose guild reference uses the manager's default policy. */
public class Player {

    public UUID uuid;
    public Ref<UUID, Guild> guild;

    public Player() {
    }

    public Player(UUID uuid, Ref<UUID, Guild> guild) {
        this.uuid = uuid;
        this.guild = guild;
    }
}
