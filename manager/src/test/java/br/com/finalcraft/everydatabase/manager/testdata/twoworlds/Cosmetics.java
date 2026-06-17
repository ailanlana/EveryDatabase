package br.com.finalcraft.everydatabase.manager.testdata.twoworlds;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Lobby-only referenced type in the two-world example. Key type: {@code Integer}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cosmetics {

    private Integer id;     // key
    private String activeSkin;
}
