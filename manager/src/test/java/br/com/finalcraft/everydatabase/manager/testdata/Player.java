package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Test entity: a player whose guild reference uses the manager's default policy. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Player {

    private UUID uuid;
    private Ref<UUID, Guild> guild;
}
