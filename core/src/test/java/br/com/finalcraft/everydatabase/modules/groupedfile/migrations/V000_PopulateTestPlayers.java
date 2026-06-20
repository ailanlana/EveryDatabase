package br.com.finalcraft.everydatabase.modules.groupedfile.migrations;

import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileMigration;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Test-only migration that seeds the {@code test_players} collection with 20 deterministic players,
 * using the layout-agnostic {@code storage.repository(...).saveAll} path. Mirrors the LocalFile
 * equivalent - proving a migration written against the public API needs no change between layouts.
 */
public final class V000_PopulateTestPlayers extends GroupedFileMigration {

    public static final V000_PopulateTestPlayers INSTANCE = new V000_PopulateTestPlayers();

    /** The exact 20 players written by this migration. Use for assertions in tests. */
    public static final List<TestPlayer> SEEDED_PLAYERS = buildPlayers();

    private static List<TestPlayer> buildPlayers() {
        List<TestPlayer> players = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            UUID   uuid  = new UUID(0, i + 1);
            String name  = "Player_" + i;
            int    score = new Random(i).nextInt(11); // seeded: deterministic score in [0, 10]
            players.add(new TestPlayer(uuid, name, score));
        }
        return Collections.unmodifiableList(players);
    }

    private V000_PopulateTestPlayers() {}

    @Override
    public String version() {
        return "000";
    }

    @Override
    public String description() {
        return "Seed test_players collection with 20 deterministic players (scores 0-10)";
    }

    @Override
    protected void executeOnStorage(GroupedFileStorage storage) {
        Repository<UUID, TestPlayer> repo = storage.repository(AbstractStorageTest.DESCRIPTOR);
        repo.saveAll(SEEDED_PLAYERS).join();
    }
}
