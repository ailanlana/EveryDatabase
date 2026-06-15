package br.com.finalcraft.everydatabase.manager;

import java.util.List;
import java.util.UUID;

/** Test entity: holds a list of references - exercises type recovery for container elements. */
public class Squad {

    public UUID id;
    public List<Ref<UUID, Guild>> members;

    public Squad() {
    }

    public Squad(UUID id, List<Ref<UUID, Guild>> members) {
        this.id = id;
        this.members = members;
    }
}
