package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.query.IndexHint;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link GroupedFileStorage} in YAML mode. The format is taken from the descriptor's
 * {@link JacksonYamlCodec} - there is no format option - so this exercises the key-major aggregate
 * layout end-to-end in YAML: files are {@code .yml} at the base root, keyed by collection name,
 * human-readable, and multiple collections of one key live in one file.
 */
@DisplayName("GroupedFileStorage - YAML")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GroupedFileStorageYamlTest {

    static final UUID UUID_ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID UUID_BOB   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID UUID_CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static final EntityDescriptor<UUID, TestPlayer> PLAYER_DATA =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("player_data")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonYamlCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

    static final EntityDescriptor<UUID, TestPlayer> AUTH_ME =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("auth_me")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonYamlCodec<>(TestPlayer.class))
            .build();

    /**
     * Set to {@code true} to delete all test files after the run.
     * When {@code false} (default), files survive under {@link #RESIDUALS_ROOT} for inspection.
     */
    static final boolean CLEAN_TEST_RESIDUALS = false;

    static final Path RESIDUALS_ROOT = Path.of("build/test-residuals/GroupedFileStorageYaml");

    // Managed manually (no @TempDir) so CLEAN_TEST_RESIDUALS can control deletion.
    Path tempDir;
    Storage storage;
    Repository<UUID, TestPlayer> repo;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        String methodName = testInfo.getTestMethod().map(Method::getName).orElse("unknown");
        tempDir = RESIDUALS_ROOT.resolve(methodName);
        deleteQuietly(tempDir);    // start each test with a clean slate
        tempDir.toFile().mkdirs();
        storage = new GroupedFileStorage(new GroupedFileConfig(tempDir));
        storage.init().join();
        repo = storage.repository(PLAYER_DATA);
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    @AfterAll
    static void handleResiduals() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[GroupedFileStorageYamlTest] CLEAN_TEST_RESIDUALS=false - keeping files for inspection:");
            System.out.println("  -> " + RESIDUALS_ROOT.toAbsolutePath());
        } else {
            deleteQuietly(RESIDUALS_ROOT);
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    TestPlayer alice() { return new TestPlayer(UUID_ALICE, "Alice", 100); }
    TestPlayer bob()   { return new TestPlayer(UUID_BOB,   "Bob",    50); }
    TestPlayer carol() { return new TestPlayer(UUID_CAROL, "Carol", 200); }

    private Path keyFile(UUID key) {
        return tempDir.resolve(key + ".yml");
    }

    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("save() creates a .yml file at the base root (not in a collection sub-dir)")
    void save_createsYmlFileAtRoot() throws IOException {
        repo.save(alice()).join();

        assertTrue(Files.exists(keyFile(UUID_ALICE)), "key file <uuid>.yml must exist at the base root");
        // No per-collection sub-directory exists in the key-major layout.
        assertFalse(Files.exists(tempDir.resolve("player_data")), "there must be no collection sub-directory");
    }

    @Test
    @Order(2)
    @DisplayName("save() + find() round-trips all fields via YAML")
    void save_thenFind_roundTrips() {
        TestPlayer original = new TestPlayer(UUID_ALICE, "Alice", 7, "world_nether", false, 1766801261605L);
        repo.save(original).join();

        Optional<TestPlayer> loaded = repo.find(UUID_ALICE).join();
        assertTrue(loaded.isPresent());
        assertEquals(original, loaded.get(), "loaded entity must equal the original");
        assertEquals(1766801261605L, loaded.get().getCreatedAt());
    }

    @Test
    @Order(3)
    @DisplayName("the file is readable YAML keyed by the collection name")
    void file_isReadableYaml() throws IOException {
        repo.save(alice()).join();

        String content = new String(Files.readAllBytes(keyFile(UUID_ALICE)));
        assertFalse(content.trim().startsWith("{"), "must be YAML, not JSON");
        assertTrue(content.contains("player_data:"), "collection name must be a top-level YAML key");
        assertTrue(content.contains("Alice"), "entity content must be present");
        assertTrue(content.contains("100"),   "entity content must be present");
    }

    @Test
    @Order(4)
    @DisplayName("two collections of one key render as sibling YAML blocks in one file")
    void groupsCollectionsAsYamlBlocks() throws IOException {
        Repository<UUID, TestPlayer> authMe = storage.repository(AUTH_ME);
        repo.save(new TestPlayer(UUID_BOB, "Bob", 50)).join();
        authMe.save(new TestPlayer(UUID_BOB, "BobAuth", 3)).join();

        String content = new String(Files.readAllBytes(keyFile(UUID_BOB)));
        assertTrue(content.contains("player_data:"), "first collection block must be present");
        assertTrue(content.contains("auth_me:"),     "second collection block must be present");

        assertEquals("Bob",     repo.find(UUID_BOB).join().orElseThrow(AssertionError::new).getName());
        assertEquals("BobAuth", authMe.find(UUID_BOB).join().orElseThrow(AssertionError::new).getName());
    }

    @Test
    @Order(5)
    @DisplayName("count() and all() see only this collection's entities")
    void countAndAll() {
        repo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        assertEquals(3L, repo.count().join());
        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
        assertEquals(3, all.size());
        assertTrue(all.containsAll(Arrays.asList(alice(), bob(), carol())));
    }

    @Test
    @Order(6)
    @DisplayName("saveAll() + findMany() works with the YAML codec")
    void saveAll_thenFindMany() {
        repo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        List<TestPlayer> found = repo.findMany(Arrays.asList(UUID_ALICE, UUID_BOB, UUID_CAROL)).join();
        assertEquals(3, found.size());
        assertTrue(found.containsAll(Arrays.asList(alice(), bob(), carol())));
    }

    @Test
    @Order(7)
    @DisplayName("findBy() (full scan + index extract) works in YAML")
    void findBy_indexQuery() {
        repo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        List<TestPlayer> found = repo.findBy("name", "Bob").join();
        assertEquals(1, found.size());
        assertEquals(UUID_BOB, found.get(0).getUuid());
    }

    @Test
    @Order(8)
    @DisplayName("delete() of one collection keeps the sibling; deleting the last drops the file")
    void delete_semantics() {
        Repository<UUID, TestPlayer> authMe = storage.repository(AUTH_ME);
        repo.save(new TestPlayer(UUID_ALICE, "Alice", 100)).join();
        authMe.save(new TestPlayer(UUID_ALICE, "AliceAuth", 7)).join();

        // Delete one collection - file survives, sibling intact.
        assertTrue(repo.delete(UUID_ALICE).join());
        assertTrue(Files.exists(keyFile(UUID_ALICE)));
        assertTrue(authMe.find(UUID_ALICE).join().isPresent());

        // Delete the last collection - file is removed.
        assertTrue(authMe.delete(UUID_ALICE).join());
        assertFalse(Files.exists(keyFile(UUID_ALICE)), "the file must be removed once empty");
    }
}
