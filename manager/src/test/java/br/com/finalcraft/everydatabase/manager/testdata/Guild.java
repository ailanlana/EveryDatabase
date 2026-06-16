package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Test entity: a guild that itself holds a nested, per-ref-TTL reference to its battle data. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Guild {

    private UUID id;
    private String name;

    @RefPolicy(ttlSeconds = 180)
    private Ref<UUID, GuildBattleData> battleData;

    /** Convenience for the common case without a battle-data reference. */
    public Guild(UUID id, String name) {
        this(id, name, null);
    }
}
