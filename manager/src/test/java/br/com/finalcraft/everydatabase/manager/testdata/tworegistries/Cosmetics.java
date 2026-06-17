package br.com.finalcraft.everydatabase.manager.testdata.tworegistries;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Lobby-only referenced type in the two-registry example. Key type: {@code Integer}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cosmetics {

    private Integer id;     // key
    private String activeSkin;
}
