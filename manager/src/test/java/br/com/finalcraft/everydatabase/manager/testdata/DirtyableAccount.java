package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * A write-back test entity using the {@link IDirtyable} interface form: a balance mutated in memory
 * (which marks it dirty) and flushed later. Jackson reads/writes the fields directly (field
 * visibility + no setters), so decoding never trips the dirtying mutator and a freshly loaded
 * instance is clean.
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public class DirtyableAccount implements IDirtyable {

    private UUID id;
    private int balance;

    @JsonIgnore
    private transient boolean dirty;

    public DirtyableAccount() {
    }

    public DirtyableAccount(UUID id, int balance) {
        this.id = id;
        this.balance = balance;
    }

    public UUID getId() {
        return id;
    }

    public int getBalance() {
        return balance;
    }

    /** A domain mutation marks the entity dirty (write-back). */
    public void deposit(int amount) {
        this.balance += amount;
        markDirty();
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markClean() {
        this.dirty = false;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }
}
