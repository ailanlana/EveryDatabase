package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Test entity: a player whose guild reference declares a per-reference 180s TTL override. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerTtl {

    private UUID uuid;

    @RefPolicy(ttlSeconds = 180)
    private Ref<UUID, Guild> guild;
}
