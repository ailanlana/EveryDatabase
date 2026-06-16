package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Test entity: holds a list of references - exercises type recovery for container elements. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Squad {

    private UUID id;
    private List<Ref<UUID, Guild>> members;
}
