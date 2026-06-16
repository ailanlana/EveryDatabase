package br.com.finalcraft.everydatabase.manager.testdata.multibackend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Multi-backend example target (PostgreSQL). Key type: {@code String} (the clan tag). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Clan {

    private String tag;     // key
    private String name;
}
