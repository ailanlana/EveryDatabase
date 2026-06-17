package br.com.finalcraft.everydatabase.manager.testdata.tworegistries;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * The shared referenced type in the two-registry example: the <b>same</b> {@code Hero} type is
 * registered in two independent {@code RefRegistry} contexts, backed by different storages. The
 * same hero id therefore resolves to <b>different data</b> depending on which registry resolves
 * it - which is impossible with a single global registry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Hero {

    private UUID id;     // key
    private String name;
    private int level;
}
