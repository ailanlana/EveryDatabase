package br.com.finalcraft.everydatabase.manager.testdata.multibackend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Multi-backend example target (H2). Key type: {@code Integer}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Stats {

    private Integer id;   // key
    private int kills;
    private int deaths;
}
