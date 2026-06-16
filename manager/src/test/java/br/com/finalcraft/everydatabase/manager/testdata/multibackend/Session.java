package br.com.finalcraft.everydatabase.manager.testdata.multibackend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Multi-backend example target (InMemory). Key type: a <b>wrapper record</b> {@link Id}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    private Id id;        // wrapper key
    private String server;

    /** Opaque session-token key. */
    public record Id(String token) {
    }
}
