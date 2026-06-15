package br.com.finalcraft.everydatabase.manager;

import java.util.UUID;

/** Test entity: a guild's battle history - referenced (not embedded) by {@link Guild}. */
public class GuildBattleData {

    public UUID id;
    public int totalBattles;

    public GuildBattleData() {
    }

    public GuildBattleData(UUID id, int totalBattles) {
        this.id = id;
        this.totalBattles = totalBattles;
    }
}
