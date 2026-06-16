package br.com.finalcraft.everydatabase.manager.testdata;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Test entity: a guild's battle history - referenced (not embedded) by {@link Guild}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuildBattleData {

    private UUID id;
    private int totalBattles;
}
