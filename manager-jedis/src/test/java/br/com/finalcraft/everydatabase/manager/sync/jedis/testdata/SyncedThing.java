package br.com.finalcraft.everydatabase.manager.sync.jedis.testdata;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A minimal entity for the Jedis cache-sync integration tests. The transport propagates on the
 * manager's write hook regardless of versioning, so no optimistic-lock field is needed here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncedThing {

    private UUID id;       // key
    private String value;
}
