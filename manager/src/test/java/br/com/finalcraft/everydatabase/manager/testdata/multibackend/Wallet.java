package br.com.finalcraft.everydatabase.manager.testdata.multibackend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Multi-backend example target (MongoDB). Key type: {@code Long} (the account number). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Wallet {

    private Long accountNumber;   // key
    private long balance;
}
