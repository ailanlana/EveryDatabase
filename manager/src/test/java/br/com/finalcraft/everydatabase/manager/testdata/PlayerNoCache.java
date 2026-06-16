package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Test entity: a player whose guild reference declares a per-reference no-cache override. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerNoCache {

    private UUID uuid;

    @RefPolicy(noCache = true)
    private Ref<UUID, Guild> guild;
}
